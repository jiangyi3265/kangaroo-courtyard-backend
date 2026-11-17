package com.ruoyi.kangaroo.ext.manage;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import java.util.*;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.util.DigestUtils;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.KgLike;
import com.ruoyi.kangaroo.ext.core.KgPrivateFiles;
import com.ruoyi.kangaroo.ext.core.KgImageMagic;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.ext.user.KgCityService;

/**
 * 平台管理员派发给员工的独立任务，不改动店内排班、工时和积分。
 *
 * 2026-11-03 主人：「任务派发，需要有个流转过程，不能只单向发送，派发任务，可以一键发送给所有全体员工。我还可以给某一个岗位，单独的发布任务」（V74）。
 * 1) 流转：待接收 → 进行中 → 待审核 → 已完成；驳回回到「已驳回」（照样能改了重交）；负责人可以随时回复 / 提问、申请延期、说明无法完成；
 *    后台可以回复、通过 / 驳回、改截止日期、转派给别人、关闭、退回重新处理。每一步记进 kg_staff_task_event，两边详情页都按时间线展示。
 *    负责人有新动作时 admin_attention = 1（后台没有站内信，列表「待处理」页签和角标就是提醒）；后台动作发员工消息并让小程序弹提醒（notice_version + 1）。
 * 2) 派发对象三种：点名（同以前，按授权选人）、全体员工（ROLES 里所有在职的人，一人一条）、按岗位（某个岗位的所有人，可再限定城市或门店）。
 *    每次派发一个批次（kg_staff_task_batch），后台按批次看「完成 x/y」。
 * 能收任务的岗位见 ROLES：合作商家是外部商家不算员工；老员工（staff）切换后退役，也不算。
 */
@Service
public class KgStaffTaskService {
    @Autowired private KgJdbc db;
    @Autowired private StaffSupport support;
    @Autowired private KgPrivateFiles files;
    @Autowired(required=false) private KgCityService cities;

    /** 能收任务的岗位，顺序 = 后台下拉顺序；全体派发时一人多岗只派一条，取这个顺序里靠前的那个岗位 */
    public static final List<String> ROLES = Collections.unmodifiableList(Arrays.asList(
        StaffRole.MANAGER, StaffRole.KEEPER, StaffRole.ONLINE_STAFF, StaffRole.MERCHANT_SERVICE, StaffRole.CITY_LEAD));
    /** 还没结束的状态：两边都还能回复 */
    static final List<String> OPEN = Arrays.asList("pending","accepted","submitted","rejected","declined");
    /** 负责人能动手做的状态：接收、提交、申请延期、说明无法完成 */
    static final List<String> WORKING = Arrays.asList("pending","accepted","rejected");
    static final Map<String,String> STATUS = new LinkedHashMap<>();
    static final Map<String,String> ACTIONS = new LinkedHashMap<>();
    static {
        STATUS.put("pending","待接收");STATUS.put("accepted","进行中");STATUS.put("submitted","待审核");STATUS.put("approved","已完成");
        STATUS.put("rejected","已驳回");STATUS.put("declined","无法完成");STATUS.put("closed","已关闭");
        ACTIONS.put("assign","派发任务");ACTIONS.put("accept","接收任务");ACTIONS.put("reply","回复");ACTIONS.put("extend_request","申请延期");
        ACTIONS.put("decline","说明无法完成");ACTIONS.put("submit","提交完成回执");ACTIONS.put("approve","审核通过");ACTIONS.put("reject","驳回");
        ACTIONS.put("extend","调整截止日期");ACTIONS.put("reassign","转派");ACTIONS.put("close","关闭任务");ACTIONS.put("reopen","退回重新处理");
    }
    /** 全体 / 按岗位一次最多派多少人：一个事务里逐条建任务、发消息，再多就分批（按岗位或城市） */
    static final int MAX_BROADCAST = 500;
    private static final String ROLE_IN = " g.role_code in ('manager','keeper','online_staff','merchant_service','city_lead') ";
    /**
     * 授权有效：门店授权看门店，按城市授权（2026-11 起，store_id 为空、只有 city_id）看城市没停用，口径同 KgStaffGrantMapper.selectActiveGrantsWithStore。
     * 原先 join kg_store 把按城市授权的城市负责人整个筛掉了：点名搜不到、按岗位派发 0 人、转派说「授权已变化」。
     */
    private static final String ACTIVE = " g.status='0' and g.del_flag='0' and"+ROLE_IN+"and ((g.city_id is null and s.status='0' and s.del_flag='0') or (g.store_id is null and c.status='0')) and u.status='0' and u.del_flag='0' ";
    private static final String GRANTS = " from kg_staff_grant g left join kg_store s on s.store_id=g.store_id left join kg_city c on c.city_id=g.city_id join kg_app_user u on u.user_id=g.app_user_id ";
    /** 任务挂在哪：门店授权 = 门店编号；城市授权没有门店，kg_staff_task.store_id 不能为空、不加迁移，记成「负的城市编号」 */
    private static final String SCOPE = "coalesce(g.store_id,-g.city_id)";
    /** 授权范围的名字：门店名，城市授权显示「城市（全城）」 */
    private static final String SCOPE_NAME = "coalesce(s.store_name,concat(c.name,'（全城）'))";
    /** 负责人在这家店（或这个城市）还有能收任务的在职授权：转岗、撤销后就看不到、也动不了原来的任务 */
    private static final String OWN = " and exists (select 1" + GRANTS + "where g.app_user_id=t.assignee_id and "+SCOPE+"=t.store_id and" + ACTIVE + ") ";
    private String now() { return text(db.one("select date_format(now(), '%Y-%m-%d %H:%i:%s') as value",p()).get("value")); }
    /** scope_city：城市授权的任务（store_id 为负）对应的城市名，decorate 里拼成店名 */
    private static final String SELECT = "select t.*,s.store_name,u.nickname,tc.name scope_city from kg_staff_task t left join kg_store s on s.store_id=t.store_id left join kg_city tc on tc.city_id=-t.store_id left join kg_app_user u on u.user_id=t.assignee_id ";
    private static final String ROUTE = "/pages/staff/platform-tasks?taskId=";
    static String text(Object o) { return o==null?"":String.valueOf(o).trim(); }
    static String required(Object o,String label,int max) {
        String s=text(o); if(s.isEmpty()||s.length()>max)throw new ServiceException(label+"请填写1至"+max+"个字",422); return s;
    }
    static String optional(Object o,String label,int max) {
        String s=text(o); if(s.length()>max)throw new ServiceException(label+"不能超过"+max+"个字",422); return s;
    }
    static long id(Object o) {
        String s=text(o); if(!s.matches("[1-9][0-9]{0,17}"))throw new ServiceException("编号不正确",422); return Long.parseLong(s);
    }
    static long version(Object o) {
        String s=text(o); if(!s.matches("[1-9][0-9]{0,8}"))throw new ServiceException("任务版本缺失，请刷新后重试",422); return Long.parseLong(s);
    }
    static String roleLabel(Object role){String r=text(role);return r.isEmpty()?"":StaffRole.label(r);}
    private String futureDate(Object raw,String label) {
        String due=text(raw);
        if(due.isEmpty()||!KgJdbc.isDate(due))throw new ServiceException("请选择"+label,422);
        if(LocalDate.parse(due).isBefore(LocalDate.parse(now().substring(0,10))))throw new ServiceException(label+"不能早于今天",422);
        return due;
    }

