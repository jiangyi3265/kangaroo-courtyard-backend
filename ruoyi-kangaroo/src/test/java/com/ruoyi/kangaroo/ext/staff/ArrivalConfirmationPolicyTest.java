package com.ruoyi.kangaroo.ext.staff;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArrivalConfirmationPolicyTest
{
    @Test void expectedDateUsesReportedDateAndFallsBackWhenBlank()
    {
        assertEquals("2026-11-11", ArrivalConfirmationPolicy.expectedDate("2026-11-11", "2026-11-08"));
        assertEquals("2026-11-08", ArrivalConfirmationPolicy.expectedDate(null, "2026-11-08"));
        assertEquals("2026-11-08", ArrivalConfirmationPolicy.expectedDate("", "2026-11-08"));
        assertNull(ArrivalConfirmationPolicy.expectedDate(null, null));
    }

    @Test void chinaCalendarDayIsIndependentOfServerDefaultZone()
    {
        TimeZone prior = TimeZone.getDefault();
        try
        {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            Date boundary = Date.from(Instant.parse("2026-11-10T16:00:00Z"));
            assertEquals("2026-11-11", ArrivalConfirmationPolicy.date(boundary));
            assertNotNull(ArrivalConfirmationPolicy.waitReason(boundary, LocalDate.of(2026, 11, 10)));
            assertNull(ArrivalConfirmationPolicy.waitReason(boundary, LocalDate.of(2026, 11, 11)));
            assertEquals("2026-11-11", ArrivalConfirmationPolicy.date(java.sql.Date.valueOf("2026-11-11")));
        }
        finally { TimeZone.setDefault(prior); }
    }
}
