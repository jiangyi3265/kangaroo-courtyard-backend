package com.ruoyi.kangaroo.ext.community;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.ext.user.KgCityService;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.StaffGrantView;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 活动来源 / 收费方式跟门店类型走、编辑不悄悄改收费方式、城市按业务城市归一（不连库） */
class KgActivityFeeModeTest
{
    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final String start = LocalDateTime.now().plusDays(3).withSecond(0).withNano(0).format(F);
    private final String end = LocalDateTime.now().plusDays(3).plusHours(2).withSecond(0).withNano(0).format(F);

    private KgActivityService activities;
    private KgJdbc db;

    @BeforeEach
    void setUp()
    {
        activities = new KgActivityService();
        db = mock(KgJdbc.class);
        when(db.one(eq("select status, del_flag from kg_app_user where user_id=:u for update"), anyMap()))
                .thenReturn(KgJdbc.p("status", "0", "delFlag", "0"));
        KgCommunityGuard guard = mock(KgCommunityGuard.class);
        // 审核结果固定为"直接上线"，测的是收费方式而不是内容审核
        when(guard.initialStatus(any())).thenReturn("online");
        // 已有报名的活动改了标题 / 介绍时会重新过一遍内容检查（10-13 起合规就能保存）；这里的文字都是合规的
        when(guard.check(any())).thenReturn("pass");
        ReflectionTestUtils.setField(activities, "db", db);
        ReflectionTestUtils.setField(activities, "guard", guard);
        ReflectionTestUtils.setField(activities, "support", mock(StaffSupport.class));
    }

    private void store(String opType)
    {
        Map<String, Object> s = new HashMap<String, Object>();
        s.put("storeName", "测试门店"); s.put("city", "平潭"); s.put("opType", opType);
        when(db.must(contains("from kg_store"), anyMap(), anyString())).thenReturn(s);
    }

    private Map<String, Object> adminCols(String feeMode, long fee)
    {
        Map<String, Object> c = new LinkedHashMap<String, Object>();
        c.put("store_id", 102L); c.put("title", "测试活动"); c.put("place", "门店前台");
        c.put("start_time", start); c.put("end_time", end); c.put("capacity", 10L);
        c.put("fee_fen", fee); c.put("fee_mode", feeMode);
        return c;
    }

    @Test
    void adminCreateDerivesSourceAndAllowedFeeModeFromStoreType()
    {
        store("managed");
        Map<String, Object> ok = adminCols("offline", 5000L);
        activities.prepareAdminCreate(ok, true);
        assertEquals("merchant", ok.get("source"));
        Map<String, Object> free = adminCols("free", 0L);
        activities.prepareAdminCreate(free, true);
        assertEquals("merchant", free.get("source"));
        ServiceException e = assertThrows(ServiceException.class, () -> activities.prepareAdminCreate(adminCols("online", 5000L), true));
        assertTrue(e.getMessage().contains("到场支付"));
        Map<String, Object> picked = adminCols("free", 0L);
        picked.put("source", "yard");
        assertThrows(ServiceException.class, () -> activities.prepareAdminCreate(picked, true));

        store("direct");
        Map<String, Object> yard = adminCols("online", 5000L);
        activities.prepareAdminCreate(yard, true);
        assertEquals("yard", yard.get("source"));
        e = assertThrows(ServiceException.class, () -> activities.prepareAdminCreate(adminCols("offline", 5000L), true));
        assertTrue(e.getMessage().contains("在线支付"));
    }

    private StaffSupport.Ctx staff()
    {
        StaffSupport.Ctx c = new StaffSupport.Ctx();
        c.storeId = 102L; c.me = new AppLoginUser(); c.me.setUserId(9001L);
        StaffGrantView g = new StaffGrantView(); g.setStoreId(102L);
        c.me.setStaffGrants(Collections.singletonList(g));
        return c;
    }

