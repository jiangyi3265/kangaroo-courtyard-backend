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

/** 「我的订单」住宿订单按钮：只有已支付待入住的订单给「核销码」，并直达订单页的核销码屏。 */
class KgStayOrderListButtonsTest
{
    private String buttons(String status)
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.list(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0, String.class).contains("from kg_stay_order o join kg_stay s")
                ? Collections.singletonList(p("orderNo", "S2610090001", "status", status, "stayName", "小院", "city", "平潭", "nights", 1L,
                  "payFen", 39800L, "refundFen", 0L, "reviewed", 0L, "orderId", 9L)) : Collections.emptyList());
        KgUserHubService service = new KgUserHubService(); ReflectionTestUtils.setField(service, "db", db);
        List<Map<String, Object>> result = service.orders(2L, null);
        assertEquals(1, result.size());
        return result.get(0).get("btns").toString();
    }

    @Test void confirmedOrderOffersVerifyCodeScreen()
    {
        String b = buttons("confirmed");
        assertTrue(b.contains("label=核销码, type=primary, url=/pages/stays/order?orderNo=S2610090001&view=verify"), b);
        assertTrue(b.contains("订单详情"), b);
    }

    @Test void otherStatusesHaveNoVerifyCodeButton()
    {
        for (String status : new String[] { "pending_payment", "checked_in", "completed", "refund_pending", "cancelled", "no_show" })
        {
            assertFalse(buttons(status).contains("核销码"), status);
        }
    }
}
