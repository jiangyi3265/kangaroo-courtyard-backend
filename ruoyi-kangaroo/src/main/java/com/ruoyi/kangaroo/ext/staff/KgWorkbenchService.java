package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.manage.KgStats;

/**
 * 门店工作台：待办聚合、任务详情与责任人 / 备注、今日经营指标。
 *
 * 待办本身不落表——它就是"还没处理完的业务数据"，每次实时聚合，处理完自然消失，不会出现"业务已办、待办还在"。
 * 只有责任人与备注这两件工作台自己的信息存进 kg_todo_meta。
 *
 * 截止时间的来源分两种，返回体里用 dueKind 区分，前端据此措辞：
 *   real —— 业务数据里真实存在的时间（面试开始时间、活动开始时间、用户确认截止时间）；
 *   sla  —— 平台设定的处理时限（见 SLA_HOURS），是一条运营规则，不是业务数据里的字段。
 */
@Service
public class KgWorkbenchService
{
    /** 平台设定的处理时限（小时）。改这里就改了工作台的逾期判定，前端会标注"平台时限"。 */
    static final Map<String, Integer> SLA_HOURS = new LinkedHashMap<String, Integer>();
    static
    {
        SLA_HOURS.put("app_review", Integer.valueOf(48));
        SLA_HOURS.put("offer", Integer.valueOf(24));
        SLA_HOURS.put("stay_refund", Integer.valueOf(48));
        SLA_HOURS.put("shop_refund", Integer.valueOf(48));
        SLA_HOURS.put("insurance", Integer.valueOf(24));
    }

    /** 待办分类，与工作台页上的筛选标签一致 */
    static final Map<String, String> TAB_OF = new LinkedHashMap<String, String>();
    static
    {
        TAB_OF.put("app_review", "报名");
        TAB_OF.put("interview", "报名");
        TAB_OF.put("offer", "报名");
        TAB_OF.put("stay_refund", "订单");
        TAB_OF.put("shop_refund", "订单");
        TAB_OF.put("insurance", "保险");
        TAB_OF.put("activity", "活动");
        TAB_OF.put("dispatch", "调度");
    }

    @Autowired
    private KgJdbc db;

    @Autowired
    private StaffSupport support;

    @Autowired
    private KgStats stats;

    static String str(Object v)
    {
        String s = KgJdbc.asStr(v);
        return s == null ? "" : s;
    }

    static long num(Object v)
    {
        Long l = KgJdbc.asLong(v);
        return l == null ? 0L : l;
    }

    /** 报名编号太长，列表里只留前缀和后 6 位 */
    static String shortNo(String no)
    {
        return no.length() > 10 ? no.substring(0, 2) + "…" + no.substring(no.length() - 6) : no;
    }

    static String mdhm(Date d)
    {
        return d == null ? "" : StaffSupport.fmt(d, "MM-dd HH:mm");
    }

    /** 相对现在的时长描述，例如「已等待 3 小时」「逾期 1 天 2 小时」 */
    static String span(long ms)
    {
        long min = Math.max(0, ms) / 60000L;
        if (min < 60)
        {
            return min + " 分钟";
        }
        long h = min / 60;
        return h < 24 ? h + " 小时" : (h / 24) + " 天 " + (h % 24) + " 小时";
    }

