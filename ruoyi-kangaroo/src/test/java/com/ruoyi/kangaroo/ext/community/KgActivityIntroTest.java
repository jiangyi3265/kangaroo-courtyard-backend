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
 * 员工端「创建活动」可以填活动介绍（2026-10-16）：新建、修改都保存介绍，去首尾空白、最多 1000 字（超了直接 422 说清楚，不再悄悄截断），
 * 和标题、地点、安全说明一起过内容检查（不连库）。
 */
class KgActivityIntroTest
{
    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static String at(int days, int hours)
    {
        return LocalDateTime.now().plusDays(days).plusHours(hours).withSecond(0).withNano(0).format(F);
    }

    private static final String INTRO = "14:00 门店一楼集合\n老师先示范，再一起动手做\n请穿方便活动的衣服";

    private KgActivityService activities;
    private KgJdbc db;
    private KgCommunityGuard guard;

    @BeforeEach
    void setUp()
    {
        activities = new KgActivityService();
        db = mock(KgJdbc.class);
        guard = mock(KgCommunityGuard.class);
        when(guard.initialStatus(any())).thenReturn("online");
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

    private Map<String, Object> form(String intro)
    {
        Map<String, Object> b = new HashMap<String, Object>();
        b.put("title", "周末手作"); b.put("place", "门店一楼手作区"); b.put("capacity", 10); b.put("category", "手作课程");
        b.put("startTime", at(3, 0)); b.put("endTime", at(3, 2)); b.put("feeYuan", "0");
        b.put("intro", intro);
        return b;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> created(Map<String, Object> body)
    {
        when(db.insert(eq("kg_activity"), anyMap())).thenThrow(new IllegalStateException("stop"));
        assertThrows(IllegalStateException.class, () -> activities.staffSave(staff(), null, body));
        ArgumentCaptor<Map<String, Object>> cols = ArgumentCaptor.forClass(Map.class);
        verify(db).insert(eq("kg_activity"), cols.capture());
        return cols.getValue();
    }

    @Test
    void createSavesTheIntroWithLineBreaksAndChecksIt()
    {
        Map<String, Object> cols = created(form("\n  " + INTRO + "  \n"));
        assertEquals(INTRO, cols.get("intro"), "保留中间的换行，只去首尾空白");
        verify(guard).check("周末手作", INTRO, "门店一楼手作区", "");
    }

    @Test
    void thousandCharactersFit()
    {
        StringBuilder s = new StringBuilder();
        while (s.length() < KgActivityService.INTRO_MAX) s.append('介');
        assertEquals(1000, KgActivityService.INTRO_MAX);
        assertEquals(s.toString(), created(form(s.toString())).get("intro"));
    }

    @Test
    void longerIntroIsRejectedNotTruncated()
    {
        StringBuilder s = new StringBuilder();
        while (s.length() <= KgActivityService.INTRO_MAX) s.append('介');
        ServiceException e = assertThrows(ServiceException.class, () -> activities.staffSave(staff(), null, form(s.toString())));
        assertEquals(Integer.valueOf(422), e.getCode());
        assertTrue(e.getMessage().contains("活动介绍最多 1000 字"), e.getMessage());
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    void blockedWordsInTheIntroAreRejected()
    {
        when(guard.check(any(), any(), any(), any())).thenThrow(new ServiceException("内容包含不允许发布的信息，请修改后再发", 422));
        assertThrows(ServiceException.class, () -> activities.staffSave(staff(), null, form(INTRO)));
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    void editSavesTheNewIntro()
    {
        Map<String, Object> a = new HashMap<String, Object>();
        a.put("activityId", 5L); a.put("storeId", 102L); a.put("source", "yard"); a.put("feeMode", "free"); a.put("feeFen", 0L);
        a.put("status", "open"); a.put("signed", 0L); a.put("capacity", 10L); a.put("title", "周末手作"); a.put("place", "门店一楼手作区");
        a.put("startTime", at(3, 0) + ":00"); a.put("endTime", at(3, 2) + ":00"); a.put("intro", "旧介绍");
        when(db.one(contains("from kg_activity where activity_id"), anyMap())).thenReturn(a);
        when(db.updateRow(eq("kg_activity"), eq("activity_id"), any(), anyMap())).thenThrow(new IllegalStateException("stop"));
        assertThrows(IllegalStateException.class, () -> activities.staffSave(staff(), 5L, form(INTRO)));
        ArgumentCaptor<Map<String, Object>> cols = ArgumentCaptor.forClass(Map.class);
        verify(db).updateRow(eq("kg_activity"), eq("activity_id"), eq(5L), cols.capture());
        assertEquals(INTRO, cols.getValue().get("intro"));
    }

    @Test
    void signedActivityResavingAnOldIntroWithTrailingNewlineIsNotTreatedAsEdited()
    {
        // 已有人报名：没改的介绍不重查（库里老介绍末尾多个换行，现在保存前去首尾空白，不能因此算成「改过」）
        Map<String, Object> a = new HashMap<String, Object>();
        a.put("activityId", 5L); a.put("storeId", 102L); a.put("source", "yard"); a.put("feeMode", "free"); a.put("feeFen", 0L);
        a.put("status", "open"); a.put("signed", 2L); a.put("capacity", 10L); a.put("title", "周末手作"); a.put("place", "门店一楼手作区");
        String start = at(3, 0), end = at(3, 2);
        a.put("startTime", start + ":00"); a.put("endTime", end + ":00"); a.put("intro", "后台写的介绍 电话 13800138000\n");
        when(db.one(contains("from kg_activity where activity_id"), anyMap())).thenReturn(a);
        // 整段内容检查：含电话 → 需人工审核；「这次改过的文字」为空时的检查 → 通过
        when(guard.check()).thenReturn("pass");
        when(guard.check(any(), any(), any(), any())).thenReturn("review");
        when(db.updateRow(eq("kg_activity"), eq("activity_id"), any(), anyMap())).thenThrow(new IllegalStateException("stop"));
        Map<String, Object> b = form("后台写的介绍 电话 13800138000\n");
        b.put("startTime", start); b.put("endTime", end);
        assertThrows(IllegalStateException.class, () -> activities.staffSave(staff(), 5L, b));
        verify(guard).check();
    }
}