    private Map<String, Object> activity(String source, String feeMode, long fee, long signed)
    {
        Map<String, Object> a = new HashMap<String, Object>();
        a.put("activityId", 5L); a.put("storeId", 102L); a.put("source", source); a.put("feeMode", feeMode); a.put("feeFen", fee);
        a.put("feeIncludes", "含材料"); a.put("status", "open"); a.put("signed", signed); a.put("capacity", 10L);
        a.put("startTime", start); a.put("endTime", end); a.put("place", "门店前台"); a.put("title", "测试活动");
        when(db.one(contains("from kg_activity where activity_id"), anyMap())).thenReturn(a);
        return a;
    }

    private Map<String, Object> editBody(String feeYuan)
    {
        Map<String, Object> b = new HashMap<String, Object>();
        b.put("title", "测试活动"); b.put("place", "门店前台"); b.put("capacity", 10); b.put("startTime", start.substring(0, 16));
        b.put("endTime", end.substring(0, 16)); b.put("feeYuan", feeYuan); b.put("intro", "只改了介绍");
        return b;
    }

    /** 跑到落库那一步就停：拿到要写的列 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> savedCols(Map<String, Object> body)
    {
        when(db.updateRow(eq("kg_activity"), eq("activity_id"), any(), anyMap())).thenThrow(new IllegalStateException("stop"));
        assertThrows(IllegalStateException.class, () -> activities.staffSave(staff(), 5L, body));
        ArgumentCaptor<Map<String, Object>> cols = ArgumentCaptor.forClass(Map.class);
        verify(db).updateRow(eq("kg_activity"), eq("activity_id"), eq(5L), cols.capture());
        return cols.getValue();
    }

    @Test
    void staffEditKeepsStoredFeeModeEvenIfStoreTypeSaysOtherwise()
    {
        store("direct");
        activity("yard", "offline", 3000L, 2L);
        Map<String, Object> cols = savedCols(editBody("30"));
        // 直营门店以前会被重算成 online，已报名的人到场付、之后的人要先在线付
        assertEquals("offline", cols.get("fee_mode"));
        assertFalse(cols.containsKey("fee_includes"));
    }

    @Test
    void staffEditWithSignupsRejectsFeeIncludesChange()
    {
        store("direct");
        activity("yard", "online", 3000L, 2L);
        Map<String, Object> b = editBody("30");
        b.put("feeIncludes", "不含材料");
        ServiceException e = assertThrows(ServiceException.class, () -> activities.staffSave(staff(), 5L, b));
        assertEquals(Integer.valueOf(409), e.getCode());
        verify(db, never()).updateRow(anyString(), anyString(), any(), anyMap());
    }

    @Test
    void feeChangeWithoutSignupsFollowsActivitySourceNotCurrentStoreType()
    {
        store("managed");
        activity("yard", "free", 0L, 0L);
        assertEquals("online", savedCols(editBody("30")).get("fee_mode"));
    }

    @Test
    void paidToPaidKeepsMode()
    {
        store("direct");
        activity("yard", "offline", 3000L, 0L);
        assertEquals("offline", savedCols(editBody("40")).get("fee_mode"));
    }

    // ---------------------------------------------------------------- 2026-11-03 费用三选一：免费 / 收费 / AA制

    /** 跑到新建落库那一步就停：拿到要写的列 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> createdCols(Runnable save)
    {
        when(db.insert(eq("kg_activity"), anyMap())).thenThrow(new IllegalStateException("stop"));
        assertThrows(IllegalStateException.class, save::run);
        ArgumentCaptor<Map<String, Object>> cols = ArgumentCaptor.forClass(Map.class);
        verify(db).insert(eq("kg_activity"), cols.capture());
        return cols.getValue();
    }

    private Map<String, Object> createBody(String kind, String feeYuan)
    {
        Map<String, Object> b = editBody(feeYuan);
        if (kind != null) b.put("feeKind", kind);
        b.put("category", "户外活动");
        return b;
    }

    @Test
    void staffCreateAaNeverChargesAndKeepsEstimate()
    {
        store("direct");
        Map<String, Object> b = createBody("aa", "30");
        b.put("aaYuan", "80");
        b.put("feeIncludes", "门票、拼车");
        Map<String, Object> cols = createdCols(() -> activities.staffSave(staff(), null, b));
        assertEquals("aa", cols.get("fee_mode"));
        assertEquals(0L, cols.get("fee_fen"), "AA 制不收报名费，填了金额也记 0");
        assertEquals(8000L, cols.get("aa_fen"));
        assertEquals("门票、拼车", cols.get("fee_includes"));
        assertEquals("户外活动", cols.get("category"));
    }

    @Test
    void staffCreatePaidNeedsPositivePriceAndFollowsSource()
    {
        store("direct");
        ServiceException e = assertThrows(ServiceException.class, () -> activities.staffSave(staff(), null, createBody("paid", "0")));
        assertEquals(Integer.valueOf(422), e.getCode());
        assertThrows(ServiceException.class, () -> activities.staffSave(staff(), null, createBody("weird", "10")));
        Map<String, Object> cols = createdCols(() -> activities.staffSave(staff(), null, createBody("paid", "88.5")));
        assertEquals("online", cols.get("fee_mode"));
        assertEquals(8850L, cols.get("fee_fen"));
        assertNull(cols.get("aa_fen"));
    }

    @Test
    void staffCreateFreeIgnoresAmountAndLegacyTypesBecomeOther()
    {
        store("managed");
        Map<String, Object> b = createBody("free", "50");
        b.put("category", "手作课程");
        Map<String, Object> cols = createdCols(() -> activities.staffSave(staff(), null, b));
        assertEquals("free", cols.get("fee_mode"));
        assertEquals(0L, cols.get("fee_fen"));
        assertEquals("其他", cols.get("category"), "新建只能选四个类型");
    }

    @Test
    void oldClientWithoutFeeKindKeepsAaAndLegacyCategory()
    {
        store("direct");
        Map<String, Object> a = activity("yard", "aa", 0L, 2L);
        a.put("aaFen", 8000L); a.put("category", "讲座沙龙"); a.put("feeIncludes", null);
        Map<String, Object> b = editBody("0");
        b.put("category", "讲座沙龙");
        Map<String, Object> cols = savedCols(b);
        assertEquals("aa", cols.get("fee_mode"), "老版本「换封面」整条保存不能把 AA 变成免费");
        assertEquals(8000L, cols.get("aa_fen"));
        assertEquals("讲座沙龙", cols.get("category"), "老活动的老类型原样保留");
    }

    @Test
    void signedActivityCannotSwitchBetweenFreeAndAa()
    {
        store("direct");
        activity("yard", "free", 0L, 2L);
        Map<String, Object> b = editBody("0");
        b.put("feeKind", "aa");
        ServiceException e = assertThrows(ServiceException.class, () -> activities.staffSave(staff(), 5L, b));
        assertEquals(Integer.valueOf(409), e.getCode());
    }

    @Test
    void userPaidActivityIsOfflineAndLegacyUserFeeStillRejected()
    {
        KgCommunityGuard guard = (KgCommunityGuard) ReflectionTestUtils.getField(activities, "guard");
        Map<String, Object> u = new HashMap<String, Object>();
        u.put("displayName", "小林"); u.put("city", "平潭");
        when(guard.user(any())).thenReturn(u);
        Map<String, Object> legacy = editBody("0");
        legacy.remove("feeYuan"); legacy.put("fee", 30);
        assertThrows(ServiceException.class, () -> activities.createByUser(9002L, legacy, null));
        Map<String, Object> b = createBody("paid", "60");
        b.put("city", "平潭");
        Map<String, Object> cols = createdCols(() -> activities.createByUser(9002L, b, null));
        assertEquals("offline", cols.get("fee_mode"), "用户活动的收费只是标价，平台不代收");
        assertEquals(6000L, cols.get("fee_fen"));
        assertEquals("user", cols.get("source"));
    }

    @Test
    void onlyYardOnlinePaidActivitiesCollectMoney()
    {
        Map<String, Object> a = new HashMap<String, Object>();
        a.put("source", "yard"); a.put("feeMode", "online");
        assertTrue(KgActivityService.collectsOnline(a, 3000L));
        assertFalse(KgActivityService.collectsOnline(a, 0L));
        a.put("feeMode", "aa");
        assertFalse(KgActivityService.collectsOnline(a, 3000L), "AA 制哪怕金额被写坏了也不建支付单");
        a.put("feeMode", "offline");
        assertFalse(KgActivityService.collectsOnline(a, 3000L));
        a.put("feeMode", "online"); a.put("source", "user");
        assertFalse(KgActivityService.collectsOnline(a, 3000L));
    }

    @Test
    void priceAndModeLabels()
    {
        Map<String, Object> a = new HashMap<String, Object>();
        a.put("feeMode", "free"); a.put("feeFen", 0L);
        assertEquals("免费", KgActivityService.priceText(a));
        assertEquals("免费", KgActivityService.feeModeLabel(a));
        assertEquals("free", KgActivityService.feeKind(a));
        a.put("feeMode", "aa");
        assertEquals("AA制", KgActivityService.priceText(a));
        assertEquals("现场AA", KgActivityService.feeModeLabel(a));
        a.put("aaFen", 8050L);
        assertEquals("约¥80.50 AA", KgActivityService.priceText(a));
        assertEquals("aa", KgActivityService.feeKind(a));
        a.put("feeMode", "online"); a.put("feeFen", 5000L); a.put("source", "yard");
        assertEquals("¥50", KgActivityService.priceText(a));
        assertEquals("在线支付", KgActivityService.feeModeLabel(a));
        a.put("feeMode", "offline"); a.put("source", "user");
        assertEquals("到场付给发起人", KgActivityService.feeModeLabel(a));
        a.put("source", "merchant");
        assertEquals("到场支付", KgActivityService.feeModeLabel(a));
        assertEquals("paid", KgActivityService.feeKind(a));
    }

    @Test
    void adminCreateAcceptsAaWithZeroFee()
    {
        store("managed");
        Map<String, Object> aa = adminCols("aa", 0L);
        aa.put("aa_fen", 6000L);
        activities.prepareAdminCreate(aa, true);
        assertEquals(6000L, aa.get("aa_fen"));
        assertThrows(ServiceException.class, () -> activities.prepareAdminCreate(adminCols("aa", 5000L), true));
        Map<String, Object> free = adminCols("free", 0L);
        free.put("aa_fen", 6000L);
        activities.prepareAdminCreate(free, true);
        assertNull(free.get("aa_fen"), "不是 AA 不留预估人均");
    }

    @Test
    void subscriptionAndListUseBusinessCity()
    {
        KgCityService cities = mock(KgCityService.class);
        when(cities.businessCity(anyString())).thenAnswer(i -> "思明区".equals(i.getArgument(0)) ? "厦门" : i.getArgument(0));
        ReflectionTestUtils.setField(activities, "cities", cities);
        activities.subscribe(9002L, "思明区", true);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> ps = ArgumentCaptor.forClass(Map.class);
        verify(db).update(contains("insert ignore into kg_activity_subscribe"), ps.capture());
        assertEquals("厦门", ps.getValue().get("c"));

        when(db.list(anyString(), anyMap())).thenReturn(Collections.emptyList());
        activities.list(null, null, "思明区", null);
        verify(db).list(contains("a.city = :c"), argThat(m -> "厦门".equals(m.get("c"))));
        activities.hots("思明区");
        verify(db, atLeastOnce()).list(contains("a.status = 'open'"), argThat(m -> "厦门".equals(m.get("c"))));
    }
}
