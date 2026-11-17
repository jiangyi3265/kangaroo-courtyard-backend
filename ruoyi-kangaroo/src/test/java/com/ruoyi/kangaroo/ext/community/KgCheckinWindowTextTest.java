package com.ruoyi.kangaroo.ext.community;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
class KgCheckinWindowTextTest {
    @Test void futureWindowNamesExactOpeningTimeInBeijing() {
        Map<String,Object> a=p("startTime",Date.from(Instant.parse("2100-11-17T00:00:00Z")),"endTime",Date.from(Instant.parse("2100-11-17T04:00:00Z")));
        assertTrue(KgActivityService.checkinWindowTip(a).contains("2100-11-17 06:00"));
        assertTrue(KgActivityService.checkinWindowText(a).contains("2100-11-17 06:00 至 2100-11-17 12:00"));
        KgActivityService service=new KgActivityService();KgJdbc db=mock(KgJdbc.class);ReflectionTestUtils.setField(service,"db",db);
        assertEquals(409,assertThrows(ServiceException.class,()->service.checkinByCode(a,"123456")).getCode());verifyNoInteractions(db);
    }
    @Test void expiredWindowDoesNotTellUserToWaitForOpening() {
        Map<String,Object> a=p("startTime",Date.from(Instant.parse("2000-11-17T00:00:00Z")));
        assertTrue(KgActivityService.checkinWindowTip(a).contains("已于 2000-11-17 12:00 结束"));
        assertFalse(KgActivityService.inCheckinWindow(a));
    }
    @Test void currentWindowAndMissingTimeAreHandled() {
        assertTrue(KgActivityService.inCheckinWindow(p("startTime",new Date(System.currentTimeMillis()+3600_000L))));
        assertEquals("活动时间待确认，请联系主办方",KgActivityService.checkinWindowText(p()));
    }
    @Test void terminalActivitiesCannotCheckInEvenDuringOriginalTimeWindow() {
        for(String status:Arrays.asList("ended","cancelled")){
            Map<String,Object>a=p("status",status,"startTime",new Date(System.currentTimeMillis()-3600_000L),"endTime",new Date(System.currentTimeMillis()+3600_000L));
            assertFalse(KgActivityService.inCheckinWindow(a));
            assertTrue(KgActivityService.checkinWindowTip(a).contains("ended".equals(status)?"已结束":"已取消"));
            KgActivityService service=new KgActivityService();KgJdbc db=mock(KgJdbc.class);ReflectionTestUtils.setField(service,"db",db);
            assertEquals(409,assertThrows(ServiceException.class,()->service.checkinByCode(a,"123456")).getCode());verifyNoInteractions(db);
        }
    }
}