    // ---------------------------------------------------------------- 派发对象

    /** 点名派发的候选人：按授权（一人一店一岗一行），可按岗位筛 */
    public Map<String,Object> recipients(String keyword,String roleCode,Integer pageNum) {
        Map<String,Object> ps=p("q",KgLike.contains(text(keyword)));String where="";
        if(!text(roleCode).isEmpty()){where=" and g.role_code=:r";ps.put("r",role(roleCode));}
        Map<String,Object> page=db.page("select g.grant_id,g.app_user_id,"+SCOPE+" store_id,g.role_code,u.nickname,"+SCOPE_NAME+" store_name"+GRANTS+"where"+ACTIVE+where+
            " and (u.nickname like :q or s.store_name like :q or c.name like :q) order by s.store_id,g.grant_id",ps,pageNum,30);
        for(Object raw:(List<?>)page.get("rows")){@SuppressWarnings("unchecked") Map<String,Object> r=(Map<String,Object>)raw;r.put("roleLabel",roleLabel(r.get("roleCode")));}
        return page;
    }
    static String role(Object raw){String r=text(raw);if(!ROLES.contains(r))throw new ServiceException("请选择要派发的岗位",422);return r;}
    /** 岗位下拉：每个岗位现在有多少在职的人（一人多店算一个） */
    public List<Map<String,Object>> roles() {
        Map<String,Long> counts=new HashMap<>();
        for(Map<String,Object> r:db.list("select g.role_code,count(distinct g.app_user_id) people"+GRANTS+"where"+ACTIVE+"group by g.role_code",p()))
            counts.put(text(r.get("roleCode")),KgJdbc.asLong(r.get("people")));
        List<Map<String,Object>> out=new ArrayList<>();
        for(String r:ROLES)out.add(p("roleCode",r,"label",StaffRole.label(r),"people",counts.containsKey(r)?counts.get(r):0L));
        return out;
    }
    private String town(Object raw){
        String t=text(raw);if(cities!=null){String n=cities.jobCity(t);t=n==null?"":n.trim();}
        return "全国".equals(t)||"不限".equals(t)?"":t;
    }
    /**
     * 全体 / 按岗位派发时实际发给谁：一人一条（一人多店、多岗只取 ROLES 顺序靠前的岗位、再取最早的那条授权），
     * 城市按门店城市归一后比（和城市负责人的范围同一口径），门店直接比编号。
     * 按城市授权的人：城市就是授权上的城市；限定门店时，授权城市和这家店同城的也算（他在这家店也有这个岗位的权限）。
     */
    List<Map<String,Object>> audienceRows(String mode,Object roleCode,Object city,Object storeId) {
        String sql="select g.grant_id,g.app_user_id,"+SCOPE+" store_id,g.role_code,u.nickname,"+SCOPE_NAME+" store_name,coalesce(c.name,s.city) city"+GRANTS+"where"+ACTIVE;Map<String,Object> ps=p();
        if("role".equals(mode)){sql+=" and g.role_code=:r";ps.put("r",role(roleCode));}
        else if(!"all".equals(mode))throw new ServiceException("派发方式不正确",422);
        String storeTown=null;
        if(!text(storeId).isEmpty()){
            sql+=" and (g.store_id=:s or g.store_id is null)";ps.put("s",id(storeId));
            Map<String,Object> st=db.one("select city from kg_store where store_id=:s",p("s",id(storeId)));
            storeTown=st==null?"":town(st.get("city"));
        }
        String want=text(city).isEmpty()?"":town(city);
        if(!text(city).isEmpty()&&want.isEmpty())throw new ServiceException("城市不正确",422);
        List<Map<String,Object>> rows=db.list(sql+" order by g.grant_id",ps);
        rows.sort(Comparator.comparingInt((Map<String,Object> r)->ROLES.indexOf(text(r.get("roleCode")))).thenComparingLong(r->KgJdbc.asLong(r.get("grantId"))));
        Map<Long,Map<String,Object>> people=new LinkedHashMap<>();
        for(Map<String,Object> r:rows){
            Long scope=KgJdbc.asLong(r.get("storeId"));
            // 限定门店时，城市授权只算和这家店同城的；门店没设城市就不算
            if(storeTown!=null&&scope!=null&&scope<0&&(storeTown.isEmpty()||!storeTown.equals(town(r.get("city")))))continue;
            if(!want.isEmpty()&&!want.equals(town(r.get("city"))))continue;
            people.putIfAbsent(KgJdbc.asLong(r.get("appUserId")),r);
        }
        return new ArrayList<>(people.values());
    }
    /** 派发前给后台看：会发给多少人、前 20 个是谁 */
    public Map<String,Object> audience(String mode,String roleCode,String city,Long storeId) {
        List<Map<String,Object>> rows=audienceRows(text(mode),roleCode,city,storeId);
        List<Map<String,Object>> sample=new ArrayList<>();
        for(Map<String,Object> r:rows.subList(0,Math.min(20,rows.size())))
            sample.add(p("appUserId",r.get("appUserId"),"nickname",r.get("nickname"),"storeName",r.get("storeName"),"roleLabel",roleLabel(r.get("roleCode"))));
        return p("count",rows.size(),"max",MAX_BROADCAST,"sample",sample);
    }

