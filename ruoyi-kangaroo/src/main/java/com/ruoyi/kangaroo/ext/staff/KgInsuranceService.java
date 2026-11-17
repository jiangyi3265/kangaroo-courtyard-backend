package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.staff.StaffSupport.str;

import java.util.Date;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.ApplicationStatus;
import com.ruoyi.kangaroo.enums.MessageCategory;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.util.MaskUtils;

/**
 * 用工保险：管理人员为已录用的候选人申请 → 后台或本店换工主理人登记保险公司保单 → 按保单日期显示保障状态。
 *
 * 新申请由 KgInsuranceFinance 收取保费；本服务不直连保险公司、不生成保单号，保单号只能人工登记（平台后台，或小程序里该门店的换工主理人），
 * 申请和保单出单都不等于当前保障已生效，没有登记之前显示"待登记保单"。
 */
@Service
public class KgInsuranceService
{
    /** 可以投保的报名状态：商家已确认录用之后 */
    private static final String INSURABLE = "'" + ApplicationStatus.STORE_CONFIRMED + "','" + ApplicationStatus.CONTRACT_SIGNED
            + "','" + ApplicationStatus.ONBOARDED + "'";

    /** 续保窗口：已出单保单的止保日在今天起这么多天之内（含已到期的），在岗人员就回到待申请名单、可以续保 */
    static final int RENEW_DAYS = 7;

    /**
     * 「在途或仍在保障期」的投保（表别名 x）：待登记保单的，或已出单、止保日还在续保窗口之后的。
     * 原先只看 status in ('pending','effective')：方案都是 30 天，保单一到期，还在岗的员工既不在待申请名单里、
     * 也申请不了，小程序和后台都没法续保（2026-10-13 复核）。待申请名单、页签数量、提交申请三处共用这一个口径
     */
    private static final String HOLDING = "(x.status in ('pending','pending_payment','refunding') or (x.status = 'effective' and x.end_date >= date_add(curdate(), interval "
            + RENEW_DAYS + " day)))";

    /** 这条报名已出单保单里最晚的止保日（表别名 a）；有值说明是续保 */
    private static final String LAST_END = "(select max(y.end_date) from kg_insurance_apply y where y.app_id = a.app_id and y.del_flag = '0' "
            + "and y.status = 'effective')";

    /**
     * 待申请名单的口径（表别名 a）：没有在途或仍在保障期的投保；出过单的（续保）还要按报名填的到店日期 + 可停留天数，
     * 到岗期比上一张保单晚结束的才列出。报名状态停在「已入职」不会变，只看保单到期的话，早已离开的人保单一到期
     * 就会一直挂在名单里、算进「超时」。没出过单的新录用、缺到店日期或天数的老数据照旧列出
     */
    private static final String NEEDS_COVER = "not exists (select 1 from kg_insurance_apply x where x.app_id = a.app_id and x.del_flag = '0' and "
            + HOLDING + ") and coalesce(date_add(a.arrival_date, interval a.stay_days - 1 day) > " + LAST_END + ", true)";

    @Autowired
    private KgJdbc db;

    @Autowired
    private StaffSupport support;

    public List<Map<String, Object>> plans()
    {
        List<Map<String, Object>> rows = db.list("select plan_id, plan_name, plan_desc, insurer, price, days from kg_insurance_plan "
                + "where status = '0' and del_flag = '0' order by sort, plan_id", p());
        for (Map<String, Object> r : rows)
        {
            r.put("name", r.get("planName"));
            r.put("desc", r.get("planDesc"));
            r.put("priceYuan", yuan(KgJdbc.asLong(r.get("price"))));
        }
        return rows;
    }

    static String yuan(Long fen)
    {
        if (fen == null)
        {
            return "0";
        }
        return fen % 100 == 0 ? String.valueOf(fen / 100) : String.format("%.2f", fen / 100.0);
    }

