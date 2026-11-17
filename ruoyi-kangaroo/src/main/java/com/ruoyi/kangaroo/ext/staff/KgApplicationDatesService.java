package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.service.IKgMessageService;

/** 到岗起止日期独立于录用状态。商家提交新版本，报名人确认后才更新正式行程。 */
@Service
public class KgApplicationDatesService {
    @Autowired private KgJdbc db;
    @Autowired private IKgMessageService messages;
    @Autowired private StaffSupport support;
    @Autowired(required=false) private CityLeadScope cityLeads;
    @Autowired(required=false) private CityGrantScope cityGrants;
    private static final List<String> EDITABLE = Arrays.asList("user_confirmed", "store_confirmed", "contract_signed");

    private Map<String,Object> application(Long id, boolean lock) {
        if (id == null || id <= 0) throw new ServiceException("报名编号无效",400);
        return db.must("select a.app_id,a.app_user_id,a.store_id,a.status,a.arrival_date,a.stay_days,"
                + "j.job_type,j.start_date job_start_date,j.min_days from kg_application a join kg_job j on j.job_id=a.job_id "
                + "where a.app_id=:a and a.del_flag='0'" + (lock ? " for update" : ""), p("a",id), "报名记录不存在");
    }
    private boolean staffAllowed(Long uid, Long store, String action) {
        Map<String,Object> ps = p("u",uid,"s",store);
        if (db.count("select count(*) from kg_app_user u join kg_store s on s.store_id=:s "
                + "where u.user_id=:u and u.status='0' and u.del_flag='0' and s.status='0' and s.del_flag='0'",ps) != 1) return false;
        for (Map<String,Object> g : db.list("select role_code from kg_staff_grant where app_user_id=:u and store_id=:s and status='0' and del_flag='0'",ps))
            if (RolePhase.usable(str(g.get("roleCode"))) && StaffPerms.allows(str(g.get("roleCode")),"jobs",action)) return true;
        if (cityLeads != null && cityLeads.covers(uid,store) && StaffPerms.allows("city_lead","jobs",action)) return true;
        // 按城市授权：本城门店按所选角色算（这家店有门店授权或撤销记录时以门店授权为准）
        String cityRole = cityGrants == null ? null : cityGrants.roleAt(uid,store);
        return cityRole != null && StaffPerms.allows(cityRole,"jobs",action);
    }
    private Map<String,Object> authorized(AppLoginUser actor, Long id, boolean staff, boolean write, boolean lock) {
        Map<String,Object> a = application(id,lock);
        if (!Arrays.asList("volunteer","parttime","fulltime").contains(str(a.get("jobType"))))
            throw new ServiceException("线上任务不设置到岗日期",422);
        Long uid = actor.getUserId();
        if (staff ? !staffAllowed(uid,number(a.get("storeId")),write ? StaffPerms.EDIT : StaffPerms.VIEW) : !uid.equals(number(a.get("appUserId"))))
            throw new ServiceException("无权查看或修改这次报名的时间",403);
        if (staff && write && uid.equals(number(a.get("appUserId")))) throw new ServiceException("请由本店其他负责人调整本人报名的日期",403);
        if (write && !EDITABLE.contains(str(a.get("status")))) throw new ServiceException("仅可在双方确认及签署协议阶段调整到岗时间",409);
        return a;
    }
    private Map<String,Object> latest(Long id) {
        return db.one("select app_id,revision,start_date,end_date,proposed_by,proposed_time,confirmed_by,confirmed_time "
                + "from kg_application_date_plan where app_id=:a order by revision desc limit 1",p("a",id));
    }
    public Map<String,Object> detail(AppLoginUser actor, Long id, boolean staff) {
        return view(actor,authorized(actor,id,staff,false,false),staff);
    }
    private Map<String,Object> view(AppLoginUser actor, Map<String,Object> a, boolean staff) {
        Long id = number(a.get("appId"));
        Map<String,Object> plan = latest(id), out = new LinkedHashMap<>();
        out.put("appId",id);
        // 仅返回日期和确认状态，不公开员工账号标识。
        out.put("revision",plan == null ? 0L : number(plan.get("revision")));
        out.put("startDate",plan == null ? null : plan.get("startDate"));
        out.put("endDate",plan == null ? null : plan.get("endDate"));
        out.put("proposedTime",plan == null ? null : plan.get("proposedTime"));
        out.put("confirmedTime",plan == null ? null : plan.get("confirmedTime"));
        out.put("state",plan == null ? "not_set" : plan.get("confirmedTime") == null ? "pending" : "confirmed");
        out.put("applicationStart",a.get("arrivalDate"));
        LocalDate start = parseOptional(a.get("arrivalDate"));
        Long days = number(a.get("stayDays"));
        out.put("applicationEnd",start == null || days == null || days <= 0 ? null : start.plusDays(days-1).toString());
        int minDays = Math.max(1,number(a.get("minDays")) == null ? 1 : number(a.get("minDays")).intValue());
        LocalDate earliest = today(), jobStart = parseOptional(a.get("jobStartDate"));
        if (jobStart != null && jobStart.isAfter(earliest)) earliest = jobStart;
        out.put("minDate",earliest.toString()); out.put("minDays",minDays);
        boolean active = EDITABLE.contains(str(a.get("status")));
        out.put("canEdit",staff && active && !actor.getUserId().equals(number(a.get("appUserId")))
                && staffAllowed(actor.getUserId(),number(a.get("storeId")),StaffPerms.EDIT));
        out.put("canConfirm",!staff && active && plan != null && plan.get("confirmedTime") == null);
        out.put("closed",!active);
        return out;
    }

    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> propose(AppLoginUser actor, Long id, Map<String,Object> body) {
        authorized(actor,id,true,true,false);
        Map<String,Object> a = authorized(actor,id,true,true,true);
        long expected = revision(body);
        LocalDate start = date(body,"startDate"), end = date(body,"endDate");
        validate(start,end,a);
        Map<String,Object> old = latest(id);
        long actual = old == null ? 0 : number(old.get("revision"));
        boolean same = old != null && start.toString().equals(str(old.get("startDate"))) && end.toString().equals(str(old.get("endDate")));
        // 双击/响应丢失后重试不重复发送通知，也不撤销已经完成的确认。
        if (same && (expected == actual || (expected == actual-1 && actor.getUserId().equals(number(old.get("proposedBy")))))) return view(actor,a,true);
        if (expected != actual) throw changed();
        db.update("insert into kg_application_date_plan (app_id,revision,start_date,end_date,proposed_by,proposed_time) "
                + "values (:a,:r,:s,:e,:u,now())",p("a",id,"r",actual+1,"s",start.toString(),"e",end.toString(),"u",actor.getUserId()));
        messages.send(number(a.get("appUserId")),"user","apply","请确认到岗时间", "商家已" + (old == null ? "设置" : "调整")
                + "到岗／入住时间：" + start + " 至 " + end + "。请打开报名进度核对并确认。", "application",id,"/pages/jobs/interview?appId="+id);
        return view(actor,a,true);
    }

    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> confirm(AppLoginUser actor, Long id, Map<String,Object> body) {
        authorized(actor,id,false,true,false);
        Map<String,Object> a = authorized(actor,id,false,true,true), plan = latest(id);
        if (plan == null || revision(body) != number(plan.get("revision"))) throw changed();
        if (plan.get("confirmedTime") != null) return view(actor,a,false);
        LocalDate start = date(plan,"startDate"), end = date(plan,"endDate");
        validate(start,end,a);
        db.update("update kg_application_date_plan set confirmed_by=:u,confirmed_time=now() where app_id=:a and revision=:r and confirmed_time is null",
                p("u",actor.getUserId(),"a",id,"r",number(plan.get("revision"))));
        int days = (int)ChronoUnit.DAYS.between(start,end)+1;
        db.update("update kg_application set arrival_date=:s,stay_days=:d,update_time=now() where app_id=:a and del_flag='0'",
                p("s",start.toString(),"d",days,"a",id));
        support.notifyStore(number(a.get("storeId")),"apply","报名人已确认到岗时间", "已确认到岗／入住时间："+start+" 至 "+end+"，共 "+days+" 天。",
                "application",id,"/pages/staff/applications?appId="+id);
        messages.send(actor.getUserId(),"user","apply","到岗时间已确认", "已与商家确认："+start+" 至 "+end+"，报名与行程日期已更新。",
                "application",id,"/pages/jobs/interview?appId="+id);
        a.put("arrivalDate",start.toString()); a.put("stayDays",days);
        return view(actor,a,false);
    }
    private static ServiceException changed() { return new ServiceException("时间段已更新，请刷新后重新核对确认",409); }
    private static long revision(Map<String,Object> body) {
        String s = body == null ? "" : str(body.get("revision"));
        if (!s.matches("[0-9]{1,15}")) throw new ServiceException("请刷新时间信息后重试",400);
        return Long.parseLong(s);
    }
    static int validate(LocalDate start, LocalDate end, Map<String,Object> a) {
        if (start.isBefore(today())) throw new ServiceException("开始日期已过，请联系商家重新设置",422);
        LocalDate earliest = parseOptional(a.get("jobStartDate"));
        if (earliest != null && start.isBefore(earliest)) throw new ServiceException("开始日期不能早于岗位可到岗日期",422);
        long days = ChronoUnit.DAYS.between(start,end)+1;
        Long min = number(a.get("minDays"));
        if (days < 1 || days > 365) throw new ServiceException("结束日期不能早于开始日期，时间段最多 365 天",422);
        if (min != null && days < min) throw new ServiceException("该岗位最短需停留 "+min+" 天",422);
        return (int)days;
    }
    private static LocalDate date(Map<String,Object> body,String key) {
        String s = body == null ? "" : str(body.get(key));
        if (!KgJdbc.isDate(s)) throw new ServiceException("请选择有效的起止日期",400);
        return LocalDate.parse(s);
    }
    private static LocalDate parseOptional(Object value) { String s = str(value); return KgJdbc.isDate(s) ? LocalDate.parse(s) : null; }
    private static LocalDate today() { return LocalDate.now(ZoneId.of("Asia/Shanghai")); }
    private static String str(Object value) { return value == null ? "" : String.valueOf(value); }
    private static Long number(Object value) { return KgJdbc.asLong(value); }
}
