package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.ApplicationStatus;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.stay.KgStayDayRules;
import com.ruoyi.kangaroo.ext.user.KgCityService;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.StaffGrantView;

/**
 * 员工端「每日概况」（2026-10-17，主人：「我手机的员工端能不能实现每天更新每个城市和小院的报名情况入住情况」）。
 *
 * 按城市 → 小院列出某一天（默认今天）的入住情况，每个城市下面再列各门店岗位的报名情况；顶部是全部的合计。
 * 数据每次打开都现查订单表和报名表，不落快照，所以「每天更新」就是当天的真实数字。
 *
 * 口径（d = 所选日期）：
 *   入住订单只算 confirmed 待入住 / checked_in 已入住 / completed 已完成（和工作台「入住率」同一组状态；退款中的已经还了房量，不算）
 *   · 今日入住 = 入住日是 d 的订单；其中「已到店」= 已办理入住（已入住 / 已完成）
 *   · 在住     = d 这一晚有人住的订单（入住日 ≤ d < 离店日；含当天待到店的；提前退房的从退房那天起不算）
 *   · 今日离店 = 已入住 / 已完成、离店日（提前退房按实际退房日）是 d 的订单；其中「已退房」= 已完成；
 *                看今天时，过了离店日还没办退房的已入住订单也算进今日离店（和订单台「今日离店」一致）
 *   · 入住率   = d 这一晚在住订单的间（床位）数之和 ÷ d 这天的房态库存（上架房型：当天单独设了库存用当天的，否则用房型默认库存）
 *   · 新订单   = 支付时间在 d 当天的订单；待审退款 = 此刻还在「退款审核中」的订单
 *   报名（kg_application，城市取岗位城市，没填用门店城市）
 *   · 新报名 = 提交时间在 d 当天；待处理 = 此刻等门店处理的（待审核 / 用户已确认待录用 / 已签责任书待办入职），其中待审核单列
 *   · 录用   = 门店确认录用时间在 d 当天；到岗 = 预计到岗日是 d 的已录用报名（已录用 / 已签约 / 已入职），其中已入职单列
 *
 * 范围：只看登录态里有授权的门店。入住情况要「房源·查看」，报名情况要「岗位·查看」（主理人 / 管家 / 员工都有）；
 * 线上任务专员两样都没有，不在范围内。员工端没有平台级运营角色，平台运营在后台看；同时有几家门店授权的，就合起来看这几家。
 *
 * 入住部分的日期边界、入住率、日期范围（2026-10-18 起）放在 {@link KgStayDayRules}，和后台「数据看板」共用；这里的静态方法只是转过去，口径不变。
 */
@Service
public class KgStaffDailyService
{
    /** 占着房的订单状态（入住 / 在住 / 离店都只看这几种） */
    static final List<String> STAY_STATUS = KgStayDayRules.STAY_STATUS;
    /** 等门店处理的报名 */
    static final List<String> APP_PENDING = Arrays.asList(ApplicationStatus.SUBMITTED, ApplicationStatus.USER_CONFIRMED,
            ApplicationStatus.CONTRACT_SIGNED);
    /** 已录用的报名（算预计到岗） */
    static final List<String> APP_HIRED = Arrays.asList(ApplicationStatus.STORE_CONFIRMED, ApplicationStatus.CONTRACT_SIGNED,
            ApplicationStatus.ONBOARDED);
    /** 往前最多看一年、往后最多看 180 天（订房最多提前 180 天） */
    static final int PAST_DAYS = KgStayDayRules.PAST_DAYS;
    static final int AHEAD_DAYS = KgStayDayRules.AHEAD_DAYS;
    static final String NO_CITY = "未填城市";
    /**
     * 线上任务的报名单独一组（10-19 复查）：线上任务的岗位城市都是「不限」，之前按岗位城市分组，城市里冒出一个叫「不限」的城市，
     * 员工看不懂；线上任务本来就不分城市，归到「线上任务」一组、排在最后。线下岗位城市写成「不限 / 全国」的照旧归门店城市
     */
    static final String ONLINE_GROUP = "线上任务";
    private static final String[] WEEK = { "周一", "周二", "周三", "周四", "周五", "周六", "周日" };

    @Autowired
    private KgJdbc db;

    /** 城市写法归一（2026-10-19）：没有时（单元测试）按原样分组 */
    @Autowired(required = false)
    private KgCityService cityNames;

    LocalDate today()
    {
        return LocalDate.now();
    }

