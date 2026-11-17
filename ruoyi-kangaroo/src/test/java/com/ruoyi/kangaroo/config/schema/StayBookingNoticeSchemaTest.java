package com.ruoyi.kangaroo.config.schema;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.junit.jupiter.api.Test;
import com.ruoyi.kangaroo.ext.KgJdbc;
class StayBookingNoticeSchemaTest {
    @Test void allThreeColumnsAreRequiredBeforeServingRequests() {
        KgJdbc db=mock(KgJdbc.class);StayBookingNoticeSchema schema=new StayBookingNoticeSchema(db,true);
        for(long n:new long[]{0,1,2}) {
            when(db.count(eq(StayBookingNoticeSchema.SQL),anyMap())).thenReturn(n);
            assertTrue(assertThrows(IllegalStateException.class,schema::check).getMessage().contains("V92__stay_booking_notice.sql"));
        }
        when(db.count(eq(StayBookingNoticeSchema.SQL),anyMap())).thenReturn(3L);assertDoesNotThrow(schema::check);
    }
    @Test void disabledCheckMakesNoDatabaseCalls() {
        KgJdbc db=mock(KgJdbc.class);new StayBookingNoticeSchema(db,false).check();verifyNoInteractions(db);
    }
}
