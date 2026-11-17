package com.ruoyi.kangaroo.ext.community;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;

/**
 * 我的行程（由岗位报名、住宿订单、活动报名自动生成）+ 公开行程找搭子 + 同行候选。
 * 只有自己主动发布了公开行程的人才会出现在别人的同行候选里；手机号、微信号、精确住址一律不展示。
 */
@Service
public class KgTripService
{
    private static final String[] WEEK = { "周一", "周二", "周三", "周四", "周五", "周六", "周日" };
    static final List<String> PLAN_TAGS = Arrays.asList("手作体验", "逛市集", "城市漫步", "户外徒步", "咖啡探店", "摄影创作", "美食探索", "其他");
    static final Map<String, String> APP_LABEL = new LinkedHashMap<String, String>();
    static
    {
        APP_LABEL.put("submitted", "已提交");
        APP_LABEL.put("review_passed", "审核通过");
        APP_LABEL.put("interview_booked", "待面试");
        APP_LABEL.put("interview_done", "面试完成");
        APP_LABEL.put("user_confirmed", "待商家确认");
        APP_LABEL.put("store_confirmed", "已确认");
        APP_LABEL.put("contract_signed", "已确认");
        APP_LABEL.put("onboarded", "已入职");
        APP_LABEL.put("completed", "已完成");
    }

    @Autowired
    private KgJdbc db;

    @Autowired
    private KgCommunityGuard guard;

    @Autowired
    private KgPostService posts;

    @Autowired
    private StaffSupport support;

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

    static LocalDate day(Object v)
    {
        String s = str(v);
        return s.length() >= 10 ? LocalDate.parse(s.substring(0, 10)) : null;
    }

    static String md(LocalDate d)
    {
        return d.getMonthValue() + "." + d.getDayOfMonth();
    }

    static String cn(LocalDate d)
    {
        return d.getMonthValue() + "月" + d.getDayOfMonth() + "日";
    }

    static String wk(LocalDate d)
    {
        return WEEK[d.getDayOfWeek().getValue() - 1];
    }

    // ---------------------------------------------------------------- 行程条目

