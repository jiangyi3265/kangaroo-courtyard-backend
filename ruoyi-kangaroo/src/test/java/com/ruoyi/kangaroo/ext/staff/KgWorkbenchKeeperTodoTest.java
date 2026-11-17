package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.AppLoginUser;

/**
 * 小院主理人的工作台不再显示换工内容（Luna 备忘录）：报名类只留线上任务，保险、调度整类不要，活动和退款保留；别的角色结果不变。
 * 只测 Java 这一层拼的 SQL 和取舍（不连库）。
 */
class KgWorkbenchKeeperTodoTest
{
    private static final String ONLINE_ONLY = "(j.job_type = 'online' or :onlineOnly = 0)";

    private final KgJdbc db = mock(KgJdbc.class);
    private final StaffSupport support = mock(StaffSupport.class);
    private final KgWorkbenchService service = new KgWorkbenchService();
    private final StaffSupport.Ctx ctx = new StaffSupport.Ctx();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "support", support);
        ctx.storeId = 102L;
        ctx.storeName = "喜洲小院";
        ctx.me = new AppLoginUser();
        ctx.me.setUserId(1L);
        when(support.ctx(102L)).thenReturn(ctx);
        when(support.ctxForStore(102L)).thenReturn(ctx);
        String soon = StaffSupport.fmt(new Date(System.currentTimeMillis() + 3600000L), "yyyy-MM-dd HH:mm:ss");
        when(db.list(contains("a.status = 'submitted'"), anyMap())).thenReturn(Arrays.asList(p("appId", 1L, "appNo", "KA0001", "realName", "老王", "submitTime", soon, "urgentFlag", "0", "title", "探店图文")));
        when(db.list(contains("from kg_stay_order"), anyMap())).thenReturn(Arrays.asList(p("orderId", 2L, "orderNo", "S1", "guestName", "小李", "refundApplyTime", soon, "refundFen", 100L)));
        when(db.list(contains("from kg_activity a"), anyMap())).thenReturn(Arrays.asList(p("activityId", 3L, "title", "手作", "startTime", soon, "n", 1L)));
        when(db.list(contains("from kg_insurance_apply"), anyMap())).thenReturn(Arrays.asList(p("applyId", 4L, "insuredName", "小张", "planName", "意外险", "startDate", "2026-11-01", "createTime", soon)));
        when(db.list(contains("hired_count < headcount"), anyMap())).thenReturn(Arrays.asList(p("jobId", 5L, "title", "前台换工", "headcount", 3L, "hiredCount", 1L, "startDate", "2026-11-01")));
    }

    private List<String> types()
    {
        List<String> out = new ArrayList<String>();
        for (Map<String, Object> m : service.collect(102L))
        {
            out.add((String) m.get("type"));
        }
        return out;
    }

    @Test
    void keeperKeepsOnlineApplicationsOrdersAndActivitiesOnly()
    {
        ctx.role = StaffRole.KEEPER;
        assertEquals(Arrays.asList("app_review", "stay_refund", "activity"), types());
        verify(db, never()).list(contains("from kg_insurance_apply"), anyMap());
        verify(db, never()).list(contains("hired_count < headcount"), anyMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    void keeperApplicationQueriesAreLimitedToOnlineJobs()
    {
        ctx.role = StaffRole.KEEPER;
        service.collect(102L);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> ps = ArgumentCaptor.forClass(Map.class);
        verify(db, atLeastOnce()).list(sql.capture(), ps.capture());
        int limited = 0;
        for (int i = 0; i < sql.getAllValues().size(); i++)
        {
            String s = sql.getAllValues().get(i);
            if (s.contains("from kg_application") || s.contains("from kg_interview"))
            {
                assertTrue(s.contains(ONLINE_ONLY), s);
                assertEquals(1, ps.getAllValues().get(i).get("onlineOnly"));
                limited++;
            }
        }
        assertEquals(3, limited, "待审核、待面试、待确认录用这三类都要限");
    }

    private int assignError(String key)
    {
        return assertThrows(com.ruoyi.common.exception.ServiceException.class, () -> service.assign(102L, key, 9L)).getCode();
    }

    @Test
    void whatAKeeperCannotSeeCannotBeAssignedToThem()
    {
        ctx.role = StaffRole.MANAGER;
        when(db.one(contains("from kg_staff_grant g join"), anyMap())).thenReturn(p("userId", 9L, "nickname", "小院主理人", "roleCode", StaffRole.KEEPER));
        when(db.one(contains("from kg_application a join kg_job j"), anyMap())).thenReturn(p("jobType", "volunteer"));
        assertEquals(422, assignError("app_review:1"), "换工报名：他的待办里没有");
        assertEquals(422, assignError("insurance:4"));
        assertEquals(422, assignError("dispatch:5"));
        verify(support, never()).notifyStaff(anyLong(), anyString(), anyString(), anyString(), anyString(), any(), anyString());

        when(db.one(contains("from kg_application a join kg_job j"), anyMap())).thenReturn(p("jobType", "online"));
        assertEquals("已转派给 小院主理人", service.assign(102L, "app_review:1", 9L), "线上任务的报名他看得到");
        assertEquals("已转派给 小院主理人", service.assign(102L, "activity:3", 9L));

        // 转给别的员工不受影响
        when(db.one(contains("from kg_staff_grant g join"), anyMap())).thenReturn(p("userId", 9L, "nickname", "员工", "roleCode", StaffRole.STAFF));
        when(db.one(contains("from kg_application a join kg_job j"), anyMap())).thenReturn(p("jobType", "volunteer"));
        assertEquals("已转派给 员工", service.assign(102L, "app_review:1", 9L));
    }

    @Test
    void theCandidateListLeavesTheKeeperOutOnlyWhenAsked()
    {
        when(db.list(contains("from kg_staff_grant g"), anyMap())).thenReturn(Arrays.asList(
                p("appUserId", 8L, "roleCode", StaffRole.KEEPER, "nickname", "小院主理人"), p("appUserId", 9L, "roleCode", StaffRole.STAFF, "nickname", "员工")));
        assertEquals(2, service.staffs(102L, false).size());
        List<Map<String, Object>> without = service.staffs(102L, true);
        assertEquals(1, without.size());
        assertEquals(9L, without.get(0).get("userId"));
    }

    @Test
    void otherRolesStillGetEverything()
    {
        for (String role : Arrays.asList(StaffRole.MANAGER, StaffRole.STAFF))
        {
            ctx.role = role;
            assertTrue(types().containsAll(Arrays.asList("app_review", "activity", "insurance", "dispatch")), role);
        }
        verify(db, atLeastOnce()).list(contains(ONLINE_ONLY), argThat(m -> Integer.valueOf(0).equals(m.get("onlineOnly"))));
        verify(db, never()).list(contains(ONLINE_ONLY), argThat(m -> Integer.valueOf(1).equals(m.get("onlineOnly"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void tabsFollowTheRole()
    {
        when(db.list(contains("from kg_todo_meta"), anyMap())).thenReturn(Collections.<Map<String, Object>>emptyList());
        ctx.role = StaffRole.KEEPER;
        Map<String, Object> keeper = service.todos(102L, null, null);
        assertEquals(Arrays.asList("全部", "报名", "订单", "活动"), new ArrayList<String>(((Map<String, Integer>) keeper.get("counts")).keySet()));
        assertFalse(String.valueOf(keeper.get("slaNote")).contains("保险"));

        ctx.role = StaffRole.MANAGER;
        Map<String, Object> manager = service.todos(102L, null, null);
        assertEquals(Arrays.asList("全部", "报名", "订单", "保险", "活动", "调度"), new ArrayList<String>(((Map<String, Integer>) manager.get("counts")).keySet()));
        assertTrue(String.valueOf(manager.get("slaNote")).contains("保险办理 24 小时"));
    }
}