    // ---------------------------------------------------------------- 列表

    private Map<String,Object> list(Long uid,String status,String keyword,Long batchId,String roleCode,Integer pageNum) {
        String where=" where 1=1"; Map<String,Object> ps=p("q",KgLike.contains(text(keyword)));
        if(uid!=null){where+=" and t.assignee_id=:u"+OWN;ps.put("u",uid);}
        if("todo".equals(status))where+=" and t.status in ('pending','accepted','rejected')";
        else if("open".equals(status))where+=" and t.status in ('pending','accepted','submitted','rejected','declined')";
        else if("attention".equals(status))where+=" and t.admin_attention='1'";
        else if(!text(status).isEmpty()){
            if(!STATUS.containsKey(status))throw new ServiceException("任务状态不正确",422);
            where+=" and t.status=:status";ps.put("status",status);
        }
        if(batchId!=null){where+=" and t.batch_id=:b";ps.put("b",batchId);}
        if(!text(roleCode).isEmpty()){where+=" and t.role_code=:r";ps.put("r",role(roleCode));}
        where+=" and (t.title like :q or s.store_name like :q or tc.name like :q or u.nickname like :q)";
        Map<String,Object> result=db.page(SELECT+where+" order by t.update_time desc,t.task_id desc",ps,pageNum,20);
        for(Object raw:(List<?>)result.get("rows")){@SuppressWarnings("unchecked") Map<String,Object> row=(Map<String,Object>)raw;decorate(row);}
        if(uid==null)result.put("attentionCount",db.count("select count(*) from kg_staff_task where admin_attention='1'",p()));
        return result;
    }
    private void decorate(Map<String,Object> row){
        row.remove("requestKey");row.remove("requestHash");
        Object scopeCity=row.remove("scopeCity");
        if(text(row.get("storeName")).isEmpty()&&!text(scopeCity).isEmpty())row.put("storeName",text(scopeCity)+"（全城）");
        row.put("statusLabel",STATUS.containsKey(text(row.get("status")))?STATUS.get(text(row.get("status"))):text(row.get("status")));
        row.put("roleLabel",roleLabel(row.get("roleCode")));
    }
    public Map<String,Object> adminList(String status,String keyword,Long batchId,String roleCode,Integer pageNum){return list(null,status,keyword,batchId,roleCode,pageNum);}
    public Map<String,Object> staffList(Long uid,String status,Integer pageNum){return list(uid,status,"",null,null,pageNum);}
    /** 按批次看：每次派发一行，完成 x/y、待审核、待处理几条 */
    public Map<String,Object> batches(String keyword,Integer pageNum) {
        Map<String,Object> page=db.page("select b.batch_id,b.title,b.scope,b.role_code,b.city,b.store_id,b.total,b.create_by,b.create_time,s.store_name,"+
            "count(t.task_id) tasks,coalesce(sum(t.status='approved'),0) done,coalesce(sum(t.status='submitted'),0) waiting,"+
            "coalesce(sum(t.status in ('closed','declined')),0) stopped,coalesce(sum(t.admin_attention='1'),0) attention,max(t.update_time) last_update "+
            "from kg_staff_task_batch b left join kg_staff_task t on t.batch_id=b.batch_id left join kg_store s on s.store_id=b.store_id "+
            "where b.title like :q group by b.batch_id order by b.batch_id desc",p("q",KgLike.contains(text(keyword))),pageNum,20);
        for(Object raw:(List<?>)page.get("rows")){
            @SuppressWarnings("unchecked") Map<String,Object> r=(Map<String,Object>)raw;String scope=text(r.get("scope"));
            String label="all".equals(scope)?"全体员工":("role".equals(scope)?"岗位："+roleLabel(r.get("roleCode")):"点名派发");
            if(!text(r.get("city")).isEmpty())label+=" · "+r.get("city");
            if(!text(r.get("storeName")).isEmpty())label+=" · "+r.get("storeName");
            r.put("scopeLabel",label);
        }
        return page;
    }

