package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.kangaroo.ext.KgJdbc;

/** Real ask() branch with Mock JDBC; no HTTP, database, or insurer integration. */
class KgSupportInsuranceTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final KgSupportService service = new KgSupportService();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        when(db.one(eq("select status, del_flag from kg_app_user where user_id=:u for update"), anyMap()))
                .thenReturn(p("status", "0", "delFlag", "0"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> card(Map<String, Object> policy)
    {
        when(db.list(anyString(), anyMap())).thenReturn(Collections.singletonList(policy));
        Map<String, Object> answer = service.ask(91L, "insurance", "我的保险保障", null, null);
        List<Map<String, Object>> cards = (List<Map<String, Object>>) answer.get("cards");
        assertEquals(1, cards.size());
        assertEquals("insurance", cards.get(0).get("type"));
        assertEquals("测试岗位", cards.get(0).get("title"));
        // Display changes must retain the existing own-account query boundary.
        verify(db).list(argThat(sql -> sql.contains("i.app_user_id = :u")
                && sql.contains("i.del_flag = '0'") && sql.contains("limit 2")),
                argThat(params -> Long.valueOf(91L).equals(params.get("u"))));
        verify(db, never()).update(anyString(), anyMap());
        verify(db, never()).insert(eq("kg_insurance_apply"), anyMap());
        return cards.get(0);
    }

    @ParameterizedTest(name = "{4}")
    @CsvSource({
        "effective, REAL-NO, 10, 20, upcoming, 已出单·待生效, true",
        "effective, REAL-NO, -20, -10, expired, 已失效, true",
        "effective, '', -1, 1, invalid, 保单信息待核实, false",
        "effective, REAL-NO, -1, 1, active, 生效中, true",
        "pending, '', -1, 1, pending, 待登记保单, false",
        "rejected, REAL-NO, -1, 1, rejected, 已驳回, false"
    })
    void supportCardUsesCoverageAndIssuance(String status, String no, int from, int to,
            String coverage, String label, boolean issued)
    {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        Map<String, Object> c = card(p("status", status, "policyNo", no,
                "startDate", today.plusDays(from).toString(), "endDate", today.plusDays(to).toString(), "title", "测试岗位"));
        assertEquals(coverage, c.get("coverageStatus"));
        assertEquals(issued, c.get("policyIssued"));
        assertEquals(label, c.get("statusLabel"));
        assertEquals(label, c.get("status"), "Existing card renderer also consumes status");
        assertEquals(no.isEmpty() ? ("effective".equals(status) ? "保单号缺失，待核实" : "保单号待登记")
                : "保单号 " + no, c.get("meta"));
    }

    @Test
    void invalidDatesAndWhitespacePolicyNeverClaimCoverage()
    {
        Map<String, Object> c = card(p("status", "effective", "policyNo", "   ",
                "startDate", "invalid-date", "endDate", null, "title", "测试岗位"));
        assertEquals("invalid", c.get("coverageStatus"));
        assertEquals(false, c.get("policyIssued"));
        assertEquals("保单信息待核实", c.get("status"));
        assertEquals("保单号缺失，待核实", c.get("meta"));
    }

    @Test
    void anonymousQuestionDoesNotReadAnyPersonalPolicy()
    {
        Map<String, Object> answer = service.ask(null, "insurance", "保险查询", null, null);
        assertEquals(Collections.emptyList(), answer.get("cards"));
        verify(db, never()).list(anyString(), anyMap());
        verify(db, never()).update(anyString(), anyMap());
    }
}
