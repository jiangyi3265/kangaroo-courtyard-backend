package com.ruoyi.kangaroo.ext.community;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.util.AccountState;

/** Reception is a chat capability, never a recruitment/audit grant. */
@Service
public class KgChatReception
{
    @Autowired private KgJdbc db;
    @Autowired(required=false) private com.ruoyi.kangaroo.ext.staff.CityLeadScope cityLeads;
    static final String ACTIVE = " from kg_staff_grant g join kg_store s on s.store_id=g.store_id "
            + "join kg_app_user u on u.user_id=g.app_user_id where g.store_id=:s and g.status='0' and g.del_flag='0' "
            + "and s.status='0' and s.del_flag='0' and u.status='0' and u.del_flag='0' "
            // 商家服务人、城市负责人不进接待：接待名单、会话席位都不算他们
            + "and g.role_code not in ('merchant_service','city_lead')";
    // 商家需求自动补的换工主理人：管岗位和报名，不碰线上任务（2026-10-29 的决定），不进线上会话、不能当线上接待。线下报名的会话照旧在席
    private static final String NOT_AUTO_MANAGER = " and not (g.role_code='manager' and coalesce(g.granted_by,'')='" + com.ruoyi.kangaroo.ext.staff.KgMerchantRequestService.GRANTED_BY + "')";
    // 线下报名会话的席位：全体在职员工，线上任务专员除外。staff(store,false) 和报名结束页的安全负责人用同一个条件，选出来的人一定在这段会话里
    private static final String OFFLINE_SEAT = " and g.role_code <> 'online_staff'";

    public static List<Long> selection(Object raw)
    {
        if (!(raw instanceof List) || ((List<?>)raw).size() > 2) throw new ServiceException("请选择零至两位本店在职员工", 422);
        List<Long> result = new ArrayList<Long>();
        for (Object value : (List<?>)raw)
        {
            if (value == null || !String.valueOf(value).matches("[1-9][0-9]{0,18}")) throw new ServiceException("接待账号格式不正确", 422);
            long id;
            try { id = Long.parseLong(String.valueOf(value)); }
            catch (NumberFormatException e) { throw new ServiceException("接待账号格式不正确", 422); }
            if (result.contains(id)) throw new ServiceException("接待账号不能重复", 422);
            result.add(id);
        }
        return result;
    }

    /** Always read current DB grant/account; Redis role snapshots do not authorize settings. */
    private Map<String,Object> manager(Long me, Long store)
    {
        // 城市负责人能替他管的门店设置接待账号（他自己不进接待名单，名单还是本店员工）
        if(cityLeads!=null&&cityLeads.covers(me,store))return p("roleCode","city_lead");
        Map<String,Object> role = db.one("select g.role_code,g.granted_by" + ACTIVE + " and g.app_user_id=:u", p("s",store,"u",me));
        if (role == null || !("manager".equals(role.get("roleCode")) || "keeper".equals(role.get("roleCode")))) throw new ServiceException("仅本店主理人或管家可设置线上接待账号",403);
        if ("manager".equals(role.get("roleCode")) && com.ruoyi.kangaroo.ext.staff.KgMerchantRequestService.GRANTED_BY.equals(role.get("grantedBy"))) throw new ServiceException("这家店是派单给你的，线上接待账号要由商家或平台设置",403);
        return role;
    }

    public Map<String,Object> settings(Long me, Long store)
    {
        manager(me,store);
        List<Map<String,Object>> rows = db.list("select u.user_id,u.nickname,g.role_code"+ACTIVE+NOT_AUTO_MANAGER+" order by g.grant_id",p("s",store));
        Map<String,Object> saved = db.one("select first_id,second_id,revision from kg_chat_reception where store_id=:s",p("s",store));
        List<Long> ids = new ArrayList<Long>();
        if (saved != null) for(String key:Arrays.asList("firstId","secondId")) if(saved.get(key)!=null)ids.add(KgJdbc.asLong(saved.get(key)));
        return p("storeId",store,"selectedUserIds",ids,"staff",rows,"revision",saved==null?0L:saved.get("revision"));
    }

    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> save(Long me,Long store,Object raw)
    {
        List<Long> selected=selection(raw);
        // All account locks precede store/conversation locks, in stable order.
        TreeSet<Long> locks=new TreeSet<Long>(selected);locks.add(me);
        for(Long uid:locks)AccountState.requireActive(db,uid);
        lockStore(store);
        manager(me,store);
        for(Long uid:selected) if(db.count("select count(*)"+ACTIVE+NOT_AUTO_MANAGER+" and g.app_user_id=:u",p("s",store,"u",uid))!=1)
            throw new ServiceException("只能选择本店当前在职且可用的员工账号",422);
        db.update("insert into kg_chat_reception(store_id,first_id,second_id,revision,updated_by,update_time) values(:s,:a,:b,1,:u,now()) "
                +"on duplicate key update first_id=:a,second_id=:b,revision=revision+1,updated_by=:u,update_time=now()",
                p("s",store,"a",selected.size()>0?selected.get(0):null,"b",selected.size()>1?selected.get(1):null,"u",me));
        assignExisting(store);
        return settings(me,store);
    }

