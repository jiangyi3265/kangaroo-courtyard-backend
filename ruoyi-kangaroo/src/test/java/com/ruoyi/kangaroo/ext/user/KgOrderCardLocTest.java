package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.kangaroo.ext.KgJdbc;

/** 「我的订单」卡片的地点行：商圈是空串时不再写成「大理 ·」（2026-10-14 回归 R3-43） */
class KgOrderCardLocTest
{
    @Test
    void emptyPartsAreSkipped()
    {
        assertEquals("大理", KgUserHubService.locText("大理", ""));
        assertEquals("大理", KgUserHubService.locText("大理", null));
        assertEquals("大理", KgUserHubService.locText("大理", "  "));
        assertEquals("大理 · 古城", KgUserHubService.locText("大理", "古城"));
        assertEquals("古城", KgUserHubService.locText(null, "古城"));
        assertEquals("", KgUserHubService.locText(null, ""));
    }

    @Test
    void stayCardWithEmptyAreaHasNoTrailingDot()
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.list(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0, String.class).contains("from kg_stay_order o join kg_stay s")
                ? Collections.singletonList(p("orderNo", "S2610140001", "status", "completed", "stayName", "小院", "city", "大理", "area", "",
                  "nights", 1L, "payFen", 18000L, "refundFen", 0L, "reviewed", 1L, "orderId", 9L)) : Collections.emptyList());
        KgUserHubService service = new KgUserHubService();
        ReflectionTestUtils.setField(service, "db", db);
        List<Map<String, Object>> result = service.orders(2L, null);
        assertEquals("大理", result.get(0).get("loc"));
    }
}
