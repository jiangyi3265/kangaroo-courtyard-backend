package com.ruoyi.kangaroo.ext.staff;

import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppUserContext;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 状态机负向与锁定读取的单元回归；真实事务竞态另由隔离 HTTP 验收验证。 */
class KgOnlineAssignmentBoundaryTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final KgOnlineTaskService service = spy(new KgOnlineTaskService());
    private final StaffSupport.Ctx ctx = new StaffSupport.Ctx();
    private final Map<String, Object> user = p("status", "0", "delFlag", "0");
    private final Map<String, Object> app = p("appUserId", 2L, "jobId", 8L, "storeId", 3L, "status", "review_passed", "delFlag", "0");
    private Map<String, Object> currentSubmission = p("submissionId", 6L, "status", "submitted");
    private Map<String, Object> currentSettlement;

    @BeforeEach void setup()
    {
        ctx.me = new AppLoginUser(); ctx.me.setUserId(1L); ctx.me.setNickname("本地主理人");
        ctx.storeId = 3L; ctx.role = "manager";
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "support", mock(StaffSupport.class));
        doReturn(p("title", "任务", "commission", "¥123.45", "commissionFen", 12345L)).when(service).assignment(ctx, 7L);
        when(db.must(anyString(), anyMap(), anyString())).thenReturn(p("appUserId", 2L, "jobId", 8L,
                "jobStore", 3L, "status", "review_passed", "workerStatus", "0", "workerDelFlag", "0"));
        when(db.list(anyString(), anyMap())).thenReturn(Collections.emptyList());
        when(db.one(anyString(), anyMap())).thenAnswer(i -> {
            String sql = i.getArgument(0);
            if (sql.contains("kg_app_user") && sql.contains("for update")) return user;
            if (sql.contains("kg_application where")) return app;
            if (sql.contains("kg_online_submission") && sql.contains("for update")) return currentSubmission;
            if (sql.contains("kg_online_settlement") && sql.contains("for update")) return currentSettlement;
            return null;
        });
        AppLoginUser owner = new AppLoginUser(); owner.setUserId(2L); AppUserContext.set(owner);
    }

    @AfterEach void clear() { AppUserContext.remove(); }

    private void rejectedReview()
    {
        for (boolean pass : new boolean[] { true, false })
        {
            ServiceException error = assertThrows(ServiceException.class, () -> service.review(ctx, 7L, pass, "补充说明"));
            assertEquals(Integer.valueOf(409), error.getCode());
        }
        verify(db, never()).update(anyString(), anyMap());
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test void withdrawnApplicationCannotBeAcceptedOrReturned() { app.put("status", "withdrawn"); rejectedReview(); }
    @Test void terminatedApplicationCannotBeAcceptedOrReturned() { app.put("status", "terminated"); rejectedReview(); }
    @Test void deletedAccountCannotCreateDebt() { user.put("delFlag", "2"); rejectedReview(); }
    @Test void disabledAccountCannotCreateDebt() { user.put("status", "1"); rejectedReview(); }

    @Test void currentApplicationLockMustStillBelongToStore()
    {
        app.put("storeId", 99L);
        assertEquals(Integer.valueOf(404), assertThrows(ServiceException.class, () -> service.review(ctx, 7L, true, "通过")).getCode());
        verify(db, never()).insert(anyString(), anyMap()); verify(db, never()).update(anyString(), anyMap());
    }

    @Test void activeReviewLocksAccountThenApplicationThenSubmissionBeforeWritingDebt()
    {
        service.review(ctx, 7L, true, "通过");
        InOrder ordered = inOrder(db);
        ordered.verify(db).one(contains("from kg_app_user where user_id = :u for update"), anyMap());
        ordered.verify(db).one(contains("from kg_application where app_id = :id for update"), anyMap());
        ordered.verify(db).one(contains("from kg_online_submission where app_id = :id order by round desc limit 1 for update"), anyMap());
        ordered.verify(db).update(contains("update kg_online_submission"), anyMap());
        ordered.verify(db).insert(eq("kg_online_settlement"), argThat(row -> Long.valueOf(12345).equals(row.get("amount_fen"))
                && Long.valueOf(2).equals(row.get("app_user_id")) && "pending_payout".equals(row.get("status"))));
    }

    @Test void acceptanceTellsTheInviteRewardWhichStoreAndWhichStaffAccepted()
    {
        // 邀请奖励要看验收人是不是邀请人、邀请人是不是本店员工：把本店（3）和验收人（1）带过去；退回修改不发
        com.ruoyi.kangaroo.ext.user.KgInviteService invites = mock(com.ruoyi.kangaroo.ext.user.KgInviteService.class);
        ReflectionTestUtils.setField(service, "invites", invites);
        service.review(ctx, 7L, true, "通过");
        verify(invites).rewardInviter(2L, "online", 3L, 1L);
        currentSubmission = p("submissionId", 6L, "status", "submitted");
        service.review(ctx, 7L, false, "补一张截图");
        verifyNoMoreInteractions(invites);
    }

    private void rejectedSubmit()
    {
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class,
                () -> service.submit(7L, p("link", "https://example.com/local"))).getCode());
        verify(db, never()).insert(anyString(), anyMap()); verify(db, never()).update(anyString(), anyMap());
    }

    @Test void staleOwnerPageCannotSubmitAfterWithdrawal() { app.put("status", "withdrawn"); rejectedSubmit(); }
    @Test void staleOwnerSessionCannotSubmitAfterDeletion() { user.put("delFlag", "2"); rejectedSubmit(); }
    @Test void concurrentNewSubmissionIsReadUnderLockAndRejected() { rejectedSubmit(); }
    @Test void concurrentAcceptanceIsReadUnderLockAndRejected()
    {
        currentSubmission = p("status", "accepted"); currentSettlement = p("settleId", 11L); rejectedSubmit();
    }
}
