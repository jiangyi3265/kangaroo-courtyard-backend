package com.ruoyi.kangaroo.service.impl;

import java.util.Arrays;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.domain.KgApplication;
import com.ruoyi.kangaroo.domain.KgInterview;
import com.ruoyi.kangaroo.dto.OperatorDTO;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.mapper.*;
import com.ruoyi.kangaroo.security.StaffGrantView;
import com.ruoyi.kangaroo.service.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercise service entry points as well as HTTP permission guards. No database or external messages. */
class KgApplicationHiringPermissionTest
{
    @Test void onlineApplicantsCanBeApprovedOrRejectedByTheirOwnAuthorizedStoreRoles()
    {
        com.ruoyi.kangaroo.ext.staff.RolePhase.set("new");
        try {
            for (String role : new String[] {"merchant", "merchant_service", "city_lead", "keeper", "online_staff"}) {
                for (String action : new String[] {"approve", "reject"}) {
                    Fixture f = new Fixture(role, action);
                    when(f.db.one(anyString(), anyMap())).thenReturn(KgJdbc.p("jobType", "online", "headcount", 2));
                    f.call(action, OperatorDTO.staff(2L, "审核人", 10L));
                    assertEquals("approve".equals(action) ? "review_passed" : "review_rejected", f.app.getStatus(), role);
                    verify(f.logs).insertKgApplicationLog(any());
                    if ("approve".equals(action)) verify(f.messages).send(eq(90L), eq("user"), anyString(), eq("接单申请已通过"), anyString(), eq("application"), eq(1L), eq("/pages/jobs/deliver?appId=1"));
                }
            }
        } finally { com.ruoyi.kangaroo.ext.staff.RolePhase.set("legacy"); }
    }

    @Test void onlineAuditStillRejectsSelfReviewFullQuotaExpiredTaskAndMissingReason()
    {
        for (String kind : new String[] {"self", "full", "expired", "reason"}) {
            Fixture f = new Fixture("merchant", "approve");
            when(f.db.one(anyString(), anyMap())).thenReturn(KgJdbc.p("jobType", "online", "headcount", 2));
            if ("self".equals(kind)) f.app.setAppUserId(2L);
            if ("full".equals(kind)) when(f.db.count(contains("app_id <>"), anyMap())).thenReturn(2L);
            if ("expired".equals(kind)) when(f.db.count(contains("deadline_time"), anyMap())).thenReturn(1L);
            assertThrows(ServiceException.class, () -> f.service.audit(1L, !"reason".equals(kind), null, OperatorDTO.staff(2L, "审核人", 10L)), kind);
            verify(f.applications, never()).updateStatusWithCas(anyLong(), anyString(), anyString(), anyString());
            verifyNoInteractions(f.logs, f.messages);
        }
    }
    private static final String[] ACTIONS = { "approve", "reject", "confirm", "onboard", "terminate", "finish" };

    private static StaffGrantView grant(long store, String role, boolean active)
    {
        StaffGrantView g = new StaffGrantView();
        g.setStoreId(store); g.setRoleCode(role); g.setActive(active);
        return g;
    }

    private static class Fixture
    {
        final KgApplicationServiceImpl service = new KgApplicationServiceImpl();
        final KgApplicationMapper applications = mock(KgApplicationMapper.class);
        final KgApplicationLogMapper logs = mock(KgApplicationLogMapper.class);
        final KgJobMapper jobs = mock(KgJobMapper.class);
        final KgInterviewMapper interviews = mock(KgInterviewMapper.class);
        final KgInterviewSlotMapper slots = mock(KgInterviewSlotMapper.class);
        final IKgMessageService messages = mock(IKgMessageService.class);
        final IKgStaffGrantService grants = mock(IKgStaffGrantService.class);
        final KgJdbc db = mock(KgJdbc.class);
        final KgApplication app = new KgApplication();

