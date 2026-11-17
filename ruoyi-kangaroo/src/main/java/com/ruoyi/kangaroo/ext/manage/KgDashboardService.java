package com.ruoyi.kangaroo.ext.manage;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.text.Collator;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.stay.KgStayDayRules;
import com.ruoyi.kangaroo.ext.stay.KgStayOrderService;

/**
 * 管理后台「数据看板」（2026-10-18，主人：「后台管理端，需要一个展示数据的模块，先展示总的数据，当日的成交金额，入住房间，
 * 还有那些房间未入住在往下筛选，可以显示每个主理人的某个地区」）。每次打开都现查，不落快照。
 *
 * 入住口径和员工端「每日概况」完全一样（{@link KgStayDayRules}）：只算待入住 / 已入住 / 已完成的订单，在住 = 入住日 ≤ d < 实际离店日，
 * 库存 = 上线小院的上架房型（当天在房态日历单独设了库存用当天的，否则用房型默认库存），入住率超过 100% 按 100% 封顶。
 *
 * 金额口径（kg_pay_order，单位分）：
 *   · 累计成交（total.gmvFen）= 已支付 / 部分退款 / 已退款的支付单金额之和 − 这些支付单的累计退款（净额）；不受筛选影响
 *   · 当日成交（day.gmvFen）  = 到账时间在 d 当天的支付单金额之和（之后退了款也还算那天成交）；退款另列在 day.refundFen
 *   · 当日退款（day.refundFen）= 最近退款时间在 d 当天的支付单的累计退款
 * 筛选（城市 / 区县 / 主理人 / 小院）先选出小院，金额按小院归属：
 *   · 旅居订房的支付单按 kg_stay_order.stay_id 归到那个小院；
 *   · 其余支付单（活动报名、增值服务）按收款门店归到这家门店的第一个小院（上线的在前，再按排序、编号）——
 *     一家门店有几个小院时，活动和服务的钱只记一次，不重复。没有小院的门店收的钱只进不筛选时的平台合计。
 * 新增用户（day.newUsers）只在不筛选时给（用户不属于哪个小院），筛选时为 null。
 */
@Service
public class KgDashboardService
{
    static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");
    /** 算成交的支付单状态（到过账的） */
    static final List<String> PAID = Arrays.asList("paid", "part_refunded", "refunded");
    static final String NO_CITY = "未填城市";
    static final String NO_DISTRICT = "未填区县";
    /** 当日成交按业务分三类（顺序就是页面顺序） */
    static final String[][] TYPES = { { "stay_order", "旅居订房" }, { "activity_signup", "活动报名" }, { "service_order", "增值服务" } };
    private static final Collator ZH = Collator.getInstance(Locale.CHINA);

    @Autowired
    private KgJdbc db;

    LocalDate today()
    {
        return LocalDate.now(CHINA);
    }

    // ------------------------------------------------------------ 筛选

    static class Filter
    {
        final String city;
        final String district;
        final String host;
        final Long stayId;

        Filter(String city, String district, String host, Long stayId)
        {
            this.city = trim(city);
            this.district = trim(district);
            this.host = trim(host);
            this.stayId = stayId;
        }

        boolean empty()
        {
            return city.isEmpty() && district.isEmpty() && host.isEmpty() && stayId == null;
        }

        /** 城市按页面上显示的城市（小院没填用门店城市）；区县、主理人都按小院上填的原样比 */
        boolean match(Stay s)
        {
            return (city.isEmpty() || city.equals(s.city)) && (district.isEmpty() || district.equals(s.district))
                    && (host.isEmpty() || host.equals(s.hostName)) && (stayId == null || stayId.equals(s.stayId));
        }

        Map<String, Object> echo()
        {
            return p("city", city, "district", district, "host", host, "stayId", stayId);
        }
    }

    /** 一个小院这一天的数字 */
    static class Stay
    {
        Long stayId, storeId;
        String stayName, storeName, hostName, hostAvatar, city, district;
        boolean online;
        int order;
        long stock, occupied, checkIn, checkInArrived, checkOut, checkOutDone, gmvFen, refundFen, payCount;

        long vacant()
        {
            return Math.max(0, stock - occupied);
        }