    /** 我的所有行程条目：kind = volunteer/parttime/fulltime/stay/activity；按开始日期排序 */
    List<Map<String, Object>> items(Long me)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> a : db.list("select a.app_id, a.status, a.arrival_date, a.stay_days, a.store_id, j.job_id, j.job_type, j.title, j.city, j.address, "
                + "j.shift_desc, j.daily_hours, s.store_name from kg_application a join kg_job j on j.job_id = a.job_id left join kg_store s on s.store_id = a.store_id "
                + "where a.app_user_id = :u and a.del_flag = '0' and a.status not in ('withdrawn','review_rejected','terminated') and j.job_type <> 'online' "
                + "and a.arrival_date is not null", p("u", me)))
        {
            LocalDate f = day(a.get("arrivalDate"));
            long days = Math.max(1, num(a.get("stayDays")));
            String jt = str(a.get("jobType"));
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("refType", "application");
            m.put("refId", a.get("appId"));
            m.put("kind", jt);
            m.put("tag", "volunteer".equals(jt) ? "换工" : ("parttime".equals(jt) ? "兼职" : "全职"));
            m.put("tone", "ochre");
            m.put("title", ("volunteer".equals(jt) ? "换宿换工 · " : "") + a.get("title"));
            m.put("city", a.get("city"));
            m.put("from", f);
            m.put("to", f.plusDays(days - 1));
            m.put("date", md(f) + "—" + md(f.plusDays(days - 1)) + "（" + days + "天）");
            String st = str(a.get("status"));
            m.put("status", APP_LABEL.getOrDefault(st, st));
            m.put("stone", Arrays.asList("store_confirmed", "contract_signed", "onboarded", "completed").contains(st) ? "green" : "ochre");
            m.put("storeId", a.get("storeId"));
            m.put("storeName", a.get("storeName"));
            m.put("jobId", a.get("jobId"));
            m.put("address", a.get("address"));
            m.put("shift", a.get("shiftDesc"));
            m.put("hours", a.get("dailyHours"));
            out.add(m);
        }
        for (Map<String, Object> o : db.list("select o.order_id, o.order_no, o.status, o.check_in, o.check_out, o.nights, s.stay_id, s.stay_name, s.city, s.area, s.store_id "
                + "from kg_stay_order o join kg_stay s on s.stay_id = o.stay_id where o.app_user_id = :u and o.del_flag = '0' "
                + "and o.status in ('pending_payment','confirmed','checked_in','completed')", p("u", me)))
        {
            LocalDate f = day(o.get("checkIn"));
            LocalDate t = day(o.get("checkOut"));
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("refType", "stay_order");
            m.put("refId", o.get("orderId"));
            m.put("kind", "stay");
            m.put("tag", "住宿");
            m.put("tone", "ochre");
            m.put("title", o.get("stayName"));
            m.put("city", o.get("city"));
            m.put("from", f);
            m.put("to", t);
            m.put("date", md(f) + "—" + md(t) + "（" + num(o.get("nights")) + "晚）");
            String st = str(o.get("status"));
            m.put("status", "pending_payment".equals(st) ? "待支付" : ("checked_in".equals(st) ? "已入住" : ("completed".equals(st) ? "已完成" : "已支付")));
            m.put("stone", "pending_payment".equals(st) ? "ochre" : "green");
            m.put("orderNo", o.get("orderNo"));
            m.put("stayId", o.get("stayId"));
            m.put("area", o.get("area"));
            m.put("storeId", o.get("storeId"));
            out.add(m);
        }
        for (Map<String, Object> g : db.list("select g.signup_id, g.status, a.activity_id, a.title, a.city, a.place, a.start_time, a.end_time from kg_activity_signup g "
                + "join kg_activity a on a.activity_id = g.activity_id where g.app_user_id = :u and g.status in ('pending_payment','confirmed','waitlist') "
                + "and a.status in ('open','paused','closed','ended')", p("u", me)))
        {
            Date s = StaffSupport.parse(g.get("startTime"));
            LocalDate f = day(StaffSupport.fmt(s, "yyyy-MM-dd"));
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("refType", "activity_signup");
            m.put("refId", g.get("signupId"));
            m.put("kind", "activity");
            m.put("tag", "活动");
            m.put("tone", "blue");
            m.put("title", g.get("title"));
            m.put("city", g.get("city"));
            m.put("from", f);
            m.put("to", f);
            m.put("date", md(f) + "（" + wk(f) + "）" + StaffSupport.fmt(s, "HH:mm"));
            String st = str(g.get("status"));
            m.put("status", "confirmed".equals(st) ? "已报名" : ("waitlist".equals(st) ? "候补中" : "待支付"));
            m.put("stone", "confirmed".equals(st) ? "green" : "ochre");
            m.put("activityId", g.get("activityId"));
            m.put("place", g.get("place"));
            m.put("time", StaffSupport.fmt(s, "HH:mm"));
            out.add(m);
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> (LocalDate) m.get("from")));
        return out;
    }

    private static Map<String, Object> strip(Map<String, Object> m)
    {
        Map<String, Object> v = new LinkedHashMap<String, Object>(m);
        v.put("from", m.get("from").toString());
        v.put("to", m.get("to").toString());
        return v;
    }

    public Map<String, Object> trips(Long me, String tab)
    {
        List<String> kinds = ("换工".equals(tab) || "义工".equals(tab)) ? Arrays.asList("volunteer") : "兼职".equals(tab) ? Arrays.asList("parttime") : "全职".equals(tab) ? Arrays.asList("fulltime")
                : "住宿".equals(tab) ? Arrays.asList("stay") : "活动".equals(tab) ? Arrays.asList("activity") : null;
        LocalDate today = LocalDate.now();
        // 同城、时间相接（间隔 ≤ 1 天）的条目归成一段行程
        List<Map<String, Object>> groups = new ArrayList<Map<String, Object>>();
        Map<String, Object> cur = null;
        for (Map<String, Object> it : items(me))
        {
            if (((LocalDate) it.get("to")).isBefore(today.minusDays(30)))
            {
                continue;
            }
            if (kinds != null && !kinds.contains(str(it.get("kind"))))
            {
                continue;
            }
            LocalDate f = (LocalDate) it.get("from");
            LocalDate t = (LocalDate) it.get("to");
            if (cur == null || !str(cur.get("city")).equals(str(it.get("city"))) || f.isAfter(((LocalDate) cur.get("toD")).plusDays(1)))
            {
                cur = new LinkedHashMap<String, Object>();
                cur.put("city", it.get("city"));
                cur.put("fromD", f);
                cur.put("toD", t);
                cur.put("items", new ArrayList<Map<String, Object>>());
                groups.add(cur);
            }
            if (t.isAfter((LocalDate) cur.get("toD")))
            {
                cur.put("toD", t);
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = (List<Map<String, Object>>) cur.get("items");
            list.add(strip(it));
        }
        for (Map<String, Object> g : groups)
        {
            LocalDate f = (LocalDate) g.remove("fromD");
            LocalDate t = (LocalDate) g.remove("toD");
            long days = ChronoUnit.DAYS.between(f, t) + 1;
            g.put("range", cn(f) + "—" + cn(t));
            g.put("total", "共" + days + "天" + (days > 1 ? (days - 1) + "晚" : ""));
            g.put("from", f.toString());
            g.put("to", t.toString());
        }
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("groups", groups);
        Map<String, Object> plan = db.one("select plan_id, city, start_date, end_date, status from kg_trip_plan where app_user_id = :u and del_flag = '0' "
                + "and status in ('pending','online') and end_date >= curdate() order by plan_id desc limit 1", p("u", me));
        res.put("myPlan", plan);
        return res;
    }

    /** 行程详情：以某个条目为主，时间线包含同城同期的所有条目 */
    public Map<String, Object> detail(Long me, String refType, Long refId)
    {
        List<Map<String, Object>> all = items(me);
        Map<String, Object> main = null;
        for (Map<String, Object> it : all)
        {
            if (str(it.get("refType")).equals(refType) && refId != null && refId.equals(KgJdbc.asLong(it.get("refId"))))
            {
                main = it;
            }
        }
        if (main == null)
        {
            throw new ServiceException("行程不存在", 404);
        }
        LocalDate f = (LocalDate) main.get("from");
        LocalDate t = (LocalDate) main.get("to");
        String city = str(main.get("city"));
        List<Map<String, Object>> related = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> it : all)
        {
            if (city.equals(str(it.get("city"))) && !((LocalDate) it.get("to")).isBefore(f) && !((LocalDate) it.get("from")).isAfter(t))
            {
                related.add(it);
            }
        }
        // 时间线：按日期聚合每天发生的事
        Map<LocalDate, List<String>> byDay = new LinkedHashMap<LocalDate, List<String>>();
        LocalDate today = LocalDate.now();
        for (Map<String, Object> it : related)
        {
            LocalDate a = (LocalDate) it.get("from");
            LocalDate b = (LocalDate) it.get("to");
            String kind = str(it.get("kind"));
            if ("stay".equals(kind))
            {
                byDay.computeIfAbsent(a, k -> new ArrayList<String>()).add("入住 · " + it.get("title"));
                byDay.computeIfAbsent(b, k -> new ArrayList<String>()).add("退房 · " + it.get("title"));
            }
            else if ("activity".equals(kind))
            {
                byDay.computeIfAbsent(a, k -> new ArrayList<String>()).add(it.get("time") + "　" + it.get("title") + "（" + it.get("status") + "）");
            }
            else
            {
                byDay.computeIfAbsent(a, k -> new ArrayList<String>()).add("到岗 · " + it.get("title"));
                if (b.isAfter(a))
                {
                    byDay.computeIfAbsent(b, k -> new ArrayList<String>()).add("离岗结算");
                }
            }
        }
        List<LocalDate> keys = new ArrayList<LocalDate>(byDay.keySet());
        keys.sort(Comparator.naturalOrder());
        List<Map<String, Object>> timeline = new ArrayList<Map<String, Object>>();
        for (LocalDate d : keys)
        {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("date", md(d));
            m.put("week", wk(d));
            List<String> lines = new ArrayList<String>();
            if (d.equals(today))
            {
                lines.add("今天");
            }
            lines.addAll(byDay.get(d));
            m.put("lines", lines);
            timeline.add(m);
        }
        List<Map<String, Object>> info = new ArrayList<Map<String, Object>>();
        Long storeId = null;
        Long appId = null;
        Long activityId = null;
        for (Map<String, Object> it : related)
        {
            String kind = str(it.get("kind"));
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            if ("stay".equals(kind))
            {
                row.put("label", "住宿信息");
                row.put("lines", Arrays.asList(str(it.get("title")), str(it.get("area")), "订单号 " + it.get("orderNo")));
                row.put("btn", "查看订单");
                row.put("to", "/pages/stays/order?orderNo=" + it.get("orderNo"));
            }
            else if ("activity".equals(kind))
            {
                row.put("label", "活动安排");
                row.put("lines", Arrays.asList(str(it.get("title")), it.get("date") + " 集合：" + it.get("place")));
                row.put("btn", "查看详情");
                row.put("to", "/pages/community/activity?signupId=" + it.get("refId"));
                activityId = activityId == null ? KgJdbc.asLong(it.get("activityId")) : activityId;
            }
            else
            {
                row.put("label", "岗位信息");
                List<String> lines = new ArrayList<String>();
                lines.add(str(it.get("title")));
                if (!str(it.get("storeName")).isEmpty())
                {
                    lines.add(str(it.get("storeName")));
                }
                if (!str(it.get("shift")).isEmpty())
                {
                    lines.add(str(it.get("shift")));
                }
                if (it.get("hours") != null)
                {
                    lines.add("工作时长：" + it.get("hours") + " 小时 / 天");
                }
                row.put("lines", lines);
                row.put("btn", "查看详情");
                row.put("to", "/pages/jobs/detail?jobId=" + it.get("jobId"));
                storeId = storeId == null ? KgJdbc.asLong(it.get("storeId")) : storeId;
                appId = appId == null ? KgJdbc.asLong(it.get("refId")) : appId;
            }
            info.add(row);
        }
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("head", strip(main));
        long days = ChronoUnit.DAYS.between(f, t) + 1;
        res.put("rangeText", cn(f) + "（" + wk(f) + "）—" + cn(t) + "（" + wk(t) + "）");
        res.put("totalText", "共" + days + "天" + (days > 1 ? (days - 1) + "晚" : ""));
        res.put("place", "stay".equals(str(main.get("kind"))) ? main.get("area") : ("activity".equals(str(main.get("kind"))) ? main.get("place") : main.get("city")));
        res.put("timeline", timeline);
        res.put("info", info);
        res.put("appId", appId);
        res.put("activityId", activityId);
        res.put("city", city);
        res.put("from", f.toString());
        res.put("to", t.toString());
        return res;
    }

    // ---------------------------------------------------------------- 公开行程

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> publishPlan(Long me, Map<String, Object> b)
    {
        guard.requireConsent(me, "community");
        guard.requirePhone(me);
        String city = str(b.get("city")).trim();
        if (city.isEmpty() || city.length() > 32)
        {
            throw new ServiceException("请选择城市", 422);
        }
        LocalDate f;
        LocalDate t;
        try
        {
            f = LocalDate.parse(str(b.get("startDate")));
            t = LocalDate.parse(str(b.get("endDate")));
        }
        catch (Exception e)
        {
            throw new ServiceException("请选择日期区间", 422);
        }
        if (f.isBefore(LocalDate.now()) || t.isBefore(f) || ChronoUnit.DAYS.between(f, t) > 90)
        {
            throw new ServiceException("日期区间需从今天起、最长 90 天", 422);
        }
        List<String> acts = new ArrayList<String>();
        Object raw = b.get("activities");
        if (raw instanceof List)
        {
            for (Object o : (List<?>) raw)
            {
                if (PLAN_TAGS.contains(str(o)) && !acts.contains(str(o)))
                {
                    acts.add(str(o));
                }
            }
        }
        if (acts.isEmpty())
        {
            throw new ServiceException("至少选择一项主要活动", 422);
        }
        String intro = StaffSupport.cut(str(b.get("intro")).trim(), 255);
        String level = guard.check(intro);
        // 同一个人同一城市只保留一条公开中的行程
        db.update("update kg_trip_plan set status = 'closed', update_time = now() where app_user_id = :u and city = :c and status in ('pending','online')",
                p("u", me, "c", city));
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("app_user_id", me);
        row.put("city", city);
        row.put("start_date", f.toString());
        row.put("end_date", t.toString());
        row.put("activities", String.join(",", acts));
        row.put("intro", intro);
        row.put("hide_stay", "false".equals(str(b.get("hideStay"))) ? "0" : "1");
        row.put("hide_transport", "false".equals(str(b.get("hideTransport"))) ? "0" : "1");
        row.put("status", guard.initialStatus(level));
        row.put("review_mode", guard.mode());
        row.put("create_time", new Date());
        long id = db.insert("kg_trip_plan", row);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("planId", id);
        m.put("status", row.get("status"));
        m.put("message", "online".equals(row.get("status")) ? "已发布到找搭子" : "已提交审核，通过后开始匹配同行");
        return m;
    }

    public String closePlan(Long me, Long planId)
    {
        if (db.update("update kg_trip_plan set status = 'closed', update_time = now() where plan_id = :p and app_user_id = :u and status in ('pending','online')",
                p("p", planId, "u", me)) == 0)
        {
            throw new ServiceException("行程不存在或已关闭", 404);
        }
        return "已关闭公开行程";
    }

    /** 同行候选：与我的公开行程同城、重叠 ≥ 2 天的其他公开行程 */
    public Map<String, Object> matches(Long me, Long planId)
    {
        Map<String, Object> mine = planId != null
                ? db.one("select * from kg_trip_plan where plan_id = :p and app_user_id = :u and del_flag = '0'", p("p", planId, "u", me))
                : db.one("select * from kg_trip_plan where app_user_id = :u and status in ('pending','online') and end_date >= curdate() and del_flag = '0' "
                        + "order by plan_id desc limit 1", p("u", me));
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        if (mine == null)
        {
            res.put("needPlan", true);
            res.put("people", new ArrayList<Object>());
            return res;
        }
        LocalDate f = day(mine.get("startDate"));
        LocalDate t = day(mine.get("endDate"));
        List<Map<String, Object>> people = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> r : db.list("select p.*, u.nickname, u.avatar from kg_trip_plan p join kg_app_user u on u.user_id = p.app_user_id "
                + "where p.city = :c and p.status = 'online' and p.del_flag = '0' and p.app_user_id <> :u and p.start_date <= :t and p.end_date >= :f "
                + "and not exists (select 1 from kg_user_block b where (b.user_id = :u and b.blocked_id = p.app_user_id) or (b.user_id = p.app_user_id and b.blocked_id = :u)) "
                + "order by p.plan_id desc limit 100", p("c", mine.get("city"), "u", me, "f", f.toString(), "t", t.toString())))
        {
            LocalDate a = day(r.get("startDate"));
            LocalDate b = day(r.get("endDate"));
            LocalDate of = a.isAfter(f) ? a : f;
            LocalDate ot = b.isBefore(t) ? b : t;
            long days = ChronoUnit.DAYS.between(of, ot) + 1;
            if (days < 2)
            {
                continue;
            }
            Long uid = KgJdbc.asLong(r.get("appUserId"));
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("planId", r.get("planId"));
            m.put("userId", uid);
            m.put("name", str(r.get("nickname")).isEmpty() ? "用户" + uid : r.get("nickname"));
            m.put("avatar", r.get("avatar"));
            m.put("days", days);
            m.put("meta", md(a) + "—" + md(b) + " · " + r.get("city"));
            m.put("hobby", str(r.get("activities")).replace(",", " / "));
            m.put("tripVerified", posts.tripVerified(uid, str(r.get("city"))));
            m.put("phoneVerified", guard.phoneVerified(uid));
            people.add(m);
        }
        people.sort((x, y) -> Long.compare(num(y.get("days")), num(x.get("days"))));
        res.put("needPlan", false);
        res.put("myPlan", strip2(mine));
        res.put("people", people);
        return res;
    }

    private static Map<String, Object> strip2(Map<String, Object> plan)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("planId", plan.get("planId"));
        m.put("city", plan.get("city"));
        m.put("startDate", str(plan.get("startDate")).substring(0, 10));
        m.put("endDate", str(plan.get("endDate")).substring(0, 10));
        m.put("status", plan.get("status"));
        return m;
    }

    /** 同行个人页：只展示对方公开行程里的信息 */
    public Map<String, Object> person(Long me, Long planId)
    {
        Map<String, Object> r = db.one("select p.*, u.nickname, u.avatar from kg_trip_plan p join kg_app_user u on u.user_id = p.app_user_id "
                + "where p.plan_id = :p and p.status = 'online' and p.del_flag = '0'", p("p", planId));
        if (r == null)
        {
            throw new ServiceException("对方的公开行程已关闭", 404);
        }
        Long uid = KgJdbc.asLong(r.get("appUserId"));
        guard.requireNotBlocked(me, uid);
        Map<String, Object> mine = db.one("select start_date, end_date from kg_trip_plan where app_user_id = :u and city = :c and status in ('pending','online') "
                + "and del_flag = '0' order by plan_id desc limit 1", p("u", me, "c", r.get("city")));
        LocalDate a = day(r.get("startDate"));
        LocalDate b = day(r.get("endDate"));
        List<Map<String, Object>> dates = new ArrayList<Map<String, Object>>();
        String overlap = "";
        if (mine != null)
        {
            LocalDate f = day(mine.get("startDate"));
            LocalDate t = day(mine.get("endDate"));
            LocalDate of = a.isAfter(f) ? a : f;
            LocalDate ot = b.isBefore(t) ? b : t;
            if (!ot.isBefore(of))
            {
                long days = ChronoUnit.DAYS.between(of, ot) + 1;
                overlap = "与你行程重叠" + days + "天（" + md(of) + "—" + md(ot) + "）";
                for (LocalDate d = of; !d.isAfter(ot) && dates.size() < 14; d = d.plusDays(1))
                {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("d", md(d));
                    m.put("w", wk(d));
                    dates.add(m);
                }
            }
        }
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("planId", planId);
        res.put("userId", uid);
        res.put("name", str(r.get("nickname")).isEmpty() ? "用户" + uid : r.get("nickname"));
        res.put("avatar", r.get("avatar"));
        res.put("meta", r.get("city") + " · " + md(a) + "—" + md(b));
        res.put("tripVerified", posts.tripVerified(uid, str(r.get("city"))));
        res.put("phoneVerified", guard.phoneVerified(uid));
        res.put("overlap", overlap.isEmpty() ? "你在" + r.get("city") + "还没有公开行程，发布后可查看重叠日期" : overlap);
        res.put("intro", str(r.get("intro")).isEmpty() ? "TA 还没有写公开简介" : r.get("intro"));
        res.put("prefs", KgPostService.split(r.get("activities")));
        res.put("dates", dates);
        res.put("overlapDays", dates.size());
        return res;
    }

    public List<Map<String, Object>> myPlans(Long me)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> r : db.list("select * from kg_trip_plan where app_user_id = :u and del_flag = '0' order by plan_id desc limit 20", p("u", me)))
        {
            Map<String, Object> m = strip2(r);
            m.put("activities", KgPostService.split(r.get("activities")));
            m.put("auditReason", r.get("auditReason"));
            out.add(m);
        }
        return out;
    }

    @Transactional(rollbackFor = Exception.class)
    public String auditPlan(Long planId, boolean pass, String reason, String operator)
    {
        Map<String, Object> r = db.must("select * from kg_trip_plan where plan_id = :p", p("p", planId), "行程不存在");
        if (!"pending".equals(str(r.get("status"))))
        {
            throw new ServiceException("只有待审核的行程可以审核");
        }
        if (!pass && (reason == null || reason.trim().isEmpty()))
        {
            throw new ServiceException("请填写驳回原因");
        }
        if (db.update("update kg_trip_plan set status = :s, audit_reason = :r, update_time = now() where plan_id = :p and status = 'pending'",
                p("s", pass ? "online" : "rejected", "r", pass ? null : reason, "p", planId)) == 0)
        {
            throw new ServiceException("状态已变化，请刷新");
        }
        // 之前审完不通知：发布时只提示「已提交审核，通过后开始匹配同行」，之后用户只能自己去「我的公开行程」翻状态（动态、活动审核都会通知）
        String plan = str(r.get("city")) + " " + str(r.get("startDate")) + " 至 " + str(r.get("endDate"));
        support.notifyUser(KgJdbc.asLong(r.get("appUserId")), "system", pass ? "你的公开行程已通过审核" : "你的公开行程未通过审核",
                pass ? plan + "，已开始为你匹配同行" : plan + "：" + StaffSupport.cut(reason.trim(), 200) + "。可修改后重新发布",
                "trip_plan", planId, pass ? "/pages/community/trip?view=match" : "/pages/community/chat?view=trip-publish");
        return pass ? "已通过" : "已驳回";
    }

    /** 给前端用的当前时间（行程页判断"今天"） */
    public String now()
    {
        return LocalDateTime.now().toString();
    }
}
