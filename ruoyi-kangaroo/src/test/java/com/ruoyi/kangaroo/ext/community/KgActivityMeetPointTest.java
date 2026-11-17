package com.ruoyi.kangaroo.ext.community;

import java.math.BigDecimal;
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
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.StaffGrantView;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 活动集合点地图坐标（V49 kg_activity.meet_lat / meet_lng，10-16 反馈「点集合地点唤不起地图」）：不连库。
 * 员工建 / 改活动、用户发起活动都收 meetLat / meetLng：要么都有、要么都空，必须在国内范围；没带这两个键 = 不改。
 * 已有人报名时：原来没标的可以补标、可以清掉，标过的不能挪到别处。卡片 / 详情把坐标原样带回。
 */
class KgActivityMeetPointTest
{
    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static String at(int days, int hours)
    {
        return LocalDateTime.now().plusDays(days).plusHours(hours).withSecond(0).withNano(0).format(F);
    }

    private final String start = at(3, 0);
    private final String end = at(3, 2);

    private KgActivityService activities;
    private KgJdbc db;
    private KgCommunityGuard guard;

    @BeforeEach
    void setUp()
    {
        activities = new KgActivityService();
        db = mock(KgJdbc.class);
        guard = mock(KgCommunityGuard.class);
        when(guard.initialStatus(any())).thenReturn("review");
        when(guard.check(any())).thenReturn("pass");
        when(guard.mode()).thenReturn("manual");
        ReflectionTestUtils.setField(activities, "db", db);
        ReflectionTestUtils.setField(activities, "guard", guard);
        ReflectionTestUtils.setField(activities, "support", mock(StaffSupport.class));
        KgCommunityFeature feature = mock(KgCommunityFeature.class);
        when(feature.enabled()).thenReturn(true);
        ReflectionTestUtils.setField(activities, "communityFeature", feature);
        Map<String, Object> s = new HashMap<String, Object>();
        s.put("storeName", "测试门店"); s.put("city", "平潭"); s.put("opType", "direct");
        when(db.must(contains("from kg_store"), anyMap(), anyString())).thenReturn(s);
    }

    private StaffSupport.Ctx staff()
    {
        StaffSupport.Ctx c = new StaffSupport.Ctx();
        c.storeId = 102L; c.me = new AppLoginUser(); c.me.setUserId(9001L);
        StaffGrantView g = new StaffGrantView(); g.setStoreId(102L);
        c.me.setStaffGrants(Collections.singletonList(g));
        return c;
    }

    private Map<String, Object> body()
    {
        Map<String, Object> b = new HashMap<String, Object>();
        b.put("title", "万圣节集合"); b.put("place", "平潭袋鼠小院门口"); b.put("capacity", 10); b.put("category", "小型聚会");
        b.put("startTime", start.substring(0, 16)); b.put("endTime", end.substring(0, 16)); b.put("feeYuan", "0");
        return b;
    }

