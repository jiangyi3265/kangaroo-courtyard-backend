package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.pay.KgPayService;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.ext.user.KgMemberWallet;
import com.ruoyi.kangaroo.ext.user.KgMembershipPolicy;

/** Free member stays must close/refund their add-ons through the same lifecycle as paid stays. */
class KgStayFreeNightCancellationTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final KgPayService pay = mock(KgPayService.class);
    private final StaffSupport support = mock(StaffSupport.class);
    private final KgMemberWallet wallet = mock(KgMemberWallet.class);
    private final KgStayOrderService service = new KgStayOrderService();
    private Map<String, Object> order;

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "pay", pay);
        ReflectionTestUtils.setField(service, "support", support);
        ReflectionTestUtils.setField(service, "memberWallet", wallet);
        order = p("orderId", 11L, "orderNo", "Sfree", "appUserId", 7L, "storeId", 9L,
                "status", "confirmed", "payFen", 0L, "memberMode", "nights", "roomTypeId", 13L,
                "units", 1L, "checkIn", KgMembershipPolicy.today().plusDays(2).toString(),
                "checkOut", KgMembershipPolicy.today().plusDays(3).toString());
        when(db.one(startsWith("select * from kg_stay_order where order_no"), anyMap())).thenReturn(order);
    }

    @Test
    void cancelReturnsBenefitsAndRoutesLinkedAddonsForManualRefund()
    {
        when(db.update(startsWith("update kg_stay_order set status='cancelled'"), anyMap())).thenReturn(1);
        when(db.update(startsWith("update kg_shop_order set status = 'refund_pending'"), anyMap())).thenReturn(2);

        service.cancel(7L, "Sfree");

        verify(wallet).releaseStay(11L);
        verify(db).update(startsWith("update kg_stay_calendar set occupied"), anyMap());
        verify(db).update(argThat(sql -> sql.startsWith("update kg_stay_order set status='cancelled'") && sql.contains("verify_code=null")), anyMap());
        verify(db).update(argThat(sql -> sql.startsWith("update kg_shop_order set status='closed'") && sql.contains("status='pending_payment'")), eq(p("no", "Sfree")));
        verify(db).update(argThat(sql -> sql.startsWith("update kg_shop_order set status = 'refund_pending'") && sql.contains("status = 'unused'")),
                eq(p("no", "Sfree", "r", "免费间夜入住订单已取消")));
        verify(support).notifyStore(eq(9L), any(), eq("增值服务待退款"), contains("2 个"), eq("stay_order"), eq(11L), anyString());
        verifyNoInteractions(pay); // cancellation does not silently issue a real refund
    }

    @Test
    void losingConcurrentCancellationDoesNotReleaseOrRefundTwice()
    {
        when(db.update(startsWith("update kg_stay_order set status='cancelled'"), anyMap())).thenReturn(0);
        service.cancel(7L, "Sfree");
        verifyNoInteractions(wallet, pay, support);
        verify(db, never()).update(contains("kg_stay_calendar"), anyMap());
        verify(db, never()).update(contains("kg_shop_order"), anyMap());
    }

    @Test
    void arrivalDayCancellationRequiresTheStoreAndDoesNotChangeLinkedOrders()
    {
        order.put("checkIn", KgMembershipPolicy.today().toString());
        assertEquals(409, assertThrows(ServiceException.class, () -> service.cancel(7L, "Sfree")).getCode());
        verify(db, never()).update(anyString(), anyMap());
        verifyNoInteractions(wallet, pay, support);
    }
}
