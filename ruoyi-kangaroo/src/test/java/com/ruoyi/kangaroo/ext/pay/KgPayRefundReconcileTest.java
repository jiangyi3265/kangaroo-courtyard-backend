package com.ruoyi.kangaroo.ext.pay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.config.KangarooProperties;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;

/**
 * 微信退款对账（2026-10-13 线上事故：门店给客人退 ¥6 / ¥4，商户号余额不足一直没退出去，谁都不知道）：
 * 要有人处理的错误码每出现一种新的就告诉门店一次（同一个错误每分钟重试不重复发），临时错误不发；
 * 微信关闭 / 退款异常第一次出现时告诉门店；整个对账事务回滚时另开小事务留下 attempts / last_error；
 * 被微信关闭的退款不再挡下一笔退款；错误码翻成白话。DB、微信网关都是替身。
 */
class KgPayRefundReconcileTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final WxPayGateway wechat = mock(WxPayGateway.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final StaffSupport support = mock(StaffSupport.class);
    private final KgPayListener stay = mock(KgPayListener.class);
    private final KgPayService service = new KgPayService();
    private Map<String, Object> refundRow;

    @BeforeEach
    void setup()
    {
        KangarooProperties properties = mock(KangarooProperties.class);
        when(properties.getPayMode()).thenReturn("wechat");
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "wechat", wechat);
        ReflectionTestUtils.setField(service, "transactionManager", txManager);
        ReflectionTestUtils.setField(service, "support", support);
        ReflectionTestUtils.setField(service, "properties", properties);
        ReflectionTestUtils.setField(service, "listeners", Collections.singletonList(stay));
        when(stay.bizType()).thenReturn("stay_order");
        when(wechat.enabled()).thenReturn(true);
        List<Map<String, Object>> due = new ArrayList<Map<String, Object>>();
        due.add(p("refundNo", "KR1", "payNo", "KP1"));
        when(db.list(contains("from kg_pay_refund where status in ('queued','processing','abnormal')"), anyMap())).thenReturn(due);
        when(db.must(contains("select pay_id from kg_pay_order"), anyMap(), anyString())).thenReturn(p("payId", 1L));
        when(db.must(contains("select * from kg_pay_order where pay_no = :n"), anyMap(), anyString()))
                .thenReturn(p("payNo", "KP1", "bizType", "stay_order", "bizId", 9L, "storeId", 102L, "amount", 600L, "refundAmount", 0L,
                        "status", "paid", "channel", "wechat", "channelTxn", "4200002026101300000000001"));
        when(db.must(contains("from kg_wx_payment"), anyMap(), anyString())).thenReturn(p("payNo", "KP1", "wxMchid", "1700000000", "wxAppid", "wx0123456789abcdef"));
        refundRow = new HashMap<String, Object>(p("refundNo", "KR1", "payNo", "KP1", "amount", 600L, "reason", "门店退款：补偿", "operator", "staff:1",
                "status", "queued", "lastError", null));
        when(db.must(contains("from kg_pay_refund where refund_no=:n for update"), anyMap(), anyString())).thenAnswer(inv -> refundRow);
        when(db.one(contains("from kg_stay_order where order_id=:id"), anyMap())).thenReturn(p("orderNo", "S2610130001", "status", "checked_in"));
    }

    private void wechatRejects(String code)
    {
        when(wechat.queryRefund("KR1")).thenReturn(null);
        when(wechat.refund(anyMap(), anyMap())).thenThrow(new WxPayGateway.GatewayException(code));
    }

    // ---------------- 微信不受理：告诉门店，一种错误只发一次 ----------------

    @Test
    void notEnoughBalanceTellsTheStoreOnceWithWhatHappensNext()
    {
        wechatRejects("NOT_ENOUGH");
        service.reconcileWechat();
        verify(db).update(contains("set last_error=:e"), argThat(m -> "NOT_ENOUGH".equals(m.get("e"))));
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(support).notifyStore(eq(102L), eq("order"), eq("退款没能提交到微信"), text.capture(), eq("stay_order"), eq(9L),
                eq("/pages/staff/stay-orders?orderNo=S2610130001&storeId=102"));
        assertEquals("订单 S2610130001 退给客人的 ¥6 没能提交到微信：商户号可用余额不足（NOT_ENOUGH）。系统每分钟自动重试，"
                + "请联系平台给微信支付商户号补足可用余额，补足后会自动退成功；不要在微信支付商户平台手动再退，否则可能重复退款。", text.getValue());

        // 下一分钟还是余额不足：last_error 已经是 NOT_ENOUGH，不再重复发
        refundRow.put("lastError", "NOT_ENOUGH");
        service.reconcileWechat();
        verify(support, times(1)).notifyStore(any(), any(), any(), any(), any(), any(), any());
    }

    // ---------------- 10-15：老资金流商户的余额不足，改从可用余额再提交一次 ----------------

    @Test
    void unsettledFundsNotEnoughRetriesFromAvailableBalanceWithTheSameRefundNo()
    {
        when(wechat.queryRefund("KR1")).thenReturn(null);
        when(wechat.refund(anyMap(), anyMap())).thenThrow(new WxPayGateway.GatewayException("NOT_ENOUGH", "交易未结算资金不足，请使用可用余额退款"));
        when(wechat.refund(anyMap(), anyMap(), eq(true))).thenReturn(remote("PROCESSING"));
        service.reconcileWechat();
        verify(wechat).refund(anyMap(), argThat(r -> "KR1".equals(r.get("refundNo"))), eq(true));
        verify(db, never()).update(contains("set last_error=:e"), anyMap());
        verify(support, never()).notifyStore(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void plainNotEnoughDoesNotRetryAndIsRecorded()
    {
        when(wechat.queryRefund("KR1")).thenReturn(null);
        when(wechat.refund(anyMap(), anyMap())).thenThrow(new WxPayGateway.GatewayException("NOT_ENOUGH", "基本账户余额不足，请充值后重新发起"));
        service.reconcileWechat();
        verify(wechat, never()).refund(anyMap(), anyMap(), eq(true));
        verify(db).update(contains("set last_error=:e"), argThat(m -> "NOT_ENOUGH".equals(m.get("e"))));
    }

    @Test
    void gatewayDetailIsOneShortLine()
    {
        WxPayGateway.GatewayException e = new WxPayGateway.GatewayException("NOT_ENOUGH", "第一行\r\n第二行\t" + new String(new char[200]).replace('\0', '字'));
        assertEquals(120, e.detail.length());
        assertFalse(e.detail.contains("\n") || e.detail.contains("\r") || e.detail.contains("\t"), e.detail);
        assertNull(new WxPayGateway.GatewayException("NOT_ENOUGH", "  ").detail);
    }

    @Test
    void aDifferentPermanentErrorIsANewNotice()
    {
        refundRow.put("lastError", "NOT_ENOUGH");
        wechatRejects("NO_AUTH");
        service.reconcileWechat();
        verify(support).notifyStore(eq(102L), eq("order"), eq("退款没能提交到微信"), contains("商户号没有退款权限（NO_AUTH）"), eq("stay_order"), eq(9L), anyString());
    }

    @Test
    void transientErrorsOnlyRetry()
    {
        for (String code : new String[] { "CHANNEL_UNAVAILABLE", "SYSTEM_ERROR", "FREQUENCY_LIMITED" })
        {
            reset(wechat);
            when(wechat.enabled()).thenReturn(true);
            wechatRejects(code);
            service.reconcileWechat();
        }
        verify(support, never()).notifyStore(any(), any(), any(), any(), any(), any(), any());
        verify(db, times(3)).update(contains("set last_error=:e"), anyMap());
    }

    // ---------------- 对账事务整个回滚：另开小事务留下痕迹 ----------------

    @Test
    void unexpectedFailureStillLeavesAttemptsAndLastError()
    {
        when(wechat.queryRefund("KR1")).thenThrow(new ServiceException("退款通知与退款单不匹配", 409));
        service.reconcileWechat();
        verify(txManager, atLeastOnce()).rollback(any());
        verify(db).update(argThat((String s) -> s.contains("attempts=attempts+1,last_error=case when last_error in (") && s.contains("'NOT_ENOUGH'")
                        && s.contains("then last_error else :e end,next_attempt_time=date_add(now(),interval 60 second)") && s.contains("status in ('queued','processing','abnormal')")),
                argThat(m -> "ServiceException".equals(m.get("e")) && "KR1".equals(m.get("n"))));
        verify(support, never()).notifyStore(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aBlipBetweenTwoBalanceErrorsDoesNotTellTheStoreAgain()
    {
        // 余额不足已经通知过；中间某一分钟网络超时，不能把记下的 NOT_ENOUGH 换掉，否则下一分钟的余额不足又算新错误再发一遍
        refundRow.put("lastError", "NOT_ENOUGH");
        wechatRejects("CHANNEL_UNAVAILABLE");
        service.reconcileWechat();
        verify(db, never()).update(contains("set last_error=:e"), anyMap());
        reset(wechat);
        when(wechat.enabled()).thenReturn(true);
        wechatRejects("NOT_ENOUGH");
        service.reconcileWechat();
        verify(support, never()).notifyStore(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectionsThatRetryingWillNotFixDoNotPromiseAnAutomaticRefund()
    {
        wechatRejects("INVALID_REQUEST");
        service.reconcileWechat();
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(support).notifyStore(eq(102L), eq("order"), eq("退款没能提交到微信"), text.capture(), anyString(), anyLong(), anyString());
        assertTrue(text.getValue().contains("重试一般不会自己好，请联系平台处理"), text.getValue());
        assertFalse(text.getValue().contains("自动退成功"), text.getValue());
    }

    @Test
    void aQueryErrorOnARefundAlreadyAtWechatIsNotCalledNotSubmitted()
    {
        refundRow.put("status", "processing");
        when(wechat.queryRefund("KR1")).thenThrow(new WxPayGateway.GatewayException("SIGN_ERROR"));
        service.reconcileWechat();
        verify(wechat, never()).refund(anyMap(), anyMap());
        verify(support).notifyStore(eq(102L), eq("order"), eq("查不到微信退款结果"),
                startsWith("订单 S2610130001 退给客人的 ¥6 已提交微信，但查询退款结果时出错：商户签名校验失败"), anyString(), anyLong(), anyString());
    }

    @Test
    void aLateNotificationCannotReopenARefundClosedHere()
    {
        // 本地已记成 closed（不再挡新退款）；迟到 / 重发的「异常」通知不能把它改回在途，也不能再动门店退款记录
        refundRow.put("status", "closed");
        when(db.must(contains("from kg_pay_refund where refund_no=:n and pay_no=:p for update"), anyMap(), anyString())).thenAnswer(inv -> refundRow);
        Map<String, Object> notice = new HashMap<String, Object>(remote("ABNORMAL"));
        notice.remove("status");
        notice.put("refund_status", "ABNORMAL");
        notice.put("mchid", "1700000000");
        when(wechat.notification(anyString(), anyString(), anyString(), anyString(), anyString(), eq(true))).thenReturn(notice);
        service.notifyWechat("{}", "S", "1", "N", "sig", true);
        verify(db, never()).update(contains("update kg_pay_refund set status"), anyMap());
        verify(stay, never()).onRefundClosed(anyMap(), anyLong());
        verify(support, never()).notifyStore(any(), any(), any(), any(), any(), any(), any());
    }

    // ---------------- 微信关闭 / 退款异常：第一次出现时告诉门店 ----------------

    private Map<String, Object> remote(String status)
    {
        return p("out_refund_no", "KR1", "out_trade_no", "KP1", "transaction_id", "4200002026101300000000001", "refund_id", "50300002026101300000000001",
                "status", status, "amount", p("total", 600L, "refund", 600L, "currency", "CNY"));
    }

    @Test
    void closedRefundTellsTheStoreToStartAgainAndReleasesTheBusinessRecord()
    {
        refundRow.put("status", "processing");
        when(wechat.queryRefund("KR1")).thenReturn(remote("CLOSED"));
        service.reconcileWechat();
        verify(db).update(contains("update kg_pay_refund set status=:s"), argThat(m -> "closed".equals(m.get("s"))));
        verify(stay).onRefundClosed(anyMap(), eq(600L));
        verify(support).notifyStore(eq(102L), eq("order"), eq("退款被微信关闭"),
                eq("订单 S2610130001 退给客人的 ¥6 被微信关闭，钱没有退出，请在订单台重新发起。"), eq("stay_order"), eq(9L),
                eq("/pages/staff/stay-orders?orderNo=S2610130001&storeId=102"));
    }

    @Test
    void closedRefundOfACancelledOrderPointsToThePlatform()
    {
        // 全额退 / 同意退款那笔把订单转成了「退款中」，订单台不能再退
        when(db.one(contains("from kg_stay_order where order_id=:id"), anyMap())).thenReturn(p("orderNo", "S2610130001", "status", "refunding"));
        refundRow.put("status", "processing");
        when(wechat.queryRefund("KR1")).thenReturn(remote("CLOSED"));
        service.reconcileWechat();
        verify(support).notifyStore(eq(102L), eq("order"), eq("退款被微信关闭"), contains("订单停在「退款中」，请联系平台在后台重新退款"), eq("stay_order"), eq(9L), anyString());
    }

    @Test
    void abnormalRefundIsAnnouncedOnlyTheFirstTime()
    {
        refundRow.put("status", "processing");
        when(wechat.queryRefund("KR1")).thenReturn(remote("ABNORMAL"));
        service.reconcileWechat();
        verify(support).notifyStore(eq(102L), eq("order"), eq("退款异常"),
                eq("订单 S2610130001 退给客人的 ¥6 退款异常（常见原因是客人银行卡已注销），请在微信支付商户平台的退款管理里处理异常退款。"),
                eq("stay_order"), eq(9L), anyString());
        // 异常的退款每分钟还会查一次：状态没变就不再发
        refundRow.put("status", "abnormal");
        service.reconcileWechat();
        verify(support, times(1)).notifyStore(any(), any(), any(), any(), any(), any(), any());
        verify(stay, never()).onRefundClosed(anyMap(), anyLong());
    }

    // ---------------- 被关闭的退款不挡下一笔 ----------------

    @Test
    void closedRefundNoLongerBlocksANewRefund()
    {
        when(db.must(contains("select * from kg_pay_order where pay_no = :n and del_flag = '0' for update"), anyMap(), anyString()))
                .thenReturn(p("payNo", "KP1", "bizType", "stay_order", "bizId", 9L, "storeId", 102L, "amount", 600L, "refundAmount", 0L,
                        "status", "paid", "channel", "wechat"));
        // 这张支付单只有一笔被微信关闭的退款：按 SQL 的条件模拟能不能查到它
        when(db.one(contains("from kg_pay_refund where pay_no=:n"), anyMap())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            return sql.contains("status in ('queued','processing','abnormal')") ? null : p("refundNo", "KR0", "status", "closed");
        });
        Map<String, Object> r = service.refund("KP1", 600L, "门店退款：重新退", "staff:1");
        assertEquals("processing", r.get("refundStatus"));
        verify(db).insert(eq("kg_pay_refund"), argThat(m -> "KP1".equals(m.get("pay_no")) && Long.valueOf(600).equals(m.get("amount"))));
    }

    @Test
    void anUnfinishedRefundStillBlocks()
    {
        when(db.must(contains("select * from kg_pay_order where pay_no = :n and del_flag = '0' for update"), anyMap(), anyString()))
                .thenReturn(p("payNo", "KP1", "amount", 600L, "refundAmount", 0L, "status", "paid", "channel", "wechat"));
        when(db.one(contains("from kg_pay_refund where pay_no=:n"), anyMap())).thenReturn(p("refundNo", "KR0", "status", "abnormal"));
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class, () -> service.refund("KP1", 600L, "再退一次", "staff:1")).getCode());
        verify(db, never()).insert(eq("kg_pay_refund"), anyMap());
    }

    // ---------------- 错误码翻成白话 ----------------

    @Test
    void refundErrorCodesAreTranslated()
    {
        assertEquals("商户号可用余额不足", KgPayService.refundErrorText("NOT_ENOUGH"));
        assertEquals("商户号没有退款权限", KgPayService.refundErrorText("NO_AUTH"));
        assertEquals("客人的微信账户异常（如已注销）", KgPayService.refundErrorText("USER_ACCOUNT_ABNORMAL"));
        assertEquals("暂时连不上微信支付", KgPayService.refundErrorText("CHANNEL_UNAVAILABLE"));
        assertEquals("SOMETHING_NEW", KgPayService.refundErrorText("SOMETHING_NEW"));
        assertEquals("系统处理出错（ServiceException）", KgPayService.refundErrorText("ServiceException"));
        assertEquals("", KgPayService.refundErrorText(null));
        assertEquals("商户号可用余额不足（NOT_ENOUGH）", KgPayService.refundErrorWithCode("NOT_ENOUGH"));
        assertEquals("SOMETHING_NEW", KgPayService.refundErrorWithCode("SOMETHING_NEW"));
        assertEquals("系统处理出错（ServiceException）", KgPayService.refundErrorWithCode("ServiceException"));
        for (String code : new String[] { "NOT_ENOUGH", "NO_AUTH", "SIGN_ERROR", "PARAM_ERROR", "INVALID_REQUEST", "USER_ACCOUNT_ABNORMAL", "MCH_NOT_EXISTS" })
        {
            assertTrue(KgPayService.PERMANENT_REFUND_ERRORS.contains(code), code);
            assertNotEquals(code, KgPayService.refundErrorText(code), code);
        }
        for (String code : new String[] { "CHANNEL_UNAVAILABLE", "SYSTEM_ERROR", "FREQUENCY_LIMITED", "CHANNEL_ERROR" })
        {
            assertFalse(KgPayService.PERMANENT_REFUND_ERRORS.contains(code), code);
        }
    }

    @Test
    void adminRefundProgressShowsTheTranslation()
    {
        when(db.one(contains("from kg_pay_refund where pay_no=:n order by refund_seq desc limit 1"), anyMap()))
                .thenReturn(p("refundNo", "KR1", "status", "queued", "lastError", "NOT_ENOUGH"));
        assertEquals("退款已登记，系统正在提交微信；退款单号：KR1；渠道提示：商户号可用余额不足（NOT_ENOUGH）", service.refundProgress("KP1"));
        when(db.one(contains("from kg_pay_refund where pay_no=:n order by refund_seq desc limit 1"), anyMap()))
                .thenReturn(p("refundNo", "KR1", "status", "closed", "amount", 600L));
        assertEquals("微信已关闭这笔 ¥6 的退款，钱没有退出，可以重新退款；退款单号：KR1", service.refundProgress("KP1"));
        // 被关闭的只是一部分（如客人按政策退一部分）：后台只有「全额退款」，要提醒会退得更多
        when(db.one(contains("from kg_pay_refund where pay_no=:n order by refund_seq desc limit 1"), anyMap()))
                .thenReturn(p("refundNo", "KR1", "status", "closed", "amount", 450L));
        assertEquals("微信已关闭这笔 ¥4.5 的退款，钱没有退出，可以重新退款（「全额退款」会退回剩下的全部 ¥6，比被关闭的这笔多，请先和门店确认该退多少）；退款单号：KR1",
                service.refundProgress("KP1"));
    }
}
