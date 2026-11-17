package com.ruoyi.kangaroo.ext.manage;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * 经营统计口径（周报 / 日报 / 看板 / 荣誉共用），全部从真实业务表现算，区间一律 [from, to)：
 * - 营收：住宿订单、二消服务订单、活动报名费，按支付成功日计入、按退款日扣减（分）
 * - 订单数：区间内支付成功的住宿 + 服务订单 + 付费活动报名
 * - 入住率：已确认 / 已入住 / 已完成的住宿订单在区间内占用的间夜 ÷（上架房型库存 × 天数）；门店没有上架房型时为 null
 * - 好评率：区间内展示中的评价里 4 星及以上的占比（基点）；没有评价时为 null
 * - 报名转化率：区间内提交的报名里已到「商家确认录用」及之后的占比；岗位履约率：其中已入职的占比
 * 数据为 null 表示「没有可统计的数据」，页面显示「—」，不回落成 0。
 */
@Component
public class KgStats
{
    /**
     * 「已到商家确认录用及之后」：store_confirmed / contract_signed / onboarded。
     * 之前把 user_confirmed（只是用户表态愿意去，商家还没确认）和所有 terminated（含审核后、面试后就终止的）都算进来，
     * 转化率虚高。已终止的只有日志里到过 store_confirmed 的才算（录用之后又被终止，录用这一步确实发生过）。
     */
    static final String CONFIRMED = "a.status in ('store_confirmed','contract_signed','onboarded') or (a.status = 'terminated' and exists "
            + "(select 1 from kg_application_log l where l.app_id = a.app_id and l.to_status = 'store_confirmed'))";

    @Autowired
    private KgJdbc db;

    static long num(Object v)
    {
        Long l = KgJdbc.asLong(v);
        return l == null ? 0L : l;
    }

    static String str(Object v)
    {
        String s = KgJdbc.asStr(v);
        return s == null ? "" : s;
    }

    private static final String REVENUE_UNION = "select 'stay' t, date(paid_time) d, pay_fen v, 1 c from kg_stay_order where store_id in (:s) and del_flag = '0' "
            + "and paid_time >= :f and paid_time < :t "
            + "union all select 'stay', date(refund_time), -refund_fen, 0 from kg_stay_order where store_id in (:s) and del_flag = '0' and refund_fen > 0 "
            + "and refund_time >= :f and refund_time < :t "
            + "union all select 'shop', date(paid_time), pay_fen, 1 from kg_shop_order where store_id in (:s) and del_flag = '0' and paid_time >= :f and paid_time < :t "
            + "union all select 'shop', date(refund_time), -refund_fen, 0 from kg_shop_order where store_id in (:s) and del_flag = '0' and refund_fen > 0 "
            + "and refund_time >= :f and refund_time < :t "
            + "union all select 'activity', date(g.paid_time), g.fee_fen, 1 from kg_activity_signup g join kg_activity a on a.activity_id = g.activity_id "
            + "where a.store_id in (:s) and g.fee_fen > 0 and g.paid_time >= :f and g.paid_time < :t "
            + "union all select 'activity', date(g.refund_time), -g.refund_fen, 0 from kg_activity_signup g join kg_activity a on a.activity_id = g.activity_id "
            + "where a.store_id in (:s) and g.refund_fen > 0 and g.refund_time >= :f and g.refund_time < :t";

    private Map<String, Object> rp(List<Long> stores, LocalDate from, LocalDate to)
    {
        return p("s", stores.isEmpty() ? java.util.Collections.singletonList(-1L) : stores, "f", from.toString(), "t", to.toString());
    }

    /** 区间营收：{stay, shop, activity, total, orders}（分 / 笔） */
    public Map<String, Long> revenue(List<Long> stores, LocalDate from, LocalDate to)
    {
        Map<String, Long> m = new LinkedHashMap<String, Long>();
        m.put("stay", 0L);
        m.put("shop", 0L);
        m.put("activity", 0L);
        long orders = 0;
        for (Map<String, Object> r : db.list("select t, sum(v) v, sum(c) c from (" + REVENUE_UNION + ") x group by t", rp(stores, from, to)))
        {
            m.put(str(r.get("t")), num(r.get("v")));
            orders += num(r.get("c"));
        }
        m.put("total", m.get("stay") + m.get("shop") + m.get("activity"));
        m.put("orders", orders);
        return m;
    }

    /** 按日营收序列（分），长度 = 天数 */
    public long[] dailyRevenue(List<Long> stores, LocalDate from, LocalDate to)
    {
        int days = (int) ChronoUnit.DAYS.between(from, to);
        long[] out = new long[Math.max(0, days)];
        for (Map<String, Object> r : db.list("select d, sum(v) v from (" + REVENUE_UNION + ") x group by d", rp(stores, from, to)))
        {
            LocalDate d = LocalDate.parse(str(r.get("d")).substring(0, 10));
            int i = (int) ChronoUnit.DAYS.between(from, d);
            if (i >= 0 && i < out.length)
            {
                out[i] += num(r.get("v"));
            }
        }
        return out;
    }

