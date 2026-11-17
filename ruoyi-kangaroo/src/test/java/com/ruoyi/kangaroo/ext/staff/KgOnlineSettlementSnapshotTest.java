package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.kangaroo.ext.KgJdbc;

/** 结算快照的单元回归；完整提交、验收、改价及付款通知链路另由隔离库 HTTP 验收覆盖。 */
class KgOnlineSettlementSnapshotTest
{
    private KgOnlineTaskService service(Map<String, Object> settlement)
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.must(anyString(), anyMap(), anyString())).thenReturn(p(
                "jobId", 21L, "jobStore", 31L, "title", "测试任务", "appUserId", 41L,
                "status", "review_passed", "workerStatus", "0", "workerDelFlag", "0", "commissionFen", 23456L));
        when(db.list(anyString(), anyMap())).thenReturn(Collections.emptyList());
        when(db.one(anyString(), anyMap())).thenAnswer(invocation ->
                invocation.getArgument(0, String.class).contains("from kg_online_settlement") ? settlement : null);
        KgOnlineTaskService service = new KgOnlineTaskService();
        ReflectionTestUtils.setField(service, "db", db);
        return service;
    }

    @Test
    void bothViewsKeepAcceptedAmountAfterTaskPriceChangesForEverySettlementState()
    {
        for (String status : new String[] { "pending_payout", "payout_registered", "confirmed", "disputed" })
        {
            KgOnlineTaskService service = service(p("settleNo", "LOCAL-TEST", "status", status, "amountFen", 12345L));
            for (boolean staffView : new boolean[] { true, false })
            {
                Map<String, Object> result = ReflectionTestUtils.invokeMethod(service, "view", 11L, staffView);
                assertEquals(12345L, result.get("commissionFen"), status + " / staff=" + staffView);
                assertEquals("¥123.45", result.get("commission"));
                Map<?, ?> settlement = (Map<?, ?>) result.get("settlement");
                assertEquals("¥123.45", settlement.get("amount"));
                assertEquals(status, settlement.get("status"));
                assertEquals(false, result.get("canSubmit"));
            }
        }
    }

    @Test
    void beforeAcceptanceCurrentTaskPriceRemainsTheQuote()
    {
        KgOnlineTaskService service = service(null);
        Map<String, Object> result = ReflectionTestUtils.invokeMethod(service, "view", 11L, false);
        assertEquals(23456L, result.get("commissionFen"));
        assertEquals("¥234.56", result.get("commission"));
        assertNull(result.get("settlement"));
        assertEquals(true, result.get("canSubmit"));
    }
}