    LocalDateTime now()
    {
        return LocalDateTime.now();
    }

    // ------------------------------------------------------------ 授权范围

    /** 能看的门店：stays = 能看入住情况的门店，apps = 能看报名情况的门店，name = 门店名 */
    static class Scope
    {
        final List<Long> stays = new ArrayList<Long>();
        final List<Long> apps = new ArrayList<Long>();
        final Map<Long, String> names = new LinkedHashMap<Long, String>();

        boolean empty()
        {
            return stays.isEmpty() && apps.isEmpty();
        }
    }

    static Scope scope(List<StaffGrantView> grants)
    {
        Scope s = new Scope();
        if (grants == null)
        {
            return s;
        }
        for (StaffGrantView g : grants)
        {
            if (g == null || g.getStoreId() == null || Boolean.FALSE.equals(g.getActive()))
            {
                continue;
            }
            String role = StaffRole.exists(g.getRoleCode()) ? g.getRoleCode() : StaffRole.STAFF;
            Long id = g.getStoreId();
            boolean stays = StaffPerms.allows(role, "rooms", StaffPerms.VIEW);
            boolean apps = StaffPerms.allows(role, "jobs", StaffPerms.VIEW);
            if (stays && !s.stays.contains(id))
            {
                s.stays.add(id);
            }
            if (apps && !s.apps.contains(id))
            {
                s.apps.add(id);
            }
            if ((stays || apps) && !s.names.containsKey(id))
            {
                s.names.put(id, g.getStoreName());
            }
        }
        return s;
    }

    // ------------------------------------------------------------ 日期

    LocalDate day(String date)
    {
        return KgStayDayRules.pickDay(date, today());
    }

    static LocalDate asDate(Object v)
    {
        return KgStayDayRules.asDate(v);
    }

    static long num(Object v)
    {
        return KgStayDayRules.num(v);
    }

    static String str(Object v)
    {
        return v == null ? "" : String.valueOf(v);
    }

    // ------------------------------------------------------------ 计数（纯函数，单测直接测）

    /** 一组数字：一个小院、一个城市或全部 */
    static class Counts
    {
        long arrivals, arrived, inHouse, guests, units, departures, departed, newOrders, refundPending, stock;
        long newApps, pending, toReview, hired, arriving, onboarded;

        void add(Counts o)
        {
            arrivals += o.arrivals; arrived += o.arrived; inHouse += o.inHouse; guests += o.guests; units += o.units;
            departures += o.departures; departed += o.departed; newOrders += o.newOrders; refundPending += o.refundPending; stock += o.stock;
            newApps += o.newApps; pending += o.pending; toReview += o.toReview; hired += o.hired; arriving += o.arriving; onboarded += o.onboarded;
        }

        boolean stayIdle()
        {
            return arrivals == 0 && inHouse == 0 && departures == 0 && newOrders == 0 && refundPending == 0;
        }

        Map<String, Object> stayMap()
        {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("arrivals", arrivals);
            m.put("arrived", arrived);
            m.put("inHouse", inHouse);
            m.put("guests", guests);
            m.put("units", units);
            m.put("stock", stock);
            Integer bp = occupancyBp(units, stock);
            m.put("occupancyBp", bp);
            m.put("occupancyText", pct(bp));
            m.put("departures", departures);
            m.put("departed", departed);
            m.put("newOrders", newOrders);
            m.put("refundPending", refundPending);
            return m;
        }

        Map<String, Object> appMap()
        {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("newApps", newApps);
            m.put("pending", pending);
            m.put("toReview", toReview);
            m.put("hired", hired);
            m.put("arriving", arriving);
            m.put("onboarded", onboarded);
            return m;
        }
    }

    /** 入住率（基点，万分之几）；当天没有库存时 null（页面写「—」）。超过 100% 时按 100% 封顶 */
    static Integer occupancyBp(long units, long stock)
    {
        return KgStayDayRules.occupancyBp(units, stock);
    }

    static String pct(Integer bp)
    {
        if (bp == null)
        {
            return "—";
        }
        // 保留一位小数，四舍五入用整数算（不用浮点格式化，免得 46.15 变成 46.1）
        int tenths = (bp + 5) / 10;
        return tenths % 10 == 0 ? (tenths / 10) + "%" : (tenths / 10) + "." + (tenths % 10) + "%";
    }

