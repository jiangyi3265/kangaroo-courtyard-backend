package com.ruoyi.kangaroo.ext.manage;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.security.StaffGrantView;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 门店任务列表分组（2026-10-14 回归 R3-7 / R3-8）：
 * 已结束、没人认领的任务不再留在「待认领」；自己认领的任务（名额没满）在自己的「进行中」里并标「我已认领」；
 * 发布时时段已经过去的任务被拒，不再通知换工。
 */
class KgTaskListBucketTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final StaffSupport support = mock(StaffSupport.class);
    private final KgTaskService service = new KgTaskService();
    private final String tomorrow = LocalDate.now().plusDays(1).toString();
    private final String yesterday = LocalDate.now().minusDays(1).toString();

    private static Map<String, Object> task(long id, String date, long quota, long taken, long doing, long done, String myStatus)
    {
        Map<String, Object> t = p("taskId", id, "title", "任务" + id, "assignMode", "volunteer", "status", "published", "taskDate", date,
                "startTime", "08:00", "endTime", "23:59", "quota", quota, "pending", 0L, "doing", doing, "done", done, "taken", taken,
                "priority", "medium", "place", "", "storeName", "小院", "roleName", "");
        t.put("myStatus", myStatus);
        return t;
    }

    @BeforeEach void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "support", support);
        // 在店换工：没有员工授权，按入职过的门店取任务
        AppLoginUser vol = new AppLoginUser();
        vol.setUserId(7151L);
        AppUserContext.set(vol);
        when(db.list(contains("select distinct store_id from kg_application"), anyMap())).thenReturn(Collections.singletonList(p("storeId", 5L)));
        List<Map<String, Object>> rows = Arrays.asList(
                task(157L, tomorrow, 3, 1, 1, 0, "accepted"),   // 我认领了 1/3，名额没满
                task(158L, tomorrow, 2, 0, 0, 0, null),         // 别的还能认领的任务
                task(168L, yesterday, 2, 0, 0, 0, null));       // 已结束、没人认领
        when(db.list(contains("from kg_task t join kg_store s"), anyMap())).thenReturn(rows);
    }

    @AfterEach void clear() { AppUserContext.remove(); }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> vol(Map<String, Object> m) { return (List<Map<String, Object>>) m.get("volunteerTasks"); }

    @SuppressWarnings("unchecked")
    private long count(Map<String, Object> m, int i) { return ((Number) ((List<Map<String, Object>>) m.get("tabs")).get(i).get("count")).longValue(); }

    @Test void openTabHasOnlyTasksTheViewerCanStillClaim()
    {
        Map<String, Object> m = service.list(null, null, "open");
        assertEquals(Collections.singletonList(158L), vol(m).stream().map(r -> r.get("taskId")).collect(java.util.stream.Collectors.toList()));
        assertEquals("", vol(m).get(0).get("mine"));
        assertEquals(1L, count(m, 0));
        assertEquals(1L, count(m, 1));
        assertEquals(1L, count(m, 2));
        // 查询带上看的人，按人分组
        verify(db).list(contains("a.app_user_id = :me"), argThat(ps -> Long.valueOf(7151L).equals(ps.get("me"))));
    }

    @Test void ownClaimedTaskIsInProgressAndMarked()
    {
        Map<String, Object> m = service.list(null, null, "doing");
        assertEquals(1, vol(m).size());
        assertEquals(157L, vol(m).get(0).get("taskId"));
        assertEquals("我已认领", vol(m).get(0).get("mine"));
        assertEquals("1/3", vol(m).get(0).get("claimed"));
    }

    @Test void endedUnclaimedTaskLeavesOpenAndIsMarkedEnded()
    {
        Map<String, Object> m = service.list(null, null, "done");
        assertEquals(1, vol(m).size());
        assertEquals(168L, vol(m).get(0).get("taskId"));
        assertEquals("【已结束】任务168", vol(m).get(0).get("title"));
    }

    private Map<String, Object> body(String endTime, boolean publish)
    {
        return p("storeId", 5L, "title", "早班布草", "taskDate", LocalDate.now().toString(), "startTime", "00:00", "endTime", endTime,
                "estHours", 1, "assignMode", "volunteer", "quota", 2, "publish", publish);
    }

    private void asManager()
    {
        StaffGrantView g = new StaffGrantView();
        g.setStoreId(5L); g.setRoleCode("manager"); g.setActive(true);
        AppLoginUser mgr = new AppLoginUser();
        mgr.setUserId(1L); mgr.setNickname("主理人"); mgr.setStaffGrants(Collections.singletonList(g));
        AppUserContext.set(mgr);
        StaffSupport.Ctx c = new StaffSupport.Ctx();
        c.me = mgr; c.storeId = 5L; c.role = "manager";
        when(support.ctx(any())).thenReturn(c);
        when(db.one(contains("select g.role_code from kg_staff_grant"), anyMap())).thenReturn(p("roleCode", "manager"));
    }

    @SuppressWarnings("unchecked")
    @Test void ownDoneTaskIsDoneUnlessAPublisherStillHasOthersToCheck()
    {
        asManager();
        Map<String, Object> t = task(170L, tomorrow, 2, 2, 1, 1, "done");
        t.put("assignMode", "staff");
        when(db.list(contains("from kg_task t join kg_store s"), anyMap())).thenReturn(Collections.singletonList(t));
        // 管家 / 主理人自己已验收、另一位还在做：仍在「进行中」，他要在那里验收别人
        List<Map<String, Object>> doing = (List<Map<String, Object>>) service.list(5L, null, "doing").get("staffTasks");
        assertEquals(1, doing.size());
        assertEquals("我已完成", doing.get(0).get("mine"));
        // 员工同样有发布、验收权限，自己完成后仍需要处理其他人的任务。
        when(db.one(contains("select g.role_code from kg_staff_grant"), anyMap())).thenReturn(p("roleCode", "staff"));
        List<Map<String, Object>> staffDoing = (List<Map<String, Object>>) service.list(5L, null, "doing").get("staffTasks");
        assertEquals(1, staffDoing.size());
        assertEquals(170L, staffDoing.get(0).get("taskId"));
        assertTrue(((List<?>)service.list(5L, null, "done").get("staffTasks")).isEmpty());
        // 全部执行人完成后，发布者也进入「已完成」。
        t.put("doing", 0L); t.put("done", 2L);
        assertEquals(1, ((List<?>)service.list(5L, null, "done").get("staffTasks")).size());
    }

    @Test void publishingATaskWhoseTimeHasPassedIsRefusedAndNobodyIsNotified()
    {
        LocalTime now = LocalTime.now();
        assumeTrue(now.isAfter(LocalTime.of(0, 2)));
        asManager();
        String ended = String.format("%02d:%02d", now.minusMinutes(1).getHour(), now.minusMinutes(1).getMinute());
        ServiceException e = assertThrows(ServiceException.class, () -> service.save(null, body(ended, true), true));
        assertEquals(Integer.valueOf(422), e.getCode());
        verify(db, never()).insert(anyString(), anyMap());
        verify(support, never()).notifyUser(any(), anyString(), anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test void aDraftWithAPassedTimeCanStillBeSaved()
    {
        LocalTime now = LocalTime.now();
        assumeTrue(now.isAfter(LocalTime.of(0, 2)));
        asManager();
        String ended = String.format("%02d:%02d", now.minusMinutes(1).getHour(), now.minusMinutes(1).getMinute());
        when(db.insert(eq("kg_task"), anyMap())).thenReturn(99L);
        assertEquals("草稿已保存", service.save(null, body(ended, false), false).get("message"));
    }
}
