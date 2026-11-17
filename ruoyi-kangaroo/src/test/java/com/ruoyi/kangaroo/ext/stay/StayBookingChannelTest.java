package com.ruoyi.kangaroo.ext.stay;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.common.exception.ServiceException;

class StayBookingChannelTest {
    @Test void explicitHouseTypeWinsOverStoreSettlementType() {
        assertThrows(ServiceException.class, () -> StayBookingChannel.requireOnline(p("houseType","direct","opType","managed")));
        assertDoesNotThrow(() -> StayBookingChannel.requireOnline(p("houseType","managed","opType","direct")));
        assertEquals("direct", StayBookingChannel.type(p("opType","direct")));
        assertEquals("managed", StayBookingChannel.type(p("opType","franchise")));
    }
    @Test void directQuoteIsRejectedBeforePricingOrBooking() {
        KgJdbc db = mock(KgJdbc.class);
        KgStayBrowseService service = new KgStayBrowseService();
        ReflectionTestUtils.setField(service,"db",db);
        when(db.one(contains("sys_config"),anyMap())).thenReturn(p("configValue","true"));
        when(db.one(contains("select t.*"),anyMap())).thenReturn(p("roomTypeId",9L,"stayId",2L));
        when(db.one(contains("select s.*"),anyMap())).thenReturn(p("stayId",2L,"houseType","direct","opType","managed"));
        ServiceException error=assertThrows(ServiceException.class, () -> service.quote(9L,"2027-01-01","2027-01-08",1,null));
        assertEquals(409,error.getCode());
        assertTrue(error.getMessage().contains("订房助手"));
        verify(db,never()).update(anyString(),anyMap());
    }
}
