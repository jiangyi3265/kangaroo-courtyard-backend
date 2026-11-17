package com.ruoyi.kangaroo.ext.stay;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.pay.KgPayService;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.security.AppLoginUser;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 门店主动退款（旅居订单台）：只有主理人、不能给自己的单退、金额 0.01 元到剩余可退、原因必填；
 * 待入住全额退 → 取消并释放房量，部分退 / 入住后退 → 订单状态不变；退款审核中 → 按自定义金额同意；
 * 同一提交编号重放、30 秒内同金额连点、微信还有在途退款都不会再退一次。
 * 2026-10-13：微信渠道只登记了退款时不跟客人说「钱会原路退回」；被微信关闭的退款不再挡下一笔；订单台说清楚卡在哪。
 */
class KgStayStaffRefundTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final KgPayService pay = mock(KgPayService.class);
    private final StaffSupport support = mock(StaffSupport.class);
    private final KgStayOrderService orders = new KgStayOrderService();
    private final LocalDate today = LocalDate.now();
    private static final String NO = "S2610110001";

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(orders, "db", db);
        ReflectionTestUtils.setField(orders, "pay", pay);
        ReflectionTestUtils.setField(orders, "support", support);
        when(db.one(contains("select order_id, store_id, pay_no from kg_stay_order"), anyMap())).thenReturn(p("orderId", 9L, "storeId", 102L, "payNo", "KP1"));
        // KgJdbc.one 查不到返回 null；Mockito 对 Map 默认返回空 Map，这里显式说明「这个提交编号没用过」
        when(db.one(contains("idem_key = :k"), anyMap())).thenReturn(null);
        when(db.update(contains("set status = 'refunding'"), anyMap())).thenReturn(1);
        when(db.must(contains("from kg_stay_staff_refund where refund_id"), anyMap(), anyString()))
                .thenAnswer(inv -> p("refundId", 1L, "kind", lastKind, "amountFen", lastAmount, "status", lastStatus));
    }

    private String lastKind = "partial";
    private long lastAmount = 0;
    /** 退款后读回的门店退款记录状态：联调渠道当场退成（success），微信渠道只登记、等系统提交微信（processing） */
    private String lastStatus = "success";

    private Map<String, Object> order(String status, long paidFen, long refundedFen)
    {
        Map<String, Object> o = new HashMap<String, Object>(p("orderId", 9L, "orderNo", NO, "storeId", 102L, "appUserId", 500L, "payNo", "KP1",
                "status", status, "checkIn", today.plusDays(3).toString(), "checkOut", today.plusDays(5).toString(), "units", 1, "roomTypeId", 31L,
                "payFen", paidFen, "refundFen", 0));
        when(db.must(contains("from kg_stay_order where order_id = :id for update"), anyMap(), anyString())).thenReturn(o);
        when(db.must(contains("from kg_pay_order where pay_no = :n and del_flag = '0' for update"), anyMap(), anyString()))
                .thenReturn(p("payNo", "KP1", "amount", paidFen, "refundAmount", refundedFen, "status", refundedFen > 0 ? "part_refunded" : "paid",
                        "channel", "mock", "storeId", 102L));
        return o;
    }

    private Map<String, Object> refund(long amount, String reason)
    {
        return orders.staffRefund(102L, NO, amount, reason, "tap-1", 1L, "黑子");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> insertedRow()
    {
        ArgumentCaptor<Map<String, Object>> row = ArgumentCaptor.forClass(Map.class);
        verify(db).insert(eq("kg_stay_staff_refund"), row.capture());
        return row.getValue();
    }

    // ---------------- 权限 ----------------

    @Test
    void onlyTheStoreManagerCanRefund()
    {
        KgStayPmsService pms = new KgStayPmsService();
        KgStayOrderService mocked = mock(KgStayOrderService.class);
        ReflectionTestUtils.setField(pms, "db", db);
        ReflectionTestUtils.setField(pms, "support", support);
        ReflectionTestUtils.setField(pms, "stayOrders", mocked);
        for (String role : new String[] { "staff", "keeper", "online_staff" })
        {
            StaffSupport.Ctx c = new StaffSupport.Ctx();
            c.storeId = 102L; c.role = role; c.me = new AppLoginUser(); c.me.setUserId(2L);
            ServiceException e = assertThrows(ServiceException.class, () -> pms.refund(c, NO, 100, "补偿", "k"));
            assertEquals(Integer.valueOf(403), e.getCode(), role);
        }
        verify(mocked, never()).staffRefund(any(), any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void cannotRefundAnOrderYouPlacedYourself()
    {
        order("confirmed", 10000, 0);
        ServiceException e = assertThrows(ServiceException.class, () -> orders.staffRefund(102L, NO, 100, "补偿", "k", 500L, "自己"));
        assertEquals(Integer.valueOf(403), e.getCode());
        verify(pay, never()).refund(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void orderOfAnotherStoreIsNotFound()
    {
        order("confirmed", 10000, 0);
        assertEquals(Integer.valueOf(404), assertThrows(ServiceException.class, () -> orders.staffRefund(104L, NO, 100, "补偿", "k", 1L, "黑子")).getCode());
    }

    // ---------------- 金额与原因 ----------------

    @Test
    void amountMustBeBetweenOneFenAndWhatIsLeft()
    {
        order("checked_in", 10000, 3000);
        assertEquals(Integer.valueOf(422), assertThrows(ServiceException.class, () -> refund(0, "补偿")).getCode());
        assertEquals(Integer.valueOf(422), assertThrows(ServiceException.class, () -> refund(-5, "补偿")).getCode());
        ServiceException over = assertThrows(ServiceException.class, () -> refund(7001, "补偿"));
        assertEquals(Integer.valueOf(422), over.getCode());
        assertTrue(over.getMessage().contains("最多还能退 ¥70"), over.getMessage());
        verify(pay, never()).refund(anyString(), anyLong(), anyString(), anyString());
        lastAmount = 7000;
        refund(7000, "空调坏了一晚");
        verify(pay).refund("KP1", 7000L, "门店退款：空调坏了一晚", "staff:1");
    }

    @Test
    void reasonIsRequiredAndAtMostOneHundredChars()
    {
        order("confirmed", 10000, 0);
        assertEquals(Integer.valueOf(422), assertThrows(ServiceException.class, () -> refund(100, "  ")).getCode());
        assertEquals(Integer.valueOf(422), assertThrows(ServiceException.class, () -> refund(100, String.join("", Collections.nCopies(101, "退")))).getCode());
        assertEquals(Integer.valueOf(422), assertThrows(ServiceException.class, () -> KgStayPmsService.parseFen("12.5")).getCode());
        assertEquals(Integer.valueOf(422), assertThrows(ServiceException.class, () -> KgStayPmsService.parseFen("-100")).getCode());
        assertEquals(1250L, KgStayPmsService.parseFen("1250"));
    }

    // ---------------- 全额 / 部分 ----------------

    @Test
    void fullRefundBeforeCheckInCancelsAndReleasesInventoryOnce()
    {
        order("confirmed", 10000, 0);
        lastKind = "full"; lastAmount = 10000;
        Map<String, Object> r = refund(10000, "客人临时有事，门店全退");
        verify(db).update(contains("set status = 'refunding'"), argThat(m -> "confirmed".equals(m.get("st")) && Long.valueOf(10000).equals(m.get("f"))));
        verify(db, times(1)).update(contains("update kg_stay_calendar set occupied = greatest(0, occupied - :u)"), anyMap());
        verify(db).update(contains("update kg_shop_order set status = 'refund_pending'"), anyMap());
        verify(pay).refund("KP1", 10000L, "门店退款：客人临时有事，门店全退", "staff:1");
        assertEquals("full", insertedRow().get("kind"));
        assertEquals("full", r.get("kind"));
        verify(support).notifyUser(eq(500L), anyString(), eq("门店给你退款 ¥100"), contains("这张订单已取消"), eq("stay_order"), eq(9L), contains("view=refund"));
    }

    @Test
    void partialRefundBeforeCheckInKeepsTheOrder()
    {
        order("confirmed", 10000, 0);
        lastAmount = 3000;
        Map<String, Object> r = refund(3000, "房间升级差价退回");
        verify(db, never()).update(contains("set status = 'refunding'"), anyMap());
        verify(db, never()).update(contains("kg_stay_calendar"), anyMap());
        verify(pay).refund("KP1", 3000L, "门店退款：房间升级差价退回", "staff:1");
        assertEquals("partial", insertedRow().get("kind"));
        assertTrue(String.valueOf(r.get("message")).contains("订单状态不变"));
        verify(support).notifyUser(eq(500L), anyString(), eq("门店给你退款 ¥30"), contains("门店给你退款 ¥30：房间升级差价退回"), eq("stay_order"), eq(9L), contains("view=trip"));
    }

    // ---------- 客人消息：退成了才说「原路退回」（2026-10-13） ----------

    @Test
    void mockRefundThatAlreadySucceededKeepsTodaysWording()
    {
        order("confirmed", 10000, 0);
        lastAmount = 3000;
        Map<String, Object> r = refund(3000, "房间升级差价退回");
        assertEquals(Boolean.FALSE, r.get("processing"));
        assertEquals("已退款 ¥30，订单状态不变，钱已原路退回客人", r.get("message"));
        verify(support).notifyUser(eq(500L), anyString(), eq("门店给你退款 ¥30"),
                eq("门店给你退款 ¥30：房间升级差价退回。订单 " + NO + "，钱会原路退回你的微信支付，到账时间以微信为准。订单照常有效。"), eq("stay_order"), eq(9L), contains("view=trip"));
    }

    @Test
    void wechatRefundStillProcessingDoesNotPromiseTheMoney()
    {
        // 10-13：微信渠道这一步只登记了退款，客人却收到「钱会原路退回」，结果商户号余额不足、钱没退出去
        order("confirmed", 10000, 0);
        lastAmount = 400; lastStatus = "processing";
        Map<String, Object> r = refund(400, "空调噪音补偿");
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(support).notifyUser(eq(500L), anyString(), eq("门店为你发起退款 ¥4"), text.capture(), eq("stay_order"), eq(9L), contains("view=trip"));
        assertEquals("门店为你发起退款 ¥4：空调噪音补偿。订单 " + NO + "，正在提交微信支付处理，退款成功后会再通知你。订单照常有效。", text.getValue());
        assertFalse(text.getValue().contains("原路退回"));
        assertEquals(Boolean.TRUE, r.get("processing"));
        assertEquals("已发起退款 ¥4，订单状态不变。退款已登记，系统正在提交微信支付，退款成功后会通知客人", r.get("message"));
    }

    @Test
    void wechatFullRefundStillProcessingSaysTheOrderIsCancelledButNotThatTheMoneyArrived()
    {
        order("confirmed", 10000, 0);
        lastKind = "full"; lastAmount = 10000; lastStatus = "processing";
        Map<String, Object> r = refund(10000, "客人临时有事，门店全退");
        verify(support).notifyUser(eq(500L), anyString(), eq("门店为你发起退款 ¥100"),
                eq("门店为你发起退款 ¥100：客人临时有事，门店全退。订单 " + NO + "，正在提交微信支付处理，退款成功后会再通知你。这张订单已取消。"),
                eq("stay_order"), eq(9L), contains("view=refund"));
        assertTrue(String.valueOf(r.get("message")).startsWith("已发起全额退款 ¥100，订单已取消"), String.valueOf(r.get("message")));
    }

    @Test
    void refundingEverythingAfterCheckInKeepsTheStatus()
    {
        order("checked_in", 10000, 0);
        lastAmount = 10000;
        refund(10000, "停水一整天，全部退还");
        verify(db, never()).update(contains("set status = 'refunding'"), anyMap());
        verify(db, never()).update(contains("kg_stay_calendar"), anyMap());
        assertEquals("partial", insertedRow().get("kind"));
        verify(pay).refund(eq("KP1"), eq(10000L), anyString(), anyString());
    }

    @Test
    void pendingApplicationIsApprovedWithTheCustomAmount()
    {
        order("refund_pending", 10000, 0);
        lastKind = "approve"; lastAmount = 6000;
        refund(6000, "协商后退六成");
        verify(db).update(contains("set status = 'refunding'"), argThat(m -> "refund_pending".equals(m.get("st")) && Long.valueOf(6000).equals(m.get("f"))));
        verify(db, times(1)).update(contains("kg_stay_calendar"), anyMap());
        assertEquals("approve", insertedRow().get("kind"));
    }

    @Test
    void latePaymentOnClosedOrderMustBeRefundedInFullWithoutReleasingAgain()
    {
        Map<String, Object> o = order("refund_pending", 10000, 0);
        o.put("closeTime", "2026-10-11 10:00:00");
        assertEquals(Integer.valueOf(422), assertThrows(ServiceException.class, () -> refund(5000, "协商")).getCode());
        lastKind = "approve"; lastAmount = 10000;
        refund(10000, "订单已关闭，全额退回");
        verify(db, never()).update(contains("kg_stay_calendar"), anyMap());
    }

    @Test
    void closedCancelledOrRefundingOrdersAreRefused()
    {
        for (String st : new String[] { "cancelled", "refunding", "closed", "pending_payment" })
        {
            order(st, 10000, 0);
            assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class, () -> refund(100, "补偿")).getCode(), st);
        }
        verify(pay, never()).refund(anyString(), anyLong(), anyString(), anyString());
    }

    // ---------------- 重复提交 / 并发 ----------------

    @Test
    void sameSubmissionReplaysWithoutRefundingTwice()
    {
        order("confirmed", 10000, 3000);
        when(db.one(contains("idem_key = :k"), anyMap())).thenReturn(p("refundId", 7L, "kind", "partial", "amountFen", 3000L, "status", "success"));
        Map<String, Object> r = refund(3000, "房间升级差价退回");
        assertEquals(Boolean.TRUE, r.get("replay"));
        assertTrue(String.valueOf(r.get("message")).contains("刚才已经提交过"));
        verify(pay, never()).refund(anyString(), anyLong(), anyString(), anyString());
        verify(db, never()).insert(eq("kg_stay_staff_refund"), anyMap());
    }

    @Test
    void replayOfARefundClosedByWechatDoesNotSayTheMoneyWentBack()
    {
        // 网络失败后用同一提交编号再点一次，这时那笔已被微信关闭：不能回「钱已原路退回客人」，也不能再退一次
        order("checked_in", 10000, 0);
        when(db.one(contains("idem_key = :k"), anyMap())).thenReturn(p("refundId", 7L, "kind", "partial", "amountFen", 400L, "status", "closed"));
        Map<String, Object> r = refund(400, "补偿");
        assertEquals("这笔退款刚才已经提交过：退款 ¥4 被微信关闭，钱没有退出，可以重新发起", r.get("message"));
        assertEquals(Boolean.FALSE, r.get("processing"));
        verify(pay, never()).refund(anyString(), anyLong(), anyString(), anyString());
        when(db.one(contains("idem_key = :k"), anyMap())).thenReturn(p("refundId", 8L, "kind", "full", "amountFen", 10000L, "status", "closed"));
        order("refunding", 10000, 0);
        assertEquals("这笔退款刚才已经提交过：全额退款 ¥100 被微信关闭，钱没有退出，订单停在退款中，请联系平台在后台重新退款", refund(10000, "全退").get("message"));
    }

    @Test
    void sameAmountWithinThirtySecondsIsTreatedAsADoubleTap()
    {
        order("confirmed", 10000, 3000);
        when(db.count(contains("interval 30 second"), anyMap())).thenReturn(1L);
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class, () -> orders.staffRefund(102L, NO, 3000, "再点一次", "tap-2", 1L, "黑子")).getCode());
        verify(pay, never()).refund(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void anotherRefundWaitingForWechatBlocksANewOne()
    {
        order("checked_in", 10000, 0);
        when(db.count(contains("from kg_pay_refund"), anyMap())).thenReturn(1L);
        ServiceException e = assertThrows(ServiceException.class, () -> refund(100, "补偿"));
        assertEquals(Integer.valueOf(409), e.getCode());
        assertTrue(e.getMessage().contains("等微信确认"));
    }

    @Test
    void refundClosedByWechatNoLongerBlocksTheNextOne()
    {
        // 只有排队 / 处理中 / 异常的微信退款挡新退款；被微信关闭（钱没退出）的不挡。10-13 之前写的是 status <> 'success'，
        // 一笔被关闭的退款会让这张单再也退不了
        order("checked_in", 10000, 0);
        lastAmount = 400;
        refund(400, "补偿");
        verify(db).count(argThat((String s) -> s.contains("from kg_pay_refund") && s.contains("status in ('queued','processing','abnormal')") && !s.contains("<>")), anyMap());
        verify(pay).refund("KP1", 400L, "门店退款：补偿", "staff:1");
        // 微信关闭回调：那笔门店退款记成没退成（closed），订单台、客人申请都不再被它挡住
        orders.onRefundClosed(p("bizId", 9L, "payNo", "KP1"), 400L);
        verify(db).update(contains("update kg_stay_staff_refund set status = 'closed' where order_id = :id and status = 'processing'"),
                argThat(m -> Long.valueOf(9).equals(m.get("id")) && Long.valueOf(400).equals(m.get("a"))));
        verify(db, never()).update(contains("update kg_stay_order"), anyMap());
    }

    @Test
    void closedStoreRefundNeitherBlocksTheGuestNorCountsAsRefunded()
    {
        Map<String, Object> o = new HashMap<String, Object>(p("orderId", 9L, "orderNo", NO, "status", "confirmed", "payNo", "KP1",
                "checkIn", today.plusDays(30).toString(), "checkOut", today.plusDays(32).toString(), "units", 1, "roomFen", 10000, "payFen", 10000,
                "policySnapshot", "{\"code\":\"moderate\"}", "staySnapshot", "{\"checkinTime\":\"15:00\"}", "nightly", "[{\"priceFen\":5000}]"));
        when(db.one(contains("select refund_amount from kg_pay_order"), anyMap())).thenReturn(p("refundAmount", 0L));
        List<Map<String, Object>> closed = new ArrayList<Map<String, Object>>();
        closed.add(p("amountFen", 400L, "status", "closed", "kind", "partial"));
        when(db.list(contains("from kg_stay_staff_refund"), anyMap())).thenReturn(closed);
        Map<String, Object> r = orders.preview(o);
        assertEquals(Boolean.TRUE, r.get("allowed"));
        assertEquals(10000L, ((Number) r.get("refundFen")).longValue());
        assertNull(r.get("refundedFen"));
    }

    @Test
    void orderDeskExplainsWhyTheWechatRefundIsStuck()
    {
        KgStayPmsService pms = new KgStayPmsService();
        ReflectionTestUtils.setField(pms, "db", db);
        when(db.one(contains("from kg_pay_refund"), anyMap())).thenReturn(p("status", "queued", "lastError", "NOT_ENOUGH"));
        assertEquals("　等微信确认到账；微信提示：商户号可用余额不足，系统每分钟自动重试", pms.refundWaitText("KP1"));
        verify(db).one(argThat((String s) -> s.contains("from kg_pay_refund") && s.contains("status in ('queued','processing','abnormal')")), anyMap());
        when(db.one(contains("from kg_pay_refund"), anyMap())).thenReturn(p("status", "processing"));
        assertEquals("　等微信确认到账", pms.refundWaitText("KP1"));
        when(db.one(contains("from kg_pay_refund"), anyMap())).thenReturn(p("status", "abnormal"));
        assertTrue(pms.refundWaitText("KP1").contains("微信支付商户平台的退款管理"));
    }

    @Test
    void locksPaymentBeforeTheOrderAndChecksTheChannel()
    {
        order("confirmed", 10000, 0);
        lastAmount = 100;
        refund(100, "补偿");
        org.mockito.InOrder seq = inOrder(db, pay);
        seq.verify(db).must(contains("from kg_pay_order where pay_no = :n and del_flag = '0' for update"), anyMap(), anyString());
        seq.verify(db).must(contains("from kg_stay_order where order_id = :id for update"), anyMap(), anyString());
        seq.verify(pay).requireRefundable(anyMap());
        seq.verify(pay).refund(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void channelNotOpenForTheStoreStopsBeforeAnyChange()
    {
        order("confirmed", 10000, 0);
        doThrow(new ServiceException("这家门店还没有开通微信收款，不能在这里退款，请联系平台在后台处理", 409)).when(pay).requireRefundable(anyMap());
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class, () -> refund(10000, "全退")).getCode());
        verify(db, never()).update(contains("set status = 'refunding'"), anyMap());
        verify(db, never()).insert(eq("kg_stay_staff_refund"), anyMap());
    }

    // ---------------- 到账回调与客人退款预览 ----------------

    @Test
    void refundCallbackMarksTheStoreRefundAsArrived()
    {
        when(db.one(contains("from kg_stay_order where order_id = :id for update"), anyMap()))
                .thenReturn(new HashMap<String, Object>(p("orderId", 9L, "orderNo", NO, "status", "checked_in", "payFen", 10000, "appUserId", 500L)));
        orders.onRefunded(p("bizId", 9L, "refundAmount", 3000L, "status", "part_refunded"), 3000L);
        verify(db).update(contains("update kg_stay_staff_refund set status = 'success'"), argThat(m -> Long.valueOf(3000).equals(m.get("a"))));
        verify(db).update(contains("set refund_fen = :t"), argThat(m -> Long.valueOf(3000).equals(m.get("t"))));
    }

    @Test
    void guestPreviewSubtractsWhatTheStoreAlreadyRefunded()
    {
        Map<String, Object> o = new HashMap<String, Object>(p("orderId", 9L, "orderNo", NO, "status", "confirmed", "payNo", "KP1",
                "checkIn", today.plusDays(30).toString(), "checkOut", today.plusDays(32).toString(), "units", 1, "roomFen", 10000, "payFen", 10000,
                "policySnapshot", "{\"code\":\"moderate\"}", "staySnapshot", "{\"checkinTime\":\"15:00\"}", "nightly", "[{\"priceFen\":5000}]"));
        when(db.one(contains("select refund_amount from kg_pay_order"), anyMap())).thenReturn(p("refundAmount", 3000L));
        when(db.list(contains("from kg_stay_staff_refund"), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        Map<String, Object> r = orders.preview(o);
        assertEquals(7000L, r.get("refundFen"));
        assertEquals(3000L, r.get("refundedFen"));
        assertEquals(Boolean.TRUE, r.get("allowed"));
        // 门店退款还在等微信确认：先不让客人申请
        List<Map<String, Object>> pending = new ArrayList<Map<String, Object>>();
        pending.add(p("amountFen", 1000L, "status", "processing", "kind", "partial"));
        when(db.list(contains("from kg_stay_staff_refund"), anyMap())).thenReturn(pending);
        Map<String, Object> blocked = orders.preview(o);
        assertEquals(Boolean.FALSE, blocked.get("allowed"));
        assertEquals(6000L, blocked.get("refundFen"));
        assertTrue(String.valueOf(blocked.get("reason")).contains("等微信确认"));
    }
}
