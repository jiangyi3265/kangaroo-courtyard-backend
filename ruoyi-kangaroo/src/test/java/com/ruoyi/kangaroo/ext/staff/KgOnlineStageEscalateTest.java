package com.ruoyi.kangaroo.ext.staff;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppUserContext;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 2026-10-14 回归：
 * R3-4 平台处理完验收申诉后，「请平台介入」不再马上放开：从平台处理时起再等 ESCALATE_DAYS 天（新一轮提交照旧按提交时间算）；
 * R3-2 员工端报名详情按接单实际走到的一步给 onlineStep / onlineTip / workerCanSubmit，结算完成的不再写「接单人可提交成果」。
 */
class KgOnlineStageEscalateTest
{
    private static final long DAY = 86400000L;
    private final KgJdbc db = mock(KgJdbc.class);
    private final KgOnlineTaskService service = new KgOnlineTaskService();
    private final Map<String, Object> app = p("appUserId", 2L, "jobId", 8L, "jobStore", 3L, "title", "短视频剪辑", "status", "review_passed",
            "workerStatus", "0", "workerDelFlag", "0", "commissionFen", 5000L, "storeName", "小院");
    private List<Map<String, Object>> subs = Collections.emptyList();
    private Map<String, Object> settlement;
    private Map<String, Object> handled;
    private long pendingAppeals;

    private static String at(long ms) { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(ms)); }

    @BeforeEach void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "support", mock(StaffSupport.class));
        app.put("deadlineTime", at(System.currentTimeMillis() + 10 * DAY));
        when(db.must(contains("from kg_application a"), anyMap(), anyString())).thenAnswer(i -> app);
        when(db.list(contains("from kg_online_submission"), anyMap())).thenAnswer(i -> subs);
        when(db.list(contains("from kg_online_material"), anyMap())).thenReturn(Collections.emptyList());
        when(db.one(anyString(), anyMap())).thenAnswer(i -> {
            String sql = i.getArgument(0);
            if (sql.contains("from kg_online_settlement")) return settlement;
            if (sql.contains("max(coalesce(handled_time, create_time))")) return handled;
            return null;
        });
        when(db.count(anyString(), anyMap())).thenAnswer(i -> ((String) i.getArgument(0)).contains("status = 'pending'") ? pendingAppeals : 0L);
        AppLoginUser worker = new AppLoginUser();
        worker.setUserId(2L);
        AppUserContext.set(worker);
    }

    @AfterEach void clear() { AppUserContext.remove(); }

    private void submittedDaysAgo(int days)
    {
        subs = Collections.singletonList(p("round", 1, "status", "submitted", "createTime", at(System.currentTimeMillis() - days * DAY), "images", ""));
    }

    @Test void escalationOpensThreeDaysAfterSubmission()
    {
        submittedDaysAgo(5);
        Map<String, Object> m = service.mine(7L);
        assertEquals(true, m.get("canEscalate"));
        assertEquals(false, m.get("escalateAgain"));
    }

    @Test void justHandledEscalationIsNotOfferedAgainRightAway()
    {
        submittedDaysAgo(5);
        long handledAt = System.currentTimeMillis() - 3600000L;
        handled = p("t", at(handledAt));
        Map<String, Object> m = service.mine(7L);
        assertEquals(false, m.get("canEscalate"));
        assertEquals(true, m.get("escalateAgain"));
        assertEquals(false, m.get("escalated"));
        // 只算这一轮提交之后发起的申诉
        verify(db).one(contains("and create_time >= :since"), argThat(ps -> String.valueOf(ps.get("since")).equals(subs.get(0).get("createTime"))));
        String expectFrom = new SimpleDateFormat("MM-dd HH:mm").format(new Date(handledAt + 3 * DAY));
        assertEquals(expectFrom, m.get("escalateFrom"));
        assertTrue(String.valueOf(((Map<?, ?>) m.get("flow")).get("tip")).contains("再次请平台介入"));
        ServiceException e = assertThrows(ServiceException.class, () -> service.escalate(7L, "门店还是没验收"));
        assertEquals(Integer.valueOf(409), e.getCode());
        assertTrue(e.getMessage().contains("再次请平台介入"), e.getMessage());
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test void escalationReopensWhenTheStoreStillHasNotReviewedDaysAfterThePlatformHandledIt()
    {
        submittedDaysAgo(9);
        handled = p("t", at(System.currentTimeMillis() - 4 * DAY));
        Map<String, Object> m = service.mine(7L);
        assertEquals(true, m.get("canEscalate"));
        assertEquals(true, m.get("escalateAgain"));
    }

    @Test void pendingEscalationStillShowsAsInProgress()
    {
        submittedDaysAgo(5);
        pendingAppeals = 1;
        Map<String, Object> m = service.mine(7L);
        assertEquals(true, m.get("escalated"));
        assertEquals(false, m.get("canEscalate"));
    }

    @Test void staffStageShowsSettlementDone()
    {
        subs = Collections.singletonList(p("round", 1, "status", "accepted", "createTime", at(System.currentTimeMillis() - 5 * DAY), "images", ""));
        settlement = p("settleNo", "OS1", "status", "confirmed", "amountFen", 5000L);
        Map<String, Object> s = service.staffStage(7L);
        assertEquals(4, s.get("onlineStep"));
        assertEquals(false, s.get("workerCanSubmit"));
        assertTrue(String.valueOf(s.get("onlineTip")).startsWith("结算完成"), String.valueOf(s.get("onlineTip")));
    }

    @Test void staffStageBeforeAnySubmissionSaysTheWorkerCanSubmit()
    {
        Map<String, Object> s = service.staffStage(7L);
        assertEquals(3, s.get("onlineStep"));
        assertEquals(true, s.get("workerCanSubmit"));
        assertEquals("申请已通过，无需重复审核。接单人可在任务交付页提交成果。", s.get("onlineTip"));
    }

    @Test void staffStageWaitingForReviewAndPastDeadline()
    {
        submittedDaysAgo(1);
        assertTrue(String.valueOf(service.staffStage(7L).get("onlineTip")).contains("等门店验收"));
        subs = Collections.emptyList();
        app.put("deadlineTime", at(System.currentTimeMillis() - DAY));
        Map<String, Object> s = service.staffStage(7L);
        assertEquals(false, s.get("workerCanSubmit"));
        assertEquals("已过提交期限，接单人不能再提交成果。", s.get("onlineTip"));
    }

    @Test void staffStageLeavesEndedApplicationsToThePage()
    {
        app.put("status", "terminated");
        assertTrue(service.staffStage(7L).isEmpty());
    }
}