    /** 实际离店日：提前退房（已完成、退房时间早于离店日）按退房那天，否则按订单的离店日 */
    static LocalDate effectiveOut(String status, LocalDate checkOut, LocalDate checkedOut)
    {
        return KgStayDayRules.effectiveOut(status, checkOut, checkedOut);
    }

    /** 把一张订单记到这一天的数字里（d = 所选日期，isToday = 看的是不是今天） */
    static void tally(Counts c, Map<String, Object> o, LocalDate d, boolean isToday)
    {
        KgStayDayRules.Mark m = KgStayDayRules.mark(o, d, isToday);
        if (m.newOrder)
        {
            c.newOrders++;
        }
        if (m.refundPending)
        {
            c.refundPending++;
        }
        if (m.arrival)
        {
            c.arrivals++;
            if (m.arrived)
            {
                c.arrived++;
            }
        }
        if (m.inHouse)
        {
            c.inHouse++;
            c.units += m.units;
            c.guests += m.guests;
        }
        if (m.departure)
        {
            c.departures++;
            if (m.departed)
            {
                c.departed++;
            }
        }
    }

    // ------------------------------------------------------------ 查询

    public Map<String, Object> daily(AppLoginUser me, String date, String city)
    {
        Scope sc = scope(me == null ? null : me.getStaffGrants());
        if (sc.empty())
        {
            throw new ServiceException("当前角色看不了门店的入住和报名情况", HttpStatus.FORBIDDEN);
        }
        LocalDate d = day(date);
        LocalDate t = today();
        boolean isToday = d.equals(t);
        String want = city == null || city.trim().isEmpty() ? "" : normCity(city.trim());
        Map<String, Object> ps = p("d", d.toString(), "d1", d.plusDays(1).toString());

        // 1) 小院 + 当天库存 + 订单：三条查询，不按小院逐个查
        Map<Long, Map<String, Object>> stays = new LinkedHashMap<Long, Map<String, Object>>();
        Map<Long, Counts> stayCounts = new HashMap<Long, Counts>();
        if (!sc.stays.isEmpty())
        {
            Map<String, Object> q = new HashMap<String, Object>(ps);
            q.put("st", sc.stays);
            for (Map<String, Object> s : db.list("select s.stay_id, s.stay_name, s.city, s.status, s.store_id, st.store_name, st.city store_city "
                    + "from kg_stay s join kg_store st on st.store_id = s.store_id where s.store_id in (:st) and s.del_flag = '0' "
                    + "order by field(s.status, 'online') desc, s.sort, s.stay_id", q))
            {
                Long id = KgJdbc.asLong(s.get("stayId"));
                stays.put(id, s);
                stayCounts.put(id, new Counts());
            }
            if (!stays.isEmpty())
            {
                for (Map<String, Object> r : db.list("select t.stay_id, coalesce(sum(coalesce(c.stock, t.stock)), 0) stock from kg_stay_room_type t "
                        + "join kg_stay s on s.stay_id = t.stay_id left join kg_stay_calendar c on c.room_type_id = t.room_type_id and c.stay_date = :d "
                        + "where s.store_id in (:st) and s.del_flag = '0' and t.del_flag = '0' and t.status = '0' group by t.stay_id", q))
                {
                    Counts c = stayCounts.get(KgJdbc.asLong(r.get("stayId")));
                    if (c != null)
                    {
                        c.stock = num(r.get("stock"));
                    }
                }
                for (Map<String, Object> o : db.list("select o.stay_id, o.status, o.check_in, o.check_out, o.units, o.guests, o.paid_time, o.checked_out_time "
                        + "from kg_stay_order o where o.store_id in (:st) and o.del_flag = '0' and ((o.check_in <= :d and o.check_out >= :d) "
                        + "or (o.paid_time >= :d and o.paid_time < :d1) or o.status = 'refund_pending' or (o.status = 'checked_in' and o.check_out < :d))", q))
                {
                    Counts c = stayCounts.get(KgJdbc.asLong(o.get("stayId")));
                    if (c != null)
                    {
                        tally(c, o, d, isToday);
                    }
                }
            }
        }

        // 2) 报名：按门店 + 城市（岗位城市，没填用门店城市；线上任务归「线上任务」一组）一条聚合
        List<Map<String, Object>> appRows = Collections.emptyList();
        if (!sc.apps.isEmpty())
        {
            Map<String, Object> q = new HashMap<String, Object>(ps);
            q.put("st", sc.apps);
            q.put("pending", APP_PENDING);
            q.put("hired", APP_HIRED);
            appRows = db.list("select a.store_id, st.store_name, case when j.job_type = 'online' then '" + ONLINE_GROUP + "' "
                    + "else coalesce(nullif(nullif(nullif(trim(j.city), ''), '不限'), '全国'), nullif(trim(st.city), ''), '') end city, "
                    + "coalesce(sum(a.submit_time >= :d and a.submit_time < :d1), 0) new_apps, "
                    + "coalesce(sum(a.status in (:pending)), 0) pending, coalesce(sum(a.status = 'submitted'), 0) to_review, "
                    + "coalesce(sum(a.store_confirm_time >= :d and a.store_confirm_time < :d1), 0) hired, "
                    + "coalesce(sum(a.arrival_date = :d and a.status in (:hired)), 0) arriving, "
                    + "coalesce(sum(a.onboard_time >= :d and a.onboard_time < :d1), 0) onboarded "
                    + "from kg_application a join kg_store st on st.store_id = a.store_id left join kg_job j on j.job_id = a.job_id "
                    + "where a.store_id in (:st) and a.del_flag = '0' and (a.status in (:pending) or (a.submit_time >= :d and a.submit_time < :d1) "
                    + "or (a.store_confirm_time >= :d and a.store_confirm_time < :d1) or a.arrival_date = :d or (a.onboard_time >= :d and a.onboard_time < :d1)) "
                    + "group by a.store_id, st.store_name, city", q);
        }
        // 城市写法先归一再分组（见 normCity）：「云南大理」和「大理」是同一个城市
        for (Map<String, Object> s : stays.values())
        {
            s.put("city", normCity(cityOf(s.get("city"), s.get("storeCity"))));
        }
        for (Map<String, Object> a : appRows)
        {
            a.put("city", normCity(cityOf(a.get("city"), null)));
        }
        return assemble(sc, d, t, now(), want, stays, stayCounts, appRows);
    }

