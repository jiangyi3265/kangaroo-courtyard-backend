package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.KgCityService;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.StaffGrantView;

/**
 * 员工端「每日概况」（2026-10-17）：入住 / 在住 / 离店的日期边界、入住率算法、报名计数、按授权收范围、按城市小计。
 * 用假的 KgJdbc，只验证口径与拼装；真实 SQL 由 e2e/test_staff_daily_database.py 在一次性库里跑 KgStaffDailyIT。
 */
class KgStaffDailyServiceTest
{
    static final LocalDate D = LocalDate.of(2026, 10, 17);
    private final KgJdbc db = mock(KgJdbc.class);
    private final KgStaffDailyService service = new KgStaffDailyService()
    {
        @Override
        LocalDate today()
        {
            return D;
        }

        @Override
        LocalDateTime now()
        {
            return LocalDateTime.of(2026, 10, 17, 9, 5);
        }
    };

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
    }

    private static Map<String, Object> order(String status, LocalDate in, LocalDate out, int units, int guests)
    {
        return new HashMap<String, Object>(p("stayId", 6L, "status", status, "checkIn", in.toString(), "checkOut", out.toString(),
                "units", units, "guests", guests));
    }

    private static KgStaffDailyService.Counts one(Map<String, Object> o, LocalDate d, boolean isToday)
    {
        KgStaffDailyService.Counts c = new KgStaffDailyService.Counts();
        KgStaffDailyService.tally(c, o, d, isToday);
        return c;
    }

    private static StaffGrantView grant(long store, String name, String role)
    {
        StaffGrantView g = new StaffGrantView();
        g.setStoreId(store);
        g.setStoreName(name);
        g.setRoleCode(role);
        g.setActive(true);
        return g;
    }

    private static AppLoginUser me(StaffGrantView... gs)
    {
        AppLoginUser u = new AppLoginUser();
        u.setUserId(1L);
        u.setStaffGrants(new ArrayList<StaffGrantView>(Arrays.asList(gs)));
        return u;
    }

    // ------------------------------------------------------------ 日期边界

    @Test
    void arrivalDayCountsAsArrivalAndInHouseNotDeparture()
    {
        KgStaffDailyService.Counts c = one(order("confirmed", D, D.plusDays(2), 2, 3), D, true);
        assertEquals(1, c.arrivals);
        assertEquals(0, c.arrived, "还没办入住");
        assertEquals(1, c.inHouse, "今晚占着房（待到店也算在住）");
        assertEquals(2, c.units);
        assertEquals(3, c.guests);
        assertEquals(0, c.departures);
        KgStaffDailyService.Counts done = one(order("checked_in", D, D.plusDays(1), 1, 1), D, true);
        assertEquals(1, done.arrived);
    }

    @Test
    void checkoutDayIsDepartureNotInHouse()
    {
        KgStaffDailyService.Counts c = one(order("checked_in", D.minusDays(2), D, 1, 2), D, true);
        assertEquals(0, c.inHouse, "离店那天晚上不占房");
        assertEquals(0, c.units);
        assertEquals(1, c.departures);
        assertEquals(0, c.departed, "还没办退房");
        KgStaffDailyService.Counts left = one(order("completed", D.minusDays(2), D, 1, 2), D, true);
        assertEquals(1, left.departures);
        assertEquals(1, left.departed);
        // 待入住、离店日到了也没来的（未到店），不算离店
        assertEquals(0, one(order("confirmed", D.minusDays(1), D, 1, 1), D, true).departures);
    }

    @Test
    void middleNightIsInHouseOnly()
    {
        KgStaffDailyService.Counts c = one(order("checked_in", D.minusDays(1), D.plusDays(1), 3, 4), D, true);
        assertEquals(0, c.arrivals);
        assertEquals(1, c.inHouse);
        assertEquals(3, c.units);
        assertEquals(0, c.departures);
        // 第二天、离店日：不再在住
        assertEquals(0, one(order("checked_in", D.minusDays(1), D.plusDays(1), 3, 4), D.plusDays(1), false).inHouse);
        // 入住前一天：什么都不算
        KgStaffDailyService.Counts before = one(order("confirmed", D.plusDays(1), D.plusDays(3), 1, 1), D, true);
        assertEquals(0, before.arrivals + before.inHouse + before.departures);
    }

    @Test
    void earlyCheckoutCountsOnTheRealCheckoutDay()
    {
        Map<String, Object> o = order("completed", D.minusDays(2), D.plusDays(3), 1, 1);
        o.put("checkedOutTime", D + " 10:20:00");
        KgStaffDailyService.Counts c = one(o, D, true);
        assertEquals(0, c.inHouse, "提前退房的那天起房已还回去");
        assertEquals(1, c.departures);
        assertEquals(1, c.departed);
        assertEquals(0, one(o, D.plusDays(3), false).departures, "原离店日不再重复算离店");
        assertEquals(1, one(o, D.minusDays(1), false).inHouse, "退房之前那晚照常在住");
        // 晚了一天才点退房：仍按订单离店日算
        Map<String, Object> late = order("completed", D.minusDays(2), D, 1, 1);
        late.put("checkedOutTime", D.plusDays(1) + " 09:00:00");
        assertEquals(1, one(late, D, true).departures);
        assertEquals(0, one(late, D.plusDays(1), false).departures);
    }

    @Test
    void overdueCheckedInCountsAsDepartureOnlyWhenLookingAtToday()
    {
        Map<String, Object> o = order("checked_in", D.minusDays(3), D.minusDays(1), 1, 1);
        assertEquals(1, one(o, D, true).departures, "和订单台「今日离店」一样：过了离店日还没退房的也要处理");
        assertEquals(0, one(o, D, false).departures);
        assertEquals(0, one(o, D, true).inHouse);
    }

    @Test
    void refundAndUnpaidOrdersDoNotOccupy()
    {
        for (String st : new String[] { "refunding", "cancelled", "closed", "pending_payment", "no_show" })
        {
            KgStaffDailyService.Counts c = one(order(st, D, D.plusDays(2), 1, 1), D, true);
            assertEquals(0, c.arrivals + c.inHouse + c.departures + c.refundPending, st);
        }
        KgStaffDailyService.Counts pending = one(order("refund_pending", D, D.plusDays(2), 1, 1), D, true);
        assertEquals(1, pending.refundPending);
        assertEquals(0, pending.inHouse, "退款审核中的和工作台入住率一样不算");
        Map<String, Object> paid = order("confirmed", D.plusDays(10), D.plusDays(12), 1, 1);
        paid.put("paidTime", D + " 23:59:59");
        assertEquals(1, one(paid, D, true).newOrders);
        paid.put("paidTime", D.plusDays(1) + " 00:00:00");
        assertEquals(0, one(paid, D, true).newOrders);
    }

    @Test
    void occupancyIsUnitsOverStock()
    {
        assertEquals(Integer.valueOf(3750), KgStaffDailyService.occupancyBp(3, 8));
        assertEquals("37.5%", KgStaffDailyService.pct(KgStaffDailyService.occupancyBp(3, 8)));
        assertEquals("50%", KgStaffDailyService.pct(KgStaffDailyService.occupancyBp(4, 8)));
        assertNull(KgStaffDailyService.occupancyBp(2, 0), "当天没有库存：不写 0%");
        assertEquals("—", KgStaffDailyService.pct(null));
        assertEquals(Integer.valueOf(10000), KgStaffDailyService.occupancyBp(9, 8), "超卖也按 100% 封顶");
        assertEquals(Integer.valueOf(3333), KgStaffDailyService.occupancyBp(1, 3));
    }

    // ------------------------------------------------------------ 授权范围

    @Test
    void scopeFollowsRolePermissions()
    {
        StaffGrantView off = grant(105L, "已撤销", "manager");
        off.setActive(false);
        KgStaffDailyService.Scope s = KgStaffDailyService.scope(Arrays.asList(grant(102L, "平潭", "manager"), grant(103L, "大理", "staff"),
                grant(104L, "线上", "online_staff"), off, grant(102L, "平潭", "keeper")));
        assertEquals(Arrays.asList(102L), s.stays, "员工无房源权限；102 通过小院主理人 keeper 授权查看");
        assertEquals(Arrays.asList(102L, 103L), s.apps);
        assertEquals(2, s.names.size(), "线上任务专员、已撤销的授权不在范围内；重复授权只算一次");
    }

    @Test
    void onlineTaskStaffOnlyIsForbiddenWithoutTouchingDb()
    {
        ServiceException e = assertThrows(ServiceException.class, () -> service.daily(me(grant(104L, "线上", "online_staff")), null, null));
        assertEquals(Integer.valueOf(403), e.getCode());
        verifyNoInteractions(db);
        assertThrows(ServiceException.class, () -> service.daily(me(), null, null));
    }

    @Test
    void dateMustBeValidAndInRange()
    {
        assertEquals(D, service.day(null));
        assertEquals(D, service.day("today"));
        assertEquals(D.minusDays(1), service.day("2026-10-16"));
        assertEquals(Integer.valueOf(422), assertThrows(ServiceException.class, () -> service.day("2026/10/16")).getCode());
        assertEquals(Integer.valueOf(422), assertThrows(ServiceException.class, () -> service.day(D.plusDays(181).toString())).getCode());
        assertEquals(Integer.valueOf(422), assertThrows(ServiceException.class, () -> service.day(D.minusDays(367).toString())).getCode());
    }

    // ------------------------------------------------------------ 拼装

    @SuppressWarnings("unchecked")
    private void fixture()
    {
        when(db.list(contains("from kg_stay s join kg_store st"), anyMap())).thenReturn(Arrays.asList(
                new HashMap<String, Object>(p("stayId", 6L, "stayName", "平潭海边小院", "city", "平潭", "status", "online", "storeId", 102L, "storeName", "平潭店", "storeCity", "平潭")),
                new HashMap<String, Object>(p("stayId", 7L, "stayName", "平潭山居", "city", "", "status", "online", "storeId", 102L, "storeName", "平潭店", "storeCity", "平潭")),
                new HashMap<String, Object>(p("stayId", 8L, "stayName", "大理古城院", "city", "大理", "status", "online", "storeId", 103L, "storeName", "大理店", "storeCity", "大理")),
                new HashMap<String, Object>(p("stayId", 9L, "stayName", "下架的院子", "city", "大理", "status", "offline", "storeId", 103L, "storeName", "大理店", "storeCity", "大理"))));
        when(db.list(contains("from kg_stay_room_type t"), anyMap())).thenReturn(Arrays.asList(
                new HashMap<String, Object>(p("stayId", 6L, "stock", 8)), new HashMap<String, Object>(p("stayId", 7L, "stock", 4)),
                new HashMap<String, Object>(p("stayId", 8L, "stock", 10))));
        Map<String, Object> o6a = order("confirmed", D, D.plusDays(2), 2, 2);
        Map<String, Object> o6b = order("checked_in", D.minusDays(1), D.plusDays(1), 1, 1);
        Map<String, Object> o7 = order("checked_in", D.minusDays(2), D, 1, 2);
        o7.put("stayId", 7L);
        Map<String, Object> o8 = order("checked_in", D.minusDays(1), D.plusDays(3), 5, 6);
        o8.put("stayId", 8L);
        o8.put("paidTime", D + " 08:00:00");
        when(db.list(contains("from kg_stay_order o"), anyMap())).thenReturn(Arrays.asList(o6a, o6b, o7, o8));
        when(db.list(contains("from kg_application a"), anyMap())).thenReturn(Arrays.asList(
                new HashMap<String, Object>(p("storeId", 102L, "storeName", "平潭店", "city", "平潭", "newApps", 3, "pending", 5, "toReview", 4, "hired", 1, "arriving", 2, "onboarded", 1)),
                new HashMap<String, Object>(p("storeId", 103L, "storeName", "大理店", "city", "", "newApps", 1, "pending", 0, "toReview", 0, "hired", 0, "arriving", 0, "onboarded", 0))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void groupsByCityWithSubtotalsAndTotalsInFourQueries()
    {
        fixture();
        Map<String, Object> out = service.daily(me(grant(102L, "平潭店", "keeper"), grant(103L, "大理店", "keeper")), null, null);
        assertEquals("2026-10-17", out.get("date"));
        assertEquals("10月17日 周六", out.get("label"));
        assertEquals(Boolean.TRUE, out.get("isToday"));
        assertEquals("09:05", out.get("updatedAt"));
        List<Map<String, Object>> cities = (List<Map<String, Object>>) out.get("cities");
        assertEquals(Arrays.asList("平潭", "大理", "未填城市"), out.get("cityOptions"), "小院多的城市在前；岗位和门店都没填城市的归到最后");
        Map<String, Object> pt = cities.get(0);
        assertEquals("平潭", pt.get("city"));
        List<Map<String, Object>> ptStays = (List<Map<String, Object>>) pt.get("stays");
        assertEquals(2, ptStays.size(), "小院没填城市时用门店城市");
        Map<String, Object> s6 = ptStays.get(0);
        assertEquals(1L, s6.get("arrivals"));
        assertEquals(2L, s6.get("inHouse"));
        assertEquals(3L, s6.get("units"));
        assertEquals(8L, s6.get("stock"));
        assertEquals("37.5%", s6.get("occupancyText"));
        Map<String, Object> ptSum = (Map<String, Object>) pt.get("sum");
        assertEquals(1L, ptSum.get("arrivals"));
        assertEquals(2L, ptSum.get("inHouse"));
        assertEquals(1L, ptSum.get("departures"));
        assertEquals(12L, ptSum.get("stock"));
        assertEquals(Integer.valueOf(2500), ptSum.get("occupancyBp"), "城市入住率 = 城市合计间数 ÷ 城市合计库存（3 ÷ 12），不是各院平均");
        assertEquals(3L, ptSum.get("newApps"));
        assertEquals(5L, ptSum.get("pending"));
        Map<String, Object> dl = cities.get(1);
        assertEquals(1, ((List<?>) dl.get("stays")).size(), "下架、当天没动静的小院不列");
        assertEquals("50%", ((Map<String, Object>) dl.get("sum")).get("occupancyText"));
        assertEquals(1L, ((Map<String, Object>) dl.get("sum")).get("newOrders"));
        Map<String, Object> total = (Map<String, Object>) out.get("total");
        assertEquals(3L, total.get("stays"));
        assertEquals(3, total.get("cities"));
        assertEquals(3L, total.get("inHouse"));
        assertEquals(8L, total.get("units"));
        assertEquals(22L, total.get("stock"));
        assertEquals("36.4%", total.get("occupancyText"));
        assertEquals(4L, total.get("newApps"));
        // 一共四条查询（小院、库存、订单、报名），不按小院逐个查
        verify(db, times(4)).list(anyString(), anyMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    void queriesOnlyGrantedStoresAndCityFilterKeepsOptions()
    {
        fixture();
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        Map<String, Object> out = service.daily(me(grant(102L, "平潭店", "keeper"), grant(104L, "线上", "online_staff")), "2026-10-16", "平潭");
        verify(db, times(4)).list(anyString(), params.capture());
        for (Map<String, Object> ps : params.getAllValues())
        {
            assertEquals(Arrays.asList(102L), ps.get("st"), "只查有授权、且角色能看的门店");
            assertEquals("2026-10-16", ps.get("d"));
            assertEquals("2026-10-17", ps.get("d1"));
        }
        assertEquals(Boolean.FALSE, out.get("isToday"));
        List<Map<String, Object>> cities = (List<Map<String, Object>>) out.get("cities");
        assertEquals(1, cities.size());
        assertEquals("平潭", cities.get(0).get("city"));
        assertTrue(((List<String>) out.get("cityOptions")).contains("大理"), "城市选项仍给全部，方便切换");
        assertEquals(1, ((Map<String, Object>) out.get("total")).get("cities"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void onlineTaskApplicationsGetTheirOwnGroupAtTheEnd()
    {
        // 10-19 复查：线上任务的岗位城市都是「不限」，之前城市里冒出一个叫「不限」的城市；现在归「线上任务」一组、排最后
        fixture();
        when(db.list(contains("from kg_application a"), anyMap())).thenReturn(Arrays.asList(
                new HashMap<String, Object>(p("storeId", 102L, "storeName", "平潭店", "city", KgStaffDailyService.ONLINE_GROUP, "newApps", 2, "pending", 1, "toReview", 1, "hired", 0, "arriving", 0, "onboarded", 0)),
                new HashMap<String, Object>(p("storeId", 102L, "storeName", "平潭店", "city", "平潭", "newApps", 1, "pending", 0, "toReview", 0, "hired", 0, "arriving", 0, "onboarded", 0)),
                new HashMap<String, Object>(p("storeId", 103L, "storeName", "大理店", "city", "", "newApps", 1, "pending", 0, "toReview", 0, "hired", 0, "arriving", 0, "onboarded", 0))));
        Map<String, Object> out = service.daily(me(grant(102L, "平潭店", "keeper"), grant(103L, "大理店", "keeper")), null, null);
        assertEquals(Arrays.asList("平潭", "大理", "未填城市", "线上任务"), out.get("cityOptions"), "线上任务一组在最后，不和城市混在一起");
        List<Map<String, Object>> cities = (List<Map<String, Object>>) out.get("cities");
        Map<String, Object> online = cities.get(cities.size() - 1);
        assertEquals("线上任务", online.get("city"));
        assertEquals(0, ((List<?>) online.get("stays")).size());
        assertEquals(2L, ((Map<String, Object>) online.get("sum")).get("newApps"));
        assertEquals(3, ((Map<String, Object>) out.get("total")).get("cities"), "「N 个城市」不把线上任务算成一个城市");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db, times(4)).list(sql.capture(), anyMap());
        String apps = sql.getAllValues().stream().filter(s -> s.contains("from kg_application a")).findFirst().orElse("");
        assertTrue(apps.contains("case when j.job_type = 'online' then '线上任务'"), "线上任务的报名按「线上任务」分组");
        assertTrue(apps.contains("nullif(nullif(nullif(trim(j.city), ''), '不限'), '全国')"), "线下岗位城市写「不限 / 全国」的归门店城市");
        assertTrue(apps.contains("group by a.store_id, st.store_name, city"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void cityNamesAreNormalisedSoOneCityIsOneGroup()
    {
        // 10-19 复查：岗位城市写「云南大理」、小院写「大理」，员工端每日概况里之前是两个城市；现在按岗位列表城市下拉同一口径归一，
        // 同一家店两种写法的报名合成一行；「线上任务」「未填城市」两组不动；按城市筛选时传哪种写法都行
        fixture();
        KgCityService names = mock(KgCityService.class);
        when(names.jobCity(anyString())).thenAnswer(i -> "云南大理".equals(i.getArgument(0)) ? "大理" : i.getArgument(0));
        ReflectionTestUtils.setField(service, "cityNames", names);
        when(db.list(contains("from kg_application a"), anyMap())).thenReturn(Arrays.asList(
                new HashMap<String, Object>(p("storeId", 103L, "storeName", "大理店", "city", "云南大理", "newApps", 2, "pending", 1, "toReview", 1, "hired", 0, "arriving", 0, "onboarded", 0)),
                new HashMap<String, Object>(p("storeId", 103L, "storeName", "大理店", "city", "大理", "newApps", 1, "pending", 2, "toReview", 0, "hired", 1, "arriving", 0, "onboarded", 0)),
                new HashMap<String, Object>(p("storeId", 102L, "storeName", "平潭店", "city", KgStaffDailyService.ONLINE_GROUP, "newApps", 1, "pending", 0, "toReview", 0, "hired", 0, "arriving", 0, "onboarded", 0))));
        Map<String, Object> out = service.daily(me(grant(102L, "平潭店", "keeper"), grant(103L, "大理店", "keeper")), null, null);
        assertEquals(Arrays.asList("平潭", "大理", "线上任务"), out.get("cityOptions"), "「云南大理」归到「大理」，不再单列一个城市");
        List<Map<String, Object>> cities = (List<Map<String, Object>>) out.get("cities");
        Map<String, Object> dali = cities.get(1);
        assertEquals("大理", dali.get("city"));
        List<Map<String, Object>> apps = (List<Map<String, Object>>) dali.get("apps");
        assertEquals(1, apps.size(), "同一家店两种写法合成一行");
        assertEquals(3L, apps.get(0).get("newApps"));
        assertEquals(3L, apps.get(0).get("pending"));
        assertEquals(1L, apps.get(0).get("hired"));
        assertEquals(3L, ((Map<String, Object>) dali.get("sum")).get("newApps"), "城市小计也是合并后的数");
        assertEquals(1, ((List<?>) dali.get("stays")).size(), "大理的小院还在这一组");
        Map<String, Object> only = service.daily(me(grant(102L, "平潭店", "keeper"), grant(103L, "大理店", "keeper")), null, "云南大理");
        List<Map<String, Object>> picked = (List<Map<String, Object>>) only.get("cities");
        assertEquals(1, picked.size());
        assertEquals("大理", picked.get(0).get("city"), "老写法传进来也能筛到");
    }

    @Test
    void cityFallbacks()
    {
        assertEquals("平潭", KgStaffDailyService.cityOf(" ", "平潭"));
        assertEquals("大理", KgStaffDailyService.cityOf("大理", "平潭"));
        assertEquals("未填城市", KgStaffDailyService.cityOf(null, ""));
    }
}