    /** A current assignment notice, never a fabricated old applicant message. */
    private void assignExisting(Long store)
    {
        List<Long> receivers=staff(store,true);
        if(receivers.isEmpty())return;
        // Store lock is held; all conversation locks precede member rows. Legacy
        // mixed pairs are deliberately excluded rather than reclassified.
        List<Map<String,Object>> conversations=db.list("select c.conv_id,c.conv_type,c.pair_key,c.source,c.source_id,c.store_id,c.title from kg_chat_conv c where c.store_id=:s and c.conv_type='store' "
                +"and c.source='online_task' and c.status='active' and (select count(*) from kg_chat_member m where m.conv_id=c.conv_id and m.role='member')=1 "
                +"and exists(select 1 from kg_chat_member a join kg_app_user u on u.user_id=a.app_user_id and u.status='0' and u.del_flag='0' "
                +"where a.conv_id=c.conv_id and a.role='member' and a.left_time is null and c.pair_key=concat('o',c.store_id,'-u',a.app_user_id) "
                +"and exists(select 1 from kg_chat_msg q where q.conv_id=c.conv_id and q.sender_id=a.app_user_id and q.status in ('visible','recalled'))) "
                +"order by c.conv_id for update",p("s",store));
        for(Map<String,Object> row:conversations)
        {
            Long conv=KgJdbc.asLong(row.get("convId"));
            Long applicant=applicant(row);
            String title=null;
            for(Long uid:receivers)
            {
                if(uid.equals(applicant))continue;
                Map<String,Object> seat=db.one("select muted,left_time from kg_chat_member where conv_id=:c and app_user_id=:u",p("c",conv,"u",uid));
                db.update("insert into kg_chat_member(conv_id,app_user_id,role,join_time) values(:c,:u,'store',now()) "
                        +"on duplicate key update left_time=null,role=if(role='owner',role,'store')",p("c",conv,"u",uid));
                if(seat!=null && "1".equals(KgJdbc.asStr(seat.get("muted"))))continue;
                // Existing notices retain their exact body and timestamp. An
                // employee newly assigned an old question receives only this
                // neutral current assignment, so they can find its conversation.
                // 标题和这段会话在员工消息列表里的标题一样是「接单人昵称 · 任务名」（2026-10-15 主人反馈）：之前固定写「线上接待安排」，
                // 看不出是哪位接单人；「线上接待安排」挪到正文开头
                if(db.count("select count(*) from kg_message where recipient_id=:u and mode='staff' and biz_type='chat_conv' and biz_id=:c and del_flag='0'",p("c",conv,"u",uid))==0)
                {
                    if(title==null)title=staffSideTitle(db,row);
                    db.update("insert into kg_message(recipient_id,mode,category,title,content,biz_type,biz_id,route,read_flag,del_flag,create_time) "
                            +"values(:u,'staff','chat',:t,'线上接待安排：已安排你接待这段线上沟通，请进入会话查看。','chat_conv',:c,:r,'0','0',now())",
                            p("u",uid,"c",conv,"t",title,"r","/pages/community/chat?convId="+conv));
                }
            }
        }
    }

    public void lockStore(Long store)
    {
        Map<String,Object> row=db.one("select store_id from kg_store where store_id=:s and status='0' and del_flag='0' for update",p("s",store));
        if(row==null)throw new ServiceException("门店不存在或已停用",403);
    }