    /** 拼成「城市 → 小院 / 门店报名」，带城市小计和全部合计；want 不空时只留这个城市（城市选项仍给全部） */
    static Map<String, Object> assemble(Scope sc, LocalDate d, LocalDate today, LocalDateTime now, String want,
            Map<Long, Map<String, Object>> stays, Map<Long, Counts> stayCounts, List<Map<String, Object>> appRows)
    {
        Map<String, Map<String, Object>> cities = new LinkedHashMap<String, Map<String, Object>>();
        Map<String, Counts> citySum = new HashMap<String, Counts>();
        for (Map.Entry<Long, Map<String, Object>> e : stays.entrySet())
        {
            Map<String, Object> s = e.getValue();
            Counts c = stayCounts.get(e.getKey());
            boolean online = "online".equals(str(s.get("status")));
            // 没上线、这天也没有任何入住动静的小院（草稿、已下架）不列
            if (!online && c.stayIdle())
            {
                continue;
            }
            String city = cityOf(s.get("city"), s.get("storeCity"));
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("stayId", e.getKey());
            row.put("stayName", s.get("stayName"));
            row.put("storeId", KgJdbc.asLong(s.get("storeId")));
            row.put("storeName", s.get("storeName"));
            row.put("online", online);
            row.putAll(c.stayMap());
            cityRow(cities, citySum, city);
            listOf(cities.get(city), "stays").add(row);
            citySum.get(city).add(c);
        }
        // 同一家店的岗位城市写了两种写法（「云南大理」「大理」）时，查询里是两行，归一后在同一个城市下合成一行
        Map<String, Object[]> appRowOf = new HashMap<String, Object[]>();
        for (Map<String, Object> a : appRows)
        {
            String city = cityOf(a.get("city"), null);
            Counts c = new Counts();
            c.newApps = num(a.get("newApps"));
            c.pending = num(a.get("pending"));
            c.toReview = num(a.get("toReview"));
            c.hired = num(a.get("hired"));
            c.arriving = num(a.get("arriving"));
            c.onboarded = num(a.get("onboarded"));
            Long storeId = KgJdbc.asLong(a.get("storeId"));
            Object[] seen = appRowOf.get(city + "|" + storeId);
            if (seen != null)
            {
                Counts merged = (Counts) seen[1];
                merged.add(c);
                @SuppressWarnings("unchecked")
                Map<String, Object> seenRow = (Map<String, Object>) seen[0];
                seenRow.putAll(merged.appMap());
                citySum.get(city).add(c);
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("storeId", storeId);
            String name = str(a.get("storeName"));
            row.put("storeName", name.isEmpty() ? str(sc.names.get(storeId)) : name);
            row.putAll(c.appMap());
            cityRow(cities, citySum, city);
            listOf(cities.get(city), "apps").add(row);
            citySum.get(city).add(c);
            Counts own = new Counts();
            own.add(c);
            appRowOf.put(city + "|" + storeId, new Object[] { row, own });
        }
        // 城市排序：小院多的在前，一样多按在住、再按城市名；「线上任务」一组永远在最后
        List<String> order = new ArrayList<String>(cities.keySet());
        order.sort((x, y) -> {
            if (ONLINE_GROUP.equals(x) != ONLINE_GROUP.equals(y)) return ONLINE_GROUP.equals(x) ? 1 : -1;
            int a = listOf(cities.get(y), "stays").size() - listOf(cities.get(x), "stays").size();
            if (a != 0) return a;
            int b = Long.compare(citySum.get(y).inHouse, citySum.get(x).inHouse);
            if (b != 0) return b;
            if (NO_CITY.equals(x) != NO_CITY.equals(y)) return NO_CITY.equals(x) ? 1 : -1;
            return x.compareTo(y);
        });
        Counts total = new Counts();
        List<Map<String, Object>> outCities = new ArrayList<Map<String, Object>>();
        for (String city : order)
        {
            if (!want.isEmpty() && !want.equals(city))
            {
                continue;
            }
            Map<String, Object> m = cities.get(city);
            Counts c = citySum.get(city);
            m.put("sum", merge(c));
            outCities.add(m);
            total.add(c);
        }
        long stayN = 0;
        // 「N 个城市」不把线上任务那一组算成一个城市
        int cityN = 0;
        for (Map<String, Object> m : outCities)
        {
            stayN += listOf(m, "stays").size();
            if (!ONLINE_GROUP.equals(m.get("city")))
            {
                cityN++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("date", d.toString());
        out.put("label", d.getMonthValue() + "月" + d.getDayOfMonth() + "日 " + WEEK[d.getDayOfWeek().getValue() - 1]);
        out.put("today", today.toString());
        out.put("isToday", d.equals(today));
        out.put("updatedAt", now.format(DateTimeFormatter.ofPattern("HH:mm")));
        out.put("city", want);
        out.put("cityOptions", order);
        Map<String, Object> sum = merge(total);
        sum.put("stays", stayN);
        sum.put("cities", cityN);
        out.put("total", sum);
        out.put("cities", outCities);
        out.put("scope", p("stores", sc.names.size(), "stays", !sc.stays.isEmpty(), "apps", !sc.apps.isEmpty()));
        out.put("note", "在住＝当晚有人住的订单（含当天待到店）；入住率＝当晚占用的间（床位）数 ÷ 当天房态库存；待审退款、待处理报名是此刻的数量。");
        return out;
    }

    private static Map<String, Object> merge(Counts c)
    {
        Map<String, Object> m = c.stayMap();
        m.putAll(c.appMap());
        return m;
    }

    /**
     * 城市写法归一（2026-10-19 复查）：岗位城市有的写「云南大理」、小院写「大理」，员工端每日概况里之前分成两个城市；
     * 和岗位列表「有岗位的城市」下拉同一口径（KgCityService.jobCity：区县归城市、全称 / 别名归字典名、「云南大理」归「大理」）。
     * 「未填城市」「线上任务」两组不动；对不上字典的原样返回
     */
    String normCity(String city)
    {
        if (cityNames == null || city == null || NO_CITY.equals(city) || ONLINE_GROUP.equals(city))
        {
            return city;
        }
        String n = cityNames.jobCity(city);
        return n == null || n.trim().isEmpty() ? city : n.trim();
    }

    static String cityOf(Object city, Object fallback)
    {
        String c = str(city).trim();
        if (c.isEmpty())
        {
            c = str(fallback).trim();
        }
        return c.isEmpty() ? NO_CITY : c;
    }

    private static Map<String, Object> cityRow(Map<String, Map<String, Object>> cities, Map<String, Counts> sums, String city)
    {
        Map<String, Object> m = cities.get(city);
        if (m == null)
        {
            m = new LinkedHashMap<String, Object>();
            m.put("city", city);
            m.put("stays", new ArrayList<Map<String, Object>>());
            m.put("apps", new ArrayList<Map<String, Object>>());
            cities.put(city, m);
            sums.put(city, new Counts());
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Map<String, Object> city, String key)
    {
        return (List<Map<String, Object>>) city.get(key);
    }
}
