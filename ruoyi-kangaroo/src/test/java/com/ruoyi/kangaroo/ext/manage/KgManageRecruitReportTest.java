package com.ruoyi.kangaroo.ext.manage;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.ExtEntity;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.KgMerchantRequestService;
import com.ruoyi.kangaroo.ext.staff.KgVolunteerDailyService;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.ext.user.KgCityService;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.security.StaffGrantView;

/** 换工主理人的招募账日报、换工招募情况周报；小院主理人 / 员工不受影响。 */
class KgManageRecruitReportTest
{
    private static final long ME = 7L;
    private static final LocalDate MON = LocalDate.of(2026, 10, 26);

    private KgJdbc db;
    private KgStats stats;
    private StaffSupport support;
    private KgVolunteerDailyService volunteers;
    private KgMerchantRequestService requests;
    private KgManageService service;
    /** 当前账号的 myStores 结果 */
    private List<Map<String, Object>> grants;

    private static Map<String, Object> grant(long store, String role, String city)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("storeId", store);
        m.put("roleCode", role);
        m.put("storeName", "店" + store);
        m.put("cover", "c" + store);
        m.put("city", city);
        return m;
    }

    @BeforeEach
    void setUp()
    {
        db = mock(KgJdbc.class);
        stats = mock(KgStats.class);
        support = mock(StaffSupport.class);
        volunteers = mock(KgVolunteerDailyService.class);
        requests = mock(KgMerchantRequestService.class);
        KgCityService cities = mock(KgCityService.class);
        when(cities.jobCity(anyString())).thenAnswer(i -> i.getArgument(0));
        service = new KgManageService();
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "stats", stats);
        ReflectionTestUtils.setField(service, "support", support);
        ReflectionTestUtils.setField(service, "cities", cities);
        ReflectionTestUtils.setField(service, "volunteers", volunteers);
        ReflectionTestUtils.setField(service, "merchantRequests", requests);
        AppLoginUser me = new AppLoginUser();
        me.setUserId(ME);
        me.setNickname("小王");
        me.setStaffGrants(Collections.singletonList(new StaffGrantView()));
        AppUserContext.set(me);
        grants = new ArrayList<Map<String, Object>>();
        when(db.list(contains("from kg_staff_grant g join kg_store s"), anyMap())).thenAnswer(i -> grants);
        when(db.list(contains("from kg_daily_entry"), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        when(db.one(anyString(), anyMap())).thenReturn(null);
        when(requests.cityLeadIds(anyLong())).thenReturn(new ArrayList<Long>());
    }

    @AfterEach
    void tearDown()
    {
        AppUserContext.remove();
    }

    private static Map<String, Object> entry(String dir, String cat, long fen)
    {
        return p("direction", dir, "category", cat, "amountFen", fen, "entryId", 1L, "payMethod", "微信");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> tile(Map<String, Object> d, String name)
    {
        for (Map<String, Object> t : (List<Map<String, Object>>) d.get("items"))
        {
            if (name.equals(t.get("name")))
            {
                return t;
            }
        }
        return null;
    }

    private void managerOnStore1()
    {
        grants.add(grant(1, "manager", "丽江"));
    }

    private void keeperOnStore1()
    {
        grants.add(grant(1, "keeper", "丽江"));
        when(stats.revenue(anyList(), any(), any())).thenReturn(revenue());
    }

    private static Map<String, Long> revenue()
    {
        Map<String, Long> m = new HashMap<String, Long>();
        for (String k : Arrays.asList("stay", "shop", "activity", "total", "orders"))
        {
            m.put(k, 0L);
        }
        return m;
    }

    // ------------------------------------------------------------------ 日报：两本账

    @Test
    void managerSeesRecruitTilesWithoutRoomOrShopIncome()
    {
        managerOnStore1();
        when(db.list(contains("ledger = 'recruit'"), anyMap())).thenAnswer(i -> {
            Map<String, Object> ps = i.getArgument(1);
            return LocalDate.now().toString().equals(ps.get("d"))
                    ? Arrays.asList(entry("income", "保险收入", 10000L), entry("expense", "保险支出", 3000L), entry("income", "其他收入/兼职", 2000L),
                            entry("expense", "其他支出/交通费", 500L))
                    : new ArrayList<Map<String, Object>>();
        });
        Map<String, Object> d = service.daily(1L, null);
        assertEquals("recruit", d.get("ledger"));
        assertEquals("换工招募日报", d.get("title"));
        List<?> items = (List<?>) d.get("items");
        assertEquals(4, items.size());
        assertNull(tile(d, "订房收入"));
        assertNull(tile(d, "二消收入"));
        assertEquals("¥100", tile(d, "保险收入").get("amount"));
        assertEquals("¥30", tile(d, "保险支出").get("amount"));
        assertEquals("¥20", tile(d, "其他收入").get("amount"));
        assertEquals("¥5", tile(d, "其他支出").get("amount"));
        assertArrayEquals(new long[] { 12000, 3500, 8500 }, (long[]) d.get("totals"));
        assertEquals("¥85", d.get("profit"));
        verify(stats, never()).revenue(anyList(), any(), any());
        assertEquals(KgManageService.RECRUIT_INCOME_CATS, d.get("incomeCats"));
        assertEquals(KgManageService.RECRUIT_EXPENSE_CATS, d.get("expenseCats"));
    }

    /** 老版本 onMetric：manual + key=income 预填「其他收入」进收入表单；其他 key 一律进支出表单 */
    @Test
    void recruitTilesDoNotSendOldClientInTheWrongDirection()
    {
        managerOnStore1();
        Map<String, Object> d = service.daily(1L, null);
        Map<String, Object> insuranceIn = tile(d, "保险收入");
        assertEquals(false, insuranceIn.get("manual"), "老版本点保险收入只打开明细，不能预填成其他收入");
        assertEquals("income", insuranceIn.get("key"));
        assertEquals("income", insuranceIn.get("direction"));
        assertEquals("保险收入", insuranceIn.get("category"));
        assertEquals(true, tile(d, "其他收入").get("manual"));
        assertEquals("income", tile(d, "其他收入").get("key"));
        assertEquals("expense", tile(d, "保险支出").get("key"));
        assertEquals("expense", tile(d, "其他支出").get("key"));
    }

    @Test
    void keeperStaysOnStayLedger()
    {
        keeperOnStore1();
        Map<String, Object> d = service.daily(1L, null);
        assertEquals("stay", d.get("ledger"));
        assertEquals("每日经营报表", d.get("title"));
        assertNotNull(tile(d, "订房收入"));
        assertEquals(KgManageService.INCOME_CATS, d.get("incomeCats"));
        verify(db, atLeastOnce()).list(contains("and ledger = 'stay'"), anyMap());
        verify(db, never()).list(contains("ledger = 'recruit'"), anyMap());
        ArgumentCaptor<Map<String, Object>> ps = paramsCaptor();
        verify(db).list(contains("and ledger = :l"), ps.capture());
        assertEquals("stay", ps.getValue().get("l"));
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> paramsCaptor()
    {
        return ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
    }

    private Map<String, Object> body(String dir, String cat)
    {
        Map<String, Object> b = new HashMap<String, Object>();
        b.put("storeId", 1L);
        b.put("direction", dir);
        b.put("category", cat);
        b.put("amountFen", 100L);
        b.put("payMethod", "微信");
        return b;
    }

    @Test
    void managerEntriesGoToRecruitLedger()
    {
        managerOnStore1();
        for (String c : KgManageService.RECRUIT_INCOME_CATS)
        {
            service.dailyAdd(body("income", c));
        }
        for (String c : KgManageService.RECRUIT_EXPENSE_CATS)
        {
            service.dailyAdd(body("expense", c));
            assertTrue(c.length() <= 32, c);
        }
        ArgumentCaptor<Map<String, Object>> row = paramsCaptor();
        verify(db, times(11)).insert(eq("kg_daily_entry"), row.capture());
        for (Map<String, Object> r : row.getAllValues())
        {
            assertEquals("recruit", r.get("ledger"));
        }
    }

    @Test
    void oldClientPrefilledOtherIncomeIsFiledUnderItsSubCategory()
    {
        managerOnStore1();
        service.dailyAdd(body("income", "其他收入"));
        service.dailyAdd(body("expense", "其他支出"));
        ArgumentCaptor<Map<String, Object>> row = paramsCaptor();
        verify(db, times(2)).insert(eq("kg_daily_entry"), row.capture());
        assertEquals("其他收入/其他收入", row.getAllValues().get(0).get("category"));
        assertEquals("其他支出/其他支出", row.getAllValues().get(1).get("category"));
    }

    @Test
    void categoriesAreCheckedAgainstTheRightLedgerAndDirection()
    {
        managerOnStore1();
        for (Map<String, Object> b : Arrays.asList(body("expense", "采购支出"), body("income", "线下房费"), body("expense", "保险收入"), body("income", "保险支出")))
        {
            ServiceException e = assertThrows(ServiceException.class, () -> service.dailyAdd(b), String.valueOf(b));
            assertEquals(422, e.getCode());
        }
        grants.clear();
        keeperOnStore1();
        assertThrows(ServiceException.class, () -> service.dailyAdd(body("income", "保险收入")));
        assertThrows(ServiceException.class, () -> service.dailyAdd(body("expense", "其他支出/办公费")));
        service.dailyAdd(body("expense", "采购支出"));
        ArgumentCaptor<Map<String, Object>> row = paramsCaptor();
        verify(db).insert(eq("kg_daily_entry"), row.capture());
        assertEquals("stay", row.getValue().get("ledger"));
    }

    @Test
    void nobodyVoidsAcrossLedgers()
    {
        managerOnStore1();
        when(db.must(contains("from kg_daily_entry"), anyMap(), anyString())).thenReturn(p("storeId", 1L, "status", "0", "ledger", "stay"));
        ServiceException e = assertThrows(ServiceException.class, () -> service.dailyVoid(9L));
        assertEquals(403, e.getCode());
        verify(db, never()).update(anyString(), anyMap());
        grants.clear();
        keeperOnStore1();
        when(db.must(contains("from kg_daily_entry"), anyMap(), anyString())).thenReturn(p("storeId", 1L, "status", "0", "ledger", "recruit"));
        assertThrows(ServiceException.class, () -> service.dailyVoid(9L));
        verify(db, never()).update(anyString(), anyMap());
        when(db.must(contains("from kg_daily_entry"), anyMap(), anyString())).thenReturn(p("storeId", 1L, "status", "0", "ledger", "stay"));
        assertEquals("已作废", service.dailyVoid(9L));
    }

    // ------------------------------------------------------------------ 日报：发送

    @Test
    void recruitReportGoesToCityLeadWithFiguresAndNoLandingPage()
    {
        managerOnStore1();
        when(requests.cityLeadIds(1L)).thenReturn(Arrays.asList(50L, ME));
        when(db.list(contains("from kg_app_user where user_id in"), anyMap())).thenReturn(Collections.singletonList(p("userId", 50L, "nickname", "负责人")));
        when(db.list(contains("ledger = 'recruit'"), anyMap())).thenReturn(Collections.singletonList(entry("income", "保险收入", 10000L)));
        Map<String, Object> r = service.dailySend(1L, null);
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(support).notifyStaff(eq(50L), eq("system"), contains("换工招募日报"), content.capture(), eq(KgManageService.BIZ_RECRUIT_DAILY), eq(1L), isNull());
        assertTrue(content.getValue().contains("保险收入"), content.getValue());
        assertFalse(content.getValue().contains("订房"), content.getValue());
        assertEquals(1, r.get("sent"));
        assertEquals("已发送给 1 位城市负责人", r.get("message"));
        verify(support, never()).notifyStaff(eq(ME), anyString(), anyString(), anyString(), anyString(), anyLong(), any());
    }

    @Test
    void withoutCityLeadRecruitReportGoesToOtherManagersOfTheStore()
    {
        managerOnStore1();
        when(db.list(contains("g.role_code = 'manager' and u.status"), anyMap())).thenReturn(Collections.singletonList(p("appUserId", 8L)));
        when(db.list(contains("from kg_app_user where user_id in"), anyMap())).thenReturn(Collections.singletonList(p("userId", 8L, "nickname", "同事")));
        Map<String, Object> r = service.dailySend(1L, null);
        verify(support).notifyStaff(eq(8L), eq("system"), anyString(), anyString(), eq("daily_report"), eq(1L), contains("/pages/manage/daily?storeId=1&date="));
        assertEquals("已发送给 1 位换工主理人", r.get("message"));
    }

    @Test
    void nobodyToSendRecruitReportToIsAClearError()
    {
        managerOnStore1();
        when(db.list(contains("g.role_code = 'manager' and u.status"), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        Map<String, Object> d = service.daily(1L, null);
        assertEquals(Collections.emptyList(), d.get("managers"));
        assertEquals("none", d.get("recipientKind"));
        ServiceException e = assertThrows(ServiceException.class, () -> service.dailySend(1L, null));
        assertEquals(422, e.getCode());
        verify(support, never()).notifyStaff(anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), any());
    }

    /** 小院主理人发给换工主理人的旅居账：他点开日报页是招募账，对不上，所以数字写正文、不给落地页 */
    @Test
    void stayReportToManagerHasNoLandingPage()
    {
        keeperOnStore1();
        when(db.list(contains("join kg_app_user u on u.user_id = g.app_user_id where g.store_id = :s"), anyMap())).thenReturn(Collections.singletonList(p("userId", 8L)));
        service.dailySend(1L, null);
        verify(support).notifyStaff(eq(8L), eq("system"), contains("每日经营报表"), contains("总营收"), eq("daily_report"), eq(1L), isNull());
    }

    // ------------------------------------------------------------------ 周报：汇总

    private static Map<String, Object> shop(long id, long recruited, long onboarded, long shortfall, long leaving)
    {
        Map<String, Object> s = new LinkedHashMap<String, Object>();
        s.put("storeId", id);
        s.put("name", "换工店" + id);
        s.put("cover", "c" + id);
        s.put("recruited", recruited);
        s.put("onboarded", onboarded);
        s.put("recruitedLast", 1L);
        s.put("onboardedLast", 0L);
        s.put("applied", 3L);
        s.put("appliedLast", 2L);
        s.put("shortfall", shortfall);
        s.put("leaving", leaving);
        return s;
    }

    private void cityShops()
    {
        // 每次调用都给新对象：服务层会往行上写金额
        when(volunteers.weekShops(eq("丽江"), any())).thenAnswer(i -> new ArrayList<Map<String, Object>>(Arrays.asList(shop(1, 2, 1, 3, 1), shop(99, 4, 2, 0, 0))));
        when(db.list(contains("group by store_id, biz_date, direction"), anyMap())).thenReturn(Arrays.asList(
                p("storeId", 1L, "bizDate", "2026-10-27", "direction", "income", "v", 20000L),
                p("storeId", 1L, "bizDate", "2026-10-28", "direction", "expense", "v", 5000L),
                p("storeId", 1L, "bizDate", "2026-10-20", "direction", "income", "v", 10000L)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> shopIn(Map<String, Object> snap, long id)
    {
        for (Map<String, Object> s : (List<Map<String, Object>>) snap.get("shops"))
        {
            if (Long.valueOf(id).equals(KgJdbc.asLong(s.get("storeId"))))
            {
                return s;
            }
        }
        return null;
    }

    private Map<String, Object> liveSnapshot()
    {
        return service.recruitSnapshot(grants.get(0), MON, grants);
    }

    @Test
    void snapshotKeepsEveryKeyOldClientsRead()
    {
        managerOnStore1();
        cityShops();
        Map<String, Object> s = liveSnapshot();
        for (String k : Arrays.asList("works", "bizCols", "orderCols", "days", "thisWeek", "lastWeek", "range", "note"))
        {
            assertNotNull(s.get(k), k);
        }
        assertEquals(4, ((List<?>) s.get("works")).size());
        assertEquals(4, ((List<?>) s.get("bizCols")).size());
        assertEquals(4, ((List<?>) s.get("orderCols")).size());
        assertEquals(7, ((List<?>) s.get("days")).size());
        assertEquals(7, ((List<?>) s.get("thisWeek")).size());
        assertEquals(7, ((List<?>) s.get("lastWeek")).size());
        assertEquals("换工招募情况周报", s.get("title"));
        assertEquals("recruit", s.get("reportType"));
    }

    @Test
    void countsCoverWholeCityMoneyOnlyGrantedShops()
    {
        managerOnStore1();
        cityShops();
        Map<String, Object> s = liveSnapshot();
        List<?> biz = (List<?>) s.get("bizCols");
        assertEquals("6", ((Map<?, ?>) biz.get(0)).get("value"));
        assertEquals("3", ((Map<?, ?>) biz.get(1)).get("value"));
        assertEquals("¥200", ((Map<?, ?>) biz.get(2)).get("value"));
        assertEquals("¥50", ((Map<?, ?>) biz.get(3)).get("value"));
        assertEquals("+100.0%", ((Map<?, ?>) biz.get(2)).get("delta"));
        Map<String, Object> mine = shopIn(s, 1);
        assertEquals(true, mine.get("canSeeMoney"));
        assertEquals("¥200", mine.get("income"));
        Map<String, Object> other = shopIn(s, 99);
        assertEquals(false, other.get("canSeeMoney"));
        assertNull(other.get("income"));
        assertNull(other.get("incomeFen"));
        assertEquals(20000L, ((List<?>) s.get("thisWeek")).get(1));
        assertEquals(10000L, ((List<?>) s.get("lastWeek")).get(1));
        verify(db).list(contains("ledger = 'recruit'"), anyMap());
    }

    /** 只有他有授权的同城门店才算钱；别的城市的授权门店不进来 */
    @Test
    void moneyScopeIsSameCityManagerGrantsOnly()
    {
        managerOnStore1();
        grants.add(grant(2, "manager", "大理"));
        grants.add(grant(3, "keeper", "丽江"));
        grants.add(grant(4, "manager", "丽江"));
        cityShops();
        liveSnapshot();
        ArgumentCaptor<Map<String, Object>> ps = paramsCaptor();
        verify(db).list(contains("group by store_id, biz_date, direction"), ps.capture());
        assertEquals(Arrays.asList(1L, 4L), ps.getValue().get("s"));
    }

    @Test
    void shopWithLedgerButNoVolunteerJobStillGetsARow()
    {
        managerOnStore1();
        grants.add(grant(4, "manager", "丽江"));
        when(volunteers.weekShops(eq("丽江"), any())).thenReturn(new ArrayList<Map<String, Object>>());
        when(db.list(contains("group by store_id, biz_date, direction"), anyMap()))
                .thenReturn(Collections.singletonList(p("storeId", 4L, "bizDate", "2026-10-27", "direction", "income", "v", 700L)));
        Map<String, Object> s = liveSnapshot();
        assertEquals("¥7", shopIn(s, 4).get("income"));
        assertNull(shopIn(s, 1), "这家店没岗位也没账，不用占一行");
        assertEquals("¥7", ((Map<?, ?>) ((List<?>) s.get("bizCols")).get(2)).get("value"));
    }

    @Test
    void frozenSnapshotHidesMoneyTheViewerHasNoGrantFor()
    {
        managerOnStore1();
        grants.add(grant(99, "manager", "丽江"));
        when(volunteers.weekShops(eq("丽江"), any())).thenAnswer(i -> new ArrayList<Map<String, Object>>(Arrays.asList(shop(1, 2, 1, 3, 1), shop(99, 4, 2, 0, 0))));
        when(db.list(contains("group by store_id, biz_date, direction"), anyMap())).thenReturn(Arrays.asList(
                p("storeId", 1L, "bizDate", "2026-10-27", "direction", "income", "v", 20000L),
                p("storeId", 99L, "bizDate", "2026-10-27", "direction", "income", "v", 30000L)));
        String frozen = JSON.toJSONString(liveSnapshot());

        Map<String, Object> seenByFull = KgManageService.maskRecruit(JSON.parseObject(frozen), Arrays.asList(1L, 99L));
        assertEquals("¥500", ((Map<?, ?>) ((List<?>) seenByFull.get("bizCols")).get(2)).get("value"));
        assertEquals(7, ((List<?>) seenByFull.get("thisWeek")).size());

        Map<String, Object> seenByOther = KgManageService.maskRecruit(JSON.parseObject(frozen), Collections.singletonList(1L));
        assertEquals("¥200", ((Map<?, ?>) ((List<?>) seenByOther.get("bizCols")).get(2)).get("value"), "总数只算他能看的店");
        assertEquals(false, shopIn(seenByOther, 99).get("canSeeMoney"));
        for (String k : Arrays.asList("income", "expense", "incomeFen", "expenseFen", "lastIncomeFen", "lastExpenseFen"))
        {
            assertNull(shopIn(seenByOther, 99).get(k), k);
        }
        assertEquals(Collections.emptyList(), seenByOther.get("thisWeek"), "按日收入分不出店，有遮挡就不给趋势");
        assertEquals("6", ((Map<?, ?>) ((List<?>) seenByOther.get("bizCols")).get(0)).get("value"), "人数不是钱，不遮");
        assertFalse(JSON.toJSONString(seenByOther).contains("30000"));
    }

    // ------------------------------------------------------------------ 周报：列表

    @Test
    void managerWithManyStoresInACityGetsOneCardTitledRecruit()
    {
        managerOnStore1();
        grants.add(grant(4, "manager", "丽江"));
        cityShops();
        Map<String, Object> l = service.weeklyList(null, MON.toString(), "week");
        List<?> cards = (List<?>) l.get("cards");
        assertEquals(1, cards.size());
        Map<?, ?> c = (Map<?, ?>) cards.get(0);
        assertEquals(1L, c.get("storeId"));
        assertEquals("丽江换工招募", c.get("name"));
        assertEquals("recruit", c.get("reportType"));
        assertEquals(4, ((List<?>) c.get("cols")).size());
        assertEquals("换工招募情况周报", l.get("title"));
        assertEquals(Collections.emptyList(), l.get("anomalies"));
        assertEquals(1, ((List<?>) l.get("stores")).size());
        assertEquals("丽江换工招募", ((Map<?, ?>) ((List<?>) l.get("stores")).get(0)).get("storeName"));
        assertEquals(false, l.get("shopMode"));
        ArgumentCaptor<Map<String, Object>> ps = paramsCaptor();
        verify(db).one(contains("from kg_weekly_report where store_id = :s and week_start = :w and report_type = :rt"), ps.capture());
        assertEquals("recruit", ps.getValue().get("rt"));
    }

    /** 业务页带来的当前门店可能是自动授权的商家店：也要落到他这座城那一张，不是空列表 */
    @Test
    void filterBySecondStoreInCityStillFindsTheCard()
    {
        managerOnStore1();
        grants.add(grant(4, "manager", "丽江"));
        cityShops();
        Map<String, Object> l = service.weeklyList(4L, MON.toString(), "week");
        assertEquals(1, ((List<?>) l.get("cards")).size());
        assertEquals(1L, ((Map<?, ?>) ((List<?>) l.get("cards")).get(0)).get("storeId"));
    }

    @Test
    void managerInTwoCitiesGetsOneCardPerCity()
    {
        managerOnStore1();
        grants.add(grant(2, "manager", "大理"));
        when(volunteers.weekShops(anyString(), any())).thenAnswer(i -> new ArrayList<Map<String, Object>>());
        Map<String, Object> l = service.weeklyList(null, MON.toString(), "week");
        assertEquals(2, ((List<?>) l.get("cards")).size());
    }

    @Test
    void storeWithoutCityIsNotMergedWithOtherCitylessStores()
    {
        grants.add(grant(1, "manager", ""));
        grants.add(grant(2, "manager", "全国"));
        Map<String, Object> l = service.weeklyList(null, MON.toString(), "week");
        assertEquals(2, ((List<?>) l.get("cards")).size());
        verify(volunteers, never()).weekShops(anyString(), any());
    }

    @Test
    void storeModeListsVolunteerShopsAndHidesMoney()
    {
        managerOnStore1();
        cityShops();
        when(db.one(contains("from kg_weekly_report"), anyMap())).thenReturn(p("reportId", 55L, "status", "draft"));
        Map<String, Object> l = service.weeklyList(1L, MON.toString(), "store");
        assertEquals(true, l.get("shopMode"));
        List<?> cards = (List<?>) l.get("cards");
        assertEquals(2, cards.size());
        Map<?, ?> mine = (Map<?, ?>) cards.get(0);
        Map<?, ?> other = (Map<?, ?>) cards.get(1);
        assertEquals(1L, mine.get("storeId"));
        assertEquals(99L, other.get("storeId"), "老版本列表靠 storeId + weekStart 区分每一行");
        assertEquals(55L, other.get("reportId"), "点开进他这一周的那张周报");
        assertEquals("¥200", ((Map<?, ?>) mine.get("money")).get("income"));
        assertNull(other.get("money"));
        assertEquals(Arrays.asList("招募(人)", "到岗(人)", "缺口", "一周内离开"), labels(other));
        assertEquals(Arrays.asList("4", "2", "0", "0"), values(other));
    }

    private static List<Object> labels(Map<?, ?> card)
    {
        List<Object> out = new ArrayList<Object>();
        for (Object o : (List<?>) card.get("cols"))
        {
            out.add(((Map<?, ?>) o).get("label"));
        }
        return out;
    }

    private static List<Object> values(Map<?, ?> card)
    {
        List<Object> out = new ArrayList<Object>();
        for (Object o : (List<?>) card.get("cols"))
        {
            out.add(((Map<?, ?>) o).get("value"));
        }
        return out;
    }

    private void stubOpsStats()
    {
        Map<String, Long> works = new HashMap<String, Long>();
        for (String k : Arrays.asList("rooms", "jobs", "activities", "content"))
        {
            works.put(k, 0L);
        }
        Map<String, Long> apps = new HashMap<String, Long>();
        apps.put("n", 0L);
        Map<String, Long> good = new HashMap<String, Long>();
        when(stats.works(anyLong(), any(), any())).thenReturn(works);
        when(stats.revenue(anyList(), any(), any())).thenReturn(revenue());
        when(stats.applications(anyList(), any(), any())).thenReturn(apps);
        when(stats.goodRate(anyList(), any(), any())).thenReturn(good);
        when(stats.dailyRevenue(anyList(), any(), any())).thenReturn(new long[7]);
    }

    @Test
    void keeperListIsUnchanged()
    {
        grants.add(grant(1, "keeper", "丽江"));
        stubOpsStats();
        Map<String, Object> l = service.weeklyList(1L, MON.toString(), "week");
        assertEquals("代运营周报", l.get("title"));
        assertEquals("ops", ((Map<?, ?>) ((List<?>) l.get("cards")).get(0)).get("reportType"));
        assertEquals("总营收(元)", labels((Map<?, ?>) ((List<?>) l.get("cards")).get(0)).get(0));
        assertEquals("店1", ((Map<?, ?>) ((List<?>) l.get("stores")).get(0)).get("storeName"));
        ArgumentCaptor<Map<String, Object>> ps = paramsCaptor();
        verify(db).one(contains("report_type = :rt"), ps.capture());
        assertEquals("ops", ps.getValue().get("rt"));
        verify(volunteers, never()).weekShops(anyString(), any());
        Map<String, Object> store = service.weeklyList(1L, null, "store");
        assertEquals(6, ((List<?>) store.get("cards")).size());
        assertEquals(false, store.get("shopMode"));
    }

    @Test
    void mixedAccountGetsEachVersionPerStore()
    {
        grants.add(grant(1, "manager", "丽江"));
        grants.add(grant(2, "keeper", "大理"));
        stubOpsStats();
        when(volunteers.weekShops(anyString(), any())).thenAnswer(i -> new ArrayList<Map<String, Object>>());
        Map<String, Object> l = service.weeklyList(null, MON.toString(), "week");
        assertEquals("周报", l.get("title"));
        List<?> cards = (List<?>) l.get("cards");
        assertEquals("recruit", ((Map<?, ?>) cards.get(0)).get("reportType"));
        assertEquals("ops", ((Map<?, ?>) cards.get(1)).get("reportType"));
        assertEquals("换工招募情况周报", service.weeklyList(1L, MON.toString(), "week").get("title"));
        assertEquals("代运营周报", service.weeklyList(2L, MON.toString(), "week").get("title"));
    }

    // ------------------------------------------------------------------ 周报：汇总接口、打开、详情

    @Test
    void summaryOfUngrantedShopInMyCityShowsMyCityReport()
    {
        managerOnStore1();
        cityShops();
        when(db.one(contains("select city from kg_store"), anyMap())).thenReturn(p("city", "丽江"));
        Map<String, Object> s = service.weeklySummary(99L, MON.toString());
        assertEquals(1L, s.get("storeId"));
        assertEquals("丽江换工招募", s.get("storeName"));
        assertEquals("recruit", s.get("reportType"));
        assertEquals(MON.toString(), s.get("weekStart"));
    }

    @Test
    void summaryOfOtherCityOrForKeeperIsRefused()
    {
        managerOnStore1();
        when(db.one(contains("select city from kg_store"), anyMap())).thenReturn(p("city", "大理"));
        ServiceException e = assertThrows(ServiceException.class, () -> service.weeklySummary(99L, MON.toString()));
        assertEquals(403, e.getCode());
        grants.clear();
        grants.add(grant(1, "keeper", "丽江"));
        when(db.one(contains("select city from kg_store"), anyMap())).thenReturn(p("city", "丽江"));
        assertThrows(ServiceException.class, () -> service.weeklySummary(99L, MON.toString()));
    }

    @Test
    void openWritesTheRightTypeOnTheAnchorStore()
    {
        managerOnStore1();
        grants.add(grant(4, "manager", "丽江"));
        when(db.one(contains("for share"), anyMap())).thenReturn(p("reportId", 60L));
        when(db.must(contains("select * from kg_weekly_report"), anyMap(), anyString()))
                .thenReturn(p("reportId", 60L, "storeId", 1L, "weekStart", MON.toString(), "status", "draft", "reportType", "recruit", "version", 0L));
        when(db.list(contains("kg_weekly_item"), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        when(volunteers.weekShops(anyString(), any())).thenAnswer(i -> new ArrayList<Map<String, Object>>());
        Map<String, Object> d = service.weeklyOpen(4L, MON.toString());
        ArgumentCaptor<Map<String, Object>> row = paramsCaptor();
        verify(db).insert(eq("kg_weekly_report"), row.capture());
        assertEquals(1L, row.getValue().get("store_id"));
        assertEquals("recruit", row.getValue().get("report_type"));
        assertEquals("recruit", d.get("reportType"));
        assertEquals("换工招募情况周报", d.get("title"));
        assertEquals("丽江换工招募", d.get("storeName"));
    }

    @Test
    void keeperOpensOpsReportEvenWhenStoreHasARecruitOne()
    {
        grants.add(grant(1, "keeper", "丽江"));
        stubOpsStats();
        ArgumentCaptor<Map<String, Object>> ps = paramsCaptor();
        when(db.one(contains("for share"), anyMap())).thenReturn(p("reportId", 61L));
        when(db.must(contains("select * from kg_weekly_report"), anyMap(), anyString()))
                .thenReturn(p("reportId", 61L, "storeId", 1L, "weekStart", MON.toString(), "status", "draft", "reportType", "ops", "version", 0L));
        when(db.list(contains("kg_weekly_item"), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        Map<String, Object> d = service.weeklyOpen(1L, MON.toString());
        verify(db).insert(eq("kg_weekly_report"), ps.capture());
        assertEquals("ops", ps.getValue().get("report_type"));
        assertEquals("代运营周报", d.get("title"));
        assertEquals("店1", d.get("storeName"));
    }

    @Test
    void keeperAndStaffCannotOpenRecruitReport()
    {
        for (String role : Arrays.asList("keeper", "staff"))
        {
            grants.clear();
            grants.add(grant(1, role, "丽江"));
            when(db.must(contains("select * from kg_weekly_report"), anyMap(), anyString()))
                    .thenReturn(p("reportId", 60L, "storeId", 1L, "weekStart", MON.toString(), "status", "submitted", "reportType", "recruit", "version", 1L));
            assertEquals(403, assertThrows(ServiceException.class, () -> service.weeklyDetail(60L)).getCode(), role);
            assertEquals(403, assertThrows(ServiceException.class, () -> service.weeklySave(60L, p("version", 1L))).getCode(), role);
            assertEquals(403, assertThrows(ServiceException.class, () -> service.weeklySubmit(60L, p("version", 1L, "confirmed", true))).getCode(), role);
        }
    }

    /** 换工主理人以前写的代运营周报（比如后台退回的消息点进来）还能打开，不是 403 */
    @Test
    void managerCanStillOpenHisOldOpsReport()
    {
        managerOnStore1();
        stubOpsStats();
        when(db.must(contains("select * from kg_weekly_report"), anyMap(), anyString()))
                .thenReturn(p("reportId", 30L, "storeId", 1L, "weekStart", MON.toString(), "status", "returned", "reportType", "ops", "version", 1L));
        when(db.list(contains("kg_weekly_item"), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        Map<String, Object> d = service.weeklyDetail(30L);
        assertEquals("ops", d.get("reportType"));
        assertEquals("店1", d.get("storeName"));
    }

    @Test
    void detailMasksFrozenSnapshotForTheViewer()
    {
        managerOnStore1();
        grants.add(grant(99, "manager", "丽江"));
        when(volunteers.weekShops(eq("丽江"), any())).thenAnswer(i -> new ArrayList<Map<String, Object>>(Arrays.asList(shop(1, 2, 1, 3, 1), shop(99, 4, 2, 0, 0))));
        when(db.list(contains("group by store_id, biz_date, direction"), anyMap())).thenReturn(Collections.singletonList(
                p("storeId", 99L, "bizDate", "2026-10-27", "direction", "income", "v", 30000L)));
        String frozen = JSON.toJSONString(liveSnapshot());
        grants.remove(1);
        when(db.must(contains("select * from kg_weekly_report"), anyMap(), anyString()))
                .thenReturn(p("reportId", 60L, "storeId", 1L, "weekStart", MON.toString(), "status", "submitted", "reportType", "recruit", "version", 1L, "snapshot", frozen));
        when(db.list(contains("kg_weekly_item"), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        Map<String, Object> d = service.weeklyDetail(60L);
        assertFalse(JSON.toJSONString(d.get("snapshot")).contains("300"), "另一家店的钱不能露");
    }

    // ------------------------------------------------------------------ 周报：提交、退回、后台

    @Test
    void submitFreezesRecruitSnapshotAndNotifiesManagersOnly()
    {
        managerOnStore1();
        cityShops();
        when(db.must(contains("select * from kg_weekly_report"), anyMap(), anyString()))
                .thenReturn(p("reportId", 60L, "storeId", 1L, "weekStart", MON.toString(), "status", "draft", "reportType", "recruit", "version", 2L));
        when(db.update(contains("status = 'submitted'"), anyMap())).thenReturn(1);
        when(db.list(contains("kg_weekly_item"), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        when(db.list(contains("role_code in ('manager') and app_user_id <> :u"), anyMap())).thenReturn(Collections.singletonList(p("appUserId", 8L)));
        service.weeklySubmit(60L, p("version", 2L, "confirmed", true));
        ArgumentCaptor<Map<String, Object>> ps = paramsCaptor();
        verify(db).update(contains("status = 'submitted'"), ps.capture());
        Map<String, Object> frozen = JSON.parseObject(String.valueOf(ps.getValue().get("snap")));
        assertEquals("recruit", frozen.get("reportType"));
        assertNotNull(frozen.get("bizCols"));
        assertNotNull(frozen.get("shops"));
        verify(support).notifyStaff(eq(8L), eq("system"), eq("周报已提交"), contains("丽江换工招募"), eq("weekly_report"), eq(60L), contains("reportId=60"));
        verify(db, never()).list(contains("role_code in ('manager','keeper')"), anyMap());
    }

    @Test
    void returnOfRecruitReportDoesNotGoToKeepers()
    {
        when(db.must(contains("from kg_weekly_report"), anyMap(), anyString()))
                .thenReturn(p("storeId", 1L, "weekStart", MON.toString(), "status", "submitted", "reportType", "recruit"));
        when(db.list(contains("role_code in ('manager')"), anyMap())).thenReturn(Collections.singletonList(p("appUserId", 8L)));
        assertEquals("已退回", service.weeklyReturn(60L, "人数不对", "admin"));
        verify(support).notifyStaff(eq(8L), eq("system"), eq("周报被退回"), contains("人数不对"), eq("weekly_report"), eq(60L), anyString());
        verify(db, never()).list(contains("role_code in ('manager','keeper')"), anyMap());
    }

    @Test
    void returnOfOpsReportStillGoesToManagerAndKeeper()
    {
        when(db.must(contains("from kg_weekly_report"), anyMap(), anyString()))
                .thenReturn(p("storeId", 1L, "weekStart", MON.toString(), "status", "submitted", "reportType", "ops"));
        when(db.list(contains("role_code in ('manager','keeper')"), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        service.weeklyReturn(30L, "数不对", "admin");
        verify(db).list(contains("role_code in ('manager','keeper')"), anyMap());
    }

    @Test
    void adminReviewContextShowsFrozenRecruitFigures()
    {
        managerOnStore1();
        cityShops();
        String frozen = JSON.toJSONString(liveSnapshot());
        when(db.must(contains("left join kg_store"), anyMap(), anyString())).thenReturn(p("reportId", 60L, "storeId", 1L, "storeName", "店1", "weekStart", MON.toString(),
                "status", "submitted", "reportType", "recruit", "snapshot", frozen, "note", "", "risk", "", "proofs", ""));
        when(db.list(contains("kg_weekly_item"), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        Map<String, Object> c = service.weeklyReviewContext(60L);
        String text = String.valueOf(c.get("text"));
        assertTrue(text.contains("换工招募情况周报"), text);
        assertTrue(text.contains("招募换工 6 人"), text);
        assertTrue(text.contains("本周收入 ¥200"), text);
        assertTrue(text.contains("换工店99：招募 4"), text);
        assertEquals("换工招募情况周报内容", c.get("title"));
    }

    @Test
    void adminEntitiesCarryTypeAndLedger()
    {
        KgManageModule module = new KgManageModule();
        ExtEntity weekly = null;
        ExtEntity daily = null;
        for (ExtEntity e : module.entities())
        {
            if ("weeklyreport".equals(e.key))
            {
                weekly = e;
            }
            if ("dailyentry".equals(e.key))
            {
                daily = e;
            }
        }
        assertEquals("周报", weekly.title);
        assertTrue(weekly.fields.stream().anyMatch(f -> "reportType".equals(f.name)));
        assertTrue(daily.fields.stream().anyMatch(f -> "ledger".equals(f.name)));
    }
}
