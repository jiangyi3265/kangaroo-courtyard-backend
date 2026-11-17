package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.util.MaskUtils;

/** Cross-store forwarding exposes a masked view, never an application authorization. */
@Service
public class KgResumeShareService {
    private final KgJdbc db;
    private final StaffSupport support;
    private final KgWorkbenchService workbench;
    /** 按城市授权的员工；单测没注入时不算 */
    @org.springframework.beans.factory.annotation.Autowired(required=false) private CityGrantScope cityGrants;
    public KgResumeShareService(KgJdbc db, StaffSupport support, KgWorkbenchService workbench) {
        this.db=db; this.support=support; this.workbench=workbench;
    }
    public List<Map<String,Object>> stores(Long source, String q) {
        StaffSupport.Ctx c = support.ctx(source);
        support.need(c,"jobs",StaffPerms.VIEW,"resume:forward","简历转发");
        return db.list("select s.store_id, s.store_name, s.city from kg_store s where s.status='0' and s.del_flag='0' and s.store_id<>:s "
            + "and exists(select 1 from kg_staff_grant g where g.store_id=s.store_id and g.status='0' and g.del_flag='0' and g.app_user_id<>:u and g.role_code not in ('online_staff','merchant_service','city_lead')) "
            + "and (:q='' or s.store_name like concat('%',:q,'%') or s.city like concat('%',:q,'%')) order by s.store_id limit 50",p("s",c.storeId,"u",c.uid(),"q",q==null?"":q.trim()));
    }
    /** Image exports use a separate whitelist; original resume text/contact fields never leave this endpoint. */
    public Map<String,Object> poster(Long source, Long appId) {
        StaffSupport.Ctx c = support.ctx(source);
        support.need(c,"jobs",StaffPerms.VIEW,"resume:poster","生成脱敏简历图片");
        Map<String,Object> a=db.one("select a.real_name,a.age,a.skills,a.arrival_date,a.stay_days,j.title,j.job_type from kg_application a join kg_job j on j.job_id=a.job_id "
            + "where a.app_id=:id and a.store_id=:s and a.del_flag='0' and exists(select 1 from kg_app_user u where u.user_id=a.app_user_id and u.del_flag='0') and a.status not in ('withdrawn','terminated','review_rejected','completed')",
            p("id",appId,"s",c.storeId));
        if (a == null) throw new ServiceException("报名已失效或无权导出", 404);
        boolean online="online".equals(a.get("jobType"));
        return p("name",MaskUtils.name(KgJdbc.asStr(a.get("realName"))),"age",a.get("age"),"jobTitle",a.get("title"),"online",online,
            "skills",com.ruoyi.kangaroo.ext.user.KgSkills.tagsOf(KgJdbc.asStr(a.get("skills"))),
            "arriveDate",online?null:a.get("arrivalDate"),"stayDays",online?null:a.get("stayDays"));
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> send(Long source, Long appId, Long target, String note, boolean track) {
        StaffSupport.Ctx c = support.ctx(source);
        support.need(c,"jobs",StaffPerms.EDIT,"resume:forward","简历转发");
        Map<String,Object> subject = db.must("select app_user_id from kg_application where app_id=:id and store_id=:s and del_flag='0'",p("id",appId,"s",c.storeId),"报名不存在");
        com.ruoyi.kangaroo.util.AccountState.requireActive(db, KgJdbc.asLong(subject.get("appUserId")));
        db.one("select app_id from kg_application where app_id=:id for update",p("id",appId));
        Map<String,Object> app = db.must("select a.app_id from kg_application a where a.app_id=:id and a.store_id=:s and a.del_flag='0' and a.status not in ('withdrawn','terminated','review_rejected','completed')",p("id",appId,"s",c.storeId),"报名已失效或无权转发");
        if (target==null || target.equals(c.storeId)) throw new ServiceException("请选择其他门店；本店请使用门店同事选项",422);
        Map<String,Object> store=db.must("select store_name from kg_store where store_id=:id and status='0' and del_flag='0'",p("id",target),"目标门店不存在或已停用");
        String suggestion=note==null?"":note.trim();
        if(suggestion.length()>120) throw new ServiceException("审核建议 120 字以内",422);
        // 联系方式按社区同一套规则认（138-1234-5678、138 1234 5678、「微信 xxx」之前都能写进来）
        if(suggestion.matches("(?s).*\\d{7,}.*") || suggestion.contains("@") || com.ruoyi.kangaroo.ext.community.ContactMask.contains(suggestion)) throw new ServiceException("脱敏转发建议中请勿填写联系方式",422);
        // 线上任务专员看不到这类站内信、也打不开跨店简历，不算收件人（否则人数多算、已读凑不满）
        List<Map<String,Object>> recipients=db.list("select distinct app_user_id from kg_staff_grant where store_id=:s and status='0' and del_flag='0' and app_user_id<>:u and role_code not in ('online_staff','merchant_service','city_lead')",p("s",target,"u",c.uid()));
        // 目标门店本城按城市授权的员工（同样不含专员、商家服务人）也收
        if(cityGrants!=null) {
            recipients=new ArrayList<>(recipients);
            for(Map<String,Object> h:cityGrants.holders(target)) {
                Long uid=KgJdbc.asLong(h.get("appUserId"));
                if(uid!=null && !uid.equals(c.uid()) && !Arrays.asList("online_staff","merchant_service","city_lead").contains(KgJdbc.asStr(h.get("roleCode")))) recipients.add(p("appUserId",uid));
            }
        }
        if(recipients.isEmpty()) throw new ServiceException("目标门店没有其他可接收的员工",409);
        Map<String,Object> row=p("app_id",appId,"store_id",c.storeId,"from_user_id",c.uid(),"from_name",StaffSupport.cut(c.name(),64),
            "target","cross_store","target_store_id",target,"target_store_name",store.get("storeName"),"suggestion",suggestion,"track",track?"1":"0","create_time",new Date());
        long id=db.insert("kg_resume_forward",row);
        List<String> ids=new ArrayList<>();
        for(Map<String,Object> r:recipients) {
            Long msg=support.notifyStaffWithId(KgJdbc.asLong(r.get("appUserId")),"staff","收到跨店脱敏简历",c.storeName+"发来一份招聘协作简历，请在消息中查看。","resume_forward",id,"/pages/staff/applications?s=5&shareId="+id);
            if(msg!=null) ids.add(String.valueOf(msg));
        }
        if(ids.isEmpty()) throw new ServiceException("目标门店员工均关闭了此类通知，本次未发送",409);
        // Never truncate a message ID in the tracking field.
        String joined=String.join(",",ids);
        if(joined.length()>500) throw new ServiceException("接收人数过多，请联系平台处理",422);
        db.update("update kg_resume_forward set msg_ids=:m where forward_id=:id",p("m",joined,"id",id));
        support.op(c,"resume:forward","跨店简历转发","general","转发至 "+store.get("storeName"),true,null);
        return p("message","已转发给"+store.get("storeName")+"的 "+ids.size()+" 位同事","count",ids.size(),"records",workbench.forwards(c.storeId,appId));
    }
    public Map<String,Object> view(Long id) {
        Map<String,Object> f=db.one("select * from kg_resume_forward where forward_id=:id and target='cross_store'",p("id",id));
        if (f == null) throw new ServiceException("转发记录不存在", 404);
        StaffSupport.Ctx c=support.ctx(KgJdbc.asLong(f.get("targetStoreId")));
        support.need(c,"jobs",StaffPerms.VIEW,"resume:view","查看脱敏简历");
        Map<String,Object> a=db.one("select a.real_name,a.age,a.skills,a.arrival_date,a.app_user_id,j.title from kg_application a join kg_job j on j.job_id=a.job_id "
            + "where a.app_id=:id and a.del_flag='0' and exists(select 1 from kg_app_user u where u.user_id=a.app_user_id and u.del_flag='0') and a.status not in ('withdrawn','terminated','review_rejected','completed')",p("id",f.get("appId")));
        if (a == null) throw new ServiceException("该报名已结束或撤回，简历转发已失效", 404);
        // Only structured, whitelisted fields leave the source store.
        return p("name",MaskUtils.name(KgJdbc.asStr(a.get("realName"))),"age",a.get("age"),"jobTitle",a.get("title"),
            "skills",com.ruoyi.kangaroo.ext.user.KgSkills.tagsOf(KgJdbc.asStr(a.get("skills"))),"arriveDate",a.get("arrivalDate"),
            "exps",exps(a.get("appUserId")),
            "from",f.get("fromName"),"suggestion",f.get("suggestion"),"storeName",f.get("targetStoreName"));
    }
    /** 跨店脱敏简历里的经历（之前这一块转出去是空的）：只带时间、岗位和类型，不带地点和描述原文；岗位名是候选人自己填的，里面的联系方式也隐去 */
    private List<Map<String,Object>> exps(Object userId) {
        List<Map<String,Object>> out=new ArrayList<>();
        for (Map<String,Object> e : db.list("select kind,title,start_date,end_date from kg_resume_exp where app_user_id=:u and del_flag='0' "
            + "order by coalesce(start_date,create_time) desc limit 10",p("u",userId))) {
            String k=KgJdbc.asStr(e.get("kind"));
            String s=e.get("startDate")==null?"":String.valueOf(e.get("startDate")).substring(0,7).replace('-','.');
            String en=e.get("endDate")==null?"至今":String.valueOf(e.get("endDate")).substring(0,7).replace('-','.');
            out.add(p("date",s.isEmpty()?"时间未填":s+"-"+en,"role",MaskUtils.contacts(KgJdbc.asStr(e.get("title")))+"（"+("volunteer".equals(k)?"换工":"parttime".equals(k)?"兼职":"training".equals(k)?"培训":"全职")+"）"));
        }
        return out;
    }
}