        Fixture(String role, String action)
        {
            ReflectionTestUtils.setField(service, "kgApplicationMapper", applications);
            ReflectionTestUtils.setField(service, "kgApplicationLogMapper", logs);
            ReflectionTestUtils.setField(service, "kgJobMapper", jobs);
            ReflectionTestUtils.setField(service, "kgInterviewMapper", interviews);
            ReflectionTestUtils.setField(service, "kgInterviewSlotMapper", slots);
            ReflectionTestUtils.setField(service, "kgMessageService", messages);
            ReflectionTestUtils.setField(service, "kgStaffGrantService", grants);
            ReflectionTestUtils.setField(service, "kgJdbcForQuota", db);
            app.setAppId(1L); app.setStoreId(10L); app.setJobId(20L); app.setAppUserId(90L);
            app.setStatus("confirm".equals(action) ? "user_confirmed" : "onboard".equals(action) ? "contract_signed"
                    : "terminate".equals(action) ? "review_passed" : "finish".equals(action) ? "interview_booked" : "submitted");
            when(applications.selectKgApplicationByAppId(1L)).thenReturn(app);
            when(applications.updateStatusWithCas(eq(1L), anyString(), anyString(), anyString())).thenReturn(1);
            when(grants.hasActiveGrant(2L, 10L)).thenReturn(true);
            when(grants.loadActiveGrantViews(2L)).thenReturn(Arrays.asList(grant(999L, "manager", true), grant(10L, role, true)));
            when(jobs.increaseHiredCount(20L)).thenReturn(1);
            when(db.one(anyString(), anyMap())).thenReturn(KgJdbc.p("jobType", "volunteer", "headcount", 100));
            when(db.one(contains("expected_date"), anyMap())).thenAnswer(i -> KgJdbc.p("expectedDate",
                    com.ruoyi.kangaroo.ext.staff.ArrivalConfirmationPolicy.date(app.getArrivalDate())));
            KgInterview interview = new KgInterview();
            interview.setInterviewId(30L); interview.setStartTime(new Date(1000));
            when(interviews.selectByAppId(1L)).thenReturn(interview);
        }

        void call(String action, OperatorDTO operator)
        {
            switch (action)
            {
                case "approve": service.audit(1L, true, null, operator); break;
                case "reject": service.audit(1L, false, "不符合要求", operator); break;
                case "confirm": service.storeConfirm(1L, operator); break;
                case "onboard": service.onboard(1L, operator); break;
                case "terminate": service.terminate(1L, "结束招募", operator); break;
                default: service.finishInterview(1L, null, null, null, null, null, operator);
            }
        }

        void assertDenied(String action)
        {
            String before = app.getStatus();
            ServiceException e = assertThrows(ServiceException.class, () -> call(action, OperatorDTO.staff(2L, "操作人", 999L)));
            assertEquals(Integer.valueOf(403), e.getCode(), action);
            assertEquals(before, app.getStatus());
            verify(applications, never()).updateStatusWithCas(anyLong(), anyString(), anyString(), anyString());
            verify(applications, never()).updateKgApplication(any());
            verifyNoInteractions(logs, jobs, interviews, slots, messages);
            // The service may read the job type to select the correct permission path, but must never write.
            verify(db, atMostOnce()).one(contains("job_type"), anyMap());
            verifyNoMoreInteractions(db);
        }
    }

    @Test void onlineSpecialistCannotWriteOfflineHiringEvenIfManagerAtAnotherStore()
    {
        for (String action : ACTIONS) new Fixture("online_staff", action).assertDenied(action);
    }

    @Test void roleOnAnotherStoreOrInactiveGrantDoesNotAuthorizeTheApplication()
    {
        for (String action : ACTIONS)
        {
            Fixture f = new Fixture("keeper", action);
            when(f.grants.loadActiveGrantViews(2L)).thenReturn(Arrays.asList(grant(999L, "manager", true), grant(10L, "keeper", false)));
            f.assertDenied(action);
        }
    }

    @Test void authorizedOfflineRolesCanCompleteTheExistingHiringTransitions()
    {
        String[] targets = { "review_passed", "review_rejected", "store_confirmed", "onboarded", "terminated", "interview_done" };
        for (String role : new String[] { "manager", "keeper", "staff" })
        {
            for (int i = 0; i < ACTIONS.length; i++)
            {
                Fixture f = new Fixture(role, ACTIONS[i]);
                f.call(ACTIONS[i], OperatorDTO.staff(2L, "操作人", 10L));
                assertEquals(targets[i], f.app.getStatus(), role + ":" + ACTIONS[i]);
                verify(f.applications).updateStatusWithCas(eq(1L), anyString(), eq(targets[i]), anyString());
                verify(f.logs).insertKgApplicationLog(any());
            }
        }
    }

    @Test void managerCannotDecideTheirOwnApplication()
    {
        for (String action : ACTIONS)
        {
            Fixture f = new Fixture("manager", action);
            f.app.setAppUserId(2L);
            f.assertDenied(action);
        }
    }

    @Test void adminAuditKeepsItsSeparateAuthorizationPath()
    {
        Fixture f = new Fixture("staff", "reject");
        f.call("reject", OperatorDTO.admin("admin-test"));
        assertEquals("review_rejected", f.app.getStatus());
        verifyNoInteractions(f.grants);
    }

