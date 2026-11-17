package com.ruoyi.kangaroo.controller.app;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.PutMapping;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.KgStaffJobService;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.security.StaffGrantView;

/**
 * PUT /app/staff/jobs/{jobId}/conditions（10-16 跟进：给已发岗位补填筛选条件）的把关，和改工作地点同一套：
 * 真实的 StaffSupport（身份、权限矩阵）+ 真实的岗位服务，只把库换成假的。
 * 账号在 1 号店是主理人、2 号店是普通员工、4 号店是线上任务专员，3 号店没有授权；身份按岗位所属门店取。
 */
class AppStaffJobConditionsEndpointTest
{
    private final AppStaffOpsController controller = new AppStaffOpsController();
    private final KgJdbc db = mock(KgJdbc.class);
    private final StaffSupport support = new StaffSupport();
    private final KgStaffJobService jobs = new KgStaffJobService();

    private static StaffGrantView grant(Long storeId, String role)
    {
        StaffGrantView g = new StaffGrantView();
        g.setStoreId(storeId); g.setStoreName("店" + storeId); g.setRoleCode(role); g.setActive(true);
        return g;
    }

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(support, "db", db);
        ReflectionTestUtils.setField(jobs, "db", db);
        ReflectionTestUtils.setField(jobs, "support", support);
        ReflectionTestUtils.setField(controller, "db", db);
        ReflectionTestUtils.setField(controller, "support", support);
        ReflectionTestUtils.setField(controller, "jobs", jobs);
        AppLoginUser me = new AppLoginUser();
        me.setUserId(9001L); me.setNickname("多店账号");
        me.setStaffGrants(new ArrayList<StaffGrantView>(Arrays.asList(grant(1L, "manager"), grant(2L, "staff"), grant(4L, "online_staff"))));
        AppUserContext.set(me);
        job(11L, 1L, "volunteer");
        job(12L, 2L, "volunteer");
        job(13L, 3L, "volunteer");
        job(14L, 1L, "online");
        job(15L, 4L, "volunteer");
        when(db.updateRow(eq("kg_job"), eq("job_id"), any(), anyMap())).thenReturn(1);
    }

    private void job(Long jobId, Long storeId, String type)
    {
        when(db.one(contains("select store_id from kg_job"), argThat(m -> m != null && jobId.equals(m.get("id"))))).thenReturn(p("storeId", storeId));
        when(db.must(contains("from kg_job"), argThat(m -> m != null && jobId.equals(m.get("id"))), anyString()))
                .thenReturn(p("jobId", jobId, "storeId", storeId, "title", "岗位" + jobId, "jobType", type, "boardType", "all", "status", "published"));
    }

    @AfterEach
    void cleanup()
    {
        AppUserContext.remove();
    }

    private static Map<String, Object> body()
    {
        return p("genderPref", "F", "restMode", "5-2", "roomType", "double", "minDays", 15);
    }

    private int code(Long jobId)
    {
        ServiceException e = assertThrows(ServiceException.class, () -> controller.jobConditions(jobId, body()));
        return e.getCode() == null ? 500 : e.getCode();
    }

    @Test
    @SuppressWarnings("unchecked")
    void managerOfTheJobsStoreCanFillThem()
    {
        AjaxResult r = controller.jobConditions(11L, body());
        assertEquals(200, r.get("code"));
        assertEquals("已保存", r.get("msg"));
        Map<String, Object> data = (Map<String, Object>) r.get("data");
        assertEquals("女", data.get("genderPrefLabel"));
        assertEquals("做五休二", data.get("restModeLabel"));
        assertEquals("双人间", data.get("roomTypeLabel"));
        assertEquals(15, data.get("minDays"));
        verify(db).updateRow(eq("kg_job"), eq("job_id"), eq(11L), argThat(cols -> "F".equals(cols.get("gender_pref")) && "5-2".equals(cols.get("rest_mode"))
                && "double".equals(cols.get("room_type")) && Integer.valueOf(15).equals(cols.get("min_days")) && !cols.containsKey("status")));
        // 操作日志记在岗位所属的 1 号店
        verify(db).insert(eq("kg_staff_op_log"), argThat(row -> Long.valueOf(1L).equals(row.get("store_id")) && "job_conditions".equals(row.get("action"))
                && "1".equals(row.get("ok"))));
    }

    @Test
    void ordinaryStaffCanEditOnlyTheJobsOwnStore()
    {
        // 当前员工权限已包含岗位编辑；操作仍必须归于岗位所属的 2 号店。
        assertEquals(200, controller.jobConditions(12L, body()).get("code"));
        verify(db).updateRow(eq("kg_job"), eq("job_id"), eq(12L), anyMap());
        verify(db).insert(eq("kg_staff_op_log"), argThat(row -> Long.valueOf(2L).equals(row.get("store_id"))
                && "job_conditions".equals(row.get("action")) && "1".equals(row.get("ok"))));
    }

    @Test
    void jobOfAnotherStoreIsRefused()
    {
        assertEquals(403, code(13L));
        verify(db, never()).updateRow(anyString(), anyString(), any(), anyMap());
    }

    @Test
    void onlineTaskSpecialistCannotEditOfflineJobs()
    {
        assertEquals(403, code(15L));
        verify(db, never()).updateRow(anyString(), anyString(), any(), anyMap());
        // 接口不对专员开放（类上 staff = true，方法上没有 onlineStaff = true）：拦截器会先把专员授权去掉
        Method m = Arrays.stream(AppStaffOpsController.class.getMethods()).filter(x -> "jobConditions".equals(x.getName())).findFirst().get();
        assertNotNull(m.getAnnotation(AppAuth.class));
        assertFalse(m.getAnnotation(AppAuth.class).onlineStaff());
        assertTrue(m.getAnnotation(AppAuth.class).merchant());
        assertFalse(AppStaffOpsController.class.getAnnotation(AppAuth.class).onlineStaff());
        assertArrayEquals(new String[] { "/jobs/{jobId}/conditions" }, m.getAnnotation(PutMapping.class).value());
    }

    @Test
    void onlineTasksHaveNoConditions()
    {
        ServiceException e = assertThrows(ServiceException.class, () -> controller.jobConditions(14L, body()));
        assertEquals(Integer.valueOf(422), e.getCode());
        assertEquals("线上任务没有这些条件", e.getMessage());
        verify(db, never()).updateRow(anyString(), anyString(), any(), anyMap());
    }

    @Test
    void unknownJobIsRefused()
    {
        assertThrows(ServiceException.class, () -> controller.jobConditions(99L, body()));
        verify(db, never()).updateRow(anyString(), anyString(), any(), anyMap());
    }
}
