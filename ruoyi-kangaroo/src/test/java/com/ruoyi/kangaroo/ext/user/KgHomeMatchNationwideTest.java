package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgActivityService;

/**
 * 首页「找机会」没选城市时全国匹配（不连库：KgJdbc 与城市服务全部打桩，只看发出去的 SQL 和参数）。
 *
 * 2026-10-16 改版：首页搜索条的圆钮按当前城市直接匹配，城市是「全部城市」时前端传空串。
 * 之前 KgHomeService.match 在文字里也找不到城市名时一律默认成「大理」：页面写着全部城市，结果只有大理的岗位、房源、活动。
 */
class KgHomeMatchNationwideTest
{
    private KgHomeService home;
    private KgJdbc db;
    private KgCityService cities;

    @BeforeEach
    void setUp()
    {
        db = mock(KgJdbc.class);
        cities = mock(KgCityService.class);
        when(cities.businessCity(anyString())).thenAnswer(inv -> ((String) inv.getArgument(0)).trim());
        home = new KgHomeService();
        ReflectionTestUtils.setField(home, "db", db);
        ReflectionTestUtils.setField(home, "cities", cities);
        ReflectionTestUtils.setField(home, "activities", mock(KgActivityService.class));
    }

    /** 打分用得到的岗位字段：门店在营、包住、没写开始日期和最少天数 */
    private static Map<String, Object> job(long id, String city)
    {
        return p("jobId", id, "title", "客栈前台" + id, "city", city, "jobType", "volunteer", "boardType", "room", "salaryType", "none",
                "storeName", "小院" + id, "safeCert", "0", "insuranceFlag", "0");
    }

    private static Map<String, Object> body(String city, String text)
    {
        Map<String, Object> b = p("city", city, "text", text, "from", "2026-10-20", "to", "2026-10-26", "people", 1);
        return b;
    }

    /** 记下 match 发出去的每一条 SQL 和参数 */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private List<Object[]> queries()
    {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map> ps = ArgumentCaptor.forClass(Map.class);
        verify(db, atLeastOnce()).list(sql.capture(), ps.capture());
        List<Object[]> out = new ArrayList<Object[]>();
        for (int i = 0; i < sql.getAllValues().size(); i++)
        {
            out.add(new Object[] { sql.getAllValues().get(i), ps.getAllValues().get(i) });
        }
        return out;
    }

    private static Object[] find(List<Object[]> qs, String fragment)
    {
        for (Object[] q : qs)
        {
            if (((String) q[0]).contains(fragment))
            {
                return q;
            }
        }
        fail("没有发出含「" + fragment + "」的查询");
        return null;
    }