    // ---------------------------------------------------------------- 详情

    private Map<String,Object> task(Long uid,Long taskId,boolean lock) {
        Map<String,Object> t=db.one("select t.* from kg_staff_task t where t.task_id=:id"+(uid==null?"":" and t.assignee_id=:u"+OWN)+(lock?" for update":""),p("id",taskId,"u",uid));
        if(t==null)throw new ServiceException("任务不存在或当前账号无权访问",404);return t;
    }
    private void current(Map<String,Object> t,Object v){if(version(v)!=id(t.get("version")))throw new ServiceException("任务已更新，请刷新后再操作",409);}
    private static void in(Map<String,Object> t,List<String> allowed,String message){if(!allowed.contains(text(t.get("status"))))throw new ServiceException(message,409);}
    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> imageViews(Object raw) {
        List<Map<String,Object>> out=new ArrayList<>();
        for(Object value:JSON.parseArray(text(raw).isEmpty()?"[]":text(raw))){
            Map<String,Object> f=db.one("select file_id,file_key from kg_staff_task_file where file_id=:id",p("id",id(value)));
            if(f!=null)out.add(p("fileId",f.get("fileId"),"url",files.sign(text(f.get("fileKey")),900)));
        }
        return out;
    }
    public Map<String,Object> detail(Long uid,Long taskId) {
        task(uid,taskId,false);Map<String,Object> t=db.one(SELECT+" where t.task_id=:id",p("id",taskId));
        List<Map<String,Object>> history=db.list("select * from kg_staff_task_submission where task_id=:id order by submission_id desc",p("id",taskId));
        for(Map<String,Object> r:history){r.put("images",imageViews(r.remove("imageIds")));r.put("statusLabel",STATUS.get(text(r.get("status"))));}
        List<Map<String,Object>> events=db.list("select event_id,actor_type,actor_id,actor_name,action,content,image_ids,due_date,create_time from kg_staff_task_event where task_id=:id order by event_id",p("id",taskId));
        for(Map<String,Object> e:events){e.put("images",imageViews(e.remove("imageIds")));e.put("actionLabel",ACTIONS.containsKey(text(e.get("action")))?ACTIONS.get(text(e.get("action"))):text(e.get("action")));}
        decorate(t);t.put("submissions",history);t.put("events",events);
        String st=text(t.get("status"));boolean open=OPEN.contains(st),working=WORKING.contains(st);
        if(uid!=null){
            t.put("canAccept","pending".equals(st));t.put("canSubmit",working);t.put("canReply",open);t.put("canRequestExtend",working);t.put("canDecline",working);
        }else{
            t.put("canReview","submitted".equals(st));t.put("canReply",open);t.put("canExtend",open);t.put("canReassign",open&&!"submitted".equals(st));
            t.put("canClose",open);t.put("canReopen","declined".equals(st)||"closed".equals(st));
        }
        return t;
    }