    public List<Long> staff(Long store,boolean online)
    {
        String filter=online?" and (not exists(select 1 from kg_chat_reception r where r.store_id=g.store_id and (r.first_id is not null or r.second_id is not null)) "
                +"or exists(select 1 from kg_chat_reception r where r.store_id=g.store_id and (r.first_id=g.app_user_id or r.second_id=g.app_user_id)))"+NOT_AUTO_MANAGER:OFFLINE_SEAT;
        List<Long> ids=new ArrayList<Long>();
        for(Map<String,Object> row:db.list("select g.app_user_id"+ACTIVE+filter+" order by g.grant_id",p("s",store)))ids.add(KgJdbc.asLong(row.get("appUserId")));
        return ids;
    }

    /**
     * 报名结束页的「安全负责人」（2026-10-31 主人反馈）：名字和头像给报名人看，点了打开这家店的线下会话（chat?appId=，「联系专员」同一条路），所以只从这段会话的席位里选，
     * 不按城市找人（本城的换工主理人没有这家店的授权时进不了这段会话）。查的是当前 role_code，切换后自然变（legacy 的 manager 是老主理人，
     * new 的 manager 是换工主理人；城市负责人、商家服务人、专员都不在席位里）；切换后残留的老 staff 行不算有授权（RolePhase.usable），不能把用户指给一个进不了工作台的账号。
     * 报名人自己不算。不带手机号，昵称里的联系方式遮掉。
     * 没有一个人时 kind = service，页面退到人工客服。
     */
    public Map<String,Object> offlineLead(Long store,Long applicant,Long jobId)
    {
        List<Map<String,Object>> seats=new ArrayList<Map<String,Object>>();
        if(store!=null)
        {
            for(Map<String,Object> row:db.list("select g.app_user_id,g.role_code,g.granted_by,u.nickname,u.avatar"+ACTIVE+OFFLINE_SEAT+" order by g.grant_id",p("s",store)))
            {
                if((applicant==null||!applicant.equals(KgJdbc.asLong(row.get("appUserId"))))&&com.ruoyi.kangaroo.ext.staff.RolePhase.usable(text(row.get("roleCode"))))seats.add(row);
            }
        }
        Long verifier=null;
        if(jobId!=null&&!seats.isEmpty())
        {
            Map<String,Object> request=db.one("select verifier_user_id from kg_merchant_request where job_id=:j and del_flag='0' limit 1",p("j",jobId));
            verifier=request==null?null:KgJdbc.asLong(request.get("verifierUserId"));
        }
        return leadView(pickLead(seats,verifier));
    }

    /** 选人的规则（纯函数）：上架这个岗位的人 → 换工主理人（自己的授权优先于派单补的）→ 小院主理人 → 其余（合作商家、老员工）；seats 已按 grant_id 排好 */
    static Map<String,Object> pickLead(List<Map<String,Object>> seats,Long verifier)
    {
        Map<String,Object> autoManager=null,keeper=null,other=null;
        for(Map<String,Object> row:seats)
        {
            if(verifier!=null&&verifier.equals(KgJdbc.asLong(row.get("appUserId"))))return row;
        }
        for(Map<String,Object> row:seats)
        {
            String role=text(row.get("roleCode"));
            if("manager".equals(role))
            {
                if(!com.ruoyi.kangaroo.ext.staff.KgMerchantRequestService.GRANTED_BY.equals(text(row.get("grantedBy"))))return row;
                if(autoManager==null)autoManager=row;
            }
            else if("keeper".equals(role))
            {
                if(keeper==null)keeper=row;
            }
            else if(other==null)other=row;
        }
        return autoManager!=null?autoManager:keeper!=null?keeper:other;
    }

    static Map<String,Object> leadView(Map<String,Object> row)
    {
        Map<String,Object> m=new LinkedHashMap<String,Object>();
        if(row==null)
        {
            m.put("kind","service");
            m.put("roleLabel","人工客服");
            m.put("name","袋鼠客服");
            m.put("avatar","");
            return m;
        }
        String role=text(row.get("roleCode"));
        boolean manager="manager".equals(role),keeper="keeper".equals(role);
        m.put("kind",manager?"manager":keeper?"keeper":"store");
        m.put("roleLabel",manager?"换工主理人":keeper?"小院主理人":"门店接待");
        m.put("name",com.ruoyi.kangaroo.util.MaskUtils.contacts(text(row.get("nickname"))));
        m.put("avatar",text(row.get("avatar")));
        return m;
    }