    /** 三个页签的数量 + 当前页签的行 */
    public Map<String, Object> overview(StaffSupport.Ctx c, String tab)
    {
        Map<String, Object> counts = new LinkedHashMap<String, Object>();
        counts.put("toBuy", db.count("select count(*) from kg_application a join kg_job j on j.job_id=a.job_id where coalesce(j.recruit_store_id,a.store_id) = :s and a.del_flag = '0' "
                + "and exists (select 1 from kg_job j where j.job_id=a.job_id and j.job_type<>'online') "
                + "and a.status in (" + INSURABLE + ") and " + NEEDS_COVER, p("s", c.storeId)));
        counts.put("pending", db.count("select count(*) from kg_insurance_apply where store_id = :s and del_flag = '0' and status = 'pending'",
                p("s", c.storeId)));
        counts.put("effective", db.count("select count(*) from kg_insurance_apply where store_id = :s and del_flag = '0' and status = 'effective'",
                p("s", c.storeId)));
        counts.put("all", db.count("select count(*) from kg_insurance_apply where store_id = :s and del_flag = '0'", p("s", c.storeId)));

        List<Map<String, Object>> rows;
        if ("effective".equals(tab) || "all".equals(tab) || "pending".equals(tab))
        {
            String where = "all".equals(tab) ? "" : " and x.status = :st";
            rows = db.list("select x.apply_id, x.app_id, x.insured_name, x.start_date, x.end_date, x.price, x.status, x.policy_no, "
                    + "x.handle_note, x.create_time, p.plan_name, a.age, j.title job_title, u.gender, rs.gender resume_gender, u.avatar "
                    + "from kg_insurance_apply x left join kg_insurance_plan p on p.plan_id = x.plan_id "
                    + "left join kg_application a on a.app_id = x.app_id left join kg_job j on j.job_id = a.job_id "
                    + "left join kg_app_user u on u.user_id = x.app_user_id left join kg_resume rs on rs.app_user_id = x.app_user_id "
                    + "where x.store_id = :s and x.del_flag = '0'" + where + " order by x.apply_id desc limit 50",
                    p("s", c.storeId, "st", tab));
            for (Map<String, Object> r : rows)
            {
                decorateApply(r);
                r.put("name", MaskUtils.name(str(r.get("insuredName"))));
                // 性别先取简历再退回账号（之前只读账号，账号没填、简历填了也显示「性别未填」）
                r.put("gender", StaffSupport.applicantGender(r.remove("resumeGender"), r.get("gender")));
            }
        }
        else
        {
            rows = db.list("select a.app_id, a.real_name, a.age, a.arrival_date, a.status, j.title job_title, u.gender, rs.gender resume_gender, u.avatar "
                    + "from kg_application a left join kg_job j on j.job_id = a.job_id left join kg_app_user u on u.user_id = a.app_user_id "
                    + "left join kg_resume rs on rs.app_user_id = a.app_user_id "
                    + "where coalesce(j.recruit_store_id,a.store_id) = :s and a.del_flag = '0' and j.job_type<>'online' and a.status in (" + INSURABLE + ") "
                    + "and " + NEEDS_COVER + " order by a.arrival_date, a.app_id limit 50", p("s", c.storeId));
            for (Map<String, Object> r : rows)
            {
                r.put("name", MaskUtils.name(str(r.remove("realName"))));
                r.put("gender", StaffSupport.applicantGender(r.remove("resumeGender"), r.get("gender")));
                r.put("statusLabel", ApplicationStatus.label(str(r.get("status"))));
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("counts", counts);
        out.put("rows", rows);
        out.put("tab", tab == null ? "toBuy" : tab);
        out.put("storeName", c.storeName);
        return out;
    }

    /**
     * 待购保险：本店已确认录用、还没有在途或仍在保障期投保的人。新录用的按到店日期算时限；
     * 保单 7 天内到期或已到期、到岗期还没结束的人员（lastEnd 有值，renew=true，见 NEEDS_COVER）按止保日期算，已到期的算「超时」。两类合在一起按时限排
     */
    public Map<String, Object> toBuy(StaffSupport.Ctx c)
    {
        List<Map<String, Object>> rows = db.list("select a.app_id, a.real_name, a.arrival_date, a.status, j.title job_title, "
                + "s.store_name, u.avatar, " + LAST_END + " last_end, datediff(coalesce(" + LAST_END + ", a.arrival_date), curdate()) days_left "
                + "from kg_application a left join kg_job j on j.job_id = a.job_id left join kg_store s on s.store_id = coalesce(j.recruit_store_id,a.store_id) "
                + "left join kg_app_user u on u.user_id = a.app_user_id "
                + "where coalesce(j.recruit_store_id,a.store_id) = :s and a.del_flag = '0' and j.job_type<>'online' and a.status in (" + INSURABLE + ") "
                + "and " + NEEDS_COVER + " order by days_left, a.app_id limit 100", p("s", c.storeId));
        int within7 = 0;
        int overdue = 0;
        for (Map<String, Object> r : rows)
        {
            r.put("name", MaskUtils.name(str(r.remove("realName"))));
            boolean renew = !str(r.get("lastEnd")).isEmpty();
            r.put("renew", renew);
            int left = StaffSupport.toInt(r.get("daysLeft"), 0);
            if (left < 0)
            {
                overdue++;
            }
            else if (left <= 7 && !renew)
            {
                // 「7日内到店」只数新录用的；即将到期待续保的只在「全部」里，卡片上写明几天后到期
                within7++;
            }
        }
        Map<String, Object> counts = new LinkedHashMap<String, Object>();
        counts.put("all", rows.size());
        counts.put("within7", within7);
        counts.put("overdue", overdue);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("counts", counts);
        out.put("rows", rows);
        out.put("storeName", c.storeName);
        return out;
    }

    /**
     * 已购保单（投保申请）列表。status：pending 待登记保单 / upcoming 待生效 / active 生效中 / expired 已失效 / rejected 已驳回；
     * days：申请时间在最近多少天内；sort：start 按起保日期，其余按申请时间倒序。
     */
    public Map<String, Object> policies(List<Long> storeIds, String status, String keyword, int days, String sort)
    {
        StringBuilder where = new StringBuilder(" where x.store_id in (:ids) and x.del_flag = '0'");
        Map<String, Object> ps = p("ids", storeIds);
        if (Arrays.asList("upcoming", "active", "expired").contains(status))
            // Match Java String.trim(): a historical control-whitespace-only
            // number must not be treated as an issued policy by SQL filters.
            ps.put("policyNoContent", "[^" + (char)0 + "-" + (char)32 + "]");
        if ("pending".equals(status) || "rejected".equals(status))
        {
            where.append(" and x.status = :st");
            ps.put("st", status);
        }
        else if ("effective".equals(status))
        {
            where.append(" and x.status = 'effective'");
        }
        else if ("upcoming".equals(status))
        {
            where.append(" and x.status = 'effective' and x.policy_no regexp :policyNoContent and x.end_date >= x.start_date and x.start_date > curdate()");
        }
        else if ("active".equals(status))
        {
            where.append(" and x.status = 'effective' and x.policy_no regexp :policyNoContent and x.end_date >= x.start_date and x.start_date <= curdate() and x.end_date >= curdate()");
        }
        else if ("expired".equals(status))
        {
            where.append(" and x.status = 'effective' and x.policy_no regexp :policyNoContent and x.end_date >= x.start_date and x.end_date < curdate()");
        }
        if (keyword != null && !keyword.trim().isEmpty())
        {
            where.append(" and (x.insured_name like :kw or x.policy_no like :kw or j.title like :kw)");
            ps.put("kw", "%" + keyword.trim() + "%");
        }
        if (days > 0)
        {
            where.append(" and x.create_time >= date_sub(now(), interval :d day)");
            ps.put("d", days);
        }
        List<Map<String, Object>> rows = db.list("select x.apply_id, x.app_id, x.store_id, x.insured_name, x.start_date, x.end_date, x.price, x.status, "
                + "x.policy_no, x.handle_note, x.handled_time, x.create_time, p.plan_name, p.insurer, j.title job_title, s.store_name, u.avatar, "
                + "(x.start_date > curdate()) not_started, (x.end_date < curdate()) ended "
                + "from kg_insurance_apply x left join kg_insurance_plan p on p.plan_id = x.plan_id "
                + "left join kg_application a on a.app_id = x.app_id left join kg_job j on j.job_id = a.job_id "
                + "left join kg_store s on s.store_id = x.store_id left join kg_app_user u on u.user_id = x.app_user_id"
                + where + " order by " + ("start".equals(sort) ? "x.start_date desc, " : "") + "x.apply_id desc limit 100", ps);
        for (Map<String, Object> r : rows)
        {
            com.ruoyi.kangaroo.util.InsuranceState.decorate(r);
            String coverage = str(r.get("coverageStatus"));
            String timeLabel;
            String time;
            if ("active".equals(coverage) || "upcoming".equals(coverage))
            {
                timeLabel = "保单起保日期";
                time = str(r.get("startDate"));
            }
            else if ("expired".equals(coverage))
            {
                timeLabel = "保单止保日期";
                time = str(r.get("endDate"));
            }
            else if ("rejected".equals(coverage))
            {
                timeLabel = "驳回时间";
                time = StaffSupport.fmt(StaffSupport.parse(r.get("handledTime")), "yyyy-MM-dd HH:mm");
            }
            else
            {
                timeLabel = "申请时间";
                time = StaffSupport.fmt(StaffSupport.parse(r.get("createTime")), "yyyy-MM-dd HH:mm");
            }
            r.put("no", str(r.get("policyNo")).trim().isEmpty() ? "待保险公司出具" : r.get("policyNo"));
            r.put("tone", "active".equals(coverage) ? "green" : ("pending".equals(coverage) || "upcoming".equals(coverage) ? "amber" : "gray"));
            r.put("timeLabel", timeLabel);
            r.put("time", time);
            r.put("name", MaskUtils.name(str(r.get("insuredName"))));
            r.put("job", str(r.get("jobTitle")) + " · " + str(r.get("storeName")));
            r.put("period", r.get("startDate") + " 至 " + r.get("endDate"));
            r.put("priceYuan", yuan(KgJdbc.asLong(r.get("price"))));
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("rows", rows);
        out.put("total", rows.size());
        return out;
    }

    /** 某条投保申请的通知记录（站内信），只看得到收件人类型与是否已读 */
    public List<Map<String, Object>> notices(StaffSupport.Ctx c, Long applyId)
    {
        Map<String, Object> x = db.must("select store_id from kg_insurance_apply where apply_id = :id and del_flag = '0'", p("id", applyId), "投保申请不存在");
        if (!c.me.hasStoreAccess(KgJdbc.asLong(x.get("storeId"))))
        {
            throw new ServiceException("没有该门店的操作权限", HttpStatus.FORBIDDEN);
        }
        List<Map<String, Object>> rows = db.list("select m.mode, m.title, m.read_flag, m.create_time from kg_message m "
                + "where m.biz_type = 'insurance' and m.biz_id = :id and m.del_flag = '0' order by m.msg_id", p("id", applyId));
        for (Map<String, Object> r : rows)
        {
            r.put("to", "user".equals(str(r.get("mode"))) ? "候选人" : "门店员工");
            r.put("read", "1".equals(str(r.get("readFlag"))));
            r.put("time", StaffSupport.fmt(StaffSupport.parse(r.get("createTime")), "MM-dd HH:mm"));
        }
        return rows;
    }

    private static void decorateApply(Map<String, Object> r)
    {
        com.ruoyi.kangaroo.util.InsuranceState.decorate(r);
        r.put("priceYuan", yuan(KgJdbc.asLong(r.get("price"))));
    }

    /** 某条报名的投保页：候选人、可选方案、已有申请（最近一条） */
    public Map<String, Object> forApplication(StaffSupport.Ctx c, Long appId)
    {
        Map<String, Object> a = app(c, appId);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        // 投保按实际接待门店授权，代招报名的管理门店不能作为前端加载权限和概览的依据。
        out.put("storeId", c.storeId);
        Map<String, Object> cand = new LinkedHashMap<String, Object>();
        cand.put("appId", appId);
        cand.put("name", a.get("realName"));
        cand.put("age", a.get("age"));
        cand.put("gender", StaffSupport.applicantGender(a.get("resumeGender"), a.get("gender")));
        cand.put("avatar", a.get("avatar"));
        cand.put("jobTitle", a.get("jobTitle"));
        Date arrival = StaffSupport.parse(a.get("arrivalDate"));
        cand.put("arrivalDate", a.get("arrivalDate"));
        cand.put("arrivalText", arrival == null ? "" : StaffSupport.fmt(arrival, "yyyy-MM-dd") + "（" + StaffSupport.week(arrival) + "）");
        cand.put("status", a.get("status"));
        cand.put("statusLabel", ApplicationStatus.label(str(a.get("status"))));
        cand.put("insurable", !"online".equals(a.get("jobType")) && INSURABLE.contains("'" + str(a.get("status")) + "'"));
        out.put("candidate", cand);
        out.put("plans", plans());
        Map<String, Object> last = db.one("select x.*, p.plan_name from kg_insurance_apply x left join kg_insurance_plan p on p.plan_id = x.plan_id "
                + "where x.app_id = :a and x.del_flag = '0' order by x.apply_id desc limit 1", p("a", appId));
        Date lastEnd = lastEnd(appId, false);
        if (last != null)
        {
            decorateApply(last);
            // 最近一条已出单、保单 7 天内到期或已到期、也没有在途申请：可以续保（面试页据此放开方案选择，不再一直「本次申请已提交」）
            last.put("renewable", "effective".equals(str(last.get("status")))
                    && db.count("select count(*) from kg_insurance_apply x where x.app_id = :a and x.del_flag = '0' and " + HOLDING, p("a", appId)) == 0);
        }
        out.put("apply", last);
        Date start = arrival;
        Date today = StaffSupport.startOfDay(new Date());
        if (start == null || start.before(today))
        {
            start = today;
        }
        // 续保接在上一张保单之后：起保日取上一张止保日的次日与上面两者中较晚的一天，保障不断档也不重叠
        if (lastEnd != null && !start.after(lastEnd))
        {
            start = StaffSupport.addDays(lastEnd, 1);
        }
        out.put("defaultStart", StaffSupport.fmt(start, "yyyy-MM-dd"));
        out.put("lastEndDate", lastEnd == null ? null : StaffSupport.fmt(lastEnd, "yyyy-MM-dd"));
        return out;
    }

    /**
     * 被保险人「签约到店 → 保险与完成」页用（调用方先核对这条报名是本人的）：返回今天正在保障的那张保单；
     * 它之后还有更新的申请（提前续保）时，把最近那条挂在 renewal 里。今天没有正在保障的保单时，照旧返回最近一条申请。
     * 字段与原来只取最近一条时一样：status / policyNo / startDate / endDate / handleNote / planName，再加 InsuranceState 的三个字段。
     * 到期前 RENEW_DAYS 天就能续保之后，原先只取最近一条（也就是续保申请），页面写「待登记保单」「已出单·待生效」，
     * 被保险人以为自己没有保障，其实老保单还在保障期内（2026-10-14 回归）
     */
    public static Map<String, Object> userCoverage(KgJdbc db, Long appId)
    {
        List<Map<String, Object>> rows = db.list("select i.status, i.policy_no, i.start_date, i.end_date, i.handle_note, "
                + "(select plan_name from kg_insurance_plan p where p.plan_id = i.plan_id) plan_name "
                + "from kg_insurance_apply i where i.app_id = :a and i.del_flag = '0' order by i.apply_id desc limit 10", p("a", appId));
        if (rows.isEmpty())
        {
            return null;
        }
        for (Map<String, Object> r : rows)
        {
            com.ruoyi.kangaroo.util.InsuranceState.decorate(r);
        }
        Map<String, Object> newest = rows.get(0);
        for (Map<String, Object> r : rows)
        {
            if ("active".equals(str(r.get("coverageStatus"))))
            {
                if (r != newest)
                {
                    r.put("renewal", newest);
                }
                return r;
            }
        }
        return newest;
    }

    /** 这条报名已出单保单里最晚的止保日（零点），没有出过单为 null；提交申请时要加锁读，见 apply() */
    private Date lastEnd(Long appId, boolean locking)
    {
        Map<String, Object> r = db.one("select max(end_date) last_end from kg_insurance_apply where app_id = :a and del_flag = '0' "
                + "and status = 'effective'" + (locking ? " for share" : ""), p("a", appId));
        Date d = r == null ? null : StaffSupport.parse(r.get("lastEnd"));
        return d == null ? null : StaffSupport.startOfDay(d);
    }

    private Map<String, Object> app(StaffSupport.Ctx c, Long appId)
    {
        Map<String, Object> a = db.must("select a.app_id, a.app_user_id, coalesce(j.recruit_store_id,a.store_id) store_id, a.real_name, a.age, a.arrival_date, a.status, "
                + "j.title job_title, j.job_type, u.gender, rs.gender resume_gender, u.avatar from kg_application a left join kg_job j on j.job_id = a.job_id "
                + "left join kg_app_user u on u.user_id = a.app_user_id left join kg_resume rs on rs.app_user_id = a.app_user_id where a.app_id = :id and a.del_flag = '0'",
                p("id", appId), "报名不存在");
        if (!java.util.Objects.equals(c.storeId, KgJdbc.asLong(a.get("storeId"))) || !c.me.hasStoreAccess(KgJdbc.asLong(a.get("storeId"))))
        {
            throw new ServiceException("没有该门店的操作权限", HttpStatus.FORBIDDEN);
        }
        return a;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> apply(StaffSupport.Ctx c, Long appId, Long planId, String startDate)
    {
        throw new ServiceException("请更新小程序，在保险缴费页面由合作商家提交并付款", 409);
    }

    /** Only called in the payment transaction; an unpaid draft must never emit an insured notification. */
    @Transactional(rollbackFor = Exception.class)
    public Map<String,Object> createForPayment(StaffSupport.Ctx c, Long appId, Long planId, String startDate)
    {
        if (!"merchant".equals(c.role)) throw new ServiceException("请由合作商家提交并付款",403);
        Map<String, Object> a = app(c, appId);
        if ("online".equals(a.get("jobType"))) throw new ServiceException("线上任务无需办理到岗保险", 409);
        if (c.uid().equals(KgJdbc.asLong(a.get("appUserId"))))
        {
            throw new ServiceException("不能为自己提交的报名投保", HttpStatus.FORBIDDEN);
        }
        // 先锁报名行再判状态：原先用的是上面 app() 的快照，与「终止」并发时已终止的报名仍会新建一条待处理的投保申请、并通知前候选人
        Map<String, Object> lockedApp = db.one("select status from kg_application where app_id = :a for update", p("a", appId));
        String st = str(lockedApp == null ? a.get("status") : lockedApp.get("status"));
        if (!INSURABLE.contains("'" + st + "'"))
        {
            throw new ServiceException("商家确认录用后才能投保（当前：" + ApplicationStatus.label(st) + "）");
        }
        Map<String, Object> plan = db.must("select * from kg_insurance_plan where plan_id = :id and status = '0' and del_flag = '0'",
                p("id", planId), "保险方案不存在或已下架");
        Date start = StaffSupport.parse(startDate);
        Date today = StaffSupport.startOfDay(new Date());
        if (start == null)
        {
            throw new ServiceException("请填写起保日期（yyyy-MM-dd）");
        }
        if (start.before(today))
        {
            throw new ServiceException("起保日期不能早于今天");
        }
        if (start.after(StaffSupport.addDays(today, 90)))
        {
            throw new ServiceException("起保日期最多提前 90 天");
        }
        int days = Math.max(1, StaffSupport.toInt(plan.get("days"), 30));
        Date end = StaffSupport.addDays(start, days - 1);
        // 并发下同一条报名只能有一条在途申请：先锁住报名行再查
        db.one("select app_id from kg_application where app_id = :a for update", p("a", appId));
        // 必须是加锁读：本事务的快照在前面 app() 的普通查询时就定下了，普通 count 看不到别人刚提交的申请，
        // 6 个并发请求会全部判"没有在途申请"（2026-09-26 回归发现，会重复投保扣费）
        if (db.count("select count(*) from kg_insurance_apply x where x.app_id = :a and x.del_flag = '0' and " + HOLDING + " for share",
                p("a", appId)) > 0)
        {
            throw new ServiceException("这位候选人已有投保申请在处理，或保单还在保障期内（到期前 " + RENEW_DAYS + " 天内可以续保）");
        }
        // 续保：起保日期要接在上一张保单之后，不能和还在保障期的那张重叠（同样要加锁读，理由同上）
        Date lastEnd = lastEnd(appId, true);
        if (lastEnd != null && !StaffSupport.startOfDay(start).after(lastEnd))
        {
            throw new ServiceException("上一张保单保障到 " + StaffSupport.fmt(lastEnd, "yyyy-MM-dd") + "，续保的起保日期请选 "
                    + StaffSupport.fmt(StaffSupport.addDays(lastEnd, 1), "yyyy-MM-dd") + " 或之后");
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("app_id", appId);
        row.put("app_user_id", a.get("appUserId"));
        row.put("store_id", a.get("storeId"));
        row.put("plan_id", planId);
        row.put("insured_name", StaffSupport.cut(str(a.get("realName")), 32));
        row.put("start_date", StaffSupport.fmt(start, "yyyy-MM-dd"));
        row.put("end_date", StaffSupport.fmt(end, "yyyy-MM-dd"));
        row.put("price", plan.get("price"));
        row.put("status", "pending_payment");
        row.put("applied_by", c.uid());
        row.put("create_by", "staff:" + c.uid());
        row.put("create_time", new Date());
        long id = db.insert("kg_insurance_apply", row);
        return p("applyId", id);
    }

    // ---------- 后台处理 / 换工主理人登记 ----------

    @Transactional(rollbackFor = Exception.class)
    public String effect(Long applyId, String policyNo, String admin)
    {
        return registerPolicy(applyId, policyNo, null, null, admin, false);
    }

    @Transactional(rollbackFor = Exception.class)
    public String effect(Long applyId,String policyNo,String startDate,String endDate,String admin) {
        return registerPolicy(applyId,policyNo,startDate,endDate,admin,false);
    }

    /**
     * 门店换工主理人在小程序登记保单号。主人定的口径：只有这条申请所在门店的换工主理人能登记，其他角色只能看。
     * 身份、门店核对完之后走和后台完全相同的 registerPolicy，校验、并发兜底、通知口径不会两边不一致。
     */
    @Transactional(rollbackFor = Exception.class)
    public String effectByManager(StaffSupport.Ctx c, Long applyId, String policyNo)
    {
        return effectByManager(c,applyId,policyNo,null,null);
    }

    @Transactional(rollbackFor = Exception.class)
    public String effectByManager(StaffSupport.Ctx c,Long applyId,String policyNo,String startDate,String endDate)
    {
        if (!"manager".equals(c.role) || !com.ruoyi.kangaroo.security.AppUserContext.messageMediationStores("manager").contains(c.storeId))
            throw new ServiceException("请由当前门店的换工主理人登记保单",403);
        // 与控制器共用保险审核权限；门店范围和不可处理本人投保的限制保持不变。
        StaffPerms.require(c.role, "insurance", StaffPerms.AUDIT);
        Map<String, Object> x = db.must("select store_id, app_user_id, insured_name, status from kg_insurance_apply "
                + "where apply_id = :id and del_flag = '0'", p("id", applyId), "投保申请不存在");
        if (c.storeId == null || !c.storeId.equals(KgJdbc.asLong(x.get("storeId"))))
        {
            throw new ServiceException("没有该门店的操作权限", HttpStatus.FORBIDDEN);
        }
        // 和 apply() 不能给自己投保同理：换工主理人本人是被保险人时，保单交给平台登记
        if (c.uid().equals(KgJdbc.asLong(x.get("appUserId"))))
        {
            throw new ServiceException("不能为自己的投保登记保单，请联系平台处理", HttpStatus.FORBIDDEN);
        }
        // 已出单 / 已驳回的先挡掉；并发下两人同时登记，由 registerPolicy 里只改 pending 的那条 update 兜底
        if (!"pending".equals(str(x.get("status"))))
        {
            throw new ServiceException("该申请已处理过", 409);
        }
        String msg = registerPolicy(applyId, policyNo, startDate, endDate, "staff:" + c.uid(), true);
        support.op(c, "insurance_effect", "登记保单号", "fund",
                MaskUtils.name(str(x.get("insuredName"))) + " · 保单号 " + policyNo.trim(), true, null);
        return msg;
    }

    /**
     * 后台与换工主理人共用的登记逻辑。byStaff 只影响错误码和提示（小程序里没有驳回入口），
     * 后台那条路径的提示与返回码保持原样。
     */
    private String registerPolicy(Long applyId, String policyNo, String startDate, String endDate, String handler, boolean byStaff)
    {
        String no = policyNo == null ? "" : policyNo.trim();
        if (no.isEmpty() || no.length() > 64)
        {
            throw new ServiceException("请填写保险公司出具的保单号（64 字以内）");
        }
        Map<String, Object> x = db.must("select * from kg_insurance_apply where apply_id = :id and del_flag = '0'", p("id", applyId), "投保申请不存在");
        // 登记保单号之前再确认一次报名还在「已录用」链路上：报名被终止 / 撤回之后这条待处理申请不能再生效，
        // 否则前候选人会收到「用工保险已生效」的通知（这种申请在报名终止时已被自动作废，这里是防并发的兜底）
        Map<String, Object> appRow = db.one("select a.status from kg_application a join kg_job j on j.job_id=a.job_id "
                + "where a.app_id=:a and a.del_flag='0' and j.job_type<>'online' for update", p("a", x.get("appId")));
        if (appRow == null || !INSURABLE.contains("'" + str(appRow.get("status")) + "'"))
        {
            throw byStaff ? new ServiceException("该候选人的报名已终止或撤回，不能再登记保单；请联系平台处理这条申请", 409)
                    : new ServiceException("该候选人的报名已终止或撤回，不能再登记保单；请驳回这条申请");
        }
        Map<String,Object> funding=db.one("select status,refund_required from kg_insurance_payment where apply_id=:id for update",p("id",applyId));
        if (funding!=null && (!"paid".equals(funding.get("status")) || StaffSupport.toInt(funding.get("refundRequired"),0)!=0))
            throw new ServiceException("保费未到账或需退款，不能登记保单",409);
        if(db.count("select count(*) from kg_insurance_refund where apply_id=:id and status in ('pending','approved','processing')",p("id",applyId))>0)
            throw new ServiceException("退款审核期间不能登记保单",409);
        String from=startDate,to=endDate;
        if(funding==null && (from==null || to==null)) {from=str(x.get("startDate"));to=str(x.get("endDate"));}
        try {
            java.time.LocalDate f=java.time.LocalDate.parse(from), t=java.time.LocalDate.parse(to);
            if(t.isBefore(f) || t.isAfter(f.plusYears(10))) throw new IllegalArgumentException();
        } catch(Exception e) {throw new ServiceException("请填写真实保单的起止日期",400);}
        int n = db.update("update kg_insurance_apply set status = 'effective', policy_no = :no, start_date=:f,end_date=:t,handled_by = :u, handled_time = now(), "
                + "update_by = :u, update_time = now() where apply_id = :id and status = 'pending'", p("id", applyId, "no", no, "u", handler,"f",from,"t",to));
        x.put("startDate",from);x.put("endDate",to);
        if (n == 0)
        {
            throw byStaff ? new ServiceException("该申请已处理过", 409) : new ServiceException("该申请已处理过");
        }
        String period = str(x.get("startDate")) + " 至 " + str(x.get("endDate"));
        support.notifyUser(KgJdbc.asLong(x.get("appUserId")), MessageCategory.CONTRACT, "用工保险已出单",
                "保单号 " + no + "，保障期限 " + period + "。", "insurance", applyId, "/pages/jobs/contract?appId=" + x.get("appId") + "&s=4");
        support.notifyStore(KgJdbc.asLong(x.get("storeId")), MessageCategory.CONTRACT, "投保已出单",
                MaskUtils.name(str(x.get("insuredName"))) + " 的保单已登记，保单号 " + no + "，保障期限 " + period + "。请按保单起止日期核对保障。", "insurance", applyId, "/pages/staff/insurance?tab=effective&storeId=" + x.get("storeId"));
        return "已登记保单号，请按保单起止日期核对保障";
    }

    @Transactional(rollbackFor = Exception.class)
    public String reject(Long applyId, String reason, String admin)
    {
        String note = reason == null ? "" : reason.trim();
        if (note.isEmpty())
        {
            throw new ServiceException("请填写驳回原因");
        }
        Map<String, Object> x = db.must("select * from kg_insurance_apply where apply_id = :id and del_flag = '0'", p("id", applyId), "投保申请不存在");
        db.one("select app_id from kg_application where app_id=:a for update", p("a", x.get("appId")));
        if(db.count("select count(*) from kg_insurance_payment where apply_id=:id",p("id",applyId))>0)
            throw new ServiceException("缴费投保请到新的投保管理页面处理，不能直接驳回资金记录",409);
        int n = db.update("update kg_insurance_apply set status = 'rejected', handle_note = :n, handled_by = :u, handled_time = now(), "
                + "update_by = :u, update_time = now() where apply_id = :id and status = 'pending'",
                p("id", applyId, "n", StaffSupport.cut(note, 255), "u", admin));
        if (n == 0)
        {
            throw new ServiceException("该申请已处理过");
        }
        support.notifyStore(KgJdbc.asLong(x.get("storeId")), MessageCategory.CONTRACT, "投保申请被驳回",
                MaskUtils.name(str(x.get("insuredName"))) + " 的投保申请未通过：" + note, "insurance", applyId, "/pages/staff/insurance?s=2&storeId=" + x.get("storeId"));
        // 被保险人之前收到过「门店已为你申请用工保险」，驳回时也要告诉他（原先只通知门店，他会一直以为在等出单、已经有保障）
        support.notifyUser(KgJdbc.asLong(x.get("appUserId")), MessageCategory.CONTRACT, "用工保险申请未通过",
                "门店为你提交的用工保险申请未通过：" + note.replaceAll("[。.！!；;，,\\s]+$", "") + "。门店会重新办理；重新出单之前，这次申请不代表你已有保障，请以出单通知和保单起止日期为准。",
                "insurance", applyId, "/pages/jobs/contract?appId=" + x.get("appId") + "&s=4");
        return "已驳回";
    }
}
