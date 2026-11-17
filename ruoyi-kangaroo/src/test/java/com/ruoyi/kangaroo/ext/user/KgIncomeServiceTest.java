package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.kangaroo.ext.KgJdbc;

/** 我的收入：合计口径（已到账 / 待到账 / 申诉中）、明细字段、状态筛选与分页参数；只读，不写库。 */
class KgIncomeServiceTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final KgIncomeService service = new KgIncomeService();
    private final String year = String.valueOf(LocalDate.now().getYear());
    private final String lastYear = String.valueOf(LocalDate.now().getYear() - 1);

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        when(db.list(contains("group by status"), anyMap())).thenReturn(Arrays.asList(
                row("status", "confirmed", "n", 2L, "fen", 30000L),
                row("status", "pending_payout", "n", 1L, "fen", 12345L),
                row("status", "payout_registered", "n", 1L, "fen", 5000L),
                row("status", "disputed", "n", 1L, "fen", 8800L)));
        when(db.page(anyString(), anyMap(), any(), any())).thenReturn(page(Collections.<Map<String, Object>>emptyList()));
    }

    private static Map<String, Object> row(Object... kv)
    {
        return new HashMap<String, Object>(p(kv));
    }

    private static Map<String, Object> page(List<Map<String, Object>> rows)
    {
        return row("rows", rows, "total", (long) rows.size(), "hasMore", false, "pageNum", 1, "pageSize", 20);
    }

    @Test
    void totalsSplitConfirmedPendingAndDisputed()
    {
        Map<String, Object> out = service.income(9L, "all", 1, 20);
        Map<?, ?> t = (Map<?, ?>) out.get("totals");
        assertEquals(30000L, t.get("confirmedFen"));
        assertEquals("¥300", t.get("confirmed"));
        // 待到账 = 待门店打款 + 门店已登记打款（待本人确认）
        assertEquals(17345L, t.get("pendingFen"));
        assertEquals("¥173.45", t.get("pending"));
        assertEquals(2L, t.get("pendingCount"));
        assertEquals(8800L, t.get("disputedFen"));
        assertEquals("¥88", t.get("disputed"));
        assertTrue(String.valueOf(out.get("note")).contains("平台不经手资金"));
        verify(db).list(contains("where app_user_id = :u group by status"), argThat(m -> Long.valueOf(9L).equals(m.get("u"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rowsCarryTitleStoreAmountStatusDatesAndNavigationIds()
    {
        when(db.page(anyString(), anyMap(), any(), any())).thenReturn(page(Arrays.asList(
                row("settleId", 3L, "settleNo", "OS1", "appId", 31L, "jobId", 21L, "amountFen", 12345L, "status", "payout_registered", "payoutMethod", "微信转账",
                        "createTime", year + "-10-08 10:00:00", "registeredTime", year + "-10-09 11:00:00", "title", "小红书探店笔记", "storeName", "平潭袋鼠小院"),
                row("settleId", 2L, "settleNo", "OS2", "appId", 30L, "jobId", 20L, "amountFen", 5000L, "status", "confirmed",
                        "createTime", lastYear + "-12-30 10:00:00", "registeredTime", lastYear + "-12-31 09:00:00", "confirmedTime", year + "-01-02 08:00:00", "title", null, "storeName", null),
                row("settleId", 1L, "appId", 29L, "amountFen", 800L, "status", "disputed", "createTime", year + "-10-01 10:00:00",
                        "updateTime", year + "-10-09 10:00:00", "title", "海报设计", "storeName", "大理小院"))));
        Map<String, Object> out = service.income(9L, null, null, null);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("rows");
        assertEquals(31L, rows.get(0).get("appId"));
        assertEquals(21L, rows.get(0).get("jobId"));
        assertEquals("小红书探店笔记", rows.get(0).get("title"));
        assertEquals("平潭袋鼠小院", rows.get(0).get("storeName"));
        assertEquals("¥123.45", rows.get(0).get("amount"));
        assertEquals("待确认到账", rows.get(0).get("statusLabel"));
        assertEquals("验收 10-08 · 登记打款 10-09", rows.get(0).get("dates"));
        assertTrue(String.valueOf(rows.get(0).get("hint")).contains("确认收款"));
        // 往年的日期带年份；任务 / 门店删了也有兜底文字
        assertEquals("已到账", rows.get(1).get("statusLabel"));
        assertEquals("验收 " + lastYear + "-12-30 · 登记打款 " + lastYear + "-12-31 · 到账 01-02", rows.get(1).get("dates"));
        assertEquals("（任务已删除）", rows.get(1).get("title"));
        assertEquals("green", rows.get(1).get("tone"));
        assertEquals("申诉中", rows.get(2).get("statusLabel"));
        assertEquals("验收 10-01 · 申诉 10-09", rows.get(2).get("dates"));
        assertEquals("all", out.get("status"));
        // 默认第 1 页、每页 20 条，最新的在前
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db).page(sql.capture(), argThat(m -> Long.valueOf(9L).equals(m.get("u"))), eq(1), eq(20));
        assertTrue(sql.getValue().endsWith(" order by s.create_time desc, s.settle_id desc"), sql.getValue());
        assertTrue(sql.getValue().contains("where s.app_user_id = :u order by"), "全部 = 不加状态条件：" + sql.getValue());
    }

    @Test
    void statusChipsMapToSettlementStates()
    {
        String[][] cases = {
                { "pending", " and s.status in ('pending_payout', 'payout_registered')" },
                { "confirmed", " and s.status = 'confirmed'" },
                { "disputed", " and s.status = 'disputed'" } };
        for (String[] c : cases)
        {
            reset(db);
            when(db.page(anyString(), anyMap(), any(), any())).thenReturn(page(Collections.<Map<String, Object>>emptyList()));
            Map<String, Object> out = service.income(9L, c[0], 2, 10);
            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(db).page(sql.capture(), anyMap(), eq(2), eq(10));
            assertTrue(sql.getValue().contains(c[1] + " order by"), c[0] + " → " + sql.getValue());
            assertEquals(c[0], out.get("status"));
        }
        // 不认识的筛选值按全部，不能拼进 SQL
        reset(db);
        when(db.page(anyString(), anyMap(), any(), any())).thenReturn(page(Collections.<Map<String, Object>>emptyList()));
        Map<String, Object> out = service.income(9L, "' or 1=1 --", 1, 20);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db).page(sql.capture(), anyMap(), any(), any());
        assertFalse(sql.getValue().contains("1=1"), sql.getValue());
        assertEquals("all", out.get("status"));
    }

    @Test
    void incomeIsReadOnly()
    {
        service.income(9L, "pending", 1, 20);
        verify(db, never()).update(anyString(), anyMap());
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    void labelsForEveryState()
    {
        assertEquals("待打款", KgIncomeService.label("pending_payout"));
        assertEquals("待确认到账", KgIncomeService.label("payout_registered"));
        assertEquals("已到账", KgIncomeService.label("confirmed"));
        assertEquals("申诉中", KgIncomeService.label("disputed"));
        assertEquals("ochre", KgIncomeService.tone("pending_payout"));
        assertEquals("blue", KgIncomeService.tone("payout_registered"));
        assertEquals("red", KgIncomeService.tone("disputed"));
    }
}
