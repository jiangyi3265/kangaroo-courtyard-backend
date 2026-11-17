package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.staff.StaffSupport.str;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.MessageCategory;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.StaffGrantView;

/**
 * 调度：需求看板（按城市/门店汇总招聘名额与缺口）、调整名额（带有效期，到期自动恢复）、紧急标记与触达、调度日志。
 *
 * 看板只统计当前账号有授权的门店。名额调整改的是岗位的 headcount，有效期开始前不生效、结束后由
 * {@link #sync()} 自动恢复（每天凌晨跑一次，看板/日志打开时也顺手跑一次，重复执行不会重复调整）。
 */
@Service
public class KgDispatchService
{
    private static final Logger log = LoggerFactory.getLogger(KgDispatchService.class);

    static final List<String> TYPES = Arrays.asList("volunteer", "parttime", "fulltime");
    static final String[] LEVELS = { "无", "一般", "较急", "紧急", "特急" };

    @Autowired
    private KgJdbc db;

    @Autowired
    private StaffSupport support;

    /** 走代理调用自己的 @Transactional 方法 */
    @Lazy
    @Autowired
    private com.ruoyi.kangaroo.ext.user.KgUserHubService hub;

    @Lazy
    @Autowired
    private KgDispatchService self;

    /** 按城市授权的员工（紧急调度触达本店人员时也算上）；单测没注入时不算 */
    @Autowired(required = false)
    private CityGrantScope cityGrants;

    private static List<Long> storeIds(StaffSupport.Ctx c)
    {
        List<Long> ids = new ArrayList<Long>();
        for (StaffGrantView g : c.me.getStaffGrants())
        {
            ids.add(g.getStoreId());
        }
        return ids;
    }

    /** 账号在某家授权门店上的角色，与 StaffSupport.ctx 同口径：不认识的角色按普通员工 */
    private static String roleOf(StaffGrantView g)
    {
        return StaffRole.exists(g.getRoleCode()) ? g.getRoleCode() : StaffRole.STAFF;
    }

    /**
     * 这家门店（storeId 为空 = 全部授权门店里至少一家）能不能导出调度日志。
     * 每家门店按账号在那家门店上的角色分别判：同一个人在 A 店是主理人、在 B 店只是员工很常见，
     * 不能拿 ctx(null) 取到的第一家门店的角色代表全部。
     */
    public boolean canExport(StaffSupport.Ctx c, Long storeId)
    {
        for (StaffGrantView g : c.me.getStaffGrants())
        {
            if ((storeId == null || storeId.equals(g.getStoreId())) && StaffPerms.allows(roleOf(g), "dispatch", StaffPerms.EXPORT))
            {
                return true;
            }
        }
        return false;
    }

    // ---------- 看板 ----------

