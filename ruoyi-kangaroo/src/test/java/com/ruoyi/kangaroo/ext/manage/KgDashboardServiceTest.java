package com.ruoyi.kangaroo.ext.manage;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * 后台「数据看板」（2026-10-18）：当日成交 / 退款的归属与筛选、入住和空房（和员工端每日概况同一套口径）、平台合计不受筛选影响、
 * 城市 → 区县分组排序、筛选项、日期范围、小院明细的房型与房间房态。用假的 KgJdbc，只验证口径与拼装。
 */
class KgDashboardServiceTest
{
    static final LocalDate D = LocalDate.of(2026, 10, 18);
    private final KgJdbc db = mock(KgJdbc.class);
    private final KgDashboardService service = new KgDashboardService()
    {
        @Override
        LocalDate today()
        {
            return D;
        }
    };

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
    }

    private static Map<String, Object> stay(long id, long store, String name, String status, String city, String district, String host)
    {
        return new HashMap<String, Object>(p("stayId", id, "storeId", store, "stayName", name, "status", status, "city", city, "district", district,
                "hostName", host, "hostAvatar", "/profile/a" + id + ".png", "storeName", "店" + store, "storeCity", "平潭"));
    }

    private static Map<String, Object> order(long stay, String status, LocalDate in, LocalDate out, int units)
    {
        return new HashMap<String, Object>(p("stayId", stay, "status", status, "checkIn", in.toString(), "checkOut", out.toString(), "units", units, "guests", 1));
    }

    private static Map<String, Object> pay(String type, Long store, Long orderStay, long amount, String paid, long refund, String refundTime)
    {
        return new HashMap<String, Object>(p("bizType", type, "storeId", store, "orderStayId", orderStay, "amount", amount, "paidTime", paid,
                "refundAmount", refund, "refundTime", refundTime));
    }

    /**
     * 小院：6、7 同一家门店 102（平潭，6 在坛南湾、7 没填区县），8 大理古城（门店 103），9 草稿且这天没动静（不列），
     * 10 已下架但还有人在住（要列，库存记 0），11 城市没填、门店城市也没填（未填城市）。
     */
    private void fixture()
    {
        Map<String, Object> s11 = stay(11, 106, "无名小院", "online", "", "", "");
        s11.put("storeCity", "");
        when(db.list(eq(KgDashboardService.STAYS_SQL), anyMap())).thenReturn(Arrays.asList(
                stay(6, 102, "海边小院", "online", "平潭", "坛南湾", "阿海"),
                stay(7, 102, "山上小院", "online", "平潭", "", "阿海"),
                stay(8, 103, "古城小院", "online", "大理", "古城区", "小云"),
                s11,
                stay(9, 104, "草稿小院", "draft", "平潭", "坛南湾", "阿海"),
                stay(10, 105, "下架小院", "offline", "大理", "古城区", "小云")));
        when(db.list(eq(KgDashboardService.STOCK_SQL), anyMap())).thenReturn(Arrays.asList(
                new HashMap<String, Object>(p("stayId", 6L, "stock", 8)), new HashMap<String, Object>(p("stayId", 7L, "stock", 2)),
                new HashMap<String, Object>(p("stayId", 8L, "stock", 5))));
        when(db.list(eq(KgDashboardService.ORDERS_SQL), anyMap())).thenReturn(Arrays.asList(
                order(6, "confirmed", D, D.plusDays(2), 2),               // 今日入住待到店 + 在住 2
                order(6, "checked_in", D.minusDays(1), D.plusDays(1), 1), // 在住 1
                order(6, "completed", D.minusDays(2), D, 1),              // 今日离店已退房
                order(7, "checked_in", D, D.plusDays(1), 3),              // 超卖：在住 3 > 库存 2
                order(8, "checked_in", D.minusDays(3), D.minusDays(1), 1), // 过了离店日没退房：今日离店
                order(10, "checked_in", D.minusDays(1), D.plusDays(2), 1), // 下架小院还有人住
                order(9, "refunding", D, D.plusDays(1), 1)));             // 退款中：不占房
        when(db.list(eq(KgDashboardService.PAYS_SQL), anyMap())).thenReturn(new ArrayList<Map<String, Object>>(Arrays.asList(
                pay("stay_order", 102L, 7L, 30000, D + " 09:00:00", 0, null),                    // 订房按订单小院 → 7
                pay("activity_signup", 102L, null, 5000, D + " 10:00:00", 0, null),              // 活动按门店第一个小院 → 6
                pay("service_order", 103L, null, 2000, D + " 11:00:00", 2000, D + " 15:00:00"),  // 当天付当天退：成交照算，退款另列
                pay("activity_signup", 999L, null, 1000, D + " 12:00:00", 0, null),              // 门店没有小院：只进平台合计
                pay("stay_order", 102L, 6L, 20000, D.minusDays(3) + " 12:00:00", 10000, D + " 08:00:00")))); // 以前付的、今天退
        when(db.one(eq(KgDashboardService.TOTAL_SQL), anyMap())).thenReturn(new HashMap<String, Object>(p("grossFen", 500000L, "refundFen", 120000L,
                "storeCount", 12L, "stayCount", 4L, "userCount", 3000L, "paidStayOrders", 180L)));
        when(db.list(eq(KgDashboardService.ROOMS_SQL), anyMap())).thenReturn(Arrays.asList(
                new HashMap<String, Object>(p("label", "间", "n", 13L)), new HashMap<String, Object>(p("label", "床位", "n", 4L))));
        when(db.count(eq(KgDashboardService.NEW_USERS_SQL), anyMap())).thenReturn(7L);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> m(Object o)
    {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> l(Object o)
    {
        return (List<Map<String, Object>>) o;
    }

    private static Map<String, Object> stayOf(Map<String, Object> out, long id)
    {
        for (Map<String, Object> r : l(out.get("regions")))
        {
            for (Map<String, Object> s : l(r.get("stays")))
            {
                if (Long.valueOf(id).equals(s.get("stayId")))
                {
                    return s;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------ 金额

    @Test
    void dayMoneyWithoutFilterIsPlatformWide()
    {
        fixture();
        Map<String, Object> out = service.overview(null, null, null, null, null);
        Map<String, Object> day = m(out.get("day"));
        assertEquals(38000L, day.get("gmvFen"), "当天到账的都算，含当天又退掉的、归不到小院的");
        assertEquals(12000L, day.get("refundFen"), "退款按退款日：当天付当天退的 + 以前付今天退的");
        assertEquals(4L, day.get("payCount"));
        List<Map<String, Object>> types = l(day.get("byType"));
        assertEquals(3, types.size());
        assertEquals(p("type", "stay_order", "label", "旅居订房", "fen", 30000L, "count", 1L), new HashMap<String, Object>(types.get(0)));
        assertEquals(p("type", "activity_signup", "label", "活动报名", "fen", 6000L, "count", 2L), new HashMap<String, Object>(types.get(1)));
        assertEquals(p("type", "service_order", "label", "增值服务", "fen", 2000L, "count", 1L), new HashMap<String, Object>(types.get(2)));
        assertEquals(7L, day.get("newUsers"));
        assertEquals(false, out.get("filtered"));
        // 小院上的当日成交：订房按订单小院，活动按门店第一个小院
        assertEquals(5000L, stayOf(out, 6).get("gmvFen"));
        assertEquals(30000L, stayOf(out, 7).get("gmvFen"));
        assertEquals(2000L, stayOf(out, 8).get("gmvFen"));
    }

    @Test
    void filtersSelectStaysAndTheirAttributedMoney()
    {
        fixture();
        Map<String, Object> pt = m(service.overview("2026-10-18", "平潭", null, null, null).get("day"));
        assertEquals(35000L, pt.get("gmvFen"), "平潭两个小院：订房 30000 + 活动 5000；没有小院的门店不算");
        assertEquals(10000L, pt.get("refundFen"));
        assertEquals(2L, pt.get("payCount"));
        assertNull(pt.get("newUsers"), "筛选时没有新增用户");
        assertEquals(0L, m(l(pt.get("byType")).get(2)).get("fen"));

        assertEquals(30000L, m(service.overview(null, null, null, null, 7L).get("day")).get("gmvFen"), "只选山上小院：门店的活动钱记在第一个小院，不重复");
        assertEquals(5000L, m(service.overview(null, "平潭", "坛南湾", null, null).get("day")).get("gmvFen"));
        Map<String, Object> host = m(service.overview(null, null, null, "小云", null).get("day"));
        assertEquals(2000L, host.get("gmvFen"));
        assertEquals(2000L, host.get("refundFen"));
        assertEquals(0L, m(service.overview(null, null, null, "阿", null).get("day")).get("gmvFen"), "主理人按名字完全一致");
        Map<String, Object> out = service.overview(null, " 平潭 ", null, null, null);
        assertEquals(true, out.get("filtered"));
        assertEquals("平潭", m(out.get("filter")).get("city"));
    }

    @Test
    void platformTotalIgnoresFilters()
    {
        fixture();
        Map<String, Object> all = m(service.overview(null, null, null, null, null).get("total"));
        Map<String, Object> one = m(service.overview(null, "大理", null, "小云", 8L).get("total"));
        assertEquals(all, one);
        assertEquals(380000L, all.get("gmvFen"), "累计成交是净额：成交 − 退款");
        assertEquals(500000L, all.get("grossFen"));
        assertEquals(120000L, all.get("refundFen"));
        assertEquals(12L, all.get("storeCount"));
        assertEquals(4L, all.get("stayCount"));
        assertEquals(17L, all.get("roomTotal"));
        assertEquals(p("间", 13L, "床位", 4L), new HashMap<String, Object>(m(all.get("roomTotalByLabel"))));
        assertEquals(3000L, all.get("userCount"));
        assertEquals(180L, all.get("paidStayOrders"));
    }

    @Test
    void totalSqlUsesNetPaidOrders()
    {
        String t = KgDashboardService.TOTAL_SQL;
        assertTrue(t.contains("sum(amount), 0) from kg_pay_order where status in (:paid)"));
        assertTrue(t.contains("sum(refund_amount), 0) from kg_pay_order where status in (:paid)"));
        assertTrue(t.contains("from kg_store where status = '0' and del_flag = '0'"));
        assertTrue(t.contains("from kg_stay where status = 'online' and del_flag = '0'"));
        assertEquals(Arrays.asList("paid", "part_refunded", "refunded"), KgDashboardService.PAID);
        assertTrue(KgDashboardService.PAYS_SQL.contains("p.paid_time >= :d and p.paid_time < :d1"));
        assertTrue(KgDashboardService.PAYS_SQL.contains("p.refund_amount > 0 and p.refund_time >= :d and p.refund_time < :d1"));
        assertTrue(KgDashboardService.STOCK_SQL.contains("s.status = 'online' and t.del_flag = '0' and t.status = '0'"), "只算上线小院的上架房型");
        assertTrue(KgDashboardService.STOCK_SQL.contains("coalesce(c.stock, t.stock)"), "当天单独设了库存用当天的");
        assertTrue(KgDashboardService.ROOMS_SQL.contains("sum(t.stock)"), "房间总数用默认库存");
    }

    @Test
    void attributionRule()
    {
        Map<Long, Long> first = new HashMap<Long, Long>();
        first.put(102L, 6L);
        java.util.Set<Long> known = new java.util.HashSet<Long>(Arrays.asList(6L, 7L));
        assertEquals(Long.valueOf(7), KgDashboardService.attribute("stay_order", 102L, 7L, first, known));
        assertEquals(Long.valueOf(6), KgDashboardService.attribute("activity_signup", 102L, null, first, known));
        assertEquals(Long.valueOf(6), KgDashboardService.attribute("service_order", 102L, 7L, first, known), "只有订房看订单上的小院");
        assertEquals(Long.valueOf(6), KgDashboardService.attribute("stay_order", 102L, 99L, first, known), "订单的小院已删：回到门店第一个小院");
        assertNull(KgDashboardService.attribute("activity_signup", 999L, null, first, known));
        assertNull(KgDashboardService.attribute("activity_signup", null, null, first, known));
    }

    // ------------------------------------------------------------ 入住与空房

    @Test
    void occupancyAndVacancyFollowTheStaffDailyRules()
    {
        fixture();
        Map<String, Object> out = service.overview(null, null, null, null, null);
        Map<String, Object> s6 = stayOf(out, 6);
        assertEquals(8L, s6.get("stock"));
        assertEquals(3L, s6.get("occupied"), "待到店的 2 间也算在住，离店那天的不算");
        assertEquals(5L, s6.get("vacant"));
        assertEquals(1L, s6.get("checkIn"));
        assertEquals(0L, s6.get("checkInArrived"));
        assertEquals(1L, s6.get("checkOut"));
        assertEquals(1L, s6.get("checkOutDone"));
        assertEquals(Integer.valueOf(3750), s6.get("occupancyBp"));
        Map<String, Object> s7 = stayOf(out, 7);
        assertEquals(0L, s7.get("vacant"), "超卖时空房记 0");
        assertEquals(Integer.valueOf(10000), s7.get("occupancyBp"), "入住率封顶 100%");
        assertEquals(1L, stayOf(out, 8).get("checkOut"), "看今天：过了离店日没退房的算今日离店");
        Map<String, Object> s10 = stayOf(out, 10);
        assertNotNull(s10, "下架但还有人住的小院要列出来");
        assertEquals(0L, s10.get("stock"));
        assertEquals(1L, s10.get("occupied"));
        assertNull(s10.get("occupancyBp"), "没有库存：入住率 null（页面写「—」）");
        assertNull(stayOf(out, 9), "草稿、这天没动静的不列");

        Map<String, Object> day = m(out.get("day"));
        assertEquals(2L, day.get("checkIn"));
        assertEquals(1L, day.get("checkInArrived"));
        assertEquals(2L, day.get("checkOut"));
        assertEquals(1L, day.get("checkOutDone"));
        assertEquals(7L, day.get("inHouseUnits"), "海边 3 + 山上 3 + 下架小院 1");
        assertEquals(15L, day.get("stockUnits"));
        assertEquals(10L, day.get("vacantUnits"), "空房按小院各自算再相加（6 空 5、8 空 5；超卖的 7 记 0）");
        assertEquals(Integer.valueOf(4667), day.get("occupancyBp"));
        assertEquals(5L, day.get("stayCount"));

        // 看别的日子：过期没退房的不算今日离店
        Map<String, Object> tomorrow = m(service.overview("2026-10-19", null, null, null, null).get("day"));
        assertEquals(false, service.overview("2026-10-19", null, null, null, null).get("today"));
        assertEquals(0L, stayOf(service.overview("2026-10-19", null, null, null, null), 8).get("checkOut"));
        assertNotNull(tomorrow);
    }

    @Test
    void filteredDayCountsOnlyThePickedStays()
    {
        fixture();
        Map<String, Object> day = m(service.overview(null, "大理", "古城区", null, null).get("day"));
        assertEquals(1L, day.get("inHouseUnits"), "古城小院的只是过期没退房，不在住；下架小院 1");
        assertEquals(5L, day.get("stockUnits"));
        assertEquals(1L, day.get("checkOut"));
        assertEquals(2L, day.get("stayCount"));
        Map<String, Object> none = m(service.overview(null, "厦门", null, null, null).get("day"));
        assertEquals(0L, none.get("stockUnits"));
        assertNull(none.get("occupancyBp"));
        assertEquals(0L, none.get("gmvFen"));
    }

    // ------------------------------------------------------------ 分组、筛选项

    @Test
    void regionsGroupByCityThenDistrictWithUnknownLast()
    {
        fixture();
        Map<String, Object> out = service.overview(null, null, null, null, null);
        List<Map<String, Object>> regions = l(out.get("regions"));
        List<String> keys = new ArrayList<String>();
        for (Map<String, Object> r : regions)
        {
            keys.add(r.get("city") + "/" + r.get("district"));
        }
        assertEquals(Arrays.asList("大理/古城区", "平潭/坛南湾", "平潭/", "未填城市/"), keys, "城市、区县按中文排序，未填的在最后");
        assertEquals("未填区县", regions.get(2).get("districtLabel"));
        assertEquals("坛南湾", regions.get(1).get("districtLabel"));
        Map<String, Object> dali = m(regions.get(0).get("totals"));
        assertEquals(5L, dali.get("stock"));
        assertEquals(1L, dali.get("occupied"));
        assertEquals(5L, dali.get("vacant"));
        assertEquals(1L, dali.get("checkOut"));
        assertEquals(2000L, dali.get("gmvFen"));
        List<Map<String, Object>> daliStays = l(regions.get(0).get("stays"));
        assertEquals(Arrays.asList(8L, 10L), Arrays.asList(daliStays.get(0).get("stayId"), daliStays.get(1).get("stayId")), "同区县里上线的在前");
        Map<String, Object> s8 = daliStays.get(0);
        assertEquals("小云", s8.get("hostName"));
        assertEquals("/profile/a8.png", s8.get("hostAvatar"));
        assertEquals("店103", s8.get("storeName"));
        assertEquals(103L, s8.get("storeId"));
        // 小院没填城市：用门店城市；门店也没填才是未填城市
        assertEquals("未填城市", KgDashboardService.cityOf("", ""));
        assertEquals("平潭", KgDashboardService.cityOf(" ", "平潭"));
    }

    @Test
    void optionsComeFromAllListedStaysRegardlessOfFilter()
    {
        fixture();
        Map<String, Object> o = m(service.overview(null, "大理", null, null, null).get("options"));
        assertEquals(Arrays.asList("大理", "平潭", "未填城市"), o.get("cities"));
        Map<String, Object> ds = m(o.get("districts"));
        assertEquals(Arrays.asList("古城区"), ds.get("大理"));
        assertEquals(Arrays.asList("坛南湾"), ds.get("平潭"), "没填的区县不进选项");
        assertFalse(ds.containsKey("未填城市"));
        List<Map<String, Object>> hosts = l(o.get("hosts"));
        assertEquals(2, hosts.size(), "没填主理人的不进选项");
        assertEquals("阿海", hosts.get(0).get("name"));
        assertEquals(2, hosts.get(0).get("stayCount"), "草稿小院不列，也不算");
        assertEquals("小云", hosts.get(1).get("name"));
        assertEquals(2, hosts.get(1).get("stayCount"));
        List<Map<String, Object>> stays = l(o.get("stays"));
        assertEquals(5, stays.size());
        assertEquals(p("stayId", 8L, "stayName", "古城小院", "city", "大理", "district", "古城区", "hostName", "小云"), new HashMap<String, Object>(stays.get(0)));
    }

    // ------------------------------------------------------------ 日期

    @Test
    void dateWindowMatchesStaffDaily()
    {
        fixture();
        assertEquals("2026-10-18", service.overview("", null, null, null, null).get("date"));
        assertEquals(true, service.overview("today", null, null, null, null).get("today"));
        assertEquals("2025-10-17", service.overview("2025-10-17", null, null, null, null).get("date"), "往前一年（366 天）可以");
        assertEquals("2027-04-16", service.overview("2027-04-16", null, null, null, null).get("date"), "往后 180 天可以");
        for (String bad : new String[] { "2025-10-16", "2027-04-17", "2026/10/18", "明天" })
        {
            ServiceException e = assertThrows(ServiceException.class, () -> service.overview(bad, null, null, null, null), bad);
            assertEquals(Integer.valueOf(422), e.getCode());
        }
        assertThrows(ServiceException.class, () -> service.stay(6L, "2027-04-17"));
    }

    // ------------------------------------------------------------ 小院明细

    @Test
    void stayDetailListsRoomTypesOrdersAndUnits()
    {
        Map<String, Object> s = stay(6, 102, "海边小院", "online", "", "坛南湾", "阿海");
        when(db.one(eq(KgDashboardService.STAY_SQL), anyMap())).thenReturn(s);
        when(db.list(eq(KgDashboardService.TYPES_SQL), anyMap())).thenReturn(Arrays.asList(
                new HashMap<String, Object>(p("roomTypeId", 31L, "typeName", "大床房", "unitLabel", "间", "status", "0", "delFlag", "0", "stock", 3)),
                new HashMap<String, Object>(p("roomTypeId", 32L, "typeName", "多人间", "unitLabel", "床位", "status", "0", "delFlag", "0", "stock", 2)),
                new HashMap<String, Object>(p("roomTypeId", 33L, "typeName", "下架房", "unitLabel", "间", "status", "1", "delFlag", "0", "stock", 9)),
                new HashMap<String, Object>(p("roomTypeId", 34L, "typeName", "下架有单", "unitLabel", "", "status", "1", "delFlag", "0", "stock", 4))));
        Map<String, Object> o1 = order(6, "checked_in", D.minusDays(1), D.plusDays(1), 1);
        o1.putAll(p("orderId", 1L, "orderNo", "SO1", "roomTypeId", 31L, "guestName", "张三", "guestPhone", "13800001234"));
        Map<String, Object> o2 = order(6, "confirmed", D, D.plusDays(2), 1);
        o2.putAll(p("orderId", 2L, "orderNo", "SO2", "roomTypeId", 31L, "guestName", "李四", "guestPhone", "139"));
        Map<String, Object> o3 = order(6, "completed", D.minusDays(2), D.plusDays(3), 1);
        o3.putAll(p("orderId", 3L, "orderNo", "SO3", "roomTypeId", 32L, "guestName", "王五", "guestPhone", "13700005678", "checkedOutTime", D + " 09:00:00"));
        Map<String, Object> o4 = order(6, "checked_in", D, D.plusDays(1), 1);
        o4.putAll(p("orderId", 4L, "orderNo", "SO4", "roomTypeId", 34L, "guestName", "赵六", "guestPhone", ""));
        when(db.list(eq(KgDashboardService.STAY_ORDERS_SQL), anyMap())).thenReturn(Arrays.asList(o1, o2, o3, o4));
        when(db.list(eq(KgDashboardService.UNITS_SQL), anyMap())).thenReturn(Arrays.asList(
                new HashMap<String, Object>(p("unitId", 101L, "unitName", "201", "roomTypeId", 31L, "status", "0", "typeName", "大床房")),
                new HashMap<String, Object>(p("unitId", 102L, "unitName", "202", "roomTypeId", 31L, "status", "0", "typeName", "大床房")),
                new HashMap<String, Object>(p("unitId", 103L, "unitName", "203", "roomTypeId", 31L, "status", "1", "typeName", "大床房")),
                new HashMap<String, Object>(p("unitId", 104L, "unitName", "301-1号床", "roomTypeId", 32L, "status", "0", "typeName", "多人间"))));
        when(db.list(eq(KgDashboardService.ASSIGN_SQL), anyMap())).thenReturn(Arrays.asList(
                new HashMap<String, Object>(p("unitId", 101L, "orderId", 1L)),
                new HashMap<String, Object>(p("unitId", 104L, "orderId", 3L))));   // 已提前退房：房间算空

        Map<String, Object> out = service.stay(6L, null);
        assertEquals("海边小院", out.get("stayName"));
        assertEquals("平潭", out.get("city"), "小院没填城市用门店城市");
        assertEquals("坛南湾", out.get("district"));
        assertEquals("店102", out.get("storeName"));
        assertEquals("阿海", out.get("hostName"));
        assertEquals("2026-10-18", out.get("date"));
        List<Map<String, Object>> types = l(out.get("roomTypes"));
        assertEquals(3, types.size(), "下架且没单的房型不列");
        Map<String, Object> t31 = types.get(0);
        assertEquals(3L, t31.get("stock"));
        assertEquals(2L, t31.get("occupied"));
        assertEquals(1L, t31.get("vacant"));
        List<Map<String, Object>> orders = l(t31.get("orders"));
        assertEquals(2, orders.size());
        assertEquals("138****1234", orders.get(0).get("guestPhoneMasked"));
        assertEquals("", orders.get(1).get("guestPhoneMasked"), "号码太短不显示");
        assertEquals("待入住", orders.get(1).get("statusLabel"));
        assertEquals(true, orders.get(1).get("arrival"));
        assertEquals("2026-10-20", orders.get(1).get("checkOut"));
        Map<String, Object> t32 = types.get(1);
        assertEquals("床位", t32.get("unitLabel"));
        assertEquals(0L, t32.get("occupied"), "今天提前退房：不在住");
        assertEquals(2L, t32.get("vacant"));
        assertEquals(true, m(l(t32.get("orders")).get(0)).get("departure"));
        Map<String, Object> t34 = types.get(2);
        assertEquals("下架有单", t34.get("typeName"));
        assertEquals(0L, t34.get("stock"), "下架房型不算库存");
        assertEquals(1L, t34.get("occupied"));
        assertEquals("间", t34.get("unitLabel"));
        assertEquals(5L, out.get("stock"));
        assertEquals(3L, out.get("occupied"));
        assertEquals(3L, out.get("vacant"));

        List<Map<String, Object>> units = l(out.get("units"));
        assertEquals(Arrays.asList("occupied", "vacant", "disabled", "vacant"),
                Arrays.asList(units.get(0).get("state"), units.get(1).get("state"), units.get(2).get("state"), units.get(3).get("state")));
        assertEquals("SO1", units.get(0).get("orderNo"));
        assertEquals("张三", units.get(0).get("guestName"));
        assertEquals("2026-10-17", units.get(0).get("checkIn"));
        assertEquals("2026-10-19", units.get(0).get("checkOut"));
        assertEquals("大床房", units.get(0).get("roomTypeName"));
        assertNull(units.get(1).get("orderNo"));
        assertNull(units.get(3).get("guestName"));
    }

    @Test
    void offlineStayHasNoStockAndMissingStayIs404()
    {
        when(db.one(eq(KgDashboardService.STAY_SQL), anyMap())).thenReturn(stay(10, 105, "下架小院", "offline", "大理", "", "小云"));
        when(db.list(eq(KgDashboardService.TYPES_SQL), anyMap())).thenReturn(Arrays.asList(
                new HashMap<String, Object>(p("roomTypeId", 51L, "typeName", "庭院房", "unitLabel", "间", "status", "0", "delFlag", "0", "stock", 5))));
        when(db.list(eq(KgDashboardService.STAY_ORDERS_SQL), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        when(db.list(eq(KgDashboardService.UNITS_SQL), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        when(db.list(eq(KgDashboardService.ASSIGN_SQL), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        Map<String, Object> out = service.stay(10L, "2026-10-18");
        assertEquals(0L, m(l(out.get("roomTypes")).get(0)).get("stock"), "没上线的小院不算库存");
        assertNull(out.get("occupancyBp"));
        assertTrue(l(out.get("units")).isEmpty(), "没建房间时 units 为空");

        when(db.one(eq(KgDashboardService.STAY_SQL), anyMap())).thenReturn(null);
        ServiceException e = assertThrows(ServiceException.class, () -> service.stay(404L, null));
        assertEquals(Integer.valueOf(404), e.getCode());
        verify(db, atLeastOnce()).one(eq(KgDashboardService.STAY_SQL), argThat(ps -> Long.valueOf(404L).equals(ps.get("s"))));
    }
}
