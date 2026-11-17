package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.pay.KgPayService;

class KgStayMinimumNightsTest {
    @Test void newBookingAllowsSevenToThirtyNights() {
        LocalDate in = LocalDate.now().plusDays(3);
        for (int n = 1; n < 7; n++) {
            final LocalDate out = in.plusDays(n);
            assertTrue(assertThrows(ServiceException.class, () -> KgStayBrowseService.checkDates(in, out)).getMessage().contains("一周起订"));
        }
        for (int n = 7; n <= 30; n++) KgStayBrowseService.checkDates(in, in.plusDays(n));
        assertThrows(ServiceException.class, () -> KgStayBrowseService.checkDates(in, in.plusDays(31)));
        LocalDate lastStart = LocalDate.now().plusDays(180);
        assertDoesNotThrow(() -> KgStayBrowseService.checkDates(lastStart, lastStart.plusDays(7)));
        assertThrows(ServiceException.class, () -> KgStayBrowseService.checkDates(lastStart.plusDays(1), lastStart.plusDays(8)));
    }

    @Test void quoteAndRecommendationRejectShortStayBeforePricing() {
        KgStayBrowseService browse = spy(new KgStayBrowseService());
        KgJdbc featureDb = mock(KgJdbc.class);
        when(featureDb.one(contains("from sys_config"), anyMap())).thenReturn(p("configValue", "true"));
        ReflectionTestUtils.setField(browse, "db", featureDb);
        doReturn(p("stayId", 7L)).when(browse).roomType(31L);
        doReturn(p("stayId", 7L)).when(browse).onlineStay(7L);
        String in = LocalDate.now().plusDays(2).toString();
        for (int n = 1; n < 7; n++) {
            String out = LocalDate.parse(in).plusDays(n).toString();
            assertTrue(assertThrows(ServiceException.class, () -> browse.quote(31L, in, out, 1, null)).getMessage().contains("一周起订"));
            assertTrue(assertThrows(ServiceException.class, () -> browse.recommend(31L, in, out, 1)).getMessage().contains("一周起订"));
        }
    }

    @Test void createUsesRealQuoteValidationAndNeverAllocatesInventoryOrPayment() {
        KgJdbc db = mock(KgJdbc.class);
        KgPayService pay = mock(KgPayService.class);
        KgStayBrowseService browse = spy(new KgStayBrowseService());
        KgJdbc featureDb = mock(KgJdbc.class);
        when(featureDb.one(contains("from sys_config"), anyMap())).thenReturn(p("configValue", "true"));
        ReflectionTestUtils.setField(browse, "db", featureDb);
        doReturn(p("stayId", 7L)).when(browse).roomType(31L);
        doReturn(p("stayId", 7L)).when(browse).onlineStay(7L);
        KgStayOrderService orders = new KgStayOrderService();
        when(db.one(contains("from sys_config"), anyMap())).thenReturn(p("configValue", "true"));
        ReflectionTestUtils.setField(orders, "db", db);
        ReflectionTestUtils.setField(orders, "browse", browse);
        ReflectionTestUtils.setField(orders, "pay", pay);
        when(db.one(contains("from kg_app_user"), anyMap())).thenReturn(p("status", "0", "delFlag", "0"));
        LocalDate in = LocalDate.now().plusDays(3);
        Map<String, Object> body = p("roomTypeId", 31L, "checkIn", in.toString(), "checkOut", in.plusDays(6).toString(), "guestName", "测试用户", "guestPhone", "13800138000", "agreementVersion", KgStayBrowseService.AGREEMENT_VERSION);
        assertTrue(assertThrows(ServiceException.class, () -> orders.create(1L, body)).getMessage().contains("一周起订"));
        verify(db, never()).update(anyString(), anyMap());
        verify(db, never()).insert(anyString(), anyMap());
        verifyNoInteractions(pay);
    }
}