    public Map<String, Object> board(StaffSupport.Ctx c, String dim, String city, String date, String type)
    {
        trySync();
        Date day = StaffSupport.parse(date);
        day = StaffSupport.startOfDay(day == null ? new Date() : day);
        String typeFilter = type != null && TYPES.contains(type) ? type : null;
        List<Map<String, Object>> jobs = db.list("select j.job_id, j.store_id, s.store_name, s.city, j.job_type, j.headcount, j.hired_count, "
                + "j.urgent_level, coalesce(j.start_date, date(j.publish_time), date(j.create_time)) begin_date "
                + "from kg_job j join kg_store s on s.store_id = j.store_id where j.store_id in (:ids) and j.del_flag = '0' "
                + "and j.status in ('published', 'paused') and j.job_type in ('volunteer', 'parttime', 'fulltime')",
                p("ids", storeIds(c)));
        Set<String> cities = new LinkedHashSet<String>();
        for (Map<String, Object> j : jobs)
        {
            cities.add(cityOf(j));
        }
        for (StaffGrantView g : c.me.getStaffGrants())
        {
            Map<String, Object> s = db.one("select city from kg_store where store_id = :s", p("s", g.getStoreId()));
            if (s != null)
            {
                cities.add(str(s.get("city")));
            }
        }
        String selCity = city != null && cities.contains(city) ? city : (cities.isEmpty() ? "" : cities.iterator().next());
        boolean byStore = "store".equals(dim);

        Map<String, Map<String, Object>> cards = new LinkedHashMap<String, Map<String, Object>>();
        Map<String, long[]> storeGap = new LinkedHashMap<String, long[]>();
        Map<String, Object> storeIdOf = new LinkedHashMap<String, Object>();
        for (Map<String, Object> j : jobs)
        {
            String t = str(j.get("jobType"));
            if (typeFilter != null && !typeFilter.equals(t))
            {
                continue;
            }
            if (byStore && !selCity.equals(cityOf(j)))
            {
                continue;
            }
            String key = byStore ? str(j.get("storeName")) : cityOf(j);
            Map<String, Object> card = cards.get(key);
            if (card == null)
            {
                card = new LinkedHashMap<String, Object>();
                card.put("name", key);
                if (byStore)
                {
                    // 看板上点门店卡片要对这家门店调整名额 / 标紧急 / 看日志，得知道是哪家
                    card.put("storeId", j.get("storeId"));
                }
                card.put("urgent", 0);
                card.put("need", new long[3]);
                card.put("arranged", new long[3]);
                card.put("gap", new long[3]);
                cards.put(key, card);
            }
            int idx = TYPES.indexOf(t);
            long need = StaffSupport.toInt(j.get("headcount"), 0);
            long hired = StaffSupport.toInt(j.get("hiredCount"), 0);
            ((long[]) card.get("need"))[idx] += need;
            ((long[]) card.get("arranged"))[idx] += hired;
            ((long[]) card.get("gap"))[idx] += Math.max(0, need - hired);
            if (StaffSupport.toInt(j.get("urgentLevel"), 0) > 0)
            {
                card.put("urgent", StaffSupport.toInt(card.get("urgent"), 0) + 1);
            }
            if (selCity.equals(cityOf(j)))
            {
                String sk = str(j.get("storeName"));
                long[] g = storeGap.get(sk);
                if (g == null)
                {
                    g = new long[1];
                    storeGap.put(sk, g);
                    storeIdOf.put(sk, j.get("storeId"));
                }
                g[0] += Math.max(0, need - hired);
            }
        }
        List<Map<String, Object>> top = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, long[]> e : storeGap.entrySet())
        {
            if (e.getValue()[0] > 0)
            {
                Map<String, Object> t = new LinkedHashMap<String, Object>();
                t.put("name", e.getKey());
                t.put("storeId", storeIdOf.get(e.getKey()));
                t.put("gap", e.getValue()[0]);
                top.add(t);
            }
        }
        top.sort((a, b) -> Long.compare(((Number) b.get("gap")).longValue(), ((Number) a.get("gap")).longValue()));

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("dim", byStore ? "store" : "city");
        out.put("cities", new ArrayList<String>(cities));
        out.put("city", selCity);
        out.put("date", StaffSupport.fmt(day, "yyyy-MM-dd"));
        out.put("type", typeFilter == null ? "" : typeFilter);
        out.put("cards", new ArrayList<Map<String, Object>>(cards.values()));
        out.put("heat", heat(jobs, selCity, day, typeFilter));
        out.put("topStores", top.size() > 5 ? top.subList(0, 5) : top);
        return out;
    }

    private static String cityOf(Map<String, Object> j)
    {
        String c = str(j.get("city"));
        return c.isEmpty() ? "未填城市" : c;
    }

    /**
     * 需求热力：某天的需求 = 该城市里"招募窗口覆盖这一天"的岗位缺口之和。
     * 招募窗口按到岗日期起 30 天计；分档与图例一致：1–5 低、6–15 中、16–30 高、30 以上紧急。
     */
    private Map<String, Object> heat(List<Map<String, Object>> jobs, String city, Date day, String typeFilter)
    {
        Calendar cal = Calendar.getInstance();
        cal.setTime(day);
        cal.set(Calendar.DAY_OF_MONTH, 1);
        Date first = StaffSupport.startOfDay(cal.getTime());
        int days = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
        int dow = cal.get(Calendar.DAY_OF_WEEK); // 周日 = 1
        int lead = dow == Calendar.SUNDAY ? 6 : dow - Calendar.MONDAY;
        long[] value = new long[days + 1];
        for (Map<String, Object> j : jobs)
        {
            if (!city.equals(cityOf(j)) || (typeFilter != null && !typeFilter.equals(str(j.get("jobType")))))
            {
                continue;
            }
            long gap = Math.max(0, StaffSupport.toInt(j.get("headcount"), 0) - StaffSupport.toInt(j.get("hiredCount"), 0));
            Date begin = StaffSupport.parse(j.get("beginDate"));
            if (gap == 0 || begin == null)
            {
                continue;
            }
            Date end = StaffSupport.addDays(begin, 29);
            for (int d = 1; d <= days; d++)
            {
                Date cur = StaffSupport.addDays(first, d - 1);
                if (!cur.before(begin) && !cur.after(end))
                {
                    value[d] += gap;
                }
            }
        }
        List<Map<String, Object>> cells = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < lead; i++)
        {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("d", 0);
            m.put("c", "");
            cells.add(m);
        }
        for (int d = 1; d <= days; d++)
        {
            long v = value[d];
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("d", d);
            m.put("v", v);
            m.put("c", v == 0 ? "" : (v <= 5 ? "low" : (v <= 15 ? "mid" : (v <= 30 ? "high" : "urgent"))));
            cells.add(m);
        }
        Map<String, Object> h = new LinkedHashMap<String, Object>();
        h.put("title", "需求热力日历（" + city + "）");
        h.put("month", StaffSupport.fmt(first, "yyyy年M月"));
        h.put("cells", cells);
        h.put("prev", StaffSupport.fmt(StaffSupport.addDays(first, -1), "yyyy-MM-dd"));
        cal.setTime(first);
        cal.add(Calendar.MONTH, 1);
        h.put("next", StaffSupport.fmt(cal.getTime(), "yyyy-MM-dd"));
        return h;
    }

    // ---------- 调整名额 ----------

    private Map<String, Object> ownJob(StaffSupport.Ctx c, Long jobId)
    {
        return ownJob(c, jobId, false);
    }

    private Map<String, Object> ownJob(StaffSupport.Ctx c, Long jobId, boolean lock)
    {
        Map<String, Object> j = db.must("select j.job_id, j.store_id, j.title, j.job_type, j.headcount, j.hired_count, j.status, "
                + "j.gender_male_count, j.gender_female_count, j.urgent_level, j.urgent_deadline, s.store_name, s.city from kg_job j join kg_store s on s.store_id = j.store_id "
                + "where j.job_id = :id and j.del_flag = '0'" + (lock ? " for update" : ""), p("id", jobId), "岗位不存在");
        if (!c.me.hasStoreAccess(KgJdbc.asLong(j.get("storeId"))))
        {
            throw new ServiceException("没有该门店的操作权限", HttpStatus.FORBIDDEN);
        }
        return j;
    }

    /** 调整页：门店（可切换）、门店岗位、按类型的需求汇总 */
    public Map<String, Object> adjustInfo(StaffSupport.Ctx c, Long jobId)
    {
        Map<String, Object> store = db.must("select store_id, store_name, city, address, cover from kg_store where store_id = :s",
                p("s", c.storeId), "门店不存在");
        List<Map<String, Object>> jobs = db.list("select job_id, title, job_type, headcount, hired_count, status from kg_job "
                + "where store_id = :s and del_flag = '0' and status in ('published', 'paused') order by field(job_type, 'volunteer', 'parttime', 'fulltime', 'online'), job_id",
                p("s", c.storeId));
        Map<String, Object> sel = null;
        for (Map<String, Object> j : jobs)
        {
            j.put("typeLabel", StaffSupport.jobTypeLabel(j.get("jobType")));
            if (sel == null || (jobId != null && jobId.equals(KgJdbc.asLong(j.get("jobId")))))
            {
                sel = j;
            }
        }
        List<Map<String, Object>> demands = new ArrayList<Map<String, Object>>();
        for (String t : TYPES)
        {
            long cur = 0;
            long hired = 0;
            for (Map<String, Object> j : jobs)
            {
                if (t.equals(str(j.get("jobType"))))
                {
                    cur += StaffSupport.toInt(j.get("headcount"), 0);
                    hired += StaffSupport.toInt(j.get("hiredCount"), 0);
                }
            }
            Map<String, Object> d = new LinkedHashMap<String, Object>();
            d.put("type", t);
            d.put("name", StaffSupport.jobTypeLabel(t));
            d.put("current", cur);
            d.put("hired", hired);
            demands.add(d);
        }
        List<Map<String, Object>> stores = new ArrayList<Map<String, Object>>();
        for (StaffGrantView g : c.me.getStaffGrants())
        {
            Map<String, Object> s = new LinkedHashMap<String, Object>();
            s.put("storeId", g.getStoreId());
            s.put("storeName", g.getStoreName());
            stores.add(s);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        store.put("sub", str(store.get("city")) + (str(store.get("address")).isEmpty() ? "" : " · " + store.get("address")));
        out.put("store", store);
        out.put("stores", stores);
        out.put("jobs", jobs);
        out.put("job", sel);
        out.put("demands", demands);
        Date today = StaffSupport.startOfDay(new Date());
        out.put("validFrom", StaffSupport.fmt(today, "yyyy-MM-dd"));
        out.put("validTo", StaffSupport.fmt(StaffSupport.addDays(today, 14), "yyyy-MM-dd"));
        out.put("canEdit", StaffPerms.allows(c.role, "dispatch", StaffPerms.EDIT));
        return out;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> adjust(StaffSupport.Ctx c, Long jobId, int delta, String validFrom, String validTo, String reason)
    {
        Map<String, Object> j = ownJob(c, jobId);
        if (j.get("genderMaleCount") != null || j.get("genderFemaleCount") != null)
        {
            throw new ServiceException("此岗位已分别设置男生、女生人数，请到岗位管理中一起修改招募计划", 422);
        }
        if (delta == 0)
        {
            throw new ServiceException("调整人数不能为 0");
        }
        String why = reason == null ? "" : reason.trim();
        if (why.length() < 2 || why.length() > 100)
        {
            throw new ServiceException("请填写调整原因（2–100 字）");
        }
        Date from = StaffSupport.parse(validFrom);
        Date to = StaffSupport.parse(validTo);
        Date today = StaffSupport.startOfDay(new Date());
        if (from == null || to == null)
        {
            throw new ServiceException("有效日期格式应为 yyyy-MM-dd");
        }
        if (from.before(today))
        {
            from = today;
        }
        if (to.before(from))
        {
            throw new ServiceException("有效期结束日期不能早于开始日期");
        }
        if (to.after(StaffSupport.addDays(from, 180)))
        {
            throw new ServiceException("有效期最长 180 天");
        }
        int before = StaffSupport.toInt(j.get("headcount"), 0);
        int hired = StaffSupport.toInt(j.get("hiredCount"), 0);
        int after = before + delta;
        // 换工岗位和后台编辑同一口径：名额不能小于已报名占用（和 KgJobMapper 的 reserved_count 同一算法：未驳回 / 撤回 / 终止的报名），
        // 否则报名和缩名额相互穿透（后台会拒，小程序之前不拦）
        int reserved = 0;
        if ("volunteer".equals(j.get("jobType")))
        {
            reserved = (int) db.count("select count(1) from kg_application where job_id = :id and del_flag = '0' "
                    + "and status not in ('review_rejected', 'withdrawn', 'terminated')", p("id", jobId));
        }
        int floor = Math.max(Math.max(1, hired), reserved);
        if (after < floor || after > 999)
        {
            throw new ServiceException("调整后名额需在 " + floor + "–999 之间（已录用 " + hired + " 人" + (reserved > hired ? "，已报名占用 " + reserved + " 人" : "") + "）");
        }
        boolean now = !from.after(today);
        if (now && db.update("update kg_job set headcount = headcount + :d, update_by = :u, update_time = now() "
                + "where job_id = :id and headcount = :before and headcount + :d >= greatest(1, hired_count) "
                + "and (job_type <> 'volunteer' or headcount + :d >= :reserved) "
                + "and gender_male_count is null and gender_female_count is null", p("d", delta, "id", jobId, "before", before, "u", "staff:" + c.uid(), "reserved", reserved)) == 0)
        {
            throw new ServiceException("名额刚被其他人调整过，请刷新后重试");
        }
        String label = StaffSupport.jobTypeLabel(j.get("jobType"));
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("store_id", j.get("storeId"));
        row.put("job_id", jobId);
        row.put("op_type", "adjust");
        row.put("operator_id", c.uid());
        row.put("operator_name", StaffSupport.cut(c.name(), 64));
        row.put("before_value", label + "：" + before);
        row.put("after_value", label + "：" + after);
        row.put("reason", why);
        row.put("valid_from", StaffSupport.fmt(from, "yyyy-MM-dd"));
        row.put("valid_to", StaffSupport.fmt(to, "yyyy-MM-dd"));
        row.put("delta", delta);
        row.put("applied_time", now ? new Date() : null);
        row.put("create_time", new Date());
        long logId = db.insert("kg_dispatch_log", row);
        support.op(c, "dispatch_adjust", "调整名额", "general", j.get("title") + " " + before + "→" + after, true, null);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("logId", logId);
        out.put("before", before);
        out.put("after", after);
        out.put("applied", now);
        return out;
    }

    // ---------- 紧急标记 ----------

    public Map<String, Object> urgentInfo(StaffSupport.Ctx c, Long jobId)
    {
        Map<String, Object> j = ownJob(c, jobId);
        Long storeId = KgJdbc.asLong(j.get("storeId"));
        int gap = Math.max(0, StaffSupport.toInt(j.get("headcount"), 0) - StaffSupport.toInt(j.get("hiredCount"), 0));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        j.put("typeLabel", StaffSupport.jobTypeLabel(j.get("jobType")));
        j.put("levelLabel", LEVELS[Math.min(4, Math.max(0, StaffSupport.toInt(j.get("urgentLevel"), 0)))]);
        out.put("job", j);
        out.put("gap", gap);
        List<Map<String, Object>> targets = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> managers = recipients(storeId, jobId, "manager");
        StringBuilder names = new StringBuilder();
        for (Map<String, Object> m : managers)
        {
            names.append(names.length() == 0 ? "" : "、").append(str(m.get("nickname")).isEmpty() ? "员工" + m.get("uid") : str(m.get("nickname")));
        }
        targets.add(target("manager", "门店负责人（" + (names.length() == 0 ? "暂无" : StaffSupport.cut(names.toString(), 12)) + "）", managers.size()));
        targets.add(target("staff", "门店员工（" + recipients(storeId, jobId, "staff").size() + " 人）", recipients(storeId, jobId, "staff").size()));
        int fav = recipients(storeId, jobId, "favorites").size();
        targets.add(target("favorites", "相关岗位候选人（收藏该岗位 " + fav + " 人）", fav));
        int city = recipients(storeId, jobId, "city").size();
        targets.add(target("city", "同城其他门店主管（" + city + " 人）", city));
        out.put("targets", targets);
        int subscribers = recipients(storeId, jobId, "subscribers").size();
        out.put("subscribers", subscribers);
        out.put("subscriberBreakdown", p("favorites", recipients(storeId, jobId, "storeFavorites").size(),
                "followers", recipients(storeId, jobId, "followers").size(), "total", subscribers));
        Date deadline = StaffSupport.parse(j.get("urgentDeadline"));
        if (deadline == null || deadline.before(new Date()))
        {
            deadline = StaffSupport.addDays(StaffSupport.startOfDay(new Date()), 2);
            Calendar cal = Calendar.getInstance();
            cal.setTime(deadline);
            cal.set(Calendar.HOUR_OF_DAY, 18);
            deadline = cal.getTime();
        }
        out.put("deadlineDate", StaffSupport.fmt(deadline, "yyyy-MM-dd"));
        out.put("deadlineTime", StaffSupport.fmt(deadline, "HH:mm"));
        out.put("canEdit", StaffPerms.allows(c.role, "dispatch", StaffPerms.EDIT));
        return out;
    }

    private static Map<String, Object> target(String key, String label, int count)
    {
        Map<String, Object> t = new LinkedHashMap<String, Object>();
        t.put("key", key);
        t.put("label", label);
        t.put("count", count);
        return t;
    }

    /** 各类触达对象的账号：manager/staff/city 走员工消息，favorites/subscribers 走用户消息 */
    private List<Map<String, Object>> recipients(Long storeId, Long jobId, String kind)
    {
        if ("manager".equals(kind) || "staff".equals(kind))
        {
            String role = "manager".equals(kind) ? " and g.role_code = 'manager'" : " and g.role_code not in ('manager','merchant_service','city_lead')";
            List<Map<String, Object>> rows = db.list("select distinct g.app_user_id uid, u.nickname from kg_staff_grant g left join kg_app_user u on u.user_id = g.app_user_id "
                    + "where g.store_id = :s and g.status = '0' and g.del_flag = '0' and u.status='0' and u.del_flag='0'" + role, p("s", storeId));
            // 本城按城市授权的人按所选角色同样算（换工主理人算 manager，其余同上面的 not in）
            List<Map<String, Object>> holders = cityGrants == null ? null : cityGrants.holders(storeId);
            if (holders == null || holders.isEmpty())
            {
                return rows;
            }
            List<Map<String, Object>> out = new ArrayList<Map<String, Object>>(rows);
            for (Map<String, Object> h : holders)
            {
                String r = StaffSupport.str(h.get("roleCode"));
                boolean hit = "manager".equals(kind) ? "manager".equals(r) : !Arrays.asList("manager", "merchant_service", "city_lead").contains(r);
                Map<String, Object> u = hit ? db.one("select nickname from kg_app_user where user_id = :u and status='0' and del_flag='0'", p("u", h.get("appUserId"))) : null;
                if (u != null)
                {
                    out.add(p("uid", h.get("appUserId"), "nickname", u == null ? null : u.get("nickname")));
                }
            }
            Map<Long, Map<String, Object>> unique = new LinkedHashMap<Long, Map<String, Object>>();
            for (Map<String, Object> recipient : out) unique.put(KgJdbc.asLong(recipient.get("uid")), recipient);
            return new ArrayList<Map<String, Object>>(unique.values());
        }
        if ("city".equals(kind))
        {
            return db.list("select distinct g.app_user_id uid from kg_staff_grant g join kg_store s on s.store_id = g.store_id join kg_app_user u on u.user_id=g.app_user_id "
                    + "where s.city = (select city from kg_store where store_id = :s) and g.store_id <> :s and g.role_code = 'manager' "
                    + "and g.status = '0' and g.del_flag = '0' and s.status='0' and s.del_flag='0' and u.status='0' and u.del_flag='0'", p("s", storeId));
        }
        if ("favorites".equals(kind))
        {
            return db.list("select distinct f.app_user_id uid from kg_job_favorite f join kg_app_user u on u.user_id=f.app_user_id where f.job_id = :j and u.status='0' and u.del_flag='0' "
                    + "and not exists (select 1 from kg_application a where a.job_id = f.job_id and a.app_user_id = f.app_user_id and a.del_flag = '0')",
                    p("j", jobId));
        }
        String favorites = "select f.app_user_id uid from kg_job_favorite f join kg_job j on j.job_id=f.job_id "
                + "join kg_app_user u on u.user_id=f.app_user_id where j.store_id=:s and j.del_flag='0' and u.status='0' and u.del_flag='0'";
        String followers = "select sub.app_user_id uid from kg_job_subscribe sub join kg_app_user u on u.user_id=sub.app_user_id "
                + "where sub.store_id=:s and sub.status='0' and " + com.ruoyi.kangaroo.ext.user.KgStoreFollowService.followRow("sub.")
                + " and u.status='0' and u.del_flag='0'";
        if ("followers".equals(kind)) return db.list("select distinct uid from (" + followers + ") r", p("s", storeId));
        if ("storeFavorites".equals(kind)) return db.list("select distinct uid from (" + favorites + ") r", p("s", storeId));
        // 仅明确关注本店或收藏本店岗位的用户；不会扩展到未关注的同城用户。
        return db.list(favorites + " union " + followers, p("s", storeId));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> urgent(StaffSupport.Ctx c, Long jobId, int level, String deadline, List<String> targets, boolean push)
    {
        // 第一次读取就是锁定读取：同时拿最新状态，并串行检查 24 小时内是否已通知。
        Map<String, Object> j = ownJob(c, jobId, true);
        if (level < 1 || level > 4)
        {
            throw new ServiceException("请选择紧急等级");
        }
        Date dl = StaffSupport.parse(deadline);
        if (dl == null)
        {
            throw new ServiceException("截止时间格式应为 yyyy-MM-dd HH:mm");
        }
        if (!dl.after(new Date()))
        {
            throw new ServiceException("截止时间必须晚于现在");
        }
        if (dl.after(StaffSupport.addDays(new Date(), 60)))
        {
            throw new ServiceException("截止时间最多 60 天后");
        }
        if (!"published".equals(str(j.get("status"))))
        {
            throw new ServiceException("只有招聘中的岗位可以标记紧急");
        }
        int oldLevel = Math.min(4, Math.max(0, StaffSupport.toInt(j.get("urgentLevel"), 0)));
        Date oldDl = StaffSupport.parse(j.get("urgentDeadline"));
        // 24 小时内这个岗位已经给用户推过一次的，这次只更新紧急标记，不再推（设计 m1 7.1 #13 / PLAN P16：每个岗位 24 小时最多推 1 次）。
        // 必须在下面写本次日志之前查，否则会把本次算进去
        boolean pushedRecently = db.count("select count(*) from kg_dispatch_log where job_id = :j and op_type = 'urgent' and reason like '%推送用户%' "
                + "and create_time > date_sub(now(), interval 24 hour)", p("j", jobId)) > 0;
        db.update("update kg_job set urgent_level = :l, urgent_deadline = :d, update_by = :u, update_time = now() where job_id = :id",
                p("l", level, "d", dl, "id", jobId, "u", "staff:" + c.uid()));

        Long storeId = KgJdbc.asLong(j.get("storeId"));
        int gap = Math.max(0, StaffSupport.toInt(j.get("headcount"), 0) - StaffSupport.toInt(j.get("hiredCount"), 0));
        String text = "【紧急招募】" + j.get("storeName") + "-" + j.get("title") + "（" + StaffSupport.jobTypeLabel(j.get("jobType")) + "）缺口 "
                + gap + " 人。截止 " + StaffSupport.fmt(dl, "MM/dd HH:mm") + "，立即报名！";
        Set<Long> staffIds = new LinkedHashSet<Long>();
        // 同城其他门店主管单独一组：他们的需求看板只统计自己有授权的门店，看不到这家店的岗位，消息要链到公开的岗位详情
        Set<Long> cityIds = new LinkedHashSet<Long>();
        Set<Long> userIds = new LinkedHashSet<Long>();
        List<String> ts = targets == null ? new ArrayList<String>() : targets;
        for (String k : ts)
        {
            if (Arrays.asList("manager", "staff", "city").contains(k))
            {
                for (Map<String, Object> r : recipients(storeId, jobId, k))
                {
                    ("city".equals(k) ? cityIds : staffIds).add(KgJdbc.asLong(r.get("uid")));
                }
            }
            else if ("favorites".equals(k))
            {
                for (Map<String, Object> r : recipients(storeId, jobId, k))
                {
                    userIds.add(KgJdbc.asLong(r.get("uid")));
                }
            }
        }
        if (push)
        {
            for (Map<String, Object> r : recipients(storeId, jobId, "subscribers"))
            {
                userIds.add(KgJdbc.asLong(r.get("uid")));
            }
        }
        staffIds.remove(c.uid());
        // 同时也在本店有授权的，按本店员工发（链到本店调度页），不重复发
        cityIds.removeAll(staffIds);
        cityIds.remove(c.uid());
        // 推给用户的名单要按约定过滤：不向往期候选人做营销触达；用户暂停了「新机会提醒」或正处于免打扰时段的不推；24 小时内推过的不再推
        int usersSkipped = 0;
        int usersMatched = userIds.size();
        Map<String, Object> skipReasons = p("recent", 0, "pastApplication", 0, "preferenceOff", 0,
                "quietHours", 0, "dailyDigest", 0, "notWritten", 0);
        if (pushedRecently)
        {
            usersSkipped = userIds.size();
            skipReasons.put("recent", usersSkipped);
            userIds.clear();
        }
        else if (!userIds.isEmpty())
        {
            Set<Long> past = new LinkedHashSet<Long>();
            for (Map<String, Object> r : db.list("select distinct app_user_id uid from kg_application where store_id = :s and del_flag = '0' and app_user_id in (:ids)",
                    p("s", storeId, "ids", new ArrayList<Long>(userIds))))
            {
                past.add(KgJdbc.asLong(r.get("uid")));
            }
            for (java.util.Iterator<Long> it = userIds.iterator(); it.hasNext();)
            {
                Long uid = it.next();
                Map<String, Object> pref = db.one("select enabled, freq from kg_notify_pref where app_user_id = :u and pref_key = 'job_new'", p("u", uid));
                boolean off = pref != null && !"1".equals(str(pref.get("enabled")));
                boolean daily = pref != null && "daily".equals(str(pref.get("freq")));
                if (!daily) daily = db.count("select count(*) from kg_job_subscribe sub where sub.store_id=:s and sub.app_user_id=:u "
                        + "and sub.status='0' and sub.freq='daily' and " + com.ruoyi.kangaroo.ext.user.KgStoreFollowService.followRow("sub.")
                        + " and not exists (select 1 from kg_job_subscribe instant where instant.store_id=:s and instant.app_user_id=:u "
                        + "and instant.status='0' and instant.freq='instant' and " + com.ruoyi.kangaroo.ext.user.KgStoreFollowService.followRow("instant.") + ")",
                        p("s", storeId, "u", uid)) > 0;
                String reason = past.contains(uid) ? "pastApplication" : off ? "preferenceOff"
                        : hub.inQuietHours(uid) ? "quietHours" : daily ? "dailyDigest" : null;
                if (reason != null)
                {
                    it.remove();
                    usersSkipped++;
                    skipReasons.put(reason, StaffSupport.toInt(skipReasons.get(reason), 0) + 1);
                }
            }
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("store_id", j.get("storeId"));
        row.put("job_id", jobId);
        row.put("op_type", "urgent");
        row.put("operator_id", c.uid());
        row.put("operator_name", StaffSupport.cut(c.name(), 64));
        row.put("before_value", LEVELS[oldLevel] + (oldDl == null ? "" : "|" + StaffSupport.fmt(oldDl, "MM/dd HH:mm")));
        row.put("after_value", LEVELS[level] + "|" + StaffSupport.fmt(dl, "MM/dd HH:mm"));
        row.put("create_time", new Date());
        int staffNotified = 0, usersNotified = 0;
        for (Long uid : staffIds)
        {
            // 本店员工直接打开这个岗位的紧急标记页（缺口、截止时间都在上面）；之前只链到看板首页，还得自己找是哪个岗位
            if (support.notifyStaffWithId(uid, MessageCategory.SYSTEM, "紧急招募：" + j.get("title"), text, "job", jobId,
                    "/pages/staff/dispatch?s=3&jobId=" + jobId) != null) staffNotified++;
        }
        for (Long uid : cityIds)
        {
            if (support.notifyStaffWithId(uid, MessageCategory.SYSTEM, "紧急招募：" + j.get("title"), text, "job", jobId,
                    "/pages/jobs/detail?jobId=" + jobId) != null) staffNotified++;
        }
        for (Long uid : userIds)
        {
            // bizType = job_urgent：映射到「新机会提醒」开关（KgMessageServiceImpl.prefKeyOf），暂停了就不会写进消息中心，也不会发订阅消息
            if (support.notifyUserWithId(uid, MessageCategory.SYSTEM, "紧急招募：" + j.get("title"), text, "job_urgent", jobId,
                    "/pages/jobs/detail?jobId=" + jobId) != null) usersNotified++;
            else {
                usersSkipped++;
                skipReasons.put("notWritten", StaffSupport.toInt(skipReasons.get("notWritten"), 0) + 1);
            }
        }
        // 只有实际写入站内通知才占用 24 小时窗口；微信结果由发送待办另行记录。
        row.put("reason", "紧急标记" + (usersNotified == 0 ? "" : "并推送用户"));
        db.insert("kg_dispatch_log", row);
        support.op(c, "dispatch_urgent", "紧急标记", "general", j.get("title") + " " + LEVELS[level], true, null);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("staffNotified", staffNotified);
        out.put("staffSkipped", staffIds.size() + cityIds.size() - staffNotified);
        out.put("usersNotified", usersNotified);
        out.put("usersMatched", usersMatched);
        out.put("usersSkipped", usersSkipped);
        out.put("skipReasons", skipReasons);
        out.put("throttled", pushedRecently);
        out.put("text", text);
        return out;
    }

    // ---------- 日志 ----------

    public Map<String, Object> logs(StaffSupport.Ctx c, Long storeId, String from, String to, String type)
    {
        trySync();
        List<Long> scope = storeIds(c);
        if (storeId != null)
        {
            if (!scope.contains(storeId))
            {
                throw new ServiceException("没有该门店的操作权限", HttpStatus.FORBIDDEN);
            }
            scope = Arrays.asList(storeId);
        }
        Map<String, Object> out = query(c, scope, from, to, type);
        List<Map<String, Object>> stores = new ArrayList<Map<String, Object>>();
        for (StaffGrantView g : c.me.getStaffGrants())
        {
            Map<String, Object> s = new LinkedHashMap<String, Object>();
            s.put("storeId", g.getStoreId());
            s.put("storeName", g.getStoreName());
            stores.add(s);
        }
        out.put("stores", stores);
        // 按所选门店判：选了某家看那家门店上的角色；「全部门店」看是否至少有一家能导出（导出时只导出能导出的那几家）
        out.put("canExport", canExport(c, storeId));
        return out;
    }

    /** 按门店范围查调度日志（最多 100 条），scope 由调用方按授权 / 导出权算好 */
    private Map<String, Object> query(StaffSupport.Ctx c, List<Long> scope, String from, String to, String type)
    {
        Date f = StaffSupport.parse(from);
        Date t = StaffSupport.parse(to);
        Date today = StaffSupport.startOfDay(new Date());
        if (t == null)
        {
            t = today;
        }
        if (f == null)
        {
            f = StaffSupport.addDays(t, -29);
        }
        StringBuilder where = new StringBuilder(" where l.store_id in (:ids) and l.create_time >= :f and l.create_time < :t");
        Map<String, Object> ps = p("ids", scope, "f", f, "t", StaffSupport.addDays(t, 1));
        if (type != null && Arrays.asList("adjust", "urgent", "sync", "undo").contains(type))
        {
            where.append(" and l.op_type = :type");
            ps.put("type", type);
        }
        List<Map<String, Object>> rows = db.list("select l.*, j.title, j.job_type, s.store_name, u.avatar from kg_dispatch_log l "
                + "left join kg_job j on j.job_id = l.job_id left join kg_store s on s.store_id = l.store_id "
                + "left join kg_app_user u on u.user_id = l.operator_id" + where + " order by l.log_id desc limit 100", ps);
        long hourAgo = System.currentTimeMillis() - 3600000L;
        for (Map<String, Object> r : rows)
        {
            String op = str(r.get("opType"));
            r.put("user", r.get("operatorName"));
            Date ct = StaffSupport.parse(r.get("createTime"));
            r.put("time", StaffSupport.fmt(ct, "yyyy-MM-dd HH:mm"));
            r.put("job", str(r.get("storeName")) + " · " + str(r.get("title")) + "（" + StaffSupport.jobTypeLabel(r.get("jobType")) + "）");
            r.put("type", "adjust".equals(op) ? "数值调整" : ("urgent".equals(op) ? "紧急标记" : ("undo".equals(op) ? "撤销调整" : "系统同步")));
            r.put("tone", "adjust".equals(op) ? "green" : ("urgent".equals(op) ? "red" : "blue"));
            r.put("change", change(r));
            r.put("source", "adjust".equals(op) ? "来源：手动调整" : ("urgent".equals(op) ? "来源：移动端标记" : ("undo".equals(op) ? "来源：本人撤销" : "来源：每日同步")));
            r.put("canUndo", "adjust".equals(op) && r.get("revertedTime") == null && c.uid().equals(KgJdbc.asLong(r.get("operatorId")))
                    && ct != null && ct.getTime() >= hourAgo);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("rows", rows);
        out.put("from", StaffSupport.fmt(f, "yyyy-MM-dd"));
        out.put("to", StaffSupport.fmt(t, "yyyy-MM-dd"));
        return out;
    }

    private static String change(Map<String, Object> r)
    {
        String op = str(r.get("opType"));
        String before = str(r.get("beforeValue"));
        String after = str(r.get("afterValue"));
        if ("adjust".equals(op))
        {
            int d = StaffSupport.toInt(r.get("delta"), 0);
            String range = r.get("validFrom") == null ? "" : "\n有效期：" + r.get("validFrom") + " 至 " + r.get("validTo")
                    + (r.get("revertedTime") != null ? "（已恢复）" : (r.get("appliedTime") == null ? "（未到生效日）" : ""));
            return before + " → " + after.replaceAll("^.*：", "") + " （" + (d > 0 ? "+" : "") + d + "）" + range;
        }
        if ("urgent".equals(op))
        {
            String[] b = before.split("\\|");
            String[] a = after.split("\\|");
            return "紧急等级：" + b[0] + " → " + a[0] + (a.length > 1 ? "\n截止时间：" + a[1] : "");
        }
        return (before.isEmpty() ? "" : before + " → ") + after + (str(r.get("reason")).isEmpty() ? "" : "\n" + r.get("reason"));
    }

    /** 撤销：只能撤销本人 1 小时内、尚未恢复的名额调整 */
    @Transactional(rollbackFor = Exception.class)
    public void undo(StaffSupport.Ctx c, Long logId)
    {
        Map<String, Object> l = db.must("select * from kg_dispatch_log where log_id = :id", p("id", logId), "日志不存在");
        if (!c.me.hasStoreAccess(KgJdbc.asLong(l.get("storeId"))))
        {
            throw new ServiceException("没有该门店的操作权限", HttpStatus.FORBIDDEN);
        }
        Date ct = StaffSupport.parse(l.get("createTime"));
        if (!"adjust".equals(str(l.get("opType"))) || !c.uid().equals(KgJdbc.asLong(l.get("operatorId")))
                || ct == null || ct.getTime() < System.currentTimeMillis() - 3600000L)
        {
            throw new ServiceException("仅支持撤销本人在 1 小时内的调整操作；超过时间请联系上级主管处理");
        }
        if (db.update("update kg_dispatch_log set reverted_time = now() where log_id = :id and reverted_time is null", p("id", logId)) == 0)
        {
            throw new ServiceException("这次调整已经恢复或撤销过了");
        }
        int delta = StaffSupport.toInt(l.get("delta"), 0);
        String result = "未到生效日，直接作废";
        if (l.get("appliedTime") != null)
        {
            Map<String, Object> j = db.must("select headcount, hired_count from kg_job where job_id = :id for update", p("id", l.get("jobId")), "岗位不存在");
            int cur = StaffSupport.toInt(j.get("headcount"), 0);
            int target = Math.max(Math.max(1, StaffSupport.toInt(j.get("hiredCount"), 0)), cur - delta);
            if (db.update("update kg_job set headcount = :h, update_by = :u, update_time = now() where job_id = :id "
                    + "and gender_male_count is null and gender_female_count is null",
                    p("h", target, "id", l.get("jobId"), "u", "staff:" + c.uid())) == 0)
            {
                throw new ServiceException("此岗位已设置男女招募计划，请到岗位管理中修改人数，不能直接撤销总名额", 422);
            }
            result = "名额 " + cur + " → " + target;
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("store_id", l.get("storeId"));
        row.put("job_id", l.get("jobId"));
        row.put("op_type", "undo");
        row.put("operator_id", c.uid());
        row.put("operator_name", StaffSupport.cut(c.name(), 64));
        row.put("after_value", result);
        row.put("reason", "撤销 #" + logId + " 的调整");
        row.put("create_time", new Date());
        db.insert("kg_dispatch_log", row);
        support.op(c, "dispatch_undo", "撤销调整", "general", "#" + logId + " " + result, true, null);
    }

    /**
     * 导出为 CSV 文本（UTF-8，带表头）。
     * storeId 不为空：只导出这家门店（导出权按账号在这家门店上的角色判）；
     * storeId 为空（筛选是「全部门店」）：导出账号在其上有导出权的全部门店，逐家按该门店上的角色判，
     * 没有导出权的门店不导出、名字放进 skippedStores 告诉前端。之前用 ctx(null) 只导出了第一家门店的日志，
     * 导出权也是按第一家门店的角色判的。
     */
    public Map<String, Object> exportCsv(StaffSupport.Ctx c, Long storeId, String from, String to, String type)
    {
        trySync();
        List<Long> scope = new ArrayList<Long>();
        List<String> skipped = new ArrayList<String>();
        for (StaffGrantView g : c.me.getStaffGrants())
        {
            if (storeId != null && !storeId.equals(g.getStoreId()))
            {
                continue;
            }
            if (StaffPerms.allows(roleOf(g), "dispatch", StaffPerms.EXPORT))
            {
                scope.add(g.getStoreId());
            }
            else
            {
                skipped.add(g.getStoreName());
            }
        }
        if (scope.isEmpty())
        {
            throw new ServiceException(storeId == null ? "你在已授权的门店里都没有调度日志导出权限" : "没有这家门店的调度日志导出权限",
                    HttpStatus.FORBIDDEN);
        }
        Map<String, Object> data = query(c, scope, from, to, type);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        StringBuilder sb = new StringBuilder("时间,操作人,类型,门店岗位,变更,来源\n");
        for (Map<String, Object> r : rows)
        {
            sb.append(csv(r.get("time"))).append(',').append(csv(r.get("user"))).append(',').append(csv(r.get("type"))).append(',')
                    .append(csv(r.get("job"))).append(',').append(csv(str(r.get("change")).replace('\n', ' '))).append(',')
                    .append(csv(r.get("source"))).append('\n');
        }
        // 数据导出按门店各记一条操作日志，记在被导出的那家门店上（各店主理人在「操作与权限日志」里才看得到）
        for (Long sid : scope)
        {
            int n = 0;
            for (Map<String, Object> r : rows)
            {
                if (sid.equals(KgJdbc.asLong(r.get("storeId"))))
                {
                    n++;
                }
            }
            support.op(sid.equals(c.storeId) ? c : support.ctx(sid), "dispatch_export", "数据导出", "export", "调度日志 " + n + " 条", true, null);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("csv", sb.toString());
        out.put("count", rows.size());
        out.put("skippedStores", skipped);
        return out;
    }

    private static String csv(Object v)
    {
        String s = str(v);
        // 防 CSV 公式注入：以 = + - @ 开头的单元格前面加单引号
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0)
        {
            s = "'" + s;
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    // ---------- 每日同步 ----------

    /** 生效到期的调整、恢复过期的调整、解除过期的紧急标记。重复执行安全（全部带 CAS 条件） */
    @Scheduled(cron = "0 5 0 * * ?")
    @Transactional(rollbackFor = Exception.class)
    public void sync()
    {
        for (Map<String, Object> l : db.list("select * from kg_dispatch_log where op_type = 'adjust' and applied_time is null "
                + "and reverted_time is null and valid_from <= curdate()", p()))
        {
            if (db.update("update kg_dispatch_log set applied_time = now() where log_id = :id and applied_time is null", p("id", l.get("logId"))) > 0)
            {
                int delta = StaffSupport.toInt(l.get("delta"), 0);
                int changed = db.update("update kg_job set headcount = greatest(greatest(1, hired_count), headcount + :d), update_time = now() where job_id = :id "
                        + "and gender_male_count is null and gender_female_count is null",
                        p("d", delta, "id", l.get("jobId")));
                if (changed == 0)
                {
                    // 招募计划后来按男女拆分，不再套用旧的总人数调整，也不能在到期时反向扣减。
                    db.update("update kg_dispatch_log set reverted_time = now() where log_id = :id", p("id", l.get("logId")));
                    syncLog(l, "岗位已设置男女招募计划或已不存在，本次总名额调整作废，请在岗位管理中确认人数");
                }
                else syncLog(l, "有效期开始，名额调整生效（" + (delta > 0 ? "+" : "") + delta + "）");
            }
        }
        for (Map<String, Object> l : db.list("select * from kg_dispatch_log where op_type = 'adjust' and applied_time is not null "
                + "and reverted_time is null and valid_to < curdate()", p()))
        {
            if (db.update("update kg_dispatch_log set reverted_time = now() where log_id = :id and reverted_time is null", p("id", l.get("logId"))) > 0)
            {
                int delta = StaffSupport.toInt(l.get("delta"), 0);
                int changed = db.update("update kg_job set headcount = greatest(greatest(1, hired_count), headcount - :d), update_time = now() where job_id = :id "
                        + "and gender_male_count is null and gender_female_count is null",
                        p("d", delta, "id", l.get("jobId")));
                syncLog(l, changed == 0 ? "岗位已设置男女招募计划或已不存在，保留现有计划，结束旧的总名额调整"
                        : "有效期结束，名额自动恢复（" + (delta > 0 ? "-" : "+") + Math.abs(delta) + "）");
            }
        }
        for (Map<String, Object> j : db.list("select job_id, store_id, urgent_level from kg_job where urgent_level > 0 "
                + "and urgent_deadline is not null and urgent_deadline < now()", p()))
        {
            if (db.update("update kg_job set urgent_level = 0, update_time = now() where job_id = :id and urgent_level > 0 "
                    + "and urgent_deadline < now()", p("id", j.get("jobId"))) > 0)
            {
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("store_id", j.get("storeId"));
                row.put("job_id", j.get("jobId"));
                row.put("op_type", "sync");
                row.put("operator_name", "系统自动同步");
                row.put("before_value", "紧急等级：" + LEVELS[Math.min(4, StaffSupport.toInt(j.get("urgentLevel"), 0))]);
                row.put("after_value", "无");
                row.put("reason", "截止时间已过，紧急标记自动解除");
                row.put("create_time", new Date());
                db.insert("kg_dispatch_log", row);
            }
        }
    }

    /** 看板/日志打开时顺手同步一次；失败只记日志，不影响页面打开，等下一次再跑 */
    private void trySync()
    {
        try
        {
            self.sync();
        }
        catch (RuntimeException e)
        {
            log.error("调度同步失败", e);
        }
    }

    private void syncLog(Map<String, Object> l, String text)
    {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("store_id", l.get("storeId"));
        row.put("job_id", l.get("jobId"));
        row.put("op_type", "sync");
        row.put("operator_name", "系统自动同步");
        row.put("after_value", text);
        row.put("reason", "对应 #" + l.get("logId"));
        row.put("create_time", new Date());
        db.insert("kg_dispatch_log", row);
    }
}
