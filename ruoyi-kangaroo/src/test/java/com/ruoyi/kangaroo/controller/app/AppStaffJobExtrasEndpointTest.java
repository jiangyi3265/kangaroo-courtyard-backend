package com.ruoyi.kangaroo.controller.app;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.KgStaffJobService;
import com.ruoyi.kangaroo.ext.staff.RolePhase;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.security.StaffGrantView;

/**
 * GET / PUT /app/staff/jobs/{jobId}/extras（V66）的把关：真实的 StaffSupport（身份、权限矩阵）+ 真实的岗位服务，只把库换成假的。
 * 账号在 1 号店是换工主理人、2 号店是线上任务专员、3 号店是合作商家，4 号店没有授权；身份按岗位所属门店取。阶段钉成 new，两个阶段的矩阵差异不在这里测。
 */
class AppStaffJobExtrasEndpointTest
{
    private final AppStaffOpsController controller = new AppStaffOpsController();
    private final KgJdbc db = mock(KgJdbc.class);
    private final StaffSupport support = new StaffSupport();
    private final KgStaffJobService jobs = new KgStaffJobService();
    private String phaseBefore;

    private static StaffGrantView grant(Long storeId, String role)
    {
        StaffGrantView g = new StaffGrantView();
        g.setStoreId(storeId);
        g.setStoreName("店" + storeId);
        g.setRoleCode(role);
        g.setActive(true);
        return g;
    }

    @BeforeEach
    void setup()
    {
        phaseBefore = RolePhase.code();
        RolePhase.set(RolePhase.NEW);
        ReflectionTestUtils.setField(support, "db", db);
        ReflectionTestUtils.setField(jobs, "db", db);
        ReflectionTestUtils.setField(jobs, "support", support);
        ReflectionTestUtils.setField(controller, "db", db);
        ReflectionTestUtils.setField(controller, "support", support);
        ReflectionTestUtils.setField(controller, "jobs", jobs);
        AppLoginUser me = new AppLoginUser();
        me.setUserId(9001L);
        me.setNickname("多店账号");
        me.setStaffGrants(new ArrayList<StaffGrantView>(Arrays.asList(grant(1L, "manager"), grant(2L, "online_staff"), grant(3L, "merchant"))));
        AppUserContext.set(me);
        job(11L, 1L, "volunteer", "published");
        job(12L, 2L, "volunteer", "published");
        job(13L, 3L, "volunteer", "published");
        job(14L, 3L, "volunteer", "draft");
        job(15L, 4L, "volunteer", "published");
        job(16L, 1L, "online", "published");
        when(db.updateRow(eq("kg_job"), eq("job_id"), any(), anyMap())).thenReturn(1);
    }

    private void job(Long jobId, Long storeId, String type, String status)
    {
        when(db.one(contains("select store_id from kg_job"), argThat(m -> m != null && jobId.equals(m.get("id"))))).thenReturn(p("storeId", storeId));
        Map<String, Object> row = new HashMap<String, Object>(p("jobId", jobId, "storeId", storeId, "title", "岗位" + jobId, "jobType", type, "boardType", "room", "status", status));
        when(db.must(contains("from kg_job"), argThat(m -> m != null && jobId.equals(m.get("id"))), anyString())).thenReturn(row);
    }

    @AfterEach
    void cleanup()
    {
        AppUserContext.remove();
        RolePhase.set(phaseBefore);
    }

    private int code(Runnable r)
    {
        ServiceException e = assertThrows(ServiceException.class, r::run);
        return e.getCode() == null ? 500 : e.getCode();
    }

    @Test
    void managerOfTheJobsStoreCanReadAndSave()
    {
        AjaxResult got = controller.jobExtras(11L);
        assertEquals(200, got.get("code"));
        AjaxResult saved = controller.saveJobExtras(11L, p("petStatus", "none"));
        assertEquals(200, saved.get("code"));
        assertEquals("已保存", saved.get("msg"));
        verify(db).updateRow(eq("kg_job"), eq("job_id"), eq(11L), argThat(cols -> "none".equals(cols.get("pet_status")) && "".equals(cols.get("pet_images")) && !cols.containsKey("status")));
        verify(db).insert(eq("kg_staff_op_log"), argThat(row -> Long.valueOf(1L).equals(row.get("store_id")) && "job_extras".equals(row.get("action")) && "1".equals(row.get("ok"))));
    }

    @Test
    void onlineSpecialistOfTheStoreIsRefusedAndLogged()
    {
        assertEquals(403, code(() -> controller.saveJobExtras(12L, p("petStatus", "none"))));
        verify(db, never()).updateRow(anyString(), anyString(), any(), anyMap());
        verify(db).insert(eq("kg_staff_op_log"), argThat(row -> Long.valueOf(2L).equals(row.get("store_id")) && "job_extras".equals(row.get("action")) && "0".equals(row.get("ok"))));
    }

    @Test
    void merchantOnlyWhileItIsADraft()
    {
        assertEquals(403, code(() -> controller.saveJobExtras(13L, p("petStatus", "none"))));
        verify(db, never()).updateRow(anyString(), anyString(), any(), anyMap());
        assertEquals(200, controller.saveJobExtras(14L, p("petStatus", "none")).get("code"));
        assertEquals(200, controller.jobExtras(13L).get("code"), "上架后还能看，只是不能改");
    }

    @Test
    void jobOfAnotherStoreIsRefused()
    {
        assertEquals(403, code(() -> controller.jobExtras(15L)));
        assertEquals(403, code(() -> controller.saveJobExtras(15L, p("petStatus", "none"))));
    }

    @Test
    void onlineTaskHasNoExtras()
    {
        assertEquals(422, code(() -> controller.jobExtras(16L)));
        assertEquals(422, code(() -> controller.saveJobExtras(16L, p("petStatus", "none"))));
    }

    @Test
    void routesAreStaffAndMerchantOnly() throws Exception
    {
        Method get = AppStaffOpsController.class.getMethod("jobExtras", Long.class);
        assertArrayEquals(new String[] { "/jobs/{jobId}/extras" }, get.getAnnotation(GetMapping.class).value());
        assertTrue(get.getAnnotation(AppAuth.class).staff());
        assertTrue(get.getAnnotation(AppAuth.class).merchant());
        assertFalse(get.getAnnotation(AppAuth.class).onlineStaff());
        Method put = AppStaffOpsController.class.getMethod("saveJobExtras", Long.class, Map.class);
        assertArrayEquals(new String[] { "/jobs/{jobId}/extras" }, put.getAnnotation(PutMapping.class).value());
        assertTrue(put.getAnnotation(AppAuth.class).staff());
        assertTrue(put.getAnnotation(AppAuth.class).merchant());
        assertFalse(put.getAnnotation(AppAuth.class).onlineStaff());
    }
}
