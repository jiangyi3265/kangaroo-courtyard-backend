package com.ruoyi.kangaroo.ext.staff;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.ruoyi.kangaroo.ext.KgJdbc;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 2026-10-14 回归 R3-6：提前续保后，被保险人的「保险与完成」页要显示今天正在保障的老保单，续保申请挂在 renewal 里；
 * 之前只取最近一条（续保申请），页面写「待登记保单」，被保险人以为自己没有保障。
 */
class KgInsuranceUserCoverageTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));

    private Map<String, Object> row(String status, String no, LocalDate start, LocalDate end)
    {
        Map<String, Object> r = p("status", status, "startDate", start.toString(), "endDate", end.toString(), "planName", "30 天意外险");
        r.put("policyNo", no);
        return r;
    }

    @SafeVarargs
    private final Map<String, Object> coverage(Map<String, Object>... newestFirst)
    {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>(Arrays.asList(newestFirst));
        when(db.list(contains("from kg_insurance_apply i where i.app_id = :a"), anyMap())).thenReturn(rows);
        return KgInsuranceService.userCoverage(db, 3697L);
    }

    @Test void pendingRenewalDoesNotHideTheCurrentPolicy()
    {
        Map<String, Object> m = coverage(row("pending", null, today.plusDays(4), today.plusDays(33)),
                row("effective", "POL-t3", today.minusDays(26), today.plusDays(3)));
        assertEquals("生效中", m.get("statusLabel"));
        assertEquals("POL-t3", m.get("policyNo"));
        Map<?, ?> renewal = (Map<?, ?>) m.get("renewal");
        assertEquals("待登记保单", renewal.get("statusLabel"));
        assertEquals(today.plusDays(4).toString(), renewal.get("startDate"));
    }

    @Test void registeredRenewalShowsAsUpcomingNextToTheCurrentPolicy()
    {
        Map<String, Object> m = coverage(row("effective", "POL-t3-renew", today.plusDays(4), today.plusDays(33)),
                row("effective", "POL-t3", today.minusDays(26), today.plusDays(3)));
        assertEquals("active", m.get("coverageStatus"));
        assertEquals("upcoming", ((Map<?, ?>) m.get("renewal")).get("coverageStatus"));
        assertEquals("POL-t3-renew", ((Map<?, ?>) m.get("renewal")).get("policyNo"));
    }

    @Test void withoutAPolicyInForceTheNewestApplicationIsShownAsBefore()
    {
        Map<String, Object> m = coverage(row("pending", null, today.plusDays(1), today.plusDays(30)),
                row("effective", "POL-old", today.minusDays(40), today.minusDays(11)));
        assertEquals("待登记保单", m.get("statusLabel"));
        assertNull(m.get("renewal"));
        Map<String, Object> only = coverage(row("effective", "POL-1", today.minusDays(2), today.plusDays(27)));
        assertEquals("生效中", only.get("statusLabel"));
        assertNull(only.get("renewal"));
    }

    @Test void noApplicationGivesNull()
    {
        when(db.list(anyString(), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        assertNull(KgInsuranceService.userCoverage(db, 1L));
    }
}
