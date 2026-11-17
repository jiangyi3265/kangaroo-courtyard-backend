package com.ruoyi.kangaroo.ext.user;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.kangaroo.ext.KgJdbc;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 订单呈现与金额快照单元测试；用户/门店隔离由真实 API 回归验证。 */
class KgOnlineOrderHistoryTest
{
    private Map<String, Object> order(String settlement, String tab)
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.list(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0, String.class).contains("from kg_application a join kg_job j")
                ? Collections.singletonList(p("appId", 7L, "status", "withdrawn", "jobType", "online", "title", "保留成果",
                  "submissionCount", 1L, "settlementStatus", settlement, "settlementFen", 12345L, "salaryMin", 999)) : Collections.emptyList());
        KgUserHubService service = new KgUserHubService(); ReflectionTestUtils.setField(service, "db", db);
        List<Map<String, Object>> result = service.orders(2L, tab);
        assertEquals(1, result.size());
        Map<String, Object> row = result.get(0);
        assertTrue(row.get("btns").toString().contains("/pages/jobs/deliver?appId=7"));
        assertFalse(row.get("btns").toString().contains("去评价"));
        return row;
    }
    @Test void withdrawnUnpaidDebtRemainsOngoingAtOriginalAmount()
    {
        for (String status : new String[] { "pending_payout", "payout_registered", "disputed" })
        {
            Map<String, Object> row = order(status, "进行中");
            assertEquals("进行中", row.get("status")); assertEquals("123.45", row.get("price"));
        }
    }
    @Test void confirmedWithdrawnDebtAppearsInCompletedHistory() { assertEquals("已完成", order("confirmed", "已完成").get("status")); }
    @Test void withdrawnUnsettledWorkRetainsReadOnlyHistory() { assertEquals("已结束", order("", "已完成").get("status")); }
}
