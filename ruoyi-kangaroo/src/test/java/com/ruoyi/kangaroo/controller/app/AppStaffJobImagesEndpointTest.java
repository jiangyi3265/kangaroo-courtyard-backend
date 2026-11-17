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
import com.ruoyi.kangaroo.testsupport.UploadedImageFixture;

/**
 * PUT /app/staff/jobs/{jobId}/images（V49：换已发岗位的照片）的把关，和改工作地点、补填筛选条件同一套：
 * 真实的 StaffSupport（身份、权限矩阵）+ 真实的岗位服务，只把库换成假的。
 * 账号在 1 号店是主理人、2 号店是普通员工、4 号店是线上任务专员，3 号店没有授权；身份按岗位所属门店取。
 */
class AppStaffJobImagesEndpointTest
{
    private static final String A = "/profile/upload/2026/10/16/a1.jpg";

    private final AppStaffOpsController controller = new AppStaffOpsController();
    private final KgJdbc db = mock(KgJdbc.class);
    private final StaffSupport support = new StaffSupport();
    private final KgStaffJobService jobs = new KgStaffJobService();
    private UploadedImageFixture imageFiles;

    private static StaffGrantView grant(Long storeId, String role)
    {
        StaffGrantView g = new StaffGrantView();
        g.setStoreId(storeId); g.setStoreName("店" + storeId); g.setRoleCode(role); g.setActive(true);
        return g;
    }

    @BeforeEach
    void setup()
    {
        imageFiles = new UploadedImageFixture(A);
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
                .thenReturn(p("jobId", jobId, "storeId", storeId, "title", "岗位" + jobId, "jobType", type, "status", "published"));
    }

    @AfterEach
    void cleanup()
    {
        AppUserContext.remove();
        imageFiles.close();
    }

    private static Map<String, Object> body()
    {
        return p("images", Arrays.asList(A));
    }

    private int code(Long jobId)
    {
        ServiceException e = assertThrows(ServiceException.class, () -> controller.jobImages(jobId, body()));
        return e.getCode() == null ? 500 : e.getCode();
    }

    @Test
    @SuppressWarnings("unchecked")
    void managerOfTheJobsStoreCanReplaceThePhotos()
    {
        AjaxResult r = controller.jobImages(11L, body());
        assertEquals(200, r.get("code"));
        assertEquals("照片已更新", r.get("msg"));
        Map<String, Object> data = (Map<String, Object>) r.get("data");
        assertEquals(Arrays.asList(A), data.get("images"));
        assertEquals(A, data.get("cover"));
        verify(db).updateRow(eq("kg_job"), eq("job_id"), eq(11L), argThat(cols -> ("[\"" + A + "\"]").equals(cols.get("images")) && A.equals(cols.get("cover"))
                && !cols.containsKey("status")));
        verify(db).insert(eq("kg_staff_op_log"), argThat(row -> Long.valueOf(1L).equals(row.get("store_id")) && "job_images".equals(row.get("action"))
                && "1".equals(row.get("ok"))));
    }

    @Test
    void ordinaryStaffCanEditOnlyTheJobsOwnStore()
    {
        // 当前员工权限已包含岗位编辑；操作仍必须归于岗位所属的 2 号店。
        assertEquals(200, controller.jobImages(12L, body()).get("code"));
        verify(db).updateRow(eq("kg_job"), eq("job_id"), eq(12L), anyMap());
        verify(db).insert(eq("kg_staff_op_log"), argThat(row -> Long.valueOf(2L).equals(row.get("store_id"))
                && "job_images".equals(row.get("action")) && "1".equals(row.get("ok"))));
    }

    @Test
    void jobOfAnotherStoreIsRefused()
    {
        assertEquals(403, code(13L));
        verify(db, never()).updateRow(anyString(), anyString(), any(), anyMap());
    }

    @Test
    void onlineTaskSpecialistCannotChangePhotos()
    {
        assertEquals(403, code(15L));
        verify(db, never()).updateRow(anyString(), anyString(), any(), anyMap());
        Method m = Arrays.stream(AppStaffOpsController.class.getMethods()).filter(x -> "jobImages".equals(x.getName())).findFirst().get();
        assertNotNull(m.getAnnotation(AppAuth.class));
        assertFalse(m.getAnnotation(AppAuth.class).onlineStaff());
        assertTrue(m.getAnnotation(AppAuth.class).merchant());
        assertFalse(AppStaffOpsController.class.getAnnotation(AppAuth.class).onlineStaff());
        assertArrayEquals(new String[] { "/jobs/{jobId}/images" }, m.getAnnotation(PutMapping.class).value());
    }

    @Test
    void onlineTasksHaveNoPhotos()
    {
        ServiceException e = assertThrows(ServiceException.class, () -> controller.jobImages(14L, body()));
        assertEquals(Integer.valueOf(422), e.getCode());
        verify(db, never()).updateRow(anyString(), anyString(), any(), anyMap());
    }

    @Test
    void unknownJobIsRefused()
    {
        assertThrows(ServiceException.class, () -> controller.jobImages(99L, body()));
        verify(db, never()).updateRow(anyString(), anyString(), any(), anyMap());
    }
}