    /** 按日订单数（支付成功的住宿 + 服务订单 + 付费活动报名） */
    public long[] dailyOrders(List<Long> stores, LocalDate from, LocalDate to)
    {
        int days = (int) ChronoUnit.DAYS.between(from, to);
        long[] out = new long[Math.max(0, days)];
        for (Map<String, Object> r : db.list("select d, sum(c) c from (" + REVENUE_UNION + ") x group by d", rp(stores, from, to)))
        {
            int i = (int) ChronoUnit.DAYS.between(from, LocalDate.parse(str(r.get("d")).substring(0, 10)));
            if (i >= 0 && i < out.length)
            {
                out[i] += num(r.get("c"));
            }
        }
        return out;
    }

    /** 入住率（基点）；没有上架房型时 null */
    public Integer occupancy(List<Long> stores, LocalDate from, LocalDate to)
    {
        long cap = db.count("select coalesce(sum(r.stock), 0) from kg_stay_room_type r join kg_stay s on s.stay_id = r.stay_id where s.store_id in (:s) "
                + "and s.status = 'online' and s.del_flag = '0' and r.status = '0' and r.del_flag = '0'", rp(stores, from, to));
        long days = ChronoUnit.DAYS.between(from, to);
        if (cap <= 0 || days <= 0)
        {
            return null;
        }
        long sold = db.count("select coalesce(sum(o.units * greatest(0, datediff(least(o.check_out, :t), greatest(o.check_in, :f)))), 0) from kg_stay_order o "
                + "where o.store_id in (:s) and o.del_flag = '0' and o.status in ('confirmed','checked_in','completed') and o.check_in < :t and o.check_out > :f",
                rp(stores, from, to));
        return (int) Math.min(10000, Math.round(sold * 10000.0 / (cap * days)));
    }

    /** 每日占用间夜 */
    public long[] dailyRoomNights(List<Long> stores, LocalDate from, LocalDate to)
    {
        int days = (int) ChronoUnit.DAYS.between(from, to);
        long[] out = new long[Math.max(0, days)];
        for (Map<String, Object> o : db.list("select check_in, check_out, units from kg_stay_order where store_id in (:s) and del_flag = '0' "
                + "and status in ('confirmed','checked_in','completed') and check_in < :t and check_out > :f", rp(stores, from, to)))
        {
            LocalDate a = LocalDate.parse(str(o.get("checkIn")).substring(0, 10));
            LocalDate b = LocalDate.parse(str(o.get("checkOut")).substring(0, 10));
            for (LocalDate d = a; d.isBefore(b); d = d.plusDays(1))
            {
                int i = (int) ChronoUnit.DAYS.between(from, d);
                if (i >= 0 && i < out.length)
                {
                    out[i] += Math.max(1, num(o.get("units")));
                }
            }
        }
        return out;
    }

    /** 好评：{n 条数, good 4 星及以上, bp 好评率（n=0 时不放）} */
    public Map<String, Long> goodRate(List<Long> stores, LocalDate from, LocalDate to)
    {
        Map<String, Object> r = db.one("select count(*) n, coalesce(sum(rating >= 4), 0) g from kg_review where store_id in (:s) and status = '0' and del_flag = '0' "
                + "and create_time >= :f and create_time < :t", rp(stores, from, to));
        Map<String, Long> m = new LinkedHashMap<String, Long>();
        long n = num(r.get("n"));
        m.put("n", n);
        m.put("good", num(r.get("g")));
        if (n > 0)
        {
            m.put("bp", Math.round(num(r.get("g")) * 10000.0 / n));
        }
        return m;
    }

    /** 报名：{n 提交数, confirmed 已确认录用及之后, onboarded 已入职} */
    public Map<String, Long> applications(List<Long> stores, LocalDate from, LocalDate to)
    {
        Map<String, Object> r = db.one("select count(*) n, coalesce(sum(" + CONFIRMED + "), 0) c, coalesce(sum(a.status = 'onboarded'), 0) o "
                + "from kg_application a where a.store_id in (:s) and a.del_flag = '0' and a.submit_time >= :f and a.submit_time < :t", rp(stores, from, to));
        Map<String, Long> m = new LinkedHashMap<String, Long>();
        m.put("n", num(r.get("n")));
        m.put("confirmed", num(r.get("c")));
        m.put("onboarded", num(r.get("o")));
        return m;
    }

    public long[] dailyApplications(List<Long> stores, LocalDate from, LocalDate to)
    {
        int days = (int) ChronoUnit.DAYS.between(from, to);
        long[] out = new long[Math.max(0, days)];
        for (Map<String, Object> r : db.list("select date(submit_time) d, count(*) c from kg_application where store_id in (:s) and del_flag = '0' "
                + "and submit_time >= :f and submit_time < :t group by date(submit_time)", rp(stores, from, to)))
        {
            int i = (int) ChronoUnit.DAYS.between(from, LocalDate.parse(str(r.get("d")).substring(0, 10)));
            if (i >= 0 && i < out.length)
            {
                out[i] = num(r.get("c"));
            }
        }
        return out;
    }