    /**
     * 线上任务专员一定在会话里（2026-10-16 主人拍板）：专员发布的线上任务，接单人那边按钮叫「联系专员」，发布的专员一定是门店这一侧的席位——
     * 即使门店设了线上接待账号、名单里没有 TA；同事里有接待资格的照旧能看到、能回复。
     * 门店会话是「门店 × 接单人」一个（o{门店}-u{接单人}），换着从不同任务进来是同一段会话，所以按「这位接单人在本店报过哪些专员发布的线上任务」算，
     * 不只看会话当前挂着的那份报名：否则接单人从另一个任务进来，前一位专员就被挤出会话、看不到自己的接单人了。
     * 专员 = kg_job.create_by 是 'staff:<uid>'，且此人现在仍是本店在职（授权、门店、账号都正常）的线上任务专员（role_code = online_staff）；
     * 授权撤销或改成其他角色后不再算，席位随之失效（和接待名单一样，以当前授权为准）。按 ID 升序。
     */
    public List<Long> publishers(Long store,Long applicant)
    {
        List<Long> ids=new ArrayList<Long>();
        if(store==null||applicant==null)return ids;
        for(Map<String,Object> row:db.list("select distinct g.app_user_id"+ACTIVE+" and g.role_code='online_staff' and exists(select 1 from kg_application a "
                +"join kg_job j on j.job_id=a.job_id where a.store_id=g.store_id and a.app_user_id=:a and a.del_flag='0' and j.job_type='online' "
                +"and j.create_by=concat('staff:',g.app_user_id)) order by g.app_user_id",p("s",store,"a",applicant)))
        {
            Long uid=KgJdbc.asLong(row.get("appUserId"));
            if(uid!=null&&!ids.contains(uid))ids.add(uid);
        }
        return ids;
    }

    /** 这份报名的任务是不是本店在职专员发布的线上任务：是就返回专员 ID（接单人「联系专员」、会话顶栏「专员 · 昵称」），否则 null */
    public Long specialistOf(Long store,Object appId)
    {
        if(store==null||appId==null)return null;
        Map<String,Object> row=db.one("select g.app_user_id"+ACTIVE+" and g.role_code='online_staff' and exists(select 1 from kg_application a "
                +"join kg_job j on j.job_id=a.job_id where a.app_id=:a and a.store_id=g.store_id and a.del_flag='0' and j.job_type='online' "
                +"and j.create_by=concat('staff:',g.app_user_id)) order by g.grant_id limit 1",p("s",store,"a",appId));
        return row==null?null:KgJdbc.asLong(row.get("appUserId"));
    }

    /** 门店这一侧的席位：接待名单（线下 / 没设名单时是全体在职员工）+ 线上会话里发布过这位接单人所接任务的专员 */
    public List<Long> seats(Long store,boolean online,Long applicant)
    {
        List<Long> base=staff(store,online);
        List<Long> ids=base==null?new ArrayList<Long>():new ArrayList<Long>(base);
        if(online&&applicant!=null)for(Long uid:publishers(store,applicant))if(!ids.contains(uid))ids.add(uid);
        return ids;
    }

    /** 一段门店会话的席位（见上）；历史混合会话（s 开头的老 pair）照旧只认接待名单，不加专员 */
    public List<Long> seats(Map<String,Object> c)
    {
        boolean online=isOnline(c);
        Long applicant=null;
        if(online&&!legacy(c))
        {
            try{applicant=applicant(c);}
            catch(ServiceException e){applicant=null;}
        }
        return seats(KgJdbc.asLong(c.get("storeId")),online,applicant);
    }

    public boolean isOnline(Map<String,Object> conv)
    {
        // Old s-pairs mixed both task kinds. Current job.type/source_id cannot
        // prove history, so ALL legacy pairs use the stricter reception scope.
        return "online_task".equals(conv.get("source")) || legacy(conv);
    }
    public static boolean legacy(Map<String,Object> conv)
    {
        return "store".equals(conv.get("convType")) && String.valueOf(conv.get("pairKey")).matches("s[0-9]+-u[0-9]+");
    }
    public void lockConversationStore(long convId)
    {
        Map<String,Object> c=db.one("select conv_type,store_id from kg_chat_conv where conv_id=:c",p("c",convId));
        if(c!=null && "store".equals(c.get("convType")))lockStore(KgJdbc.asLong(c.get("storeId")));
    }