    @Test
    @SuppressWarnings("unchecked")
    void blankCityMatchesNationwideInsteadOfDali()
    {
        when(db.list(startsWith("select j.*, s.store_name"), anyMap())).thenReturn(Arrays.asList(job(1L, "丽江"), job(2L, "平潭")));
        Map<String, Object> res = home.match(null, body("", "想找个包住的地方待一周"));

        assertEquals("", res.get("city"), "全国匹配回空串，前端照旧显示「全部城市」");
        assertEquals(Boolean.FALSE, res.get("empty"));
        List<Map<String, Object>> jobs = (List<Map<String, Object>>) res.get("jobs");
        assertEquals(2, jobs.size(), "不同城市的岗位都能匹配上");
        // 城市维度不扣分：全部城市本来就不限城市
        for (Map<String, Object> r : (List<Map<String, Object>>) res.get("reasons"))
        {
            if ("城市".equals(r.get("label")))
            {
                assertEquals(100, r.get("score"));
                assertEquals("你选择了全部城市，在全国范围内为你推荐。", r.get("desc"));
            }
        }

        List<Object[]> qs = queries();
        for (Object[] q : qs)
        {
            String sql = (String) q[0];
            Map<String, Object> ps = (Map<String, Object>) q[1];
            assertFalse(ps.containsValue("大理"), "不再默认成大理：" + sql + " " + ps);
            assertFalse(ps.containsKey("c"), "全国匹配不带城市参数：" + sql);
            assertFalse(sql.contains("j.city"), sql);
            assertFalse(sql.contains("s.city = :c"), sql);
            assertFalse(sql.contains("city = :c"), sql);
        }
        String jobs200 = (String) find(qs, "select j.*, s.store_name")[0];
        assertTrue(jobs200.endsWith("order by j.urgent_level desc, coalesce(j.publish_time, j.create_time) desc limit 200"), jobs200);
        assertTrue(((String) find(qs, "from kg_stay s where ")[0]).endsWith("order by s.sort, s.stay_id limit 30"));
        String activities = (String) find(qs, "from kg_activity a where ")[0];
        assertTrue(activities.endsWith("order by start_time limit 20"), activities);
        assertTrue(activities.contains("kangaroo.community.enabled"), activities);
        // 没有同城市群可扩，也不去查城市概况
        verify(cities, never()).overview(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void allCitiesLabelAndCityRelaxAlsoStayNationwide()
    {
        Map<String, Object> b = body("全部城市", "");
        b.put("relax", Arrays.asList("city", "date"));
        Map<String, Object> res = home.match(null, b);
        assertEquals("", res.get("city"));
        assertEquals(Boolean.TRUE, res.get("empty"));
        // 一条都没有时的冲突说明里不再写「只在大理内选择较少」
        for (String c : (List<String>) res.get("conflicts"))
        {
            assertFalse(c.startsWith("城市："), c);
        }
        for (Object[] q : queries())
        {
            assertFalse(((Map<String, Object>) q[1]).containsKey("c"), (String) q[0]);
        }
        verify(cities, never()).overview(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void cityWrittenInTheTextStillWins()
    {
        when(db.list(startsWith("select name from kg_city"), anyMap())).thenReturn(Collections.singletonList(p("name", "丽江")));
        Map<String, Object> res = home.match(null, body("", "丽江换工单间一个月"));
        assertEquals("丽江", res.get("city"));
        List<Object[]> qs = queries();
        Object[] stays = find(qs, "from kg_stay s where s.city = :c and ");
        assertEquals("丽江", ((Map<String, Object>) stays[1]).get("c"));
        Object[] acts = find(qs, "from kg_activity a where ");
        assertTrue(((String) acts[0]).contains("city = :c and "));
        assertTrue(((String) acts[0]).contains("kangaroo.community.enabled"));
        assertEquals("丽江", ((Map<String, Object>) acts[1]).get("c"));
        assertTrue(((String) acts[0]).endsWith("limit 10"));
        String jobs = (String) find(qs, "select j.*, s.store_name")[0];
        assertTrue(jobs.contains("trim(j.city)") && jobs.endsWith(" limit 100"), jobs);
    }

    @Test
    void singleCharacterDistrictNamesAreNotTakenAsTheCity()
    {
        // 城市库里有「义」「和」「新」「理」「温」这类一个字的区县：之前「找换工」被认成城市「义」，结果一条都没有
        List<String> names = Arrays.asList("义", "和", "新", "理", "温", "平潭", "大理", "大理白族自治州", "丽江");
        assertEquals("", KgHomeService.cityInText("找换工", names));
        assertEquals("", KgHomeService.cityInText("和朋友一起，新手也可以", names));
        assertEquals("", KgHomeService.cityInText("南方温暖的地方", names));
        assertEquals("平潭", KgHomeService.cityInText("想去平潭做换工", names));
        // 取最长的；一样长取先出现的
        assertEquals("大理白族自治州", KgHomeService.cityInText("大理白族自治州的小院", names));
        assertEquals("丽江", KgHomeService.cityInText("丽江或者大理都行", names));
        assertEquals("", KgHomeService.cityInText("", names));
        assertEquals("", KgHomeService.cityInText(null, names));
    }

    @Test
    @SuppressWarnings("unchecked")
    void wantedJobTypeIsFilteredInSqlBeforeTheLimit()
    {
        // 全国匹配「全职」：类型条件要在取前 200 个之前，不然全职多半排不进前 200
        home.match(null, body("", "想找全职或者兼职"));
        Object[] q = find(queries(), "select j.*, s.store_name");
        String sql = (String) q[0];
        assertTrue(sql.contains(" and j.job_type in (:jt)"), sql);
        assertTrue(sql.indexOf("j.job_type in (:jt)") < sql.indexOf("limit 200"), sql);
        assertEquals(Arrays.asList("parttime", "fulltime"), ((Map<String, Object>) q[1]).get("jt"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void noWantedTypeKeepsAllOfflineTypes()
    {
        home.match(null, body("", "想找个包住的地方"));
        Object[] q = find(queries(), "select j.*, s.store_name");
        assertFalse(((String) q[0]).contains("j.job_type in (:jt)"), (String) q[0]);
        assertFalse(((Map<String, Object>) q[1]).containsKey("jt"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void onlyCityAndDestinationLevelNamesAreDetected()
    {
        // 区县名会和日常说法撞上：「想去大城市」里的「大城」是河北大城县。认城市只查地级城市和目的地
        home.match(null, body("", "想去大城市"));
        Object[] q = find(queries(), "select name from kg_city");
        assertTrue(((String) q[0]).contains("level in ('city', 'destination')"), (String) q[0]);
    }

    @Test
    void singleCharacterCityInTheDbDoesNotNarrowTheMatch()
    {
        when(db.list(startsWith("select name from kg_city"), anyMap())).thenReturn(Arrays.asList(p("name", "义"), p("name", "大理")));
        Map<String, Object> res = home.match(null, body("", "找换工"));
        assertEquals("", res.get("city"), "「找换工」不再被认成城市「义」，按全国匹配");
    }

    @Test
    @SuppressWarnings("unchecked")
    void chosenCityKeepsItsOwnFilterAndConflictLine()
    {
        Map<String, Object> res = home.match(null, body("大理", ""));
        assertEquals("大理", res.get("city"));
        assertTrue(((List<String>) res.get("conflicts")).contains("城市：只在大理内选择较少"));
        for (Map<String, Object> r : (List<Map<String, Object>>) res.get("reasons"))
        {
            if ("城市".equals(r.get("label")))
            {
                assertEquals("你选择了大理，优先推荐当地的机会。", r.get("desc"));
            }
        }
        Object[] stays = find(queries(), "from kg_stay s where s.city = :c and ");
        assertEquals("大理", ((Map<String, Object>) stays[1]).get("c"));
    }
}
