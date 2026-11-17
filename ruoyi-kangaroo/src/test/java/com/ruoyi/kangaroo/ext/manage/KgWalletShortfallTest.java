package com.ruoyi.kangaroo.ext.manage;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;

/**
 * 2026-11-09 主人拍板：主理人结算申请通过后订单退款冲回、余额变负，打款前要提示。
 * 情形：入账 159.60 → 申请结算 100 → 后台通过 → 订单退款冲回 -159.60（另有 3.00 收益），真实余额 3.00 - 100 = -97.00。
 */
class KgWalletShortfallTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final StaffSupport support = mock(StaffSupport.class);
    private final KgWalletService wallet = new KgWalletService();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(wallet, "db", db);
        ReflectionTestUtils.setField(wallet, "support", support);
        ReflectionTestUtils.setField(wallet, "fourEyes", true);
        when(db.one(contains("from kg_wallet_entry where app_user_id"), anyMap())).thenReturn(p("avail", 300L, "pend", 0L));
        when(db.count(contains("from kg_wallet_withdraw where app_user_id = :u and status in ('pending','approved','paid')"), anyMap())).thenReturn(10000L);
    }

    private void withdraw(String status)
    {
        when(db.must(contains("from kg_wallet_withdraw where withdraw_id"), anyMap(), anyString())).thenReturn(
                p("withdrawId", 5L, "appUserId", 9L, "amountFen", 10000L, "status", status, "auditedBy", "admin:a", "auditNote", "核对无误"));
    }

    @Test
    void realBalanceIsNotClampedToZero()
    {
        assertEquals(-9700L, wallet.realBalance(9L));
        assertEquals(Long.valueOf(0L), wallet.balance(9L).get("available"));
    }

    @Test
    void approvedRowWithNegativeBalanceIsMarkedAndPaidNeedsConfirm()
    {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(p("withdrawId", 5L, "appUserId", 9L, "amountFen", 10000L, "status", "approved"));
        rows.add(p("withdrawId", 6L, "appUserId", 9L, "amountFen", 10000L, "status", "paid"));
        wallet.markShortfall(rows);
        assertEquals("余额不足：当前 -97.00", rows.get(0).get("extNote"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> confirm = (Map<String, Map<String, Object>>) rows.get(0).get("extConfirm");
        assertEquals("confirmShortfall", confirm.get("paid").get("param"));
        assertTrue(String.valueOf(confirm.get("paid").get("text")).contains("97.00"));
        assertNull(rows.get(1).get("extNote"));
    }

    @Test
    void paidIsRefusedUntilShortfallIsConfirmed()
    {
        withdraw("approved");
        ServiceException e = assertThrows(ServiceException.class, () -> wallet.audit(5L, "paid", "流水 123", "admin:b"));
        assertEquals(Integer.valueOf(409), e.getCode());
        assertTrue(e.getMessage().contains("-97.00") && e.getMessage().contains("¥97.00"), e.getMessage());
        verify(db, never()).update(contains("status = 'paid'"), anyMap());
        wallet.audit(5L, "paid", "流水 123", "admin:b", true);
        verify(db).update(contains("status = 'paid'"), argThat(m -> String.valueOf(m.get("n")).contains("已确认仍打款")));
    }

    @Test
    void paidWithEnoughBalanceNeedsNoConfirm()
    {
        when(db.one(contains("from kg_wallet_entry where app_user_id"), anyMap())).thenReturn(p("avail", 16260L, "pend", 0L));
        withdraw("approved");
        wallet.audit(5L, "paid", "流水 123", "admin:b");
        verify(db).update(contains("status = 'paid'"), argThat(m -> "核对无误".equals(m.get("n"))));
    }

    @Test
    void approvedButUnpaidCanBeRevokedWithReason()
    {
        withdraw("approved");
        assertThrows(ServiceException.class, () -> wallet.audit(5L, "revoke", " ", "admin:b"));
        assertThrows(ServiceException.class, () -> wallet.audit(5L, "reject", "余额不足", "admin:b"));
        wallet.audit(5L, "revoke", "订单退款后余额不足", "admin:b");
        verify(db).update(contains("status = 'rejected'"), argThat(m -> String.valueOf(m.get("n")).startsWith(KgWalletService.REVOKE_PREFIX + "订单退款后余额不足")));
        verify(support).notifyUser(eq(9L), anyString(), eq("结算申请已退回"), contains("金额已退回可结算余额"), eq("wallet_withdraw"), eq(5L), anyString());
        withdraw("paid");
        assertThrows(ServiceException.class, () -> wallet.audit(5L, "revoke", "晚了", "admin:b"));
    }
}
