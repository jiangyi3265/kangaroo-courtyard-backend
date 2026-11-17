package com.ruoyi.kangaroo.ext.stay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * 2026-10-18 旅居 C3/C5（不连库）：房型卡上每个房型自己的评分与条数（来自绑定了该房型的家人评价），
 * 预订须知一条一行、写明带身份证现场登记
 */
class KgStayRoomScoreTest
{
    private static Map<String, Object> row(Object... kv)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2)
        {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    @Test
    void roomScoreRoundsLikeReviewPageAndHidesWhenNone()
    {
        Map<String, Object> t = new LinkedHashMap<String, Object>();
        // 4 + 5 + 5 = 14 / 3 = 4.67 → 4.7（和家人评价页综合分同一个四舍五入）
        KgStayBrowseService.roomScore(t, new long[] { 14, 3 });
        assertEquals(4.7, t.get("rating"));
        assertEquals(3L, t.get("reviewCount"));

        Map<String, Object> none = new LinkedHashMap<String, Object>();
        KgStayBrowseService.roomScore(none, null);
        assertTrue(none.containsKey("rating"));
        assertNull(none.get("rating"), "没评价的房型不给分，也不拿小院总分顶替");
        assertEquals(0L, none.get("reviewCount"));
    }

    @Test
    void roomTypeScoresGroupsByOrderRoomTypeOfVisibleReviews()
    {
        KgJdbc db = mock(KgJdbc.class);
        KgStayBrowseService browse = new KgStayBrowseService();
        ReflectionTestUtils.setField(browse, "db", db);
        when(db.list(anyString(), anyMap())).thenReturn(Arrays.asList(
                row("roomTypeId", 11L, "n", 2L, "total", 9L),
                row("roomTypeId", 12L, "n", 1L, "total", 3L),
                row("roomTypeId", null, "n", 5L, "total", 25L)));
        Map<Long, long[]> m = browse.roomTypeScores(7L);
        assertEquals(2, m.size(), "订单上没有房型的评价不算到任何房型");
        assertArrayEquals(new long[] { 9, 2 }, m.get(11L));
        assertArrayEquals(new long[] { 3, 1 }, m.get(12L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> ps = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db).list(sql.capture(), ps.capture());
        String s = sql.getValue();
        // 房型取评价对应的住宿订单，和家人评价页按房型筛选同一口径；只算显示中、未删除的住宿评价
        assertTrue(s.contains("join kg_stay_order o on v.biz_type = 'stay_order' and o.order_id = v.biz_id and o.stay_id = v.target_id"), s);
        assertTrue(s.contains("v.target_type = 'room' and v.target_id = :s and v.status = '0' and v.del_flag = '0'"), s);
        assertTrue(s.contains("group by o.room_type_id"), s);
        assertEquals(7L, ps.getValue().get("s"));
    }

    @Test
    void bookingNoticeIsOneItemPerLineAndMentionsIdCard()
    {
        String[] lines = KgStayBrowseService.AGREEMENT_SUMMARY.split("\n");
        assertEquals(5, lines.length, "五条须知各占一行，页面按换行显示");
        for (int i = 0; i < lines.length; i++)
        {
            assertTrue(lines[i].startsWith((i + 1) + ". "), lines[i]);
        }
        assertTrue(lines[1].contains("身份证") && lines[1].contains("现场"), lines[1]);
        assertEquals("2026-10", KgStayBrowseService.AGREEMENT_VERSION, "须知内容变了，版本跟着换（下单时核对版本）");
    }
}
