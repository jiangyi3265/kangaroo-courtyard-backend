package com.ruoyi.kangaroo.ext.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.domain.JobTabFilters;
import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * 全部筛选页（POST /app/job/search）里兼职 / 全职页签自己的条件（2026-10-17）：和岗位列表同一口径（JobTabFilters），
 * 不连库，只看拼出来的 SQL 和参数
 */
class KgJobSearchTabFilterTest
{
    private KgUserHubService hub;
    private KgJdbc db;

    @BeforeEach
    void setUp()
    {
        db = mock(KgJdbc.class);
        KgCityService cities = mock(KgCityService.class);
        when(cities.businessCity(anyString())).thenAnswer(inv -> ((String) inv.getArgument(0)).trim());
        when(db.list(anyString(), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        hub = new KgUserHubService();
        ReflectionTestUtils.setField(hub, "db", db);
        ReflectionTestUtils.setField(hub, "cityService", cities);
        ReflectionTestUtils.setField(hub, "home", mock(KgHomeService.class));
    }

    private static Map<String, Object> body(Object... kv)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2)
        {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    /** 跑一次 search，返回 [sql, params, 结果] */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private Object[] search(Map<String, Object> b)
    {
        Map<String, Object> res = hub.search(null, b);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map> ps = ArgumentCaptor.forClass(Map.class);
        verify(db).list(sql.capture(), ps.capture());
        return new Object[] { sql.getValue(), ps.getValue(), res };
    }

    @Test
    void partTimeDailyBand()
    {
        Object[] r = search(body("jobType", "parttime", "payDay", 120, "board", "self"));
        String sql = (String) r[0];
        Map<?, ?> ps = (Map<?, ?>) r[1];
        assertTrue(sql.contains(JobTabFilters.DAILY_MIN_SQL + " >= :pd"), sql);
        assertEquals(120L, ps.get("pd"));
        assertTrue(sql.contains(" and coalesce(j.board_type, 'none') in ('none','')"), sql);
        // 没有结果时给出「放宽」建议：薪资档、食宿
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) r[2];
        assertTrue(String.valueOf(res.get("relax")).contains("payBand"), String.valueOf(res.get("relax")));
        assertTrue(String.valueOf(res.get("conflicts")).contains("日薪：120 元/天以上"), String.valueOf(res.get("conflicts")));
    }

    @Test
    void fullTimeMonthlyBandTypeAndBoard()
    {
        Object[] r = search(body("jobType", "fulltime", "payMonthMin", "3000", "payMonthMax", 5000, "categories", Arrays.asList("hotel", "other"), "board", "meal"));
        String sql = (String) r[0];
        Map<?, ?> ps = (Map<?, ?>) r[1];
        assertTrue(sql.contains(JobTabFilters.MONTHLY_MIN_SQL + " >= :pmn"), sql);
        assertTrue(sql.contains(JobTabFilters.MONTHLY_MIN_SQL + " < :pmx"), sql);
        assertEquals(3000L, ps.get("pmn"));
        assertEquals(5000L, ps.get("pmx"));
        assertTrue(sql.contains(" or not (coalesce(s.store_name, '') like :cw0"), sql);
        assertTrue(sql.contains(" and j.board_type in ('all','meal')"), sql);
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) r[2];
        assertTrue(String.valueOf(res.get("relax")).contains("categories"));
    }

    @Test
    void oldBoardValuesStillWork()
    {
        String none = (String) search(body("board", "none"))[0];
        assertFalse(none.contains("board_type"), none);
        setUp();
        String all = (String) search(body("board", "all"))[0];
        assertTrue(all.contains(" and j.board_type = 'all'"), all);
        setUp();
        String room = (String) search(body("board", "room"))[0];
        assertTrue(room.contains(" and j.board_type in ('all','room')"), room);
    }

    @Test
    void badValuesAreRejected()
    {
        assertEquals("食宿条件不合法", assertThrows(ServiceException.class, () -> hub.search(null, body("board", "part"))).getMessage());
        assertEquals("岗位类型不合法", assertThrows(ServiceException.class, () -> hub.search(null, body("categories", "cafe"))).getMessage());
        assertEquals("薪资条件不合法", assertThrows(ServiceException.class, () -> hub.search(null, body("payDay", "八十"))).getMessage());
        assertEquals("薪资区间不合法", assertThrows(ServiceException.class, () -> hub.search(null, body("payMonthMin", 5000, "payMonthMax", 5000))).getMessage());
        List<String> none = new ArrayList<String>();
        // 空的类型 / 0 的薪资档 = 不筛
        String sql = (String) search(body("categories", none, "payDay", 0))[0];
        assertFalse(sql.contains("store_name, '') like"), sql);
        assertFalse(sql.contains(":pd"), sql);
    }
}
