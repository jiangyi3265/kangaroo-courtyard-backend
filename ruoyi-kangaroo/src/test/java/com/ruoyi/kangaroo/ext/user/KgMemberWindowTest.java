package com.ruoyi.kangaroo.ext.user;

import com.ruoyi.common.exception.ServiceException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;

class KgMemberWindowTest {
    private final LocalDateTime start = LocalDateTime.of(2026, 10, 4, 12, 0);
    private final LocalDateTime end = start.plusDays(1);

    @Test void acceptsControllerNormalizedDates() {
        assertDoesNotThrow(() -> KgHomeMemberModule.checkWindow(p("start_time", start, "end_time", end)));
    }

    @Test void acceptsPartialEditsMergedWithDatabaseTimestamp() {
        assertDoesNotThrow(() -> KgHomeMemberModule.checkWindow(p("start_time", Timestamp.valueOf(start), "end_time", end)));
        assertDoesNotThrow(() -> KgHomeMemberModule.checkWindow(p("start_time", start, "end_time", Timestamp.valueOf(end))));
    }

    @Test void comparesCamelNormalizedDatabaseStringsWithNewDates() {
        assertDoesNotThrow(() -> KgHomeMemberModule.checkWindow(p("start_time", "2026-10-04 12:00:00", "end_time", end)));
        assertDoesNotThrow(() -> KgHomeMemberModule.checkWindow(p("start_time", start, "end_time", "2026-10-05 12:00:00")));
        assertThrows(ServiceException.class, () -> KgHomeMemberModule.checkWindow(p("start_time", "2026-10-04 12:00:00", "end_time", start)));
        assertThrows(ServiceException.class, () -> KgHomeMemberModule.checkWindow(p("start_time", end, "end_time", "2026-10-04 12:00:00")));
    }

    @Test void rejectsEqualAndReversedNormalizedWindows() {
        for (LocalDateTime invalidEnd : new LocalDateTime[]{start, start.minusSeconds(1)}) {
            ServiceException e = assertThrows(ServiceException.class,
                () -> KgHomeMemberModule.checkWindow(p("start_time", start, "end_time", invalidEnd)));
            assertEquals(422, e.getCode());
        }
    }

    @Test void rejectsMalformedBoundEvenWithOtherEndOpen() {
        ServiceException e = assertThrows(ServiceException.class,
            () -> KgHomeMemberModule.checkWindow(p("start_time", "not-a-date")));
        assertEquals(422, e.getCode());
    }

    @Test void preservesOpenEndedWindows() {
        assertDoesNotThrow(() -> KgHomeMemberModule.checkWindow(p("start_time", start)));
        assertDoesNotThrow(() -> KgHomeMemberModule.checkWindow(p("end_time", end)));
        assertDoesNotThrow(() -> KgHomeMemberModule.checkWindow(p()));
    }
}