    private void stored(long signed, Object lat, Object lng)
    {
        Map<String, Object> a = new HashMap<String, Object>();
        a.put("activityId", 5L); a.put("storeId", 102L); a.put("source", "yard"); a.put("feeMode", "free"); a.put("feeFen", 0L);
        a.put("status", "open"); a.put("signed", signed); a.put("capacity", 10L);
        a.put("startTime", start); a.put("endTime", end); a.put("place", "平潭袋鼠小院门口"); a.put("title", "万圣节集合");
        a.put("meetLat", lat); a.put("meetLng", lng);
        when(db.one(contains("from kg_activity where activity_id"), anyMap())).thenReturn(a);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> savedCols(Map<String, Object> b)
    {
        when(db.updateRow(eq("kg_activity"), eq("activity_id"), any(), anyMap())).thenThrow(new IllegalStateException("stop"));
        assertThrows(IllegalStateException.class, () -> activities.staffSave(staff(), 5L, b));
        ArgumentCaptor<Map<String, Object>> cols = ArgumentCaptor.forClass(Map.class);
        verify(db).updateRow(eq("kg_activity"), eq("activity_id"), eq(5L), cols.capture());
        return cols.getValue();
    }

    // ---------------------------------------------------------------- 校验

    @Test
    void absentKeysLeaveColumnsAlone()
    {
        Map<String, Object> cols = new LinkedHashMap<String, Object>();
        KgActivityService.meetPoint(new HashMap<String, Object>(), cols);
        assertFalse(cols.containsKey("meet_lat"));
        assertFalse(cols.containsKey("meet_lng"));
    }

    @Test
    void validPointIsStoredWithSixDecimals()
    {
        Map<String, Object> b = new HashMap<String, Object>();
        b.put("meetLat", 25.5101234567); b.put("meetLng", "119.7912");
        Map<String, Object> cols = new LinkedHashMap<String, Object>();
        KgActivityService.meetPoint(b, cols);
        assertEquals(new BigDecimal("25.510123"), cols.get("meet_lat"));
        assertEquals(new BigDecimal("119.791200"), cols.get("meet_lng"));
    }

    @Test
    void bothEmptyClearsThePoint()
    {
        Map<String, Object> b = new HashMap<String, Object>();
        b.put("meetLat", null); b.put("meetLng", "");
        Map<String, Object> cols = new LinkedHashMap<String, Object>();
        KgActivityService.meetPoint(b, cols);
        assertTrue(cols.containsKey("meet_lat"));
        assertNull(cols.get("meet_lat"));
        assertNull(cols.get("meet_lng"));
    }

    @Test
    void halfAPointIsRejected()
    {
        Map<String, Object> b = new HashMap<String, Object>();
        b.put("meetLat", "25.51");
        ServiceException e = assertThrows(ServiceException.class, () -> KgActivityService.meetPoint(b, new LinkedHashMap<String, Object>()));
        assertEquals(Integer.valueOf(422), e.getCode());
        assertTrue(e.getMessage().contains("同时"));
    }

    @Test
    void pointsOutsideChinaOrGarbageAreRejected()
    {
        // 经纬度写反（119, 25）、0,0、海外、乱码都不收
        Object[][] bad = { { "119.79", "25.51" }, { 0, 0 }, { "48.85", "2.35" }, { "-33.8", "151.2" }, { "abc", "119.7" } };
        for (Object[] p : bad)
        {
            Map<String, Object> b = new HashMap<String, Object>();
            b.put("meetLat", p[0]); b.put("meetLng", p[1]);
            ServiceException e = assertThrows(ServiceException.class, () -> KgActivityService.meetPoint(b, new LinkedHashMap<String, Object>()), p[0] + "," + p[1]);
            assertEquals(Integer.valueOf(422), e.getCode());
        }
        // 南海、漠河、喀什、抚远都在范围内
        Object[][] ok = { { "16.83", "112.33" }, { "53.47", "122.37" }, { "39.47", "75.99" }, { "48.36", "134.29" } };
        for (Object[] p : ok)
        {
            Map<String, Object> b = new HashMap<String, Object>();
            b.put("meetLat", p[0]); b.put("meetLng", p[1]);
            Map<String, Object> cols = new LinkedHashMap<String, Object>();
            KgActivityService.meetPoint(b, cols);
            assertNotNull(cols.get("meet_lat"), p[0] + "," + p[1]);
        }
    }

    // ---------------------------------------------------------------- 员工端

    @Test
    @SuppressWarnings("unchecked")
    void staffCreateWritesThePoint()
    {
        when(db.insert(eq("kg_activity"), anyMap())).thenThrow(new IllegalStateException("stop"));
        Map<String, Object> b = body();
        b.put("meetLat", "25.510123"); b.put("meetLng", "119.791200");
        assertThrows(IllegalStateException.class, () -> activities.staffSave(staff(), null, b));
        ArgumentCaptor<Map<String, Object>> cols = ArgumentCaptor.forClass(Map.class);
        verify(db).insert(eq("kg_activity"), cols.capture());
        assertEquals(new BigDecimal("25.510123"), cols.getValue().get("meet_lat"));
        assertEquals(new BigDecimal("119.791200"), cols.getValue().get("meet_lng"));
    }

    @Test
    void staffEditWithoutKeysKeepsStoredPoint()
    {
        // 「更换封面」等整条保存不带坐标：不能把已标的集合点清掉
        stored(3L, new BigDecimal("25.510123"), new BigDecimal("119.791200"));
        Map<String, Object> cols = savedCols(body());
        assertFalse(cols.containsKey("meet_lat"));
        assertFalse(cols.containsKey("meet_lng"));
    }

    @Test
    void signedUpActivityMayAddAPointItNeverHad()
    {
        // 线上万圣节那种：已经有人报名、没标过点，员工补标后报名的人就能点开地图
        stored(3L, null, null);
        Map<String, Object> b = body();
        b.put("meetLat", "25.510123"); b.put("meetLng", "119.791200");
        Map<String, Object> cols = savedCols(b);
        assertEquals(new BigDecimal("25.510123"), cols.get("meet_lat"));
        assertEquals("open", cols.get("status"), "补标坐标不转审核，报名中的还是报名中");
    }

    @Test
    void signedUpActivityCannotMoveItsPoint()
    {
        stored(3L, new BigDecimal("25.510123"), new BigDecimal("119.791200"));
        Map<String, Object> b = body();
        b.put("meetLat", "25.600000"); b.put("meetLng", "119.791200");
        ServiceException e = assertThrows(ServiceException.class, () -> activities.staffSave(staff(), 5L, b));
        assertEquals(Integer.valueOf(409), e.getCode());
        assertTrue(e.getMessage().contains("集合点"));
        verify(db, never()).updateRow(anyString(), anyString(), any(), anyMap());
    }

    @Test
    void signedUpActivityMaySendTheSamePointOrClearIt()
    {
        stored(3L, new BigDecimal("25.510123"), new BigDecimal("119.791200"));
        Map<String, Object> same = body();
        same.put("meetLat", 25.510123); same.put("meetLng", 119.7912);
        assertEquals(new BigDecimal("25.510123"), savedCols(same).get("meet_lat"));

        setUp();
        stored(3L, new BigDecimal("25.510123"), new BigDecimal("119.791200"));
        Map<String, Object> clear = body();
        clear.put("meetLat", null); clear.put("meetLng", null);
        Map<String, Object> cols = savedCols(clear);
        assertTrue(cols.containsKey("meet_lat"));
        assertNull(cols.get("meet_lat"));
    }

    @Test
    void noSignupsMayMoveThePoint()
    {
        stored(0L, new BigDecimal("25.510123"), new BigDecimal("119.791200"));
        Map<String, Object> b = body();
        b.put("meetLat", "26.000000"); b.put("meetLng", "119.500000");
        assertEquals(new BigDecimal("26.000000"), savedCols(b).get("meet_lat"));
    }

    // ---------------------------------------------------------------- 用户发起

    @Test
    @SuppressWarnings("unchecked")
    void userCreateWritesThePointAndRejectsHalfPoints()
    {
        Map<String, Object> active = new HashMap<String, Object>();
        active.put("status", "0"); active.put("delFlag", "0");
        when(db.one(contains("from kg_app_user where user_id"), anyMap())).thenReturn(active);
        Map<String, Object> u = new HashMap<String, Object>();
        u.put("displayName", "小满"); u.put("city", "平潭");
        when(guard.user(anyLong())).thenReturn(u);
        when(db.insert(eq("kg_activity"), anyMap())).thenThrow(new IllegalStateException("stop"));

        Map<String, Object> b = body();
        b.put("city", "平潭"); b.put("meetLat", "25.510123"); b.put("meetLng", "119.791200");
        assertThrows(IllegalStateException.class, () -> activities.createByUser(7L, b, null));
        ArgumentCaptor<Map<String, Object>> cols = ArgumentCaptor.forClass(Map.class);
        verify(db).insert(eq("kg_activity"), cols.capture());
        assertEquals(new BigDecimal("119.791200"), cols.getValue().get("meet_lng"));

        Map<String, Object> half = body();
        half.put("city", "平潭"); half.put("meetLng", "119.791200");
        ServiceException e = assertThrows(ServiceException.class, () -> activities.createByUser(7L, half, null));
        assertEquals(Integer.valueOf(422), e.getCode());
    }

    @Test
    void userCreateWithoutAPointStillWorks()
    {
        Map<String, Object> active = new HashMap<String, Object>();
        active.put("status", "0"); active.put("delFlag", "0");
        when(db.one(contains("from kg_app_user where user_id"), anyMap())).thenReturn(active);
        Map<String, Object> u = new HashMap<String, Object>();
        u.put("displayName", "小满"); u.put("city", "平潭");
        when(guard.user(anyLong())).thenReturn(u);
        when(db.insert(eq("kg_activity"), argThat(m -> m != null && !m.containsKey("meet_lat")))).thenReturn(88L);
        Map<String, Object> b = body();
        b.put("city", "平潭");
        assertEquals(Long.valueOf(88L), activities.createByUser(7L, b, null));
    }

    // ---------------------------------------------------------------- 卡片 / 详情带回坐标

    @Test
    void detailReturnsThePointAndDeadline()
    {
        Map<String, Object> a = new HashMap<String, Object>();
        a.put("activityId", 5L); a.put("source", "user"); a.put("status", "open"); a.put("signed", 1L); a.put("capacity", 10L);
        a.put("startTime", start); a.put("endTime", end); a.put("deadline", at(2, 0)); a.put("place", "古城南门"); a.put("title", "夜游");
        a.put("appUserId", 7L); a.put("meetLat", new BigDecimal("25.510123")); a.put("meetLng", new BigDecimal("119.791200"));
        when(db.one(contains("from kg_activity where activity_id"), anyMap())).thenReturn(a);
        Map<String, Object> m = activities.detail(null, 5L, "other", null);
        assertEquals(new BigDecimal("25.510123"), m.get("meetLat"));
        assertEquals(new BigDecimal("119.791200"), m.get("meetLng"));
        assertTrue(String.valueOf(m.get("deadlineText")).matches("\\d+月\\d+日 \\d{2}:\\d{2}"));

        a.put("meetLat", null); a.put("meetLng", null); a.put("deadline", null);
        Map<String, Object> none = activities.detail(null, 5L, "other", null);
        assertTrue(none.containsKey("meetLat"));
        assertNull(none.get("meetLat"));
        assertEquals("开始前", none.get("deadlineText"));
    }
}
