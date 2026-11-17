package com.ruoyi.kangaroo.ext.staff;

import java.util.Collections;
import java.util.Date;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.StaffGrantView;
import com.ruoyi.kangaroo.service.IKgApplicationService;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Role checks must apply to the existing interview actions as well as meeting-info edits. */
class KgStaffInterviewPermissionTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final StaffSupport support = mock(StaffSupport.class);
    private final IKgApplicationService apps = mock(IKgApplicationService.class);
    private final KgStaffInterviewService service = new KgStaffInterviewService();
    private final StaffSupport.Ctx ctx = new StaffSupport.Ctx();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "support", support);
        ReflectionTestUtils.setField(service, "apps", apps);
        // Exercise the real permission matrix; suppress only operation-log side effects.
        doCallRealMethod().when(support).need(any(), anyString(), anyString(), anyString(), anyString());
        StaffGrantView grant = new StaffGrantView();
        grant.setStoreId(102L); grant.setRoleCode("staff"); grant.setActive(true);
        ctx.me = new AppLoginUser(); ctx.me.setUserId(1L);
        ctx.me.setStaffGrants(Collections.singletonList(grant));
        ctx.storeId = 102L; ctx.role = "staff";
        when(db.must(contains("from kg_interview i join kg_application a"), anyMap(), eq("面试不存在")))
            .thenReturn(p("interviewId", 5L, "appId", 201L, "slotId", 9L, "storeId", 102L,
                "appUserId", 7L, "jobId", 10L, "jobType", "volunteer", "status", "interview_booked",
                "istatus", "booked", "realName", "测试候选人", "startTime", new Date(1000)));
        when(db.must(contains("from kg_interview_slot"), anyMap(), eq("面试时段不存在")))
            .thenReturn(p("slotId", 11L, "storeId", 102L, "jobId", 10L,
                "startTime", new Date(System.currentTimeMillis() + 86400000L),
                "endTime", new Date(System.currentTimeMillis() + 90000000L)));
        when(db.update(anyString(), anyMap())).thenReturn(1);
        when(db.one(anyString(), anyMap())).thenReturn(null);
    }

    private void denied(Runnable action)
    {
        ctx.role = "online_staff";
        ctx.me.getStaffGrants().get(0).setRoleCode("online_staff");
        ServiceException error = assertThrows(ServiceException.class, action::run);
        assertEquals(Integer.valueOf(403), error.getCode());
        verify(db, never()).update(anyString(), anyMap());
        verify(db, never()).insert(anyString(), anyMap());
        verifyNoInteractions(apps);
    }

    @Test void onlineSpecialistCannotReschedule() { denied(() -> service.reschedule(ctx, 5L, 11L)); }
    @Test void onlineSpecialistCannotRate() { denied(() -> service.rate(ctx, 5L, 4, "记录")); }
    @Test void onlineSpecialistCannotFinish() { denied(() -> service.finish(ctx, 5L)); }

    @Test
    void authorizedStaffCanRescheduleAndKeeperCanRate()
    {
        ctx.role = "staff";
        assertNotNull(service.reschedule(ctx, 5L, 11L).get("startTime"));
        // 门店改期只换时段、记改期时间，不占候选人自己的改期次数
        verify(db).update(contains("rescheduled_time = now()"), anyMap());
        verify(db, never()).update(contains("reschedule_count"), anyMap());
        ctx.role = "keeper";
        service.rate(ctx, 5L, 4, "记录");
        verify(db).update(contains("staff_rating = :r"), argThat(params -> Integer.valueOf(4).equals(params.get("r"))));
    }

    @Test
    void managerCanFinishThroughApplicationStateMachine()
    {
        ctx.role = "manager";
        service.finish(ctx, 5L);
        verify(apps).finishInterview(eq(201L), isNull(), isNull(), isNull(), isNull(), isNull(), any());
    }

    @Test
    void roleWithoutOfflineJobRightsDoesNotAdvertiseWriteActions()
    {
        ctx.role = "online_staff";
        Map<String, Object> detail = service.detail(ctx, 5L);
        assertEquals(Boolean.FALSE, detail.get("canReschedule"));
        assertEquals(Boolean.FALSE, detail.get("canRate"));
        assertEquals(Boolean.FALSE, detail.get("canFinish"));
    }
}