    private Map<String, Object> todo(String type, Object bizId, String title, String sub, Date due, String dueKind, String chip, String chipTone, String route)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        String key = type + ":" + bizId;
        m.put("key", key);
        m.put("type", type);
        m.put("bizId", bizId);
        m.put("tab", TAB_OF.get(type));
        m.put("title", title);
        m.put("sub", sub);
        m.put("chip", chip);
        m.put("chipTone", chipTone);
        // 报名人花积分加急了（只有待审核报名会是 true，见 collect）；这类待办的小标签显示红色「加急」，列表里单独排在最上面一组
        m.put("urgent", Boolean.FALSE);
        m.put("route", route);
        m.put("dueKind", due == null ? "none" : dueKind);
        m.put("due", due == null ? "" : mdhm(due));
        long left = due == null ? 0L : due.getTime() - System.currentTimeMillis();
        boolean over = due != null && left < 0;
        m.put("over", over ? "逾期 " + span(-left) : "");
        m.put("overdue", Boolean.valueOf(over));
        m.put("dueTs", due == null ? null : Long.valueOf(due.getTime()));
        return m;
    }

    private static Date plusHours(Date base, Integer hours)
    {
        return base == null || hours == null ? null : new Date(base.getTime() + hours.intValue() * 3600000L);
    }

    // ---------------------------------------------------------------- 待办聚合

    /** 门店当前所有待处理的业务，按类型实时聚合 */
    List<Map<String, Object>> collect(Long storeId)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        Map<String, Object> ps = p("s", storeId);
        String role = support.ctxForStore(storeId).role;
        ps.put("onlineAllowed", StaffPerms.allows(role, "online", StaffPerms.AUDIT) ? 1 : 0);
        // 小院主理人的工作台不再显示换工内容（Luna 备忘录，2026-10-30）：报名类只留线上任务，保险、调度整类不要。
        // 只改待办，不收权限：StaffPerms 不动，老版本小程序和消息里的深链继续能打开换工报名
        boolean keeper = StaffRole.KEEPER.equals(role);
        ps.put("onlineOnly", keeper ? 1 : 0);

        // 之前只按提交时间排，用户花积分加急（V37 urgent_flag）的报名在工作台上看不出来；现在加急的排前面、带 urgent 标记，
        // 每条待办右侧那个红色小标签由「待审核」换成「加急」（工作台页模板不用改，标题里本来就写着「报名待审核」）
        for (Map<String, Object> r : db.list("select a.app_id, a.app_no, a.real_name, a.submit_time, a.urgent_flag, j.title from kg_application a "
                + "join kg_job j on j.job_id = a.job_id where a.store_id = :s and a.del_flag = '0' and a.status = 'submitted' and (j.job_type <> 'online' or :onlineAllowed = 1) and (j.job_type = 'online' or :onlineOnly = 0) order by a.urgent_flag desc, a.submit_time", ps))
        {
            Date submit = StaffSupport.parse(r.get("submitTime"));
            boolean urgent = "1".equals(str(r.get("urgentFlag")));
            Map<String, Object> m = todo("app_review", r.get("appId"), "报名待审核 · " + str(r.get("title")),
                    str(r.get("realName")) + " · 报名编号 " + shortNo(str(r.get("appNo"))), plusHours(submit, SLA_HOURS.get("app_review")), "sla",
                    urgent ? "加急" : "待审核", "red", "/pages/staff/applications?s=2&appId=" + r.get("appId"));
            m.put("urgent", Boolean.valueOf(urgent));
            out.add(m);
        }

        for (Map<String, Object> r : db.list("select i.interview_id, i.start_time, a.app_id, a.app_no, a.real_name, j.title from kg_interview i "
                + "join kg_application a on a.app_id = i.app_id join kg_job j on j.job_id = a.job_id "
                + "where a.store_id = :s and i.del_flag = '0' and i.status = 'booked' and (j.job_type = 'online' or :onlineOnly = 0) order by i.start_time", ps))
        {
            out.add(todo("interview", r.get("interviewId"), "面试待进行 · " + str(r.get("title")),
                    str(r.get("realName")) + " · 报名编号 " + shortNo(str(r.get("appNo"))), StaffSupport.parse(r.get("startTime")), "real",
                    "待面试", "amber", "/pages/staff/interviews?id=" + r.get("interviewId")));
        }

        for (Map<String, Object> r : db.list("select a.app_id, a.app_no, a.real_name, a.user_confirm_time, j.title from kg_application a "
                + "join kg_job j on j.job_id = a.job_id where a.store_id = :s and a.del_flag = '0' and a.status = 'user_confirmed' and (j.job_type = 'online' or :onlineOnly = 0) order by a.user_confirm_time", ps))
        {
            Date t = StaffSupport.parse(r.get("userConfirmTime"));
            out.add(todo("offer", r.get("appId"), "待确认录用 · " + str(r.get("title")),
                    str(r.get("realName")) + " 已确认意向 · " + shortNo(str(r.get("appNo"))), plusHours(t, SLA_HOURS.get("offer")), "sla",
                    "待确认", "red", "/pages/staff/applications?s=2&appId=" + r.get("appId")));
        }

        // 角色切换后旅居、服务订单的退款只由平台在后台审：门店工作台不再列「退款待处理」（门店点同意也是 403），只收知会消息
        if (!RolePhase.isNew())
        {
            for (Map<String, Object> r : db.list("select order_id, order_no, guest_name, refund_apply_time, refund_fen from kg_stay_order "
                    + "where store_id = :s and del_flag = '0' and status in ('refund_pending','refunding') order by refund_apply_time", ps))
            {
                Date t = StaffSupport.parse(r.get("refundApplyTime"));
                out.add(todo("stay_refund", r.get("orderId"), "住宿退款待处理",
                        str(r.get("guestName")) + " · 订单 " + str(r.get("orderNo")) + " · " + KgStats.yuanText(num(r.get("refundFen"))),
                        plusHours(t, SLA_HOURS.get("stay_refund")), "sla", "待退款", "red", "/pages/staff/insurance?s=4&tab=refund&orderNo=" + r.get("orderNo")));
            }

            for (Map<String, Object> r : db.list("select order_id, order_no, user_name, refund_apply_time, refund_fen from kg_shop_order "
                    + "where store_id = :s and del_flag = '0' and status = 'refund_pending' order by refund_apply_time", ps))
            {
                Date t = StaffSupport.parse(r.get("refundApplyTime"));
                out.add(todo("shop_refund", r.get("orderId"), "服务退款待处理",
                        str(r.get("userName")) + " · 订单 " + str(r.get("orderNo")) + " · " + KgStats.yuanText(num(r.get("refundFen"))),
                        plusHours(t, SLA_HOURS.get("shop_refund")), "sla", "待退款", "red", "/pages/staff/insurance?s=4&tab=refund&orderNo=" + r.get("orderNo")));
            }
        }

        if (!keeper)
        {
            for (Map<String, Object> r : db.list("select i.apply_id, i.insured_name, i.start_date, i.create_time, pl.plan_name from kg_insurance_apply i "
                    + "left join kg_insurance_plan pl on pl.plan_id = i.plan_id where i.store_id = :s and i.del_flag = '0' and i.status = 'pending' "
                    + "order by i.create_time", ps))
            {
                Date t = StaffSupport.parse(r.get("createTime"));
                out.add(todo("insurance", r.get("applyId"), "保险待办理",
                        str(r.get("insuredName")) + " · " + str(r.get("planName")) + " · 起保 " + str(r.get("startDate")),
                        plusHours(t, SLA_HOURS.get("insurance")), "sla", "待办理", "amber", "/pages/staff/insurance?tab=pending"));
            }
        }

        for (Map<String, Object> r : db.list("select a.activity_id, a.title, a.start_time, count(g.signup_id) n from kg_activity a "
                + "left join kg_activity_signup g on g.activity_id = a.activity_id and g.status = 'confirmed' and g.checkin_time is null "
                + "where a.store_id = :s and a.del_flag = '0' and a.status in ('open','paused','closed') and a.start_time >= now() "
                + "and a.start_time < date_add(now(), interval 24 hour) group by a.activity_id, a.title, a.start_time order by a.start_time", ps))
        {
            out.add(todo("activity", r.get("activityId"), "活动即将开始 · " + str(r.get("title")),
                    "待签到 " + num(r.get("n")) + " 人", StaffSupport.parse(r.get("startTime")), "real", "待签到", "amber",
                    "/pages/staff/activities?s=4&id=" + r.get("activityId")));
        }

        // 调度对小院主理人是整类去掉，不能改成只看线上：线上任务的 hired_count 永远是 0，每个上架的任务都会被算成缺满员
        if (!keeper)
        {
            for (Map<String, Object> r : db.list("select job_id, title, headcount, hired_count, start_date from kg_job "
                    + "where store_id = :s and del_flag = '0' and status = 'published' and hired_count < headcount order by start_date", ps))
            {
                long gap = num(r.get("headcount")) - num(r.get("hiredCount"));
                Date start = StaffSupport.parse(r.get("startDate"));
                out.add(todo("dispatch", r.get("jobId"), "岗位缺人 · " + str(r.get("title")),
                        "还缺 " + gap + " 人（需 " + num(r.get("headcount")) + " 人，已录用 " + num(r.get("hiredCount")) + " 人）", start, "real",
                        "缺 " + gap + " 人", "amber", "/pages/staff/dispatch?s=2&jobId=" + r.get("jobId")));
            }
        }
        if (!StaffPerms.allows(role, "rooms", StaffPerms.VIEW))
            out.removeIf(item -> "stay_refund".equals(item.get("type")) || "shop_refund".equals(item.get("type")));
        return out;
    }

    /** 待办列表：keyword 匹配标题 / 副标题（含编号），tab 为「全部」或分类名 */
    public Map<String, Object> todos(Long storeId, String tab, String keyword)
    {
        StaffSupport.Ctx c = support.ctx(storeId);
        List<Map<String, Object>> all = collect(c.storeId);
        attachMeta(c.storeId, all);
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        String t = tab == null || tab.isEmpty() || "全部".equals(tab) ? "" : tab;

        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        counts.put("全部", Integer.valueOf(all.size()));
        // 小院主理人没有保险、调度这两类待办（见 collect），页签也不留
        boolean keeper = StaffRole.KEEPER.equals(c.role);
        for (String name : keeper ? Arrays.asList("报名", "订单", "活动") : Arrays.asList("报名", "订单", "保险", "活动", "调度"))
        {
            counts.put(name, Integer.valueOf(0));
        }
        int overdue = 0;
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> m : all)
        {
            String bucket = str(m.get("tab"));
            counts.put(bucket, Integer.valueOf(counts.getOrDefault(bucket, Integer.valueOf(0)).intValue() + 1));
            if (Boolean.TRUE.equals(m.get("overdue")))
            {
                overdue++;
            }
            if (!t.isEmpty() && !t.equals(bucket))
            {
                continue;
            }
            if (!kw.isEmpty() && !(str(m.get("title")) + str(m.get("sub")) + str(m.get("owner"))).toLowerCase().contains(kw))
            {
                continue;
            }
            rows.add(m);
        }
        rows.sort((a, b) -> {
            // 加急的待审核报名排最前（报名人花了积分，说好了「门店那边会排在前面」），其余照旧按截止时间
            boolean ua = Boolean.TRUE.equals(a.get("urgent"));
            boolean ub = Boolean.TRUE.equals(b.get("urgent"));
            if (ua != ub)
            {
                return ua ? -1 : 1;
            }
            Long x = (Long) a.get("dueTs");
            Long y = (Long) b.get("dueTs");
            if (x == null || y == null)
            {
                return x == null && y == null ? 0 : (x == null ? 1 : -1);
            }
            return Long.compare(x.longValue(), y.longValue());
        });

        // 分组：加急报名 / 逾期 / 今天到期 / 本周到期 / 暂无截止。
        // 之前只按截止时间分组，加急报名（平台时限一般还没到）会排在逾期、今天到期的事项后面；现在单独一组放在最上面
        List<Map<String, Object>> groups = new ArrayList<Map<String, Object>>();
        Map<String, Object> urgentGroup = group("加急报名", false);
        urgentGroup.put("urgent", Boolean.TRUE);
        groups.add(urgentGroup);
        groups.add(group("已逾期", true));
        groups.add(group("今天到期", false));
        groups.add(group("本周到期", false));
        groups.add(group("暂无截止时间", false));
        long now = System.currentTimeMillis();
        long endOfDay = StaffSupport.startOfDay(new Date()).getTime() + 86400000L;
        for (Map<String, Object> m : rows)
        {
            if (Boolean.TRUE.equals(m.get("urgent")))
            {
                items(urgentGroup).add(m);
                continue;
            }
            Long due = (Long) m.get("dueTs");
            int g = due == null ? 4 : (due.longValue() < now ? 1 : (due.longValue() < endOfDay ? 2 : (due.longValue() < endOfDay + 6 * 86400000L ? 3 : 4)));
            items(groups.get(g)).add(m);
        }
        groups.removeIf(g -> items(g).isEmpty());

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("storeId", c.storeId);
        out.put("storeName", c.storeName);
        out.put("groups", groups);
        out.put("total", Integer.valueOf(all.size()));
        out.put("overdue", Integer.valueOf(overdue));
        out.put("counts", counts);
        // 切换后退款不归门店，时限说明里不再写退款
        out.put("slaNote", "「平台时限」是平台设定的处理时限：报名审核 48 小时、确认录用 24 小时" + (RolePhase.isNew() ? "" : "、退款 48 小时") + (keeper ? "。" : "、保险办理 24 小时。"));
        return out;
    }

    private static Map<String, Object> group(String title, boolean overdue)
    {
        Map<String, Object> g = new LinkedHashMap<String, Object>();
        g.put("title", title);
        g.put("overdue", Boolean.valueOf(overdue));
        g.put("urgent", Boolean.FALSE);
        g.put("items", new ArrayList<Map<String, Object>>());
        return g;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> g)
    {
        return (List<Map<String, Object>>) g.get("items");
    }

    /** 把 kg_todo_meta 里的责任人与备注贴到待办上 */
    private void attachMeta(Long storeId, List<Map<String, Object>> list)
    {
        if (list.isEmpty())
        {
            return;
        }
        Map<String, Map<String, Object>> meta = new LinkedHashMap<String, Map<String, Object>>();
        for (Map<String, Object> r : db.list("select * from kg_todo_meta where store_id = :s", p("s", storeId)))
        {
            meta.put(str(r.get("todoKey")), r);
        }
        for (Map<String, Object> m : list)
        {
            Map<String, Object> r = meta.get(str(m.get("key")));
            m.put("owner", r == null ? "" : str(r.get("ownerName")));
            m.put("ownerId", r == null ? null : r.get("ownerId"));
            m.put("note", r == null ? "" : str(r.get("note")));
        }
    }

    // ---------------------------------------------------------------- 首页概览

    /** 工作台首页：今日三项概览 + 优先处理 */
    public Map<String, Object> home(Long storeId)
    {
        StaffSupport.Ctx c = support.ctx(storeId);
        List<Long> s = java.util.Collections.singletonList(c.storeId);
        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);
        LocalDate yesterday = today.minusDays(1);
        Integer occ = stats.occupancy(s, today, tomorrow);
        Integer occY = stats.occupancy(s, yesterday, today);
        Map<String, Long> rev = stats.revenue(s, today, tomorrow);
        Map<String, Long> revY = stats.revenue(s, yesterday, today);
        Map<String, Long> app = stats.applications(s, today, tomorrow);
        Map<String, Long> appY = stats.applications(s, yesterday, today);

        List<Map<String, Object>> overview = new ArrayList<Map<String, Object>>();
        overview.add(stat("入住率", KgStats.pct(occ), occ == null ? "" : KgStats.deltaBp(occ.longValue(), occY == null ? null : occY.longValue()),
                occ == null || occY == null || occ.intValue() >= occY.intValue()));
        // 派单补的商家店：只管岗位和报名，不露这家店的钱（切换前没有这种授权）
        boolean noMoney = RolePhase.isNew() && support.autoManager(c);
        overview.add(stat("营收(元)", noMoney ? "—" : KgStats.yuanText(rev.get("total")).replace("¥", ""), noMoney ? "" : KgStats.delta(rev.get("total"), revY.get("total")),
                rev.get("total").longValue() >= revY.get("total").longValue()));
        overview.add(stat("新增报名", String.valueOf(app.get("n")), KgStats.delta(app.get("n"), appY.get("n")), app.get("n").longValue() >= appY.get("n").longValue()));

        List<Map<String, Object>> all = collect(c.storeId);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("storeId", c.storeId);
        out.put("storeName", c.storeName);
        out.put("overview", overview);
        out.put("date", today.toString());
        out.put("total", Integer.valueOf(all.size()));
        out.put("counts", countByType(all));
        out.put("dataNote", "入住率、营收按今天 0 点起统计，与昨天同口径对比。");
        return out;
    }

    private static Map<String, Integer> countByType(List<Map<String, Object>> all)
    {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (Map<String, Object> m : all)
        {
            String t = str(m.get("type"));
            counts.put(t, Integer.valueOf(counts.getOrDefault(t, Integer.valueOf(0)).intValue() + 1));
        }
        return counts;
    }

    private static Map<String, Object> stat(String label, String value, String delta, boolean up)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("label", label);
        m.put("value", value);
        m.put("delta", delta);
        m.put("up", Boolean.valueOf(up));
        return m;
    }

    /** 今日经营数据：六个指标 + 近 7 天趋势（归一化到 0–1 供迷你折线用） */
    public Map<String, Object> metrics(Long storeId)
    {
        StaffSupport.Ctx c = support.ctx(storeId);
        List<Long> s = java.util.Collections.singletonList(c.storeId);
        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);
        LocalDate yesterday = today.minusDays(1);
        LocalDate weekAgo = today.minusDays(6);

        Integer occ = stats.occupancy(s, today, tomorrow);
        Integer occY = stats.occupancy(s, yesterday, today);
        Map<String, Long> rev = stats.revenue(s, today, tomorrow);
        Map<String, Long> revY = stats.revenue(s, yesterday, today);
        Map<String, Long> good = stats.goodRate(s, weekAgo, tomorrow);
        Map<String, Long> goodPrev = stats.goodRate(s, weekAgo.minusDays(7), weekAgo);
        Map<String, Long> act = stats.activities(s, today, tomorrow);
        Map<String, Long> actY = stats.activities(s, yesterday, today);

        long[] dRev = stats.dailyRevenue(s, weekAgo, tomorrow);
        long[] dOrders = stats.dailyOrders(s, weekAgo, tomorrow);
        long[] dNights = stats.dailyRoomNights(s, weekAgo, tomorrow);
        long[] dPeople = stats.dailyActivityPeople(s, weekAgo, tomorrow);

        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(metric("入住率", KgStats.pct(occ), occ == null ? "" : KgStats.deltaBp(occ.longValue(), occY == null ? null : occY.longValue()),
                occ == null || occY == null || occ.intValue() >= occY.intValue(), dNights));
        // 派单补的商家店：只管岗位和报名，不露这家店的钱（切换前没有这种授权）
        boolean noMoney = RolePhase.isNew() && support.autoManager(c);
        rows.add(metric("营收(元)", noMoney ? "—" : KgStats.yuanText(rev.get("total")).replace("¥", ""), noMoney ? "" : KgStats.delta(rev.get("total"), revY.get("total")),
                rev.get("total").longValue() >= revY.get("total").longValue(), noMoney ? null : dRev));
        rows.add(metric("订单量", String.valueOf(rev.get("orders")), KgStats.delta(rev.get("orders"), revY.get("orders")),
                rev.get("orders").longValue() >= revY.get("orders").longValue(), dOrders));
        rows.add(metric("二消收入(元)", noMoney ? "—" : KgStats.yuanText(rev.get("shop")).replace("¥", ""), noMoney ? "" : KgStats.delta(rev.get("shop"), revY.get("shop")),
                rev.get("shop").longValue() >= revY.get("shop").longValue(), null));
        rows.add(metric("好评率", good.get("bp") == null ? "—" : KgStats.pct(good.get("bp")),
                good.get("bp") == null ? "" : KgStats.deltaBp(good.get("bp"), goodPrev.get("bp")),
                good.get("bp") == null || goodPrev.get("bp") == null || good.get("bp").longValue() >= goodPrev.get("bp").longValue(), null));
        rows.add(metric("活动报名(人)", String.valueOf(act.get("people")), KgStats.delta(act.get("people"), actY.get("people")),
                act.get("people").longValue() >= actY.get("people").longValue(), dPeople));

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("storeId", c.storeId);
        out.put("storeName", c.storeName);
        out.put("metrics", rows);
        out.put("source", "系统自动统计");
        out.put("note", "好评率按近 7 天的评价计算；其余为今天 0 点起的数据，趋势为近 7 天。");
        return out;
    }

    /** 迷你折线要的是 0–1，按区间内最大值归一，全 0 时不给点（前端不画线） */
    private static Map<String, Object> metric(String label, String value, String delta, boolean up, long[] series)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("label", label);
        m.put("value", value);
        m.put("delta", delta);
        m.put("up", Boolean.valueOf(up));
        List<Double> pts = new ArrayList<Double>();
        if (series != null)
        {
            long max = 0;
            for (long v : series)
            {
                max = Math.max(max, v);
            }
            if (max > 0)
            {
                for (long v : series)
                {
                    pts.add(Double.valueOf(Math.round(Math.max(0, v) * 1000.0 / max) / 1000.0));
                }
            }
        }
        m.put("points", pts);
        return m;
    }

    // ---------------------------------------------------------------- 任务详情

    public Map<String, Object> detail(Long storeId, String key)
    {
        StaffSupport.Ctx c = support.ctx(storeId);
        Map<String, Object> hit = null;
        for (Map<String, Object> m : collect(c.storeId))
        {
            if (str(m.get("key")).equals(key))
            {
                hit = m;
                break;
            }
        }
        if (hit == null)
        {
            throw new ServiceException("这条待办已经处理完了", 404);
        }
        attachMeta(c.storeId, java.util.Collections.singletonList(hit));
        String type = str(hit.get("type"));
        Long bizId = KgJdbc.asLong(hit.get("bizId"));

        List<Map<String, Object>> related = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> logs = new ArrayList<Map<String, Object>>();
        String desc;
        Date created = null;

        if ("app_review".equals(type) || "offer".equals(type))
        {
            Map<String, Object> a = db.must("select a.*, j.title, j.job_type from kg_application a join kg_job j on j.job_id = a.job_id "
                    + "where a.app_id = :id and a.store_id = :s", p("id", bizId, "s", c.storeId), "报名不存在");
            created = StaffSupport.parse(a.get("submitTime"));
            related.add(rel("报名编号", str(a.get("appNo")), "/pages/staff/applications?s=2&appId=" + bizId));
            related.add(rel("岗位", str(a.get("title")), "/pages/staff/jobs?s=1"));
            related.add(rel("报名人", str(a.get("realName")) + " · " + StaffSupport.toInt(a.get("age"), 0) + " 岁", "/pages/staff/applications?s=2&appId=" + bizId));
            boolean remote = "online".equals(str(a.get("jobType")));
            related.add(remote ? rel("参与方式", "全国远程 · 无需到店", "") : rel("到岗日期", str(a.get("arrivalDate")) + " · " + StaffSupport.toInt(a.get("stayDays"), 0) + " 天", ""));
            desc = remote ? "审核接单资料，通过后接单人远程完成任务；请在任务中验收成果并处理结算。" : "app_review".equals(type) ? "查看简历与技能，决定通过初筛还是拒绝；通过后可直接约面试。"
                    : "候选人已确认意向，请在门店侧确认录用，确认后进入签署与入职流程。";
            log(logs, a.get("submitTime"), "提交报名", false);
            log(logs, a.get("auditTime"), "初筛" + ("review_rejected".equals(str(a.get("status"))) ? "拒绝" : "通过"), false);
            log(logs, a.get("userConfirmTime"), "候选人确认意向", false);
            log(logs, a.get("storeConfirmTime"), "门店确认录用", false);
        }
        else if ("interview".equals(type))
        {
            Map<String, Object> i = db.must("select i.*, a.app_no, a.real_name, a.app_id, j.title from kg_interview i "
                    + "join kg_application a on a.app_id = i.app_id join kg_job j on j.job_id = a.job_id "
                    + "where i.interview_id = :id and a.store_id = :s", p("id", bizId, "s", c.storeId), "面试不存在");
            created = StaffSupport.parse(i.get("createTime"));
            related.add(rel("报名编号", str(i.get("appNo")), "/pages/staff/applications?s=2&appId=" + i.get("appId")));
            related.add(rel("岗位", str(i.get("title")), "/pages/staff/jobs?s=1"));
            related.add(rel("候选人", str(i.get("realName")), "/pages/staff/interviews?id=" + bizId));
            related.add(rel("面试时段", mdhm(StaffSupport.parse(i.get("startTime"))) + " – " + StaffSupport.fmt(StaffSupport.parse(i.get("endTime")), "HH:mm"), ""));
            desc = "按约定时段进行视频面试，结束后在面试管理里填写评价并推进流程。";
            log(logs, i.get("createTime"), "候选人预约面试", false);
            // reschedule_count 只数候选人自己改的；门店改期只记 rescheduled_time（时间是最近一次改期）
            int userReschedules = StaffSupport.toInt(i.get("rescheduleCount"), 0);
            if (userReschedules > 0 || i.get("rescheduledTime") != null)
            {
                log(logs, i.get("rescheduledTime"), userReschedules > 0 ? "面试改期（候选人已改 " + userReschedules + " 次）" : "面试改期", false);
            }
        }
        else if ("stay_refund".equals(type) || "shop_refund".equals(type))
        {
            String table = "stay_refund".equals(type) ? "kg_stay_order" : "kg_shop_order";
            Map<String, Object> o = db.must("select * from " + table + " where order_id = :id and store_id = :s", p("id", bizId, "s", c.storeId), "订单不存在");
            created = StaffSupport.parse(o.get("refundApplyTime"));
            related.add(rel("订单号", str(o.get("orderNo")), "/pages/staff/insurance?s=4&tab=refund&orderNo=" + o.get("orderNo")));
            related.add(rel("申请退款", KgStats.yuanText(num(o.get("refundFen"))), ""));
            related.add(rel("退款原因", str(o.get("refundReason")), ""));
            related.add(rel("下单时间", mdhm(StaffSupport.parse(o.get("createTime"))), ""));
            desc = "核对退款原因与退款规则后审批；同意后按原路退回，拒绝需填写理由并会通知用户。";
            log(logs, o.get("createTime"), "用户下单", false);
            log(logs, o.get("paidTime"), "支付成功", false);
            log(logs, o.get("refundApplyTime"), "用户申请退款", true);
        }
        else if ("insurance".equals(type))
        {
            Map<String, Object> i = db.must("select i.*, pl.plan_name, a.app_no from kg_insurance_apply i left join kg_insurance_plan pl on pl.plan_id = i.plan_id "
                    + "left join kg_application a on a.app_id = i.app_id where i.apply_id = :id and i.store_id = :s", p("id", bizId, "s", c.storeId), "保险申请不存在");
            created = StaffSupport.parse(i.get("createTime"));
            related.add(rel("被保人", str(i.get("insuredName")), "/pages/staff/insurance?tab=pending"));
            related.add(rel("方案", str(i.get("planName")), ""));
            related.add(rel("保障期间", str(i.get("startDate")) + " 至 " + str(i.get("endDate")), ""));
            related.add(rel("关联报名", str(i.get("appNo")), i.get("appId") == null ? "" : "/pages/staff/applications?s=2&appId=" + i.get("appId")));
            desc = "在保险渠道完成投保后，回到保险管理登记保单号；登记后用户可在小程序查看保障。";
            log(logs, i.get("createTime"), "发起投保申请", false);
        }
        else if ("activity".equals(type))
        {
            Map<String, Object> a = db.must("select * from kg_activity where activity_id = :id and store_id = :s", p("id", bizId, "s", c.storeId), "活动不存在");
            created = StaffSupport.parse(a.get("createTime"));
            related.add(rel("活动", str(a.get("title")), "/pages/staff/activities?s=1"));
            related.add(rel("开始时间", mdhm(StaffSupport.parse(a.get("startTime"))), ""));
            related.add(rel("地点", str(a.get("place")), ""));
            related.add(rel("已报名", num(a.get("signed")) + " 人", "/pages/staff/activities?s=4&id=" + bizId));
            desc = "活动开始前确认物料与人员，开始后在活动管理里逐个核销签到。";
            log(logs, a.get("createTime"), "创建活动", false);
        }
        else
        {
            Map<String, Object> j = db.must("select * from kg_job where job_id = :id and store_id = :s", p("id", bizId, "s", c.storeId), "岗位不存在");
            created = StaffSupport.parse(j.get("createTime"));
            related.add(rel("岗位", str(j.get("title")), "/pages/staff/jobs?s=1"));
            related.add(rel("需求人数", num(j.get("headcount")) + " 人", ""));
            related.add(rel("到岗日期", str(j.get("startDate")), ""));
            related.add(rel("岗位报名", "查看报名列表", "/pages/staff/applications?s=1"));
            desc = "人手不足：可在调度后台调整名额或从人才库邀请合适的人报名。";
            log(logs, j.get("publishTime"), "岗位发布", false);
        }

        for (Map<String, Object> r : db.list("select * from kg_todo_meta where store_id = :s and todo_key = :k", p("s", c.storeId, "k", key)))
        {
            if (r.get("assignedTime") != null)
            {
                log(logs, r.get("assignedTime"), str(r.get("assignedBy")) + " 把任务转派给 " + str(r.get("ownerName")), false);
            }
            if (r.get("noteTime") != null)
            {
                log(logs, r.get("noteTime"), str(r.get("noteBy")) + " 添加备注", false);
            }
        }
        logs.sort((a, b) -> str(a.get("ts")).compareTo(str(b.get("ts"))));

        Map<String, Object> task = new LinkedHashMap<String, Object>();
        task.put("key", key);
        task.put("title", hit.get("title"));
        task.put("subtitle", hit.get("sub"));
        task.put("due", hit.get("due"));
        task.put("dueKind", hit.get("dueKind"));
        task.put("dueText", str(hit.get("due")) + ("sla".equals(str(hit.get("dueKind"))) ? "（平台时限）" : ""));
        task.put("overdue", hit.get("overdue"));
        task.put("over", hit.get("over"));
        task.put("created", mdhm(created));
        task.put("owner", str(hit.get("owner")).isEmpty() ? "未指定" : str(hit.get("owner")));
        task.put("ownerId", hit.get("ownerId"));
        task.put("note", hit.get("note"));
        task.put("desc", desc);
        task.put("related", related);
        task.put("logs", logs);
        task.put("route", hit.get("route"));

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("task", task);
        out.put("staffs", staffs(c.storeId, hiddenFromKeeper(hit)));
        return out;
    }

    private static Map<String, Object> rel(String label, String value, String route)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("label", label);
        m.put("value", value);
        m.put("route", route);
        return m;
    }

    private void log(List<Map<String, Object>> logs, Object time, String text, boolean red)
    {
        Date d = StaffSupport.parse(time);
        if (d == null)
        {
            return;
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ts", StaffSupport.fmt(d, "yyyy-MM-dd HH:mm"));
        m.put("time", mdhm(d));
        m.put("text", text);
        m.put("red", Boolean.valueOf(red));
        logs.add(m);
    }

    /**
     * 这条待办小院主理人的待办列表里有没有（见 collect）：换工 / 兼职 / 全职的报名、面试、录用，保险、调度都没有。
     * 这些转给他就落空了：他收到通知，全部待办里找不到，责任人却一直显示他。
     */
    private boolean hiddenFromKeeper(Map<String, Object> todo)
    {
        String type = str(todo.get("type"));
        if ("insurance".equals(type) || "dispatch".equals(type))
        {
            return true;
        }
        Map<String, Object> job = null;
        if ("app_review".equals(type) || "offer".equals(type))
        {
            job = db.one("select j.job_type from kg_application a join kg_job j on j.job_id = a.job_id where a.app_id = :id", p("id", todo.get("bizId")));
        }
        else if ("interview".equals(type))
        {
            job = db.one("select j.job_type from kg_interview i join kg_application a on a.app_id = i.app_id join kg_job j on j.job_id = a.job_id where i.interview_id = :id", p("id", todo.get("bizId")));
        }
        return job != null && !"online".equals(str(job.get("jobType")));
    }

    /** 本店在职员工（转派候选），不分待办类型。基线就有的公开方法，类补丁发布不能去掉（build_backend.py 会拦） */
    public List<Map<String, Object>> staffs(Long storeId)
    {
        return staffs(storeId, false);
    }

    /** 本店在职员工（转派候选）；withoutKeeper = 这条待办小院主理人看不到，候选里不放他 */
    public List<Map<String, Object>> staffs(Long storeId, boolean withoutKeeper)
    {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> r : db.list("select g.app_user_id, g.role_code, u.nickname, u.avatar from kg_staff_grant g "
                + "join kg_app_user u on u.user_id = g.app_user_id where g.store_id = :s and g.status = '0' and g.del_flag = '0' and u.del_flag = '0' "
                + "and g.role_code not in ('merchant_service','city_lead') order by g.grant_id", p("s", storeId)))
        {
            if (withoutKeeper && com.ruoyi.kangaroo.enums.StaffRole.KEEPER.equals(str(r.get("roleCode"))))
            {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("userId", r.get("appUserId"));
            m.put("name", str(r.get("nickname")).isEmpty() ? "员工" + r.get("appUserId") : str(r.get("nickname")));
            m.put("avatar", r.get("avatar"));
            m.put("role", com.ruoyi.kangaroo.enums.StaffRole.label(str(r.get("roleCode"))));
            rows.add(m);
        }
        return rows;
    }

    // ---------------------------------------------------------------- 转派与备注

    private void upsertMeta(Long storeId, String key, Map<String, Object> cols)
    {
        Map<String, Object> row = db.one("select id from kg_todo_meta where store_id = :s and todo_key = :k", p("s", storeId, "k", key));
        cols.put("update_time", new Date());
        if (row == null)
        {
            cols.put("store_id", storeId);
            cols.put("todo_key", key);
            db.insert("kg_todo_meta", cols);
        }
        else
        {
            db.updateRow("kg_todo_meta", "id", row.get("id"), cols);
        }
    }

    private Map<String, Object> requireTodo(Long storeId, String key)
    {
        for (Map<String, Object> m : collect(storeId))
        {
            if (str(m.get("key")).equals(key))
            {
                return m;
            }
        }
        throw new ServiceException("这条待办已经处理完了", 404);
    }

    @Transactional(rollbackFor = Exception.class)
    public String assign(Long storeId, String key, Long ownerId)
    {
        StaffSupport.Ctx c = support.ctx(storeId);
        Map<String, Object> todo = requireTodo(c.storeId, key);
        Map<String, Object> owner = db.one("select u.user_id, u.nickname, g.role_code from kg_staff_grant g join kg_app_user u on u.user_id = g.app_user_id "
                + "where g.store_id = :s and g.app_user_id = :o and g.status = '0' and g.del_flag = '0' and g.role_code not in ('merchant_service','city_lead')", p("s", c.storeId, "o", ownerId));
        if (owner == null)
        {
            throw new ServiceException("只能转派给本店在职员工", 422);
        }
        if (com.ruoyi.kangaroo.enums.StaffRole.KEEPER.equals(str(owner.get("roleCode"))) && hiddenFromKeeper(todo))
        {
            throw new ServiceException("小院主理人的待办里没有这一类，不能转派给他", 422);
        }
        String name = str(owner.get("nickname")).isEmpty() ? "员工" + ownerId : str(owner.get("nickname"));
        Map<String, Object> cols = new LinkedHashMap<String, Object>();
        cols.put("owner_id", ownerId);
        cols.put("owner_name", StaffSupport.cut(name, 64));
        cols.put("assigned_by", StaffSupport.cut(c.name(), 64));
        cols.put("assigned_time", new Date());
        upsertMeta(c.storeId, key, cols);
        support.op(c, "todo:assign", "待办转派", "general", key + " → " + name, true, null);
        if (!ownerId.equals(c.uid()))
        {
            support.notifyStaff(ownerId, "staff", "有一条待办转派给你", c.name() + " 把「" + str(todo.get("title")) + "」转派给你",
                    "todo", null, "/pages/staff/index?s=2");
        }
        return "已转派给 " + name;
    }

    /**
     * 生成待办清单：把当前这一刻的待办按类型汇总成一条站内信发给自己，
     * 便于离开工作台后按清单逐条处理。汇总的是实时聚合的结果，不新建任何任务。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> digest(Long storeId)
    {
        StaffSupport.Ctx c = support.ctx(storeId);
        List<Map<String, Object>> all = collect(c.storeId);
        int overdue = 0;
        Map<String, Integer> byTab = new LinkedHashMap<String, Integer>();
        for (Map<String, Object> m : all)
        {
            String tab = str(m.get("tab"));
            byTab.put(tab, Integer.valueOf(byTab.getOrDefault(tab, Integer.valueOf(0)).intValue() + 1));
            if (Boolean.TRUE.equals(m.get("overdue")))
            {
                overdue++;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : byTab.entrySet())
        {
            sb.append(sb.length() > 0 ? "、" : "").append(e.getKey()).append(" ").append(e.getValue()).append(" 条");
        }
        String content = all.isEmpty() ? "当前没有待处理的事项。"
                : ("共 " + all.size() + " 条：" + sb + (overdue > 0 ? "；其中已逾期 " + overdue + " 条。" : "。"));
        support.notifyStaff(c.uid(), "staff", c.storeName + " 待办清单（" + StaffSupport.fmt(new Date(), "MM-dd HH:mm") + "）", content,
                "todo", null, "/pages/staff/index?s=2");
        support.op(c, "todo:digest", "生成待办清单", "general", content, true, null);

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("total", Integer.valueOf(all.size()));
        out.put("overdue", Integer.valueOf(overdue));
        out.put("counts", byTab);
        out.put("message", all.isEmpty() ? "当前没有待处理的事项" : "已生成清单并发到你的消息中心：" + content);
        return out;
    }

    // ---------------------------------------------------------------- 简历转发

    /**
     * 把一份报名转发给主理人或全体在职员工（只在门店内部流转，不外发；简历本身不复制进站内信，
     * 收件人点开还是回到报名详情页，权限仍按各自的角色校验）。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> forward(Long storeId, Long appId, String target, String suggestion, boolean track)
    {
        StaffSupport.Ctx c = support.ctx(storeId);
        support.need(c, "jobs", StaffPerms.EDIT, "resume:forward", "简历转发");
        Map<String, Object> subject = db.must("select app_user_id from kg_application where app_id=:id and store_id=:s and del_flag='0'", p("id",appId,"s",c.storeId), "报名不存在");
        com.ruoyi.kangaroo.util.AccountState.requireActive(db, KgJdbc.asLong(subject.get("appUserId")));
        db.one("select app_id from kg_application where app_id=:id for update", p("id",appId));
        Map<String, Object> a = db.must("select a.app_no, a.real_name, j.title, j.job_type from kg_application a join kg_job j on j.job_id = a.job_id "
                + "where a.app_id = :id and a.store_id = :s and a.del_flag = '0'", p("id", appId, "s", c.storeId), "报名不存在");
        String tg = "manager".equals(target) ? "manager" : "store";
        String note = suggestion == null ? "" : suggestion.trim();
        if (note.length() > 120)
        {
            throw new ServiceException("审核建议 120 字以内", 422);
        }

        // 线上任务专员只看得到线上任务的报名（消息中心和报名详情都按这个口径拦），非线上岗位的报名不算他们，
        // 否则「已转发给 N 位同事」多算人、开了已读记录也永远凑不满
        boolean onlineJob = "online".equals(str(a.get("jobType")));
        List<Long> targets = new ArrayList<Long>();
        for (Map<String, Object> r : db.list("select app_user_id, role_code from kg_staff_grant where store_id = :s and status = '0' and del_flag = '0'",
                p("s", c.storeId)))
        {
            String role = str(r.get("roleCode"));
            if ("manager".equals(tg) && !com.ruoyi.kangaroo.enums.StaffRole.MANAGER.equals(role))
            {
                continue;
            }
            if (!onlineJob && com.ruoyi.kangaroo.enums.StaffRole.onlineOnly(role))
            {
                continue;
            }
            // 城市负责人平时不做日常，不接转发
            if (com.ruoyi.kangaroo.enums.StaffRole.CITY_LEAD.equals(role))
            {
                continue;
            }
            Long uid = KgJdbc.asLong(r.get("appUserId"));
            if (uid != null && !uid.equals(c.uid()) && !targets.contains(uid))
            {
                targets.add(uid);
            }
        }
        if (targets.isEmpty())
        {
            throw new ServiceException("manager".equals(tg) ? "本店还没有其他换工主理人账号" : "本店暂时没有其他在职员工", 409);
        }

        StringBuilder ids = new StringBuilder();
        String content = str(a.get("realName")) + " 报名「" + str(a.get("title")) + "」" + (note.isEmpty() ? "" : "；建议：" + note);
        int sent = 0;
        for (Long uid : targets)
        {
            // 收件人把这类员工通知关掉时 send 返回 null —— 那条提醒确实没发出去，不能算进"已转发"
            Long msgId = support.notifyStaffWithId(uid, "staff", c.name() + " 转来一份简历", content, "application", appId,
                    "/pages/staff/applications?s=2&appId=" + appId);
            if (msgId != null)
            {
                sent++;
                ids.append(ids.length() > 0 ? "," : "").append(msgId);
            }
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("app_id", appId);
        row.put("store_id", c.storeId);
        row.put("from_user_id", c.uid());
        row.put("from_name", StaffSupport.cut(c.name(), 64));
        row.put("target", tg);
        row.put("suggestion", note);
        row.put("msg_ids", StaffSupport.cut(ids.toString(), 500));
        row.put("track", track ? "1" : "0");
        row.put("create_time", new Date());
        db.insert("kg_resume_forward", row);
        support.op(c, "resume:forward", "简历转发", "general", "报名 " + str(a.get("appNo")) + " → " + ("manager".equals(tg) ? "换工主理人" : "门店群"), true, null);

        String who = "manager".equals(tg) ? "换工主理人" : "同事";
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("message", sent == targets.size()
                ? "已转发给 " + sent + " 位" + who
                : (sent == 0
                        ? "已记录这次转发，但 " + targets.size() + " 位" + who + "都关掉了这类员工通知，没有收到提醒"
                        : "已转发给 " + sent + " 位" + who + "；另有 " + (targets.size() - sent) + " 位关掉了这类员工通知"));
        out.put("count", Integer.valueOf(sent));
        out.put("targets", Integer.valueOf(targets.size()));
        out.put("records", forwards(c.storeId, appId));
        return out;
    }

    /** 校验对该门店的员工授权并返回门店ID（storeId 为空时取第一家授权门店） */
    public Long storeOf(Long storeId)
    {
        return support.ctx(storeId).storeId;
    }

    /** 某份报名的转发记录；开了「记录已读」的，统计站内信已读人数 */
    public List<Map<String, Object>> forwards(Long storeId, Long appId)
    {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> r : db.list("select * from kg_resume_forward where store_id = :s and app_id = :a order by forward_id desc",
                p("s", storeId, "a", appId)))
        {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("from", r.get("fromName"));
            m.put("target", "cross_store".equals(str(r.get("target"))) ? str(r.get("targetStoreName")) : ("manager".equals(str(r.get("target"))) ? "换工主理人" : "门店群"));
            m.put("suggestion", r.get("suggestion"));
            m.put("time", StaffSupport.fmt(StaffSupport.parse(r.get("createTime")), "MM-dd HH:mm"));
            String ids = str(r.get("msgIds"));
            int total = ids.isEmpty() ? 0 : ids.split(",").length;
            m.put("total", Integer.valueOf(total));
            if ("1".equals(str(r.get("track"))) && total > 0)
            {
                long read = db.count("select count(*) from kg_message where msg_id in (" + ids + ") and read_flag = '1'", p());
                m.put("read", Long.valueOf(read));
                m.put("readText", "已读 " + read + "/" + total);
            }
            else
            {
                m.put("readText", total > 0 ? "未开启已读统计" : "");
            }
            rows.add(m);
        }
        return rows;
    }

    @Transactional(rollbackFor = Exception.class)
    public String note(Long storeId, String key, String note)
    {
        StaffSupport.Ctx c = support.ctx(storeId);
        requireTodo(c.storeId, key);
        String v = note == null ? "" : note.trim();
        if (v.length() > 200)
        {
            throw new ServiceException("备注 200 字以内", 422);
        }
        Map<String, Object> cols = new LinkedHashMap<String, Object>();
        cols.put("note", v.isEmpty() ? null : v);
        cols.put("note_by", StaffSupport.cut(c.name(), 64));
        cols.put("note_time", v.isEmpty() ? null : new Date());
        upsertMeta(c.storeId, key, cols);
        support.op(c, "todo:note", "待办备注", "general", key, true, null);
        return v.isEmpty() ? "已清空备注" : "备注已保存";
    }
}