    /** 活动：{held 举办场次, people 确认参与人次} */
    public Map<String, Long> activities(List<Long> stores, LocalDate from, LocalDate to)
    {
        Map<String, Long> m = new LinkedHashMap<String, Long>();
        m.put("held", db.count("select count(*) from kg_activity where store_id in (:s) and del_flag = '0' and status not in ('cancelled','rejected','pending','draft') "
                + "and start_time >= :f and start_time < :t", rp(stores, from, to)));
        m.put("people", db.count("select count(*) from kg_activity_signup g join kg_activity a on a.activity_id = g.activity_id where a.store_id in (:s) "
                + "and g.status = 'confirmed' and a.start_time >= :f and a.start_time < :t", rp(stores, from, to)));
        return m;
    }

    public long[] dailyActivityPeople(List<Long> stores, LocalDate from, LocalDate to)
    {
        int days = (int) ChronoUnit.DAYS.between(from, to);
        long[] out = new long[Math.max(0, days)];
        for (Map<String, Object> r : db.list("select date(a.start_time) d, count(*) c from kg_activity_signup g join kg_activity a on a.activity_id = g.activity_id "
                + "where a.store_id in (:s) and g.status = 'confirmed' and a.start_time >= :f and a.start_time < :t group by date(a.start_time)", rp(stores, from, to)))
        {
            int i = (int) ChronoUnit.DAYS.between(from, LocalDate.parse(str(r.get("d")).substring(0, 10)));
            if (i >= 0 && i < out.length)
            {
                out[i] = num(r.get("c"));
            }
        }
        return out;
    }

    /** 周报「本周完成工作」：房源操作次数、岗位新建 / 发布 / 关闭次数、举办活动场次、同步的 Vlog 条数 */
    public Map<String, Long> works(Long storeId, LocalDate from, LocalDate to)
    {
        List<Long> s = java.util.Collections.singletonList(storeId);
        Map<String, Long> m = new LinkedHashMap<String, Long>();
        m.put("rooms", db.count("select count(*) from kg_staff_op_log where store_id = :s1 and ok = '1' and (action like 'stay%' or action like 'roomtype%') "
                + "and create_time >= :f and create_time < :t", p("s1", storeId, "f", from.toString(), "t", to.toString())));
        m.put("jobs", db.count("select count(*) from kg_job where store_id = :s1 and del_flag = '0' and ((create_time >= :f and create_time < :t) "
                + "or (publish_time >= :f and publish_time < :t) or (close_time >= :f and close_time < :t))", p("s1", storeId, "f", from.toString(), "t", to.toString())));
        m.put("activities", activities(s, from, to).get("held"));
        m.put("content", db.count("select count(*) from kg_stay_vlog where store_id = :s1 and del_flag = '0' and create_time >= :f and create_time < :t",
                p("s1", storeId, "f", from.toString(), "t", to.toString())));
        return m;
    }

    // ---------------------------------------------------------------- 展示工具

    /** ¥5,780 / ¥5,780.50：整元沿用原展示，有分时保留两位，不改账面金额。 */
    public static String yuanText(Long fen)
    {
        if (fen == null)
        {
            return "—";
        }
        return (fen < 0 ? "-¥" : "¥") + String.format(Locale.ROOT,
                fen % 100 == 0 ? "%,.0f" : "%,.2f", BigDecimal.valueOf(fen, 2).abs());
    }

    /** 5,780.50（两位小数，千分位） */
    public static String yuan2(long fen)
    {
        return (fen < 0 ? "-" : "") + String.format(Locale.ROOT, "%,.2f", BigDecimal.valueOf(fen, 2).abs());
    }

    public static String pct(Number bp)
    {
        if (bp == null)
        {
            return "—";
        }
        long v = bp.longValue();
        return v % 100 == 0 ? (v / 100) + "%" : String.format("%.1f%%", v / 100.0);
    }

    /** 数量环比：+12.5% / -3.1%；上期为 0 时无法计算 */
    public static String delta(long cur, long prev)
    {
        if (prev == 0)
        {
            return cur == 0 ? "持平" : "新增";
        }
        double d = (cur - prev) * 100.0 / Math.abs(prev);
        return (d >= 0 ? "+" : "") + String.format("%.1f%%", d);
    }

    /** 比率变化（百分点） */
    public static String deltaBp(Long cur, Long prev)
    {
        if (cur == null || prev == null)
        {
            return "—";
        }
        long d = cur - prev;
        return (d >= 0 ? "+" : "-") + pct(Math.abs(d));
    }

    public static boolean up(long cur, long prev)
    {
        return cur >= prev;
    }

    public static List<Long> list(long[] a)
    {
        List<Long> out = new ArrayList<Long>();
        for (long v : a)
        {
            out.add(v);
        }
        return out;
    }

    public static Long rateBp(long part, long whole)
    {
        return whole <= 0 ? null : Math.round(part * 10000.0 / whole);
    }
}
