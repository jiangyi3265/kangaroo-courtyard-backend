package com.ruoyi.kangaroo.ext.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgPostService;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Service results with mocked JDBC rows; real HTTP/MySQL remains a separate regression. */
class KgMoneyCardsTest
{
    private KgHomeService home(KgJdbc db)
    {
        KgHomeService service = new KgHomeService();
        KgCityService cities = mock(KgCityService.class);
        when(cities.businessCity("验收城")).thenReturn("验收城");
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "cities", cities);
        return service;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> stays(KgHomeService service, int budget, String to, List<String> relax)
    {
        return (List<Map<String, Object>>) service.match(null,
                p("city", "验收城", "budget", budget, "from", "2026-10-09", "to", to, "relax", relax)).get("stays");
    }

    @Test
    void sevenNightBudgetUsesCentsAndPreservesUnlimitedRelaxationAndExistingCap() throws Exception
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.list(anyString(), anyMap())).thenAnswer(call -> {
            String sql = call.getArgument(0);
            return sql.contains("from kg_stay s") ? Collections.singletonList(p("stayId", 19L, "price", 12345L)) : Collections.emptyList();
        });
        KgHomeService service = home(db);
        List<String> noRelax = Collections.emptyList();
        assertTrue(stays(service, 864, "2026-10-15", noRelax).isEmpty(), "864.15 exceeds 864; do not truncate before comparison");
        Map<String, Object> card = stays(service, 865, "2026-10-15", noRelax).get(0);
        assertEquals(0, new BigDecimal("123.45").compareTo((BigDecimal) card.get("price")));
        assertTrue(new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(card)).get("price").isNumber());
        assertEquals(1, stays(service, 0, "2026-10-15", noRelax).size());
        assertEquals(1, stays(service, 865, "2026-10-22", noRelax).size());
        assertEquals(1, stays(service, 864, "2026-10-15", Collections.singletonList("budget")).size());
        Map<String, Object> overBudget = stays(service, 500, "2026-10-15", Collections.singletonList("budget")).get(0);
        assertEquals(60, overBudget.get("score"));
        assertTrue(((String) overBudget.get("reason")).startsWith("超出预算"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void activityCardsKeepCentsAndJsonNumberTypeIncludingFreeAndWholePrices() throws Exception
    {
        for (long fee : new long[] {1234, 1, 1200, 0})
        {
            KgJdbc db = mock(KgJdbc.class);
            when(db.list(anyString(), anyMap())).thenAnswer(call -> {
                String sql = call.getArgument(0);
                return sql.contains("from kg_activity where") ? Collections.singletonList(p("activityId", 29L, "feeFen", fee)) : Collections.emptyList();
            });
            Map<String, Object> result = home(db).match(null, p("city", "验收城", "budget", 0));
            Map<String, Object> card = ((List<Map<String, Object>>) result.get("activities")).get(0);
            assertEquals(0, BigDecimal.valueOf(fee, 2).compareTo((BigDecimal) card.get("price")));
            assertTrue(new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(card)).get("price").isNumber());
            assertEquals(fee == 0 ? "免费" : "元/人", card.get("unit"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void communityRelatedStayKeepsExistingStringAndNullContractWithCents()
    {
        for (Long fee : new Long[] {12345L, 1L, 1200L, 0L, null})
        {
            KgJdbc db = mock(KgJdbc.class);
            when(db.one(anyString(), anyMap())).thenAnswer(call -> {
                String sql = call.getArgument(0);
                return sql.contains("from kg_stay s") ? p("stayId", 19L, "price", fee) : null;
            });
            KgPostService service = new KgPostService();
            ReflectionTestUtils.setField(service, "db", db);
            List<Map<String, Object>> cards = ReflectionTestUtils.invokeMethod(service, "related", "验收城");
            assertEquals(1, cards.size());
            Object price = cards.get(0).get("price");
            if (fee == null) assertNull(price);
            else
            {
                assertTrue(price instanceof String);
                assertEquals(0, BigDecimal.valueOf(fee, 2).compareTo(new BigDecimal((String) price)));
            }
        }
    }
}