    /** 2026-11-10：预计日期当天才可确认，不再放行前一天。 */
    @Test void onboardWaitsUntilTheExpectedArrivalDay()
    {
        java.time.LocalDate today = java.time.LocalDate.of(2026, 11, 10);
        assertNull(KgApplicationServiceImpl.onboardTooEarly(null, today));
        assertNotNull(KgApplicationServiceImpl.onboardTooEarly(java.sql.Date.valueOf("2026-11-11"), today), "前 1 天必须拒绝");
        assertNull(KgApplicationServiceImpl.onboardTooEarly(java.sql.Date.valueOf("2026-11-10"), today));
        assertNull(KgApplicationServiceImpl.onboardTooEarly(java.sql.Date.valueOf("2026-11-01"), today), "到岗日过了照旧能标");
        assertNotNull(KgApplicationServiceImpl.onboardTooEarly(java.sql.Date.valueOf("2026-11-12"), today));
        assertEquals("预计到店日期是 2026-11-22，当天起才能确认到店；如提前到店，请先更新到店信息，再由门店核实确认",
                KgApplicationServiceImpl.onboardTooEarly(java.sql.Date.valueOf("2026-11-22"), today));
    }

    @Test void onboardBeforeTheAgreedArrivalIsRefusedWithoutWriting()
    {
        Date far = new Date(System.currentTimeMillis() + 10L * 24 * 3600_000);
        Fixture f = new Fixture("manager", "onboard");
        f.app.setArrivalDate(far);
        ServiceException e = assertThrows(ServiceException.class, () -> f.call("onboard", OperatorDTO.staff(2L, "操作人", 10L)));
        assertEquals(Integer.valueOf(409), e.getCode());
        assertTrue(e.getMessage().startsWith("预计到店日期是 "), e.getMessage());
        assertEquals("contract_signed", f.app.getStatus());
        verify(f.applications, never()).updateStatusWithCas(anyLong(), anyString(), anyString(), anyString());
        verify(f.applications, never()).updateKgApplication(any());
        verifyNoInteractions(f.messages, f.logs);
        // 没权限的人照旧先收到 403，不提示到岗日
        Fixture denied = new Fixture("online_staff", "onboard");
        denied.app.setArrivalDate(far);
        denied.assertDenied("onboard");
        // 到岗日当天照常能标
        Fixture ok = new Fixture("manager", "onboard");
        ok.app.setArrivalDate(new Date());
        ok.call("onboard", OperatorDTO.staff(2L, "操作人", 10L));
        assertEquals("onboarded", ok.app.getStatus());
    }

    @Test void reportedArrivalTomorrowOverridesAnEarlierApplicationDateForBothStaffAndAdmin()
    {
        String tomorrow = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1).toString();
        for (OperatorDTO operator : new OperatorDTO[] { OperatorDTO.staff(2L, "操作人", 10L), OperatorDTO.admin("admin-test") })
        {
            Fixture f = new Fixture("manager", "onboard");
            f.app.setArrivalDate(java.sql.Date.valueOf("2020-01-01"));
            when(f.db.one(contains("expected_date"), anyMap())).thenReturn(KgJdbc.p("expectedDate", tomorrow));
            ServiceException e = assertThrows(ServiceException.class, () -> f.call("onboard", operator));
            assertEquals(Integer.valueOf(409), e.getCode());
            assertTrue(e.getMessage().contains(tomorrow));
            assertEquals("contract_signed", f.app.getStatus());
            verify(f.db).one(eq("select app_id from kg_application where app_id=:a for update"), eq(KgJdbc.p("a", 1L)));
            verify(f.db).one(argThat(sql -> sql.contains("coalesce(r.arrive_date, a.arrival_date)") && sql.endsWith("for update")), anyMap());
            verify(f.applications, never()).updateStatusWithCas(anyLong(), anyString(), anyString(), anyString());
            verify(f.applications, never()).updateKgApplication(any());
            verifyNoInteractions(f.messages, f.logs, f.slots, f.interviews);
        }
    }

    @Test void reportedTodayOrPastCanConfirmAndLocksBeforeTransition()
    {
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        for (String day : new String[] { today.toString(), today.minusDays(1).toString() })
        {
            Fixture f = new Fixture("manager", "onboard");
            f.app.setArrivalDate(java.sql.Date.valueOf(today.plusDays(5)));
            when(f.db.one(contains("expected_date"), anyMap())).thenReturn(KgJdbc.p("expectedDate", day));
            f.call("onboard", OperatorDTO.staff(2L, "操作人", 10L));
            assertEquals("onboarded", f.app.getStatus());
            org.mockito.InOrder order = inOrder(f.db, f.applications);
            order.verify(f.db).one(eq("select app_id from kg_application where app_id=:a for update"), anyMap());
            order.verify(f.db).one(contains("expected_date"), anyMap());
            order.verify(f.applications).updateStatusWithCas(eq(1L), eq("contract_signed"), eq("onboarded"), anyString());
            verify(f.messages).send(eq(90L), eq("user"), anyString(), eq("入职成功"), anyString(), eq("application"), eq(1L), anyString());
        }
    }

    @Test void candidateCanStillFinishTheirOwnBookedInterview()
    {
        Fixture f = new Fixture("staff", "finish");
        f.call("finish", OperatorDTO.user(90L, "候选人"));
        assertEquals("interview_done", f.app.getStatus());
        verifyNoInteractions(f.grants);
    }
}