    // ---------------------------------------------------------------- 派发

    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> assign(Map<String,Object> body,String operator,Long adminId) {
        String title=required(body.get("title"),"任务标题",100),description=required(body.get("description"),"任务要求",3000);
        String due=text(body.get("dueDate"));if(!due.isEmpty()&&(!KgJdbc.isDate(due)||LocalDate.parse(due).isBefore(LocalDate.parse(now().substring(0,10)))))throw new ServiceException("截止日期不能早于今天",422);
        String request=required(body.get("requestId"),"提交标识",64);if(!request.matches("[A-Za-z0-9_-]{16,64}"))throw new ServiceException("提交标识不正确",422);
        String mode=text(body.get("mode")).isEmpty()?"pick":text(body.get("mode"));
        String roleCode=null,city=null;Long storeId=null;List<Map<String,Object>> targets;Set<Long> grants=new TreeSet<>();
        if("pick".equals(mode)){
            if(!(body.get("grantIds") instanceof List))throw new ServiceException("请选择派给谁",422);
            for(Object o:(List<?>)body.get("grantIds"))grants.add(id(o));
            if(grants.isEmpty()||grants.size()>100)throw new ServiceException("每次点名1至100人；人多请用「发给全体员工」或「按岗位」",422);
            targets=null;
        }else{
            if("role".equals(mode))roleCode=role(body.get("roleCode"));
            city=text(body.get("city")).isEmpty()?null:text(body.get("city"));
            storeId=text(body.get("storeId")).isEmpty()?null:id(body.get("storeId"));
            targets=audienceRows(mode,roleCode,city,storeId);
            if(targets.isEmpty())throw new ServiceException("没有符合条件的在职员工",422);
            if(targets.size()>MAX_BROADCAST)throw new ServiceException("一次最多发给"+MAX_BROADCAST+"人（这次有"+targets.size()+"人），请按岗位或城市分开派发",422);
        }
        String key="stafftask:"+adminId+":"+request;
        String hash=DigestUtils.md5DigestAsHex(JSON.toJSONString(Arrays.asList(title,description,due,mode,roleCode,city,storeId,grants)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(!db.claimIdem(key,"staff_task")){
            List<Map<String,Object>> old=db.list("select task_id,batch_id,request_hash from kg_staff_task where request_key=:k for update",p("k",key));
            if(old.isEmpty()||!hash.equals(old.get(0).get("requestHash")))throw new ServiceException("本次派发已处理，请刷新后核对",409);
            return p("taskIds",taskIds(old),"count",old.size(),"batchId",old.get(0).get("batchId"));
        }
        long batchId=db.insert("kg_staff_task_batch",p("title",title,"scope",mode,"role_code",roleCode,"city",city,"store_id",storeId,"total",0,
            "request_key",key,"create_by",operator,"create_time",now()));
        if(targets==null){
            targets=new ArrayList<>();
            for(Long grantId:grants){
                Map<String,Object> g=db.one("select g.grant_id,"+SCOPE+" store_id,g.app_user_id,g.role_code"+GRANTS+" where g.grant_id=:id and"+ACTIVE+" for update",p("id",grantId));
                if(g==null)throw new ServiceException("所选员工的授权已变化，请重新选择",409);
                targets.add(g);
            }
        }
        List<Long> ids=new ArrayList<>();Set<String> recipients=new HashSet<>();
        for(Map<String,Object> g:targets){
            if(!recipients.add(g.get("storeId")+":"+g.get("appUserId")))continue;
            long taskId=db.insert("kg_staff_task",p("batch_id",batchId,"title",title,"description",description,"due_date",due.isEmpty()?null:due,
                "store_id",g.get("storeId"),"assignee_id",g.get("appUserId"),"role_code",g.get("roleCode"),"status","pending","version",1,"notice_version",1,"seen_notice_version",0,
                "admin_attention","0","request_key",key,"request_hash",hash,"create_by",operator,"create_time",now(),"update_time",now()));
            event(taskId,"admin",adminId,operator,"assign",null,null,due.isEmpty()?null:due,null);
            support.notifyStaff(id(g.get("appUserId")),"staff","有新的管理员任务",title,"staff_task",taskId,ROUTE+taskId);ids.add(taskId);
        }
        db.update("update kg_staff_task_batch set total=:n where batch_id=:b",p("n",ids.size(),"b",batchId));
        return p("taskIds",ids,"count",ids.size(),"batchId",batchId);
    }
    private List<Long> taskIds(List<Map<String,Object>> rows){List<Long> ids=new ArrayList<>();for(Map<String,Object> r:rows)ids.add(id(r.get("taskId")));return ids;}
    private void event(long taskId,String actorType,Long actorId,String actorName,String action,String content,String imageIds,String due,Long refId){
        db.insert("kg_staff_task_event",p("task_id",taskId,"actor_type",actorType,"actor_id",actorId,"actor_name",StaffSupport.cut(text(actorName),64),"action",action,
            "content",content==null||content.isEmpty()?null:content,"image_ids",imageIds,"due_date",due,"ref_id",refId,"create_time",now()));
    }
    private String staffName(Long uid){Map<String,Object> u=db.one("select nickname from kg_app_user where user_id=:u",p("u",uid));String n=u==null?"":text(u.get("nickname"));return n.isEmpty()?"员工"+uid:n;}
    /** 任务挂的门店名；城市授权的（store_id 为负）显示「城市（全城）」 */
    private String scopeName(Object raw){
        Long sid=KgJdbc.asLong(raw);if(sid==null)return "";
        Map<String,Object> r=sid<0?db.one("select concat(name,'（全城）') store_name from kg_city where city_id=:c",p("c",-sid)):db.one("select s.store_name from kg_store s where s.store_id=:s",p("s",sid));
        return r==null?"":text(r.get("storeName"));
    }

    // ---------------------------------------------------------------- 负责人（小程序）

    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> upload(Long uid,Long taskId,MultipartFile file) {
        Map<String,Object> t=task(uid,taskId,true);in(t,OPEN,"任务已结束，不能再上传图片");
        if(file==null||file.isEmpty()||file.getSize()>20L*1024*1024)throw new ServiceException("请选择20MB以内的图片",422);
        KgImageMagic.require(file,"jpg","png");Map<String,Object> saved=files.save(file,"stafftask");
        long fileId=db.insert("kg_staff_task_file",p("task_id",taskId,"app_user_id",uid,"file_key",saved.get("key"),"create_time",now()));
        return p("fileId",fileId,"url",files.sign(text(saved.get("key")),900));
    }
    private String ownImages(Long uid,Long taskId,Object raw,int min) {
        Set<Long> ids=new LinkedHashSet<>();
        if(raw instanceof List)for(Object o:(List<?>)raw)ids.add(id(o));
        if(ids.size()<min||ids.size()>9)throw new ServiceException(min>0?"请上传1至9张完成图片":"图片最多9张",422);
        for(Long fileId:ids)if(db.count("select count(*) from kg_staff_task_file where file_id=:id and task_id=:t and app_user_id=:u",p("id",fileId,"t",taskId,"u",uid))!=1)throw new ServiceException("图片不属于本任务，请重新上传",422);
        return ids.isEmpty()?null:JSON.toJSONString(ids);
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> submit(Long uid,Long taskId,Map<String,Object> body) {
        Map<String,Object> t=task(uid,taskId,true);current(t,body.get("version"));in(t,WORKING,"当前任务不能提交，请刷新查看审核状态");
        String content=required(body.get("content"),"完成说明",3000);
        if(!(body.get("fileIds") instanceof List))throw new ServiceException("请上传至少1张完成图片",422);
        String images=ownImages(uid,taskId,body.get("fileIds"),1);
        long submissionId=db.insert("kg_staff_task_submission",p("task_id",taskId,"content",content,"image_ids",images,"status","submitted","create_time",now()));
        db.update("update kg_staff_task set status='submitted',version=version+1,seen_notice_version=notice_version,admin_attention='1',update_time=now() where task_id=:id",p("id",taskId));
        event(taskId,"staff",uid,staffName(uid),"submit",content,images,null,submissionId);
        return detail(uid,taskId);
    }
    /**
     * 负责人的流转动作：accept 接收 / reply 回复、提问（可带图）/ extend_request 申请延期（要写想延到哪天和原因）/ decline 说明无法完成（要写原因）。
     * 都要带 version（乐观锁），都记时间线、都标「后台待处理」（接收除外：只是告诉后台在做了）。
     */
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> act(Long uid,Long taskId,Map<String,Object> body) {
        Map<String,Object> t=task(uid,taskId,true);current(t,body.get("version"));
        String action=text(body.get("action"));String content,images=null,due=null,set;Map<String,Object> ps=p("id",taskId);
        if("accept".equals(action)){
            in(t,Collections.singletonList("pending"),"任务已经接收过了，请刷新");
            content=optional(body.get("content"),"留言",1000);set="status='accepted'";
        }else if("reply".equals(action)){
            in(t,OPEN,"任务已结束，不能再回复");
            content=optional(body.get("content"),"回复",1000);images=ownImages(uid,taskId,body.get("fileIds"),0);
            if(content.isEmpty()&&images==null)throw new ServiceException("请填写回复内容或附上图片",422);
            set="admin_attention='1'";
        }else if("extend_request".equals(action)){
            in(t,WORKING,"当前状态不能申请延期，请刷新");
            due=futureDate(body.get("dueDate"),"希望延到的日期");content=required(body.get("content"),"延期原因",1000);
            set="requested_due_date=:due,admin_attention='1'";ps.put("due",due);
        }else if("decline".equals(action)){
            in(t,WORKING,"当前状态不能这样操作，请刷新");
            content=required(body.get("content"),"无法完成的原因",1000);set="status='declined',admin_attention='1'";
        }else throw new ServiceException("操作不正确",422);
        db.update("update kg_staff_task set "+set+",version=version+1,seen_notice_version=notice_version,update_time=now() where task_id=:id",ps);
        event(taskId,"staff",uid,staffName(uid),action,content,images,due,null);
        return detail(uid,taskId);
    }

    // ---------------------------------------------------------------- 后台

    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> review(Long taskId,Map<String,Object> body,String operator,Long adminId) {
        Map<String,Object> t=task(null,taskId,true);current(t,body.get("version"));
        if(!"submitted".equals(t.get("status")))throw new ServiceException("只有待审核的提交可以审核",409);
        String action=text(body.get("action"));if(!Arrays.asList("approve","reject").contains(action))throw new ServiceException("审核操作不正确",422);
        String note="reject".equals(action)?required(body.get("reason"),"驳回原因",1000):text(body.get("reason"));
        if(note.length()>1000)throw new ServiceException("审核意见不能超过1000字",422);
        Map<String,Object> latest=db.one("select submission_id from kg_staff_task_submission where task_id=:id and status='submitted' order by submission_id desc limit 1 for update",p("id",taskId));
        if(latest==null)throw new ServiceException("没有待审核的完成表单",409);
        String status="approve".equals(action)?"approved":"rejected";
        db.update("update kg_staff_task_submission set status=:s,review_note=:n,review_by=:by,review_time=now() where submission_id=:id",p("s",status,"n",note,"by",operator,"id",latest.get("submissionId")));
        db.update("update kg_staff_task set status=:s,version=version+1,notice_version=notice_version+:n,admin_attention='0',update_time=now() where task_id=:id",p("s",status,"n","rejected".equals(status)?1:0,"id",taskId));
        event(taskId,"admin",adminId,operator,action,note,null,null,KgJdbc.asLong(latest.get("submissionId")));
        support.notifyStaff(id(t.get("assigneeId")),"staff","approved".equals(status)?"任务审核通过":"任务被驳回，请修改后重交",text(t.get("title"))+(note.isEmpty()?"":"："+note),"staff_task",taskId,ROUTE+taskId);
        return detail(null,taskId);
    }
    /**
     * 后台的流转动作：reply 回复 / extend 改截止日期（同意延期申请也走这里）/ reassign 转派给另一位员工 / close 关闭 / reopen 退回重新处理（无法完成、已关闭的）。
     * 处理过就清掉「待处理」；需要负责人看的动作 notice_version + 1，小程序会弹提醒。
     */
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> handle(Long taskId,Map<String,Object> body,String operator,Long adminId) {
        Map<String,Object> t=task(null,taskId,true);current(t,body.get("version"));
        String action=text(body.get("action")),title=text(t.get("title"));Long assignee=id(t.get("assigneeId"));
        String content,due=null,set,msgTitle;Map<String,Object> ps=p("id",taskId);
        if("reply".equals(action)){
            in(t,OPEN,"任务已结束，不能再回复");content=required(body.get("content"),"回复内容",1000);
            set="admin_attention='0'";msgTitle="管理员回复了你的任务";
        }else if("extend".equals(action)){
            in(t,OPEN,"任务已结束，不能再改截止日期");due=futureDate(body.get("dueDate"),"新的截止日期");content=optional(body.get("content"),"说明",1000);
            set="due_date=:due,requested_due_date=null,admin_attention='0'";ps.put("due",due);msgTitle="任务截止日期已调整为 "+due;
        }else if("close".equals(action)){
            in(t,OPEN,"任务已经结束了，请刷新");content=optional(body.get("content"),"关闭原因",1000);
            set="status='closed',requested_due_date=null,admin_attention='0'";msgTitle="任务已关闭";
        }else if("reopen".equals(action)){
            in(t,Arrays.asList("declined","closed"),"只有「无法完成」或「已关闭」的任务可以退回重新处理");content=optional(body.get("content"),"说明",1000);
            set="status='pending',admin_attention='0'";msgTitle="任务退回给你重新处理";
        }else if("reassign".equals(action)){
            in(t,Arrays.asList("pending","accepted","rejected","declined"),"待审核、已结束的任务不能转派");content=optional(body.get("content"),"转派说明",1000);
            Map<String,Object> g=db.one("select g.grant_id,"+SCOPE+" store_id,g.app_user_id,g.role_code,u.nickname,"+SCOPE_NAME+" store_name"+GRANTS+" where g.grant_id=:id and"+ACTIVE+" for update",p("id",id(body.get("grantId"))));
            if(g==null)throw new ServiceException("所选员工的授权已变化，请重新选择",409);
            // 城市授权的任务 store_id 是负数，不能用 id() 比（id() 只认正数）
            if(assignee.equals(KgJdbc.asLong(g.get("appUserId")))&&Objects.equals(KgJdbc.asLong(t.get("storeId")),KgJdbc.asLong(g.get("storeId"))))throw new ServiceException("已经是这个人在负责了",422);
            if(db.count("select count(*) from kg_staff_task where request_key=:k and store_id=:s and assignee_id=:u",p("k",t.get("requestKey"),"s",g.get("storeId"),"u",g.get("appUserId")))>0)
                throw new ServiceException("这个人已经有这项任务了，可以直接关闭这条",409);
            String from=scopeName(t.get("storeId"));
            String note="由 "+staffName(assignee)+(from.isEmpty()?"":"（"+from+"）")+" 转给 "+(text(g.get("nickname")).isEmpty()?"员工"+g.get("appUserId"):text(g.get("nickname")))+"（"+text(g.get("storeName"))+"）";
            db.update("update kg_staff_task set store_id=:s,assignee_id=:u,role_code=:r,status='pending',requested_due_date=null,admin_attention='0',version=version+1,"+
                "notice_version=notice_version+1,seen_notice_version=0,update_time=now() where task_id=:id",p("s",g.get("storeId"),"u",g.get("appUserId"),"r",g.get("roleCode"),"id",taskId));
            event(taskId,"admin",adminId,operator,"reassign",note+(content.isEmpty()?"":"。"+content),null,null,null);
            support.notifyStaff(assignee,"staff","任务已转给其他同事",title+(content.isEmpty()?"":"："+content),"staff_task",taskId,"/pages/staff/platform-tasks");
            support.notifyStaff(id(g.get("appUserId")),"staff","有新的管理员任务（转派）",title,"staff_task",taskId,ROUTE+taskId);
            return detail(null,taskId);
        }else throw new ServiceException("操作不正确",422);
        db.update("update kg_staff_task set "+set+",version=version+1,notice_version=notice_version+1,update_time=now() where task_id=:id",ps);
        event(taskId,"admin",adminId,operator,action,content,null,due,null);
        support.notifyStaff(assignee,"staff",msgTitle,title+(content.isEmpty()?"":"："+content),"staff_task",taskId,ROUTE+taskId);
        return detail(null,taskId);
    }

    // ---------------------------------------------------------------- 小程序提醒

    /** 待办数 = 待接收、进行中、已驳回；弹窗 = 还没结束、后台动过（notice_version 比看过的新）的，带上后台最近一步是什么 */
    public Map<String,Object> reminder(Long uid) {
        String where=" where t.assignee_id=:u"+OWN;
        long count=db.count("select count(*) from kg_staff_task t"+where+" and t.status in ('pending','accepted','rejected')",p("u",uid));
        List<Map<String,Object>> rows=db.list("select t.task_id,t.title,t.status,t.notice_version,coalesce(s.store_name,concat(tc.name,'（全城）')) store_name,"+
            "(select e.action from kg_staff_task_event e where e.task_id=t.task_id and e.actor_type='admin' order by e.event_id desc limit 1) last_action "+
            "from kg_staff_task t left join kg_store s on s.store_id=t.store_id left join kg_city tc on tc.city_id=-t.store_id"+where+" and t.status in ('pending','accepted','submitted','rejected','declined') and t.notice_version>t.seen_notice_version order by t.update_time,t.task_id limit 50",p("u",uid));
        return p("todoCount",count,"unseen",rows);
    }
    @Transactional(rollbackFor=Exception.class)
    public void acknowledge(Long uid,List<Map<String,Object>> items) {
        if(items==null||items.size()>50)throw new ServiceException("提醒数量不正确",422);
        for(Map<String,Object> item:items){
            long taskId=id(item.get("taskId")),v=version(item.get("noticeVersion"));
            // 不把晚到的旧弹窗确认覆盖到新一轮驳回提醒上。
            db.update("update kg_staff_task t set seen_notice_version=:v where t.task_id=:id and t.assignee_id=:u and t.notice_version=:v"+OWN,p("id",taskId,"u",uid,"v",v));
        }
    }
}
