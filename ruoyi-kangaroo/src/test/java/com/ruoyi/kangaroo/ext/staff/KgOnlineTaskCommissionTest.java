package com.ruoyi.kangaroo.ext.staff;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 线上任务佣金：后台改价精确换算、结算回落不截断、扩展行同步（不连库） */
class KgOnlineTaskCommissionTest
{
    @Test
    void adminPriceConvertsToFenExactlyWithCreateTaskLimits()
    {
        assertEquals(1250L, KgOnlineTaskService.commissionFen(new BigDecimal("12.5")));
        assertEquals(1250L, KgOnlineTaskService.commissionFen(new BigDecimal("12.50")));
        assertEquals(100L, KgOnlineTaskService.commissionFen(new BigDecimal("1")));
        assertEquals(500000L, KgOnlineTaskService.commissionFen(new BigDecimal("5000.00")));
        for (String bad : new String[] { "0.99", "5000.01", "12.345", "-3", "0" })
        {
            assertThrows(ServiceException.class, () -> KgOnlineTaskService.commissionFen(new BigDecimal(bad)), bad);
        }
        assertThrows(ServiceException.class, () -> KgOnlineTaskService.commissionFen(null));
    }

    @Test
    void settlementFallbackForLegacyRowsKeepsCents()
    {
        Map<String, Object> j = new HashMap<String, Object>();
        assertEquals(0L, KgOnlineTaskService.commission(j));
        // KgJdbc.camel 对 decimal(12,2) 给 BigDecimal，整数值给 Long，两种都要认
        j.put("salaryMin", new BigDecimal("12.50"));
        assertEquals(1250L, KgOnlineTaskService.commission(j));
        j.put("salaryMin", 30L);
        assertEquals(3000L, KgOnlineTaskService.commission(j));
        // 有扩展行时只认 commission_fen
        j.put("commissionFen", 1999L);
        assertEquals(1999L, KgOnlineTaskService.commission(j));
    }

    @Test
    @SuppressWarnings("unchecked")
    void jobEditWritesCommissionAndDerivesDeliverDescFromDeadline()
    {
        KgOnlineTaskService tasks = new KgOnlineTaskService();
        KgJdbc db = mock(KgJdbc.class);
        ReflectionTestUtils.setField(tasks, "db", db);
        Map<String, Object> ext = new HashMap<String, Object>();
        ext.put("deadlineTime", "2026-10-05 18:00:00");
        when(db.one(contains("from kg_online_task"), anyMap())).thenReturn(ext);
        assertEquals("10月5日 18:00 前", tasks.syncFromJobEdit(7L, 2000L, "ops-test"));
        ArgumentCaptor<Map<String, Object>> ps = ArgumentCaptor.forClass(Map.class);
        verify(db).update(contains("update kg_online_task set commission_fen"), ps.capture());
        assertEquals(2000L, ps.getValue().get("f"));
        assertEquals(7L, ps.getValue().get("id"));

        // 没有扩展行的老数据：不写、返回 null（调用方保持原交付日期）
        reset(db);
        when(db.one(contains("from kg_online_task"), anyMap())).thenReturn(null);
        assertNull(tasks.syncFromJobEdit(8L, 2000L, "ops-test"));
        verify(db, never()).update(anyString(), anyMap());

        assertThrows(ServiceException.class, () -> tasks.syncFromJobEdit(7L, 50L, "ops-test"));
    }
}
