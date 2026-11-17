package com.ruoyi.kangaroo.ext.community;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
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
 * 员工端修改活动不能清掉后台填的介绍 / 安全说明 / 报名截止 / 口号（不连库）。
 * 2026-10-03 审计发现：员工端「保存修改」「更换封面」不带这几项，staffSave 却整行更新，改一次标题就把它们写成空。
 */
class KgActivityStaffEditKeepFieldsTest
{
    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static String at(int days, int hours)
    {
        return LocalDateTime.now().plusDays(days).plusHours(hours).withSecond(0).withNano(0).format(F);
    }

    private final String start = at(3, 0);
    private final String end = at(3, 2);
    private final String deadline = at(2, 0);

    private KgActivityService activities;
    private KgJdbc db;

    @BeforeEach
    void setUp()
    {
        activities = new KgActivityService();
        db = mock(KgJdbc.class);
        KgCommunityGuard guard = mock(KgCommunityGuard.class);
        when(guard.initialStatus(any())).thenReturn("online");
        // 已有报名的活动改了标题 / 介绍时会重新过一遍内容检查（10-13 起合规就能保存）；这里的文字都是合规的
        when(guard.check(any())).thenReturn("pass");
        ReflectionTestUtils.setField(activities, "db", db);
        ReflectionTestUtils.setField(activities, "guard", guard);
        ReflectionTestUtils.setField(activities, "support", mock(StaffSupport.class));
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

    /** 库里这场活动：后台填过介绍、安全说明、报名截止、口号 */
    private void stored(long signed)
    {
        Map<String, Object> a = new HashMap<String, Object>();
        a.put("activityId", 5L); a.put("storeId", 102L); a.put("source", "yard"); a.put("feeMode", "free"); a.put("feeFen", 0L);
        a.put("status", "open"); a.put("signed", signed); a.put("capacity", 10L);
        a.put("startTime", start); a.put("endTime", end); a.put("deadline", deadline); a.put("place", "门店前台"); a.put("title", "测试活动");
        a.put("intro", "后台写的活动介绍"); a.put("safetyNote", "后台写的安全说明"); a.put("slogan", "后台口号");
        when(db.one(contains("from kg_activity where activity_id"), anyMap())).thenReturn(a);
    }

    /** 员工端表单里有的几项（标题、时间、地点、人数、费用、封面、类型） */
    private Map<String, Object> formBody(String startTime, String endTime)
    {
        Map<String, Object> b = new HashMap<String, Object>();
        b.put("title", "改了标题"); b.put("place", "门店前台"); b.put("capacity", 10); b.put("category", "手作课程");
        b.put("startTime", startTime.substring(0, 16)); b.put("endTime", endTime.substring(0, 16)); b.put("feeYuan", "0");
        return b;
    }

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
    void editWithoutTheseKeysLeavesStoredValuesUntouched()
    {
        stored(2L);
        Map<String, Object> cols = savedCols(formBody(start, end));
        // 不在要写的列里 = 这几列保持库里的原值
        assertFalse(cols.containsKey("intro"));
        assertFalse(cols.containsKey("safety_note"));
        assertFalse(cols.containsKey("deadline"));
        assertFalse(cols.containsKey("slogan"));
        assertEquals("改了标题", cols.get("title"));
    }

    @Test
    void editThatSendsTheKeysWritesThem()
    {
        stored(0L);
        Map<String, Object> b = formBody(start, end);
        b.put("intro", "新的介绍"); b.put("safetyNote", "新的安全说明"); b.put("deadline", at(1, 0).substring(0, 16)); b.put("slogan", "新口号");
        Map<String, Object> cols = savedCols(b);
        assertEquals("新的介绍", cols.get("intro"));
        assertEquals("新的安全说明", cols.get("safety_note"));
        assertNotNull(cols.get("deadline"));
        assertEquals("新口号", cols.get("slogan"));
    }

    @Test
    void explicitEmptyDeadlineClearsIt()
    {
        // 页面在「开始时间提前到原截止之前」时经员工确认后传空，表示改成开始前都可报名
        stored(0L);
        Map<String, Object> b = formBody(start, end);
        b.put("deadline", "");
        Map<String, Object> cols = savedCols(b);
        assertTrue(cols.containsKey("deadline"));
        assertNull(cols.get("deadline"));
    }

    @Test
    void keptDeadlineMustStillBeBeforeNewStart()
    {
        stored(0L);
        // 开始时间提前到原报名截止（2 天后）之前：不能留下「开始之后才截止」的活动
        ServiceException e = assertThrows(ServiceException.class, () -> activities.staffSave(staff(), 5L, formBody(at(1, 0), at(1, 2))));
        assertEquals(Integer.valueOf(422), e.getCode());
        assertTrue(e.getMessage().contains("报名截止"));
        verify(db, never()).updateRow(anyString(), anyString(), any(), anyMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    void createStillWritesAllColumns()
    {
        // 新建不变：没填的介绍 / 安全说明写空、截止为空（开始前都可报名）
        when(db.insert(eq("kg_activity"), anyMap())).thenThrow(new IllegalStateException("stop"));
        assertThrows(IllegalStateException.class, () -> activities.staffSave(staff(), null, formBody(start, end)));
        ArgumentCaptor<Map<String, Object>> cols = ArgumentCaptor.forClass(Map.class);
        verify(db).insert(eq("kg_activity"), cols.capture());
        assertEquals("", cols.getValue().get("intro"));
        assertEquals("", cols.getValue().get("safety_note"));
        assertTrue(cols.getValue().containsKey("deadline"));
        assertNull(cols.getValue().get("deadline"));
    }
}