        /** 没上线的小院（草稿、已下架）只有这一天有入住动静或有钱进出时才列 */
        boolean visible()
        {
            return online || occupied > 0 || checkIn > 0 || checkOut > 0 || payCount > 0 || refundFen > 0;
        }
    }

    // ------------------------------------------------------------ 纯函数（单测直接测）

    static String trim(Object v)
    {
        return v == null ? "" : String.valueOf(v).trim();
    }

    static String cityOf(Object city, Object fallback)
    {
        String c = trim(city);
        if (c.isEmpty())
        {
            c = trim(fallback);
        }
        return c.isEmpty() ? NO_CITY : c;
    }

    static String maskPhone(Object phone)
    {
        String s = trim(phone);
        return s.length() < 7 ? "" : s.substring(0, 3) + "****" + s.substring(s.length() - 4);
    }

    /** 城市、区县排序：按中文排序，「未填」的放最后 */
    static int compareName(String a, String b, String unknown)
    {
        boolean ua = a.isEmpty() || unknown.equals(a), ub = b.isEmpty() || unknown.equals(b);
        if (ua != ub)
        {
            return ua ? 1 : -1;
        }
        return ZH.compare(a, b);
    }

    /** 小院行（按 上线在前、排序、编号 查出来的顺序） */
    static Map<Long, Stay> stays(List<Map<String, Object>> rows)
    {
        Map<Long, Stay> out = new LinkedHashMap<Long, Stay>();
        int i = 0;
        for (Map<String, Object> r : rows)
        {
            Stay s = new Stay();
            s.stayId = KgJdbc.asLong(r.get("stayId"));
            s.storeId = KgJdbc.asLong(r.get("storeId"));
            s.stayName = trim(r.get("stayName"));
            s.storeName = trim(r.get("storeName"));
            s.hostName = trim(r.get("hostName"));
            s.hostAvatar = trim(r.get("hostAvatar"));
            s.city = cityOf(r.get("city"), r.get("storeCity"));
            s.district = trim(r.get("district"));
            s.online = "online".equals(trim(r.get("status")));
            s.order = i++;
            out.put(s.stayId, s);
        }
        return out;
    }

    /** 每家门店的第一个小院（stays 已经按 上线在前、排序、编号 排好） */
    static Map<Long, Long> firstStayOfStore(Map<Long, Stay> stays)
    {
        Map<Long, Long> m = new HashMap<Long, Long>();
        for (Stay s : stays.values())
        {
            if (s.storeId != null && !m.containsKey(s.storeId))
            {
                m.put(s.storeId, s.stayId);
            }
        }
        return m;
    }

    /** 支付单归到哪个小院：旅居订房按订单上的小院，其余按收款门店的第一个小院；归不到返回 null */
    static Long attribute(String bizType, Long payStore, Long orderStay, Map<Long, Long> firstStay, Set<Long> known)
    {
        if ("stay_order".equals(bizType) && orderStay != null && known.contains(orderStay))
        {
            return orderStay;
        }
        return payStore == null ? null : firstStay.get(payStore);
    }

    /** 把订单记到各小院的入住数字上 */
    static void tallyOrders(Map<Long, Stay> stays, List<Map<String, Object>> orders, LocalDate d, boolean isToday)
    {
        for (Map<String, Object> o : orders)
        {
            Stay s = stays.get(KgJdbc.asLong(o.get("stayId")));
            if (s == null)
            {
                continue;
            }
            KgStayDayRules.Mark m = KgStayDayRules.mark(o, d, isToday);
            if (m.arrival)
            {
                s.checkIn++;
                if (m.arrived)
                {
                    s.checkInArrived++;
                }
            }
            if (m.inHouse)
            {
                s.occupied += m.units;
            }
            if (m.departure)
            {
                s.checkOut++;
                if (m.departed)
                {
                    s.checkOutDone++;
                }
            }
        }
    }

    /** 当日的钱：一组支付单（到账或退款在 d 当天的）按类型合计 */
    static class Money
    {
        long gmvFen, refundFen, payCount;
        final Map<String, long[]> byType = new LinkedHashMap<String, long[]>();

        Money()
        {
            for (String[] t : TYPES)
            {
                byType.put(t[0], new long[2]);
            }
        }

        void paid(String type, long fen)
        {
            gmvFen += fen;
            payCount++;
            String k = byType.containsKey(type) ? type : "other";
            long[] v = byType.get(k);
            if (v == null)
            {
                v = new long[2];
                byType.put(k, v);
            }
            v[0] += fen;
            v[1]++;
        }