    static Long applicant(Map<String,Object> c)
    {
        if(c==null || !"store".equals(c.get("convType")))throw new ServiceException("会话归属不正确",403);
        Long store=KgJdbc.asLong(c.get("storeId"));
        String key=String.valueOf(c.get("pairKey"));
        String prefix=legacy(c)?"s"+store+"-u":"online_task".equals(c.get("source"))?"o"+store+"-u":"offline:"+store+":u";
        if(store==null || !key.startsWith(prefix) || !key.substring(prefix.length()).matches("[1-9][0-9]{0,18}"))
            throw new ServiceException("历史会话归属不完整，请从任务重新进入",403);
        try{return Long.valueOf(key.substring(prefix.length()));}
        catch(NumberFormatException e){throw new ServiceException("会话归属不正确",403);}
    }

    /** 门店会话里报名人 / 接单人的名字：昵称，没填写「用户{id}」（和消息里发送人的名字同一规则）；会话归属不完整时退回会话标题（门店名） */
    static String applicantName(KgJdbc db, Map<String,Object> c)
    {
        Long uid;
        try{uid=applicant(c);}
        catch(ServiceException e){return text(c.get("title"));}
        Map<String,Object> u=db.one("select user_id, nickname from kg_app_user where user_id = :u",p("u",uid));
        String name=u==null?"":text(u.get("nickname"));
        return name.isEmpty()?"用户"+uid:name;
    }

    /**
     * 门店会话在员工这一侧的标题：「报名人昵称 · 岗位 / 任务名」（取会话当前对应的那份报名），一眼看出在和谁聊、聊的是哪份报名。
     * 员工消息列表里会话那一行（KgChatService 发消息时写）、「线上接待安排」提醒都用这一条规则；
     * acceptance/20261015-feedback/refresh_chat_titles.sql 按同一规则一次性刷新 10-15 以前写下的旧行，改这里要一起改。
     */
    static String staffSideTitle(KgJdbc db, Map<String,Object> c)
    {
        Map<String,Object> job=c.get("sourceId")==null?null
                :db.one("select j.title from kg_application a join kg_job j on j.job_id = a.job_id where a.app_id = :a",p("a",c.get("sourceId")));
        String jobTitle=job==null?"":text(job.get("title"));
        String name=applicantName(db,c);
        return StaffSupport.cut(jobTitle.isEmpty()?name:name+" · "+jobTitle,64);
    }

    private static String text(Object v)
    {
        String s=KgJdbc.asStr(v);
        return s==null?"":s;
    }

    /**
     * 城市负责人顶上：他不在接待名单和默认席位里（平时不收这些会话的提醒），但能管的门店的报名他能进会话回复；进过之后才算成员、才收提醒。
     * 只在切换后有；范围每次现算（CityLeadScope）。
     */
    public boolean steppingIn(Long me,Long store)
    {
        return cityLeads!=null&&me!=null&&store!=null&&cityLeads.covers(me,store);
    }

    public void requireStoreIdentity(Long me, Map<String,Object> c)
    {
        if(c==null || !"store".equals(c.get("convType")))return;
        Long store=KgJdbc.asLong(c.get("storeId"));
        Long applicant=applicant(c);
        if(db.count("select count(*) from kg_chat_member where conv_id=:c and role='member'",p("c",c.get("convId")))!=1
                || db.count("select count(*) from kg_chat_member m join kg_app_user u on u.user_id=m.app_user_id "
                +"where m.conv_id=:c and m.role='member' and m.app_user_id=:a and u.status='0' and u.del_flag='0'",
                p("c",c.get("convId"),"a",applicant))!=1)
            throw new ServiceException("会话报名人已不可用",403);
        if(!applicant.equals(me) && !steppingIn(me,store) && (!seats(c).contains(me) || (legacy(c) && !staff(store,false).contains(me))))
            throw new ServiceException("当前账号没有此会话的接待权限",403);
    }
}
