package com.ruoyi.kangaroo.util;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InsuranceStateTest {
    private final LocalDate today = LocalDate.of(2026, 10, 6);
    private void state(String status, String no, String start, String end, String expected) {
        Map<String,Object> row=p("status",status,"policyNo",no,"startDate",start,"endDate",end);
        InsuranceState.decorate(row,today);
        assertEquals(expected,row.get("coverageStatus"));
        if (!"active".equals(expected)) assertNotEquals("生效中",row.get("statusLabel"));
    }
    @Test void pendingHasNoCoverageEvenWithNumber() { state("pending","NO","2026-10-06","2026-10-10","pending"); }
    @Test void issuedFutureIsUpcoming() { state("effective","NO","2026-10-07","2026-10-10","upcoming"); }
    @Test void startBoundaryIsActive() { state("effective","NO","2026-10-06","2026-10-10","active"); }
    @Test void endBoundaryIsActive() { state("effective","NO","2026-10-01","2026-10-06","active"); }
    @Test void pastIsExpired() { state("effective","NO","2026-10-01","2026-10-05","expired"); }
    @Test void missingNumberIsInvalid() { state("effective"," ","2026-10-06","2026-10-10","invalid"); }
    @Test void missingDateIsInvalid() { state("effective","NO",null,"2026-10-10","invalid"); }
    @Test void reversedDatesAreInvalid() { state("effective","NO","2026-10-10","2026-10-06","invalid"); }
    @Test void rejectedHasNoCoverage() { state("rejected","NO","2026-10-06","2026-10-10","rejected"); }
}
