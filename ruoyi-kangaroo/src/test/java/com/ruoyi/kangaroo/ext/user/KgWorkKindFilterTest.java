package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.core.KgVolunteerKinds;

/**
 * 筛选页「类型」按换工类型筛：按 work_kind 精确匹配，选「其他」时连还没选类型的换工岗位一起列出；
 * 不再按标题里的词去猜老岗位的类型（不连库，只看拼出来的 SQL 和参数）。
 */
class KgWorkKindFilterTest
{
    private KgUserHubService hub;
    private KgJdbc db;
    private KgVolunteerKinds kinds;

    @BeforeEach
    void setUp()
    {
        db = mock(KgJdbc.class);
        KgCityService cities = mock(KgCityService.class);
        when(cities.businessCity(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(db.list(anyString(), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        kinds = mock(KgVolunteerKinds.class);
        when(kinds.label("photo")).thenReturn("摄影换工");
        hub = new KgUserHubService();
        ReflectionTestUtils.setField(hub, "db", db);
        ReflectionTestUtils.setField(hub, "cityService", cities);
        ReflectionTestUtils.setField(hub, "home", mock(KgHomeService.class));
        ReflectionTestUtils.setField(hub, "volunteerKinds", kinds);
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

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private Object[] search(Map<String, Object> b)
    {
        hub.search(null, b);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map> ps = ArgumentCaptor.forClass(Map.class);
        verify(db).list(sql.capture(), ps.capture());
        return new Object[] { sql.getValue(), ps.getValue() };
    }

    @Test
    void kindFilterMatchesTheKindExactlyAndNoLongerGuessesFromTitles()
    {
        Object[] r = search(body("jobType", "volunteer", "workKinds", Arrays.asList("photo", "craft")));
        String sql = (String) r[0];
        Map<?, ?> ps = (Map<?, ?>) r[1];
        assertTrue(sql.contains(" and j.job_type = 'volunteer' and (j.work_kind in (:wk))"), sql);
        assertEquals(Arrays.asList("photo", "craft"), ps.get("wk"));
        // 之前没选类型的老岗位按词模糊匹配：「手工咖啡馆」会被当成手作教学。现在只认岗位上选的类型
        assertFalse(sql.contains("j.work_kind = ''"), sql);
        assertFalse(sql.contains("like"), sql);
        assertFalse(ps.keySet().stream().anyMatch(k -> String.valueOf(k).startsWith("wkw")), String.valueOf(ps));
    }

    @Test
    void otherAlsoCoversVolunteerJobsWithoutAKind()
    {
        // 线上唯一的换工岗位没选类型：之前「其他」永远是空的，现在算在「其他」里
        Object[] r = search(body("jobType", "volunteer", "workKinds", Collections.singletonList("other")));
        String sql = (String) r[0];
        assertTrue(sql.contains(" and j.job_type = 'volunteer' and (j.work_kind in (:wk) or j.work_kind = '')"), sql);
        assertEquals(Collections.singletonList("other"), ((Map<?, ?>) r[1]).get("wk"));
    }

    @Test
    void otherTogetherWithAnotherKindStillIncludesUntypedJobs()
    {
        Object[] r = search(body("jobType", "volunteer", "workKinds", Arrays.asList("photo", "other")));
        assertTrue(((String) r[0]).contains("(j.work_kind in (:wk) or j.work_kind = '')"), (String) r[0]);
        assertEquals(Arrays.asList("photo", "other"), ((Map<?, ?>) r[1]).get("wk"));
    }

    @Test
    void malformedKindIsRefused()
    {
        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i < 40; i++) tooLong.append('a');
        for (String bad : new String[] { tooLong.toString(), "Photo", "摄影" })
        {
            ServiceException e = assertThrows(ServiceException.class, () -> hub.search(null, body("jobType", "volunteer", "workKinds", Collections.singletonList(bad))), bad);
            assertEquals(Integer.valueOf(422), e.getCode(), bad);
            assertEquals("换工类型不合法", e.getMessage(), bad);
        }
        verify(db, never()).list(anyString(), anyMap());
    }

    @Test
    void noKindsMeansNoExtraCondition()
    {
        Object[] r = search(body("jobType", "volunteer"));
        String sql = (String) r[0];
        assertFalse(sql.contains("work_kind"), sql);
    }

    @Test
    void oldKeywordChipsStillWorkAlongsideKinds()
    {
        // 兼职 / 全职的「岗位类型」关键词照旧走标题 / 标签模糊匹配
        Object[] r = search(body("jobType", "parttime", "types", Arrays.asList("店员/助手")));
        String sql = (String) r[0];
        assertTrue(sql.contains("j.title like :t0"), sql);
        assertFalse(sql.contains("work_kind"), sql);
    }

    @Test
    void appendWorkKindsExactForNormalKindsAndBlankAlsoForOther()
    {
        StringBuilder where = new StringBuilder();
        Map<String, Object> ps = new HashMap<String, Object>();
        KgUserHubService.appendWorkKinds(where, ps, Collections.singletonList("photo"));
        assertEquals(" and j.job_type = 'volunteer' and (j.work_kind in (:wk))", where.toString());
        assertEquals(Collections.singletonList("photo"), ps.get("wk"));

        where = new StringBuilder();
        ps = new HashMap<String, Object>();
        KgUserHubService.appendWorkKinds(where, ps, Collections.singletonList("other"));
        assertEquals(" and j.job_type = 'volunteer' and (j.work_kind in (:wk) or j.work_kind = '')", where.toString());
        assertEquals(Collections.singletonList("other"), ps.get("wk"));

        where = new StringBuilder();
        KgUserHubService.appendWorkKinds(where, new HashMap<String, Object>(), Collections.<String>emptyList());
        assertEquals("", where.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void resultCardsCarryKindAndLabel()
    {
        Map<String, Object> row = new HashMap<String, Object>(p("jobId", 5L, "title", "海边摄影换工", "city", "平潭", "workKind", "photo", "dailyHours", "4小时"));
        when(db.list(anyString(), anyMap())).thenReturn(Collections.singletonList(row));
        KgHomeService home = mock(KgHomeService.class);
        when(home.jobCard(anyMap())).thenAnswer(inv -> {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("jobId", 5L);
            m.put("tags", new ArrayList<String>());
            return m;
        });
        ReflectionTestUtils.setField(hub, "home", home);
        List<Map<String, Object>> jobs = (List<Map<String, Object>>) hub.search(null, body("jobType", "volunteer")).get("jobs");
        assertEquals("photo", jobs.get(0).get("workKind"));
        assertEquals("摄影换工", jobs.get(0).get("workKindLabel"));
    }
}
