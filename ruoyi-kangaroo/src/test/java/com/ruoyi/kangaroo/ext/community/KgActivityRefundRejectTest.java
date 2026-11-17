package com.ruoyi.kangaroo.ext.community;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.Date;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.pay.KgPayService;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;

/**
 * 2026-11-09 主人拍板：活动退款被驳回 → 恢复报名、保留名额（满了也恢复，超员 1 人）；
 * 整场取消时「已取消但钱没退」的付款也转退款待审批；重新报名复用旧行时旧支付单号记进 refund_note。不连库。
 */
class KgActivityRefundRejectTest
{
    static final long DAY = 24 * 3600_000L;
    final KgJdbc db = mock(KgJdbc.class);
    final StaffSupport support = mock(StaffSupport.class);
    final KgChatService chats = mock(KgChatService.class);
    final KgPayService pay = mock(KgPayService.class);
    final KgActivityService service = new KgActivityService();

    @BeforeEach
    void wire()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "support", support);
        ReflectionTestUtils.setField(service, "chats", chats);
        ReflectionTestUtils.setField(service, "pay", pay);
        when(db.one(startsWith("select activity_id from kg_activity_signup"), anyMap())).thenReturn(p("activityId", 5L));
        when(db.update(anyString(), anyMap())).thenReturn(1);
    }

    Map<String, Object> activity(String status, long startInMs, long signed, long capacity)
    {
        return p("activityId", 5L, "status", status, "title", "周末手作", "storeId", 9L, "place", "小院", "signed", signed, "capacity", capacity,
                "startTime", new Date(System.currentTimeMillis() + startInMs));
    }

    void given(Map<String, Object> signup, Map<String, Object> activity)
    {
        when(db.must(startsWith("select * from kg_activity_signup"), anyMap(), anyString())).thenReturn(signup);
        when(db.one(startsWith("select * from kg_activity where"), anyMap())).thenReturn(activity);
    }

    static Map<String, Object> pending(String cancelReason)
    {
        return p("signupId", 11L, "activityId", 5L, "appUserId", 7L, "name", "小杨", "status", "refund_pending", "payNo", "KP1", "feeFen", 3000L,
                "refundFen", 3000L, "cancelReason", cancelReason);
    }

    @Test
    void rejectRestoresSignupAndKeepsSeat()
    {
        given(pending("用户取消"), activity("open", 3 * DAY, 4, 10));
        assertEquals("已驳回退款，报名已恢复", service.rejectRefund(11L, " 不符合退款条件 ", "staff:3"));
        verify(db).update(argThat(sql -> sql.startsWith("update kg_activity_signup set status = 'confirmed', refund_fen = 0, cancel_reason = null, cancel_time = null")
                && sql.contains("instr(coalesce(refund_note, ''), '旧支付单')") && sql.contains("status = 'refund_pending'")),
                argThat(m -> "驳回：不符合退款条件（staff:3）".equals(m.get("n")) && Long.valueOf(11L).equals(m.get("s"))));
        verify(db).update(eq("update kg_activity set signed = signed + 1 where activity_id = :id"), eq(p("id", 5L)));
        verify(chats).joinActivityGroup(5L, 7L);
        verify(support).notifyUser(eq(7L), eq("order"), eq("退款未通过，报名已恢复"),
                argThat(t -> t.contains("不符合退款条件") && t.contains("报名已恢复、名额保留")), eq("activity"), eq(5L), eq("/pages/community/activity?signupId=11"));
        verify(support).notifyStore(eq(9L), eq("system"), eq("活动报名已恢复"), argThat(t -> t.startsWith("小杨 的退款未通过") && !t.contains("多出")),
                eq("activity"), eq(5L), anyString());
    }

    @Test
    void fullActivityStillRestoresAndSaysOneOver()
    {
        given(pending("用户取消"), activity("closed", 3 * DAY, 10, 10));
        assertEquals("已驳回退款，报名已恢复（活动名额已满，这场会超出 1 人）", service.rejectRefund(11L, "不符合", "staff:3"));
        // 不带 signed < capacity 条件：满了也加回
        verify(db).update(eq("update kg_activity set signed = signed + 1 where activity_id = :id"), anyMap());
        verify(support).notifyStore(eq(9L), anyString(), anyString(), contains("多出 1 人"), anyString(), anyLong(), anyString());
    }

    @Test
    void cancelledStartedOrLatePaidCannotBeRejected()
    {
        Object[][] cases = {
            { pending("活动取消：下雨"), activity("cancelled", 3 * DAY, 0, 10), "整场取消" },
            { pending("用户取消"), activity("open", -3600_000L, 4, 10), "活动已开始" },
            { pending("用户取消"), activity("ended", -DAY, 4, 10), "活动已开始" },
            { pending("报名已关闭后才到账（迟到支付），待人工全额退款"), activity("open", 3 * DAY, 4, 10), "报名关闭后才到账" },
        };
        for (Object[] c : cases)
        {
            reset(support, chats);
            clearInvocations(db);
            @SuppressWarnings("unchecked") Map<String, Object> s = (Map<String, Object>) c[0];
            @SuppressWarnings("unchecked") Map<String, Object> a = (Map<String, Object>) c[1];
            given(s, a);
            ServiceException e = assertThrows(ServiceException.class, () -> service.rejectRefund(11L, "不符合", "staff:3"), String.valueOf(c[2]));
            assertEquals(409, e.getCode());
            assertTrue(e.getMessage().contains(String.valueOf(c[2])) && e.getMessage().contains("同意退款"), e.getMessage());
            verify(db, never()).update(startsWith("update kg_activity_signup"), anyMap());
            verify(db, never()).update(startsWith("update kg_activity set signed"), anyMap());
            verifyNoInteractions(support, chats);
        }
    }

    @Test
    void refundAlreadyQueuedCannotBeRejected()
    {
        given(pending("用户取消"), activity("open", 3 * DAY, 4, 10));
        when(db.count(startsWith("select count(*) from kg_pay_refund"), anyMap())).thenReturn(1L);
        assertEquals(409, assertThrows(ServiceException.class, () -> service.rejectRefund(11L, "不符合", "staff:3")).getCode());
        verify(db, never()).update(startsWith("update kg_activity_signup"), anyMap());
    }

    @Test
    void notPendingIsRejectedBeforeAnyChange()
    {
        Map<String, Object> s = pending("用户取消");
        s.put("status", "cancelled");
        given(s, activity("open", 3 * DAY, 4, 10));
        assertEquals(409, assertThrows(ServiceException.class, () -> service.rejectRefund(11L, "不符合", "staff:3")).getCode());
        verify(db, never()).update(anyString(), anyMap());
    }

    @Test
    void approveKeepsOldPayNoteInRefundNote()
    {
        given(pending("用户取消"), activity("open", 3 * DAY, 4, 10));
        service.approveRefund(11L, "staff:3");
        verify(db).update(argThat(sql -> sql.startsWith("update kg_activity_signup set status = 'refunding', refund_note = left(concat(:n,")), anyMap());
        verify(pay).refund(eq("KP1"), eq(3000L), anyString(), eq("staff:3"));
    }

    @Test
    void wholeCancelAlsoRefundsCancelledButPaidSignups()
    {
        when(db.list(contains("join kg_pay_order o on o.pay_no = g.pay_no"), anyMap())).thenReturn(Collections.singletonList(
                p("signupId", 12L, "appUserId", 8L, "name", "小林", "cancelReason", "临时有事", "payLeft", 3000L)));
        when(db.list(contains("o.pay_no from kg_pay_order o join kg_activity_signup g"), anyMap())).thenReturn(Collections.singletonList(p("payNo", "KP0")));
        String msg = service.transition(activity("open", 3 * DAY, 3, 10), "cancel", "下雨", "staff:3");
        assertTrue(msg.startsWith("活动已取消，1 笔报名费转为退款待审批"), msg);
        assertTrue(msg.contains("另有 1 笔重新报名前的旧付款") && msg.contains("KP0"), msg);
        verify(db).update(argThat(sql -> sql.startsWith("update kg_activity_signup set status = 'refund_pending', refund_fen = :rf") && sql.contains("status in ('cancelled','closed')")),
                argThat(m -> Long.valueOf(3000L).equals(m.get("rf")) && Long.valueOf(12L).equals(m.get("s"))
                        && "活动取消：下雨（之前已取消：临时有事）".equals(m.get("r"))));
        verify(support).notifyUser(eq(8L), eq("system"), eq("活动已取消"), contains("没退的报名费 ¥30 也会在审核后原路退回"), eq("activity"), eq(5L), anyString());
    }

    @Test
    void wholeCancelWithoutUnrefundedPaymentsKeepsOldMessage()
    {
        assertEquals("活动已取消，已通知所有报名的人", service.transition(activity("open", 3 * DAY, 0, 10), "cancel", "下雨", "staff:3"));
    }

    @Test
    void resignupRecordsPaidButUnrefundedOldPayNo()
    {
        when(db.one(startsWith("select amount, refund_amount, status from kg_pay_order"), anyMap())).thenReturn(p("amount", 3000L, "refundAmount", 0L, "status", "paid"));
        String note = ReflectionTestUtils.invokeMethod(service, "oldPayNote", p("signupNo", "A1", "payNo", "KP2", "refundNote", "驳回：不符合（staff:3）；旧支付单 KP1（¥30 已付未退）"));
        assertEquals("旧支付单 KP2（¥30 已付未退）；旧支付单 KP1（¥30 已付未退）", note);
        // 已退完 / 已关闭的旧单不记；之前记下的照样留着
        when(db.one(startsWith("select amount, refund_amount, status from kg_pay_order"), anyMap())).thenReturn(p("amount", 3000L, "refundAmount", 3000L, "status", "refunded"));
        assertEquals("旧支付单 KP1（¥30 已付未退）", ReflectionTestUtils.invokeMethod(service, "oldPayNote", p("payNo", "KP3", "refundNote", "同意（staff:3）；旧支付单 KP1（¥30 已付未退）")));
        assertNull(ReflectionTestUtils.invokeMethod(service, "oldPayNote", p("payNo", "KP3", "refundNote", "同意（staff:3）")));
        assertNull(ReflectionTestUtils.invokeMethod(service, "oldPayNote", p("payNo", null, "refundNote", null)));
    }
}