        List<Map<String, Object>> types()
        {
            List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
            for (String[] t : TYPES)
            {
                long[] v = byType.get(t[0]);
                out.add(row(t[0], t[1], v));
            }
            long[] other = byType.get("other");
            if (other != null)
            {
                out.add(row("other", "其他", other));
            }
            return out;
        }

        private static Map<String, Object> row(String type, String label, long[] v)
        {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("type", type);
            m.put("label", label);
            m.put("fen", v[0]);
            m.put("count", v[1]);
            return m;
        }
    }

    /**
     * 当日支付单：先归到小院（记到小院的当日成交 / 退款上），再按筛选合计。
     * 不筛选时合计全平台（含归不到小院的）；筛选时只合计归到被选中小院的。
     */
    static Money money(Map<Long, Stay> stays, List<Map<String, Object>> pays, LocalDate d, Filter f)
    {
        Map<Long, Long> first = firstStayOfStore(stays);
        Money all = new Money();
        Money picked = new Money();
        for (Map<String, Object> r : pays)
        {
            String type = trim(r.get("bizType"));
            Long stayId = attribute(type, KgJdbc.asLong(r.get("storeId")), KgJdbc.asLong(r.get("orderStayId")), first, stays.keySet());
            Stay s = stayId == null ? null : stays.get(stayId);
            boolean paidToday = d.equals(KgStayDayRules.asDate(r.get("paidTime")));
            long refund = KgStayDayRules.num(r.get("refundAmount"));
            boolean refundToday = refund > 0 && d.equals(KgStayDayRules.asDate(r.get("refundTime")));
            long fen = KgStayDayRules.num(r.get("amount"));
            if (paidToday)
            {
                all.paid(type, fen);
                if (s != null)
                {
                    s.gmvFen += fen;
                    s.payCount++;
                }
            }
            if (refundToday)
            {
                all.refundFen += refund;
                if (s != null)
                {
                    s.refundFen += refund;
                }
            }
            // 筛选时的合计要等小院可见性、筛选都定了才知道；先把「归到哪个小院」记下
            r.put("_stay", stayId);
            r.put("_paid", paidToday);
            r.put("_refund", refundToday ? refund : 0L);
        }
        if (f.empty())
        {
            return all;
        }
        for (Map<String, Object> r : pays)
        {
            Stay s = r.get("_stay") == null ? null : stays.get((Long) r.get("_stay"));
            if (s == null || !s.visible() || !f.match(s))
            {
                continue;
            }
            if (Boolean.TRUE.equals(r.get("_paid")))
            {
                picked.paid(trim(r.get("bizType")), KgStayDayRules.num(r.get("amount")));
            }
            picked.refundFen += (Long) r.get("_refund");
        }
        return picked;
    }

    static Map<String, Object> stayMap(Stay s)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("stayId", s.stayId);
        m.put("stayName", s.stayName);
        m.put("storeId", s.storeId);
        m.put("storeName", s.storeName);
        m.put("hostName", s.hostName);
        m.put("hostAvatar", s.hostAvatar);
        m.put("city", s.city);
        m.put("district", s.district);
        m.put("online", s.online);
        m.put("stock", s.stock);
        m.put("occupied", s.occupied);
        m.put("vacant", s.vacant());
        m.put("checkIn", s.checkIn);
        m.put("checkInArrived", s.checkInArrived);
        m.put("checkOut", s.checkOut);
        m.put("checkOutDone", s.checkOutDone);
        m.put("gmvFen", s.gmvFen);
        m.put("occupancyBp", KgStayDayRules.occupancyBp(s.occupied, s.stock));
        return m;
    }

    /** 筛选项：城市、各城市的区县、主理人（带小院数）、小院 —— 都从这一天能列出来的小院里取，不受当前筛选影响 */
    static Map<String, Object> options(List<Stay> visible)
    {
        List<String> cities = new ArrayList<String>();
        Map<String, List<String>> districts = new LinkedHashMap<String, List<String>>();
        Map<String, Integer> hosts = new HashMap<String, Integer>();
        for (Stay s : visible)
        {
            if (!cities.contains(s.city))
            {
                cities.add(s.city);
            }
            if (!s.district.isEmpty())
            {
                List<String> ds = districts.get(s.city);
                if (ds == null)
                {
                    ds = new ArrayList<String>();
                    districts.put(s.city, ds);
                }
                if (!ds.contains(s.district))
                {
                    ds.add(s.district);
                }
            }
            if (!s.hostName.isEmpty())
            {
                hosts.put(s.hostName, hosts.getOrDefault(s.hostName, 0) + 1);
            }
        }
        cities.sort((a, b) -> compareName(a, b, NO_CITY));
        Map<String, Object> ds = new LinkedHashMap<String, Object>();
        for (String c : cities)
        {
            List<String> l = districts.get(c);
            if (l != null)
            {
                l.sort((a, b) -> compareName(a, b, NO_DISTRICT));
                ds.put(c, l);
            }
        }
        List<String> names = new ArrayList<String>(hosts.keySet());
        names.sort((a, b) -> {
            int n = hosts.get(b) - hosts.get(a);
            return n != 0 ? n : ZH.compare(a, b);
        });
        List<Map<String, Object>> hostList = new ArrayList<Map<String, Object>>();
        for (String n : names)
        {
            Map<String, Object> h = new LinkedHashMap<String, Object>();
            h.put("name", n);
            h.put("stayCount", hosts.get(n));
            hostList.add(h);
        }
        List<Map<String, Object>> stayList = new ArrayList<Map<String, Object>>();
        for (Stay s : visible)
        {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("stayId", s.stayId);
            m.put("stayName", s.stayName);
            m.put("city", s.city);
            m.put("district", s.district);
            m.put("hostName", s.hostName);
            stayList.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("cities", cities);
        out.put("districts", ds);
        out.put("hosts", hostList);
        out.put("stays", stayList);
        return out;
    }

    /** 列表按 城市 → 区县 排（中文排序，未填的在最后），同一区县里按小院原来的顺序 */
    static final Comparator<Stay> REGION_ORDER = (a, b) -> {
        int c = compareName(a.city, b.city, NO_CITY);
        if (c != 0) return c;
        int d = compareName(a.district, b.district, NO_DISTRICT);
        if (d != 0) return d;
        return Integer.compare(a.order, b.order);
    };

    static List<Map<String, Object>> regions(List<Stay> picked)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        Map<String, Object> cur = null;
        long[] sum = null;
        String key = null;
        for (Stay s : picked)
        {
            String k = s.city + "\n" + s.district;
            if (!k.equals(key))
            {
                key = k;
                sum = new long[6];
                cur = new LinkedHashMap<String, Object>();
                cur.put("city", s.city);
                cur.put("district", s.district);
                cur.put("districtLabel", s.district.isEmpty() ? NO_DISTRICT : s.district);
                cur.put("totals", null);
                cur.put("stays", new ArrayList<Map<String, Object>>());
                cur.put("_sum", sum);
                out.add(cur);
            }
            sum[0] += s.stock;
            sum[1] += s.occupied;
            sum[2] += s.vacant();
            sum[3] += s.checkIn;
            sum[4] += s.checkOut;
            sum[5] += s.gmvFen;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> l = (List<Map<String, Object>>) cur.get("stays");
            l.add(stayMap(s));
        }
        for (Map<String, Object> r : out)
        {
            long[] v = (long[]) r.remove("_sum");
            Map<String, Object> t = new LinkedHashMap<String, Object>();
            t.put("stock", v[0]);
            t.put("occupied", v[1]);
            t.put("vacant", v[2]);
            t.put("checkIn", v[3]);
            t.put("checkOut", v[4]);
            t.put("gmvFen", v[5]);
            t.put("occupancyBp", KgStayDayRules.occupancyBp(v[1], v[0]));
            r.put("totals", t);
        }
        return out;
    }

    /** 平台合计（不受筛选影响） */
    static Map<String, Object> total(Map<String, Object> row, List<Map<String, Object>> labels)
    {
        long gross = KgStayDayRules.num(row == null ? null : row.get("grossFen"));
        long refund = KgStayDayRules.num(row == null ? null : row.get("refundFen"));
        Map<String, Object> t = new LinkedHashMap<String, Object>();
        t.put("gmvFen", gross - refund);
        t.put("grossFen", gross);
        t.put("refundFen", refund);
        t.put("storeCount", KgStayDayRules.num(row == null ? null : row.get("storeCount")));
        t.put("stayCount", KgStayDayRules.num(row == null ? null : row.get("stayCount")));
        long rooms = 0;
        Map<String, Object> byLabel = new LinkedHashMap<String, Object>();
        for (Map<String, Object> l : labels)
        {
            long n = KgStayDayRules.num(l.get("n"));
            String label = trim(l.get("label"));
            label = label.isEmpty() ? "间" : label;
            rooms += n;
            byLabel.put(label, KgStayDayRules.num(byLabel.get(label)) + n);
        }
        t.put("roomTotal", rooms);
        t.put("roomTotalByLabel", byLabel);
        t.put("userCount", KgStayDayRules.num(row == null ? null : row.get("userCount")));
        t.put("paidStayOrders", KgStayDayRules.num(row == null ? null : row.get("paidStayOrders")));
        return t;
    }

    // ------------------------------------------------------------ 查询

    static final String STAYS_SQL = "select s.stay_id, s.stay_name, s.city, s.district, s.status, s.store_id, s.host_name, s.host_avatar, "
            + "st.store_name, st.city store_city from kg_stay s join kg_store st on st.store_id = s.store_id where s.del_flag = '0' "
            + "order by field(s.status, 'online') desc, s.sort, s.stay_id";
    /** 当天库存：上线小院的上架房型，当天单独设了库存用当天的，否则用房型默认库存（和员工端每日概况同一条规则） */
    static final String STOCK_SQL = "select t.stay_id, coalesce(sum(coalesce(c.stock, t.stock)), 0) stock from kg_stay_room_type t "
            + "join kg_stay s on s.stay_id = t.stay_id left join kg_stay_calendar c on c.room_type_id = t.room_type_id and c.stay_date = :d "
            + "where s.del_flag = '0' and s.status = 'online' and t.del_flag = '0' and t.status = '0' group by t.stay_id";
    static final String ORDERS_SQL = "select o.stay_id, o.status, o.check_in, o.check_out, o.units, o.guests, o.paid_time, o.checked_out_time "
            + "from kg_stay_order o where o.del_flag = '0' and o.status in (:hold) and ((o.check_in <= :d and o.check_out >= :d) "
            + "or (o.status = 'checked_in' and o.check_out < :d))";
    static final String PAYS_SQL = "select p.biz_type, p.store_id, p.amount, p.paid_time, p.refund_amount, p.refund_time, so.stay_id order_stay_id "
            + "from kg_pay_order p left join kg_stay_order so on p.biz_type = 'stay_order' and so.order_id = p.biz_id "
            + "where coalesce(p.del_flag, '0') = '0' and p.status in (:paid) and ((p.paid_time >= :d and p.paid_time < :d1) "
            + "or (p.refund_amount > 0 and p.refund_time >= :d and p.refund_time < :d1))";
    static final String TOTAL_SQL = "select "
            + "(select coalesce(sum(amount), 0) from kg_pay_order where status in (:paid) and coalesce(del_flag, '0') = '0') gross_fen, "
            + "(select coalesce(sum(refund_amount), 0) from kg_pay_order where status in (:paid) and coalesce(del_flag, '0') = '0') refund_fen, "
            + "(select count(*) from kg_store where status = '0' and del_flag = '0') store_count, "
            + "(select count(*) from kg_stay where status = 'online' and del_flag = '0') stay_count, "
            + "(select count(*) from kg_app_user where del_flag = '0') user_count, "
            + "(select count(*) from kg_stay_order where del_flag = '0' and paid_time is not null) paid_stay_orders";
    static final String ROOMS_SQL = "select coalesce(nullif(trim(t.unit_label), ''), '间') label, coalesce(sum(t.stock), 0) n from kg_stay_room_type t "
            + "join kg_stay s on s.stay_id = t.stay_id where s.del_flag = '0' and s.status = 'online' and t.del_flag = '0' and t.status = '0' "
            + "group by label order by n desc";
    static final String NEW_USERS_SQL = "select count(*) from kg_app_user where del_flag = '0' "
            + "and coalesce(register_time, create_time) >= :d and coalesce(register_time, create_time) < :d1";
    /** 首页待办：等平台审核的旅居 / 服务订单退款（全平台，不受筛选影响） */
    static final String REFUND_TODO_SQL = "select (select count(*) from kg_stay_order where status = 'refund_pending' and del_flag = '0') stay, "
            + "(select count(*) from kg_shop_order where status = 'refund_pending' and del_flag = '0') shop";

    /**
     * 角色切换后旅居、服务订单的退款只由平台在后台审（门店点同意是 403），首页要提醒有几笔在等；切换前门店会审，不给（null）。
     * stay / shop 分开给，页面各自点进旅居订单、服务订单列表并筛出「退款审核中」
     */
    Map<String, Object> refundTodo(Map<String, Object> ps)
    {
        if (!KgStayOrderService.refundByPlatform())
        {
            return null;
        }
        Map<String, Object> r = db.one(REFUND_TODO_SQL, ps);
        return p("stayRefundPending", r == null ? 0L : KgStayDayRules.num(r.get("stay")), "shopRefundPending", r == null ? 0L : KgStayDayRules.num(r.get("shop")));
    }

    public Map<String, Object> overview(String date, String city, String district, String host, Long stayId)
    {
        LocalDate t = today();
        LocalDate d = KgStayDayRules.pickDay(date, t);
        boolean isToday = d.equals(t);
        Filter f = new Filter(city, district, host, stayId);
        Map<String, Object> ps = p("d", d.toString(), "d1", d.plusDays(1).toString(), "hold", KgStayDayRules.STAY_STATUS, "paid", PAID);

        Map<Long, Stay> stays = stays(db.list(STAYS_SQL, ps));
        for (Map<String, Object> r : db.list(STOCK_SQL, ps))
        {
            Stay s = stays.get(KgJdbc.asLong(r.get("stayId")));
            if (s != null)
            {
                s.stock = KgStayDayRules.num(r.get("stock"));
            }
        }
        tallyOrders(stays, db.list(ORDERS_SQL, ps), d, isToday);
        Money money = money(stays, new ArrayList<Map<String, Object>>(db.list(PAYS_SQL, ps)), d, f);

        List<Stay> visible = new ArrayList<Stay>();
        for (Stay s : stays.values())
        {
            if (s.visible())
            {
                visible.add(s);
            }
        }
        visible.sort(REGION_ORDER);
        List<Stay> picked = new ArrayList<Stay>();
        long checkIn = 0, arrived = 0, checkOut = 0, departed = 0, occupied = 0, stock = 0, vacant = 0;
        for (Stay s : visible)
        {
            if (!f.match(s))
            {
                continue;
            }
            picked.add(s);
            checkIn += s.checkIn;
            arrived += s.checkInArrived;
            checkOut += s.checkOut;
            departed += s.checkOutDone;
            occupied += s.occupied;
            stock += s.stock;
            vacant += s.vacant();
        }

        Map<String, Object> day = new LinkedHashMap<String, Object>();
        day.put("gmvFen", money.gmvFen);
        day.put("refundFen", money.refundFen);
        day.put("payCount", money.payCount);
        day.put("byType", money.types());
        day.put("newUsers", f.empty() ? Long.valueOf(db.count(NEW_USERS_SQL, ps)) : null);
        day.put("checkIn", checkIn);
        day.put("checkInArrived", arrived);
        day.put("checkOut", checkOut);
        day.put("checkOutDone", departed);
        day.put("inHouseUnits", occupied);
        day.put("stockUnits", stock);
        day.put("vacantUnits", vacant);
        day.put("occupancyBp", KgStayDayRules.occupancyBp(occupied, stock));
        day.put("stayCount", (long) picked.size());

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("date", d.toString());
        out.put("today", isToday);
        out.put("todayDate", t.toString());
        out.put("filter", f.echo());
        out.put("filtered", !f.empty());
        out.put("options", options(visible));
        out.put("total", total(db.one(TOTAL_SQL, ps), db.list(ROOMS_SQL, ps)));
        out.put("todo", refundTodo(ps));
        out.put("day", day);
        out.put("regions", regions(picked));
        out.put("note", "在住＝当晚有人住的订单（含当天待到店）；入住率＝当晚占用的间（床位）数 ÷ 当天房态库存；"
                + "当日成交＝当天到账的支付单（之后退款也算当天成交，退款另列）；筛选时活动、服务的钱记在收款门店的第一个小院。");
        return out;
    }

    // ------------------------------------------------------------ 小院明细

    static final String STAY_SQL = "select s.stay_id, s.stay_name, s.city, s.district, s.status, s.store_id, s.host_name, s.host_avatar, "
            + "st.store_name, st.city store_city from kg_stay s join kg_store st on st.store_id = s.store_id where s.stay_id = :s and s.del_flag = '0'";
    static final String TYPES_SQL = "select t.room_type_id, t.type_name, t.unit_label, t.status, t.del_flag, coalesce(c.stock, t.stock) stock "
            + "from kg_stay_room_type t left join kg_stay_calendar c on c.room_type_id = t.room_type_id and c.stay_date = :d "
            + "where t.stay_id = :s order by t.sort, t.room_type_id";
    static final String STAY_ORDERS_SQL = "select o.order_id, o.order_no, o.room_type_id, o.status, o.check_in, o.check_out, o.units, o.guests, "
            + "o.guest_name, o.guest_phone, o.paid_time, o.checked_out_time from kg_stay_order o where o.stay_id = :s and o.del_flag = '0' "
            + "and o.status in (:hold) and ((o.check_in <= :d and o.check_out >= :d) or (o.status = 'checked_in' and o.check_out < :d)) "
            + "order by o.check_in, o.order_id";
    static final String UNITS_SQL = "select u.unit_id, u.unit_name, u.room_type_id, u.status, t.type_name from kg_stay_unit u "
            + "left join kg_stay_room_type t on t.room_type_id = u.room_type_id where u.stay_id = :s and u.del_flag = '0' "
            + "order by coalesce(t.sort, 0), u.room_type_id, u.sort, u.unit_id";
    static final String ASSIGN_SQL = "select a.unit_id, a.order_id from kg_stay_assign a where a.stay_id = :s and a.status = 'active' "
            + "and a.start_date <= :d and a.end_date > :d order by a.assign_id";

    public Map<String, Object> stay(Long stayId, String date)
    {
        LocalDate t = today();
        LocalDate d = KgStayDayRules.pickDay(date, t);
        Map<String, Object> ps = p("s", stayId, "d", d.toString(), "hold", KgStayDayRules.STAY_STATUS);
        Map<String, Object> s = stayId == null ? null : db.one(STAY_SQL, ps);
        if (s == null)
        {
            throw new ServiceException("小院不存在或已删除", 404);
        }
        return stayDetail(s, d, d.equals(t), db.list(TYPES_SQL, ps), db.list(STAY_ORDERS_SQL, ps), db.list(UNITS_SQL, ps), db.list(ASSIGN_SQL, ps));
    }

    static Map<String, Object> stayDetail(Map<String, Object> s, LocalDate d, boolean isToday, List<Map<String, Object>> types,
            List<Map<String, Object>> orders, List<Map<String, Object>> units, List<Map<String, Object>> assigns)
    {
        boolean online = "online".equals(trim(s.get("status")));
        // 订单按房型分组；只留这一天有动静的（今日入住 / 在住 / 今日离店）
        Map<Long, List<Map<String, Object>>> byType = new LinkedHashMap<Long, List<Map<String, Object>>>();
        Map<Long, Long> occupiedByType = new HashMap<Long, Long>();
        Map<Long, Map<String, Object>> inHouse = new HashMap<Long, Map<String, Object>>();
        for (Map<String, Object> o : orders)
        {
            KgStayDayRules.Mark m = KgStayDayRules.mark(o, d, isToday);
            if (!m.arrival && !m.inHouse && !m.departure)
            {
                continue;
            }
            Long typeId = KgJdbc.asLong(o.get("roomTypeId"));
            String st = trim(o.get("status"));
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("orderNo", o.get("orderNo"));
            row.put("guestName", trim(o.get("guestName")));
            row.put("guestPhoneMasked", maskPhone(o.get("guestPhone")));
            row.put("checkIn", dateText(o.get("checkIn")));
            row.put("checkOut", dateText(o.get("checkOut")));
            row.put("units", Math.max(1, KgStayDayRules.num(o.get("units"))));
            row.put("status", st);
            row.put("statusLabel", KgStayOrderService.statusLabel(st));
            row.put("arrival", m.arrival);
            row.put("inHouse", m.inHouse);
            row.put("departure", m.departure);
            List<Map<String, Object>> l = byType.get(typeId);
            if (l == null)
            {
                l = new ArrayList<Map<String, Object>>();
                byType.put(typeId, l);
            }
            l.add(row);
            if (m.inHouse)
            {
                occupiedByType.put(typeId, occupiedByType.getOrDefault(typeId, 0L) + m.units);
                inHouse.put(KgJdbc.asLong(o.get("orderId")), o);
            }
        }
        List<Map<String, Object>> outTypes = new ArrayList<Map<String, Object>>();
        long stockAll = 0, occAll = 0, vacantAll = 0;
        for (Map<String, Object> t : types)
        {
            Long typeId = KgJdbc.asLong(t.get("roomTypeId"));
            boolean onShelf = "0".equals(trim(t.get("status"))) && "0".equals(trim(t.get("delFlag")));
            List<Map<String, Object>> l = byType.get(typeId);
            // 下架 / 删掉的房型只有这一天还有订单时才列（库存记 0）
            if (!onShelf && l == null)
            {
                continue;
            }
            long stock = online && onShelf ? KgStayDayRules.num(t.get("stock")) : 0;
            long occ = occupiedByType.getOrDefault(typeId, 0L);
            long vacant = Math.max(0, stock - occ);
            stockAll += stock;
            occAll += occ;
            vacantAll += vacant;
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("roomTypeId", typeId);
            m.put("typeName", trim(t.get("typeName")));
            String label = trim(t.get("unitLabel"));
            m.put("unitLabel", label.isEmpty() ? "间" : label);
            m.put("onShelf", onShelf);
            m.put("stock", stock);
            m.put("occupied", occ);
            m.put("vacant", vacant);
            m.put("orders", l == null ? new ArrayList<Map<String, Object>>() : l);
            outTypes.add(m);
        }
        Map<Long, Map<String, Object>> unitOrder = new HashMap<Long, Map<String, Object>>();
        for (Map<String, Object> a : assigns)
        {
            Map<String, Object> o = inHouse.get(KgJdbc.asLong(a.get("orderId")));
            Long unitId = KgJdbc.asLong(a.get("unitId"));
            if (o != null && !unitOrder.containsKey(unitId))
            {
                unitOrder.put(unitId, o);
            }
        }
        List<Map<String, Object>> outUnits = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> u : units)
        {
            Long unitId = KgJdbc.asLong(u.get("unitId"));
            Map<String, Object> o = unitOrder.get(unitId);
            // 排了房、这晚有人住的算「住」（哪怕房间后来停用了）；没人住的停用房间算「停用」
            String state = o != null ? "occupied" : ("1".equals(trim(u.get("status"))) ? "disabled" : "vacant");
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("unitId", unitId);
            m.put("unitName", trim(u.get("unitName")));
            m.put("roomTypeId", KgJdbc.asLong(u.get("roomTypeId")));
            m.put("roomTypeName", trim(u.get("typeName")));
            m.put("state", state);
            m.put("orderNo", o == null ? null : o.get("orderNo"));
            m.put("guestName", o == null ? null : trim(o.get("guestName")));
            m.put("checkIn", o == null ? null : dateText(o.get("checkIn")));
            m.put("checkOut", o == null ? null : dateText(o.get("checkOut")));
            outUnits.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("stayId", KgJdbc.asLong(s.get("stayId")));
        out.put("stayName", trim(s.get("stayName")));
        out.put("storeId", KgJdbc.asLong(s.get("storeId")));
        out.put("storeName", trim(s.get("storeName")));
        out.put("hostName", trim(s.get("hostName")));
        out.put("hostAvatar", trim(s.get("hostAvatar")));
        out.put("city", cityOf(s.get("city"), s.get("storeCity")));
        out.put("district", trim(s.get("district")));
        out.put("online", online);
        out.put("date", d.toString());
        out.put("today", isToday);
        out.put("stock", stockAll);
        out.put("occupied", occAll);
        out.put("vacant", vacantAll);
        out.put("occupancyBp", KgStayDayRules.occupancyBp(occAll, stockAll));
        out.put("roomTypes", outTypes);
        out.put("units", outUnits);
        return out;
    }

    static String dateText(Object v)
    {
        LocalDate d = KgStayDayRules.asDate(v);
        return d == null ? null : d.toString();
    }
}
