package com.ruoyi.kangaroo.ext.user;

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

import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * 岗位多条件筛选与新机会订阅推送的查询条件（不连库：KgJdbc 全部打桩，只看拼出来的 SQL 和参数）。
 *
 * 守住的是 2026-10-02 修掉的几件事：
 *   · 列表页的筛选胶囊把 fulltime 这类岗位大类代码当关键词去 like 标题，结果永远 0 条 —— 大类必须走 j.job_type；
 *   · 订阅推送与筛选必须同一口径（大类代码、"店员/助手"按斜杠拆开）；
 *   · 城市先经 KgCityService.businessCity 归一（思明区 → 厦门）再拼 like。
 */
class KgJobSearchFilterTest
{
    private KgUserHubService hub;
    private KgJdbc db;

    @BeforeEach
    void setUp()
    {
        db = mock(KgJdbc.class);
        KgCityService cities = mock(KgCityService.class);
        when(cities.businessCity(anyString())).thenAnswer(inv -> {
            String n = ((String) inv.getArgument(0)).trim();
            return "思明区".equals(n) ? "厦门" : n;
        });
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

    @Test
    @SuppressWarnings("unchecked")
    void onlineOrdersShowRemoteDeliveryInsteadOfStaleOnsiteSchedule()
    {
        Map<String,Object> app = body("appId",91L,"jobType","online","status","contract_signed","title","远程任务","city","平潭",
                "arrivalDate","2030-01-01","stayDays",30,"salaryMin",100,"salaryUnit","元/单");
        when(db.list(contains("from kg_application a join kg_job j"),anyMap())).thenReturn(Collections.singletonList(app));
        Map<String,Object> result = hub.orders(9001L,null).get(0);
        assertEquals("全国远程",result.get("loc"));
        assertEquals("无需到店 · 按任务要求交付",result.get("date"));
        assertNull(result.get("signed"));
        List<Map<String,Object>> buttons = (List<Map<String,Object>>)result.get("btns");
        assertFalse(buttons.toString().contains("签约到店"));
        assertFalse(buttons.toString().contains("到店确认"));
        assertTrue(buttons.toString().contains("/pages/jobs/deliver?appId=91"));
        app.put("jobType","parttime");
        result = hub.orders(9001L,null).get(0);
        assertEquals("平潭",result.get("loc"));
        assertTrue(result.get("date").toString().contains("30天"));
        assertTrue(result.get("btns").toString().contains("到店确认"));
        assertTrue(result.get("btns").toString().contains("/pages/jobs/contract?appId=91&s=5"));
    }

    /** 跑一次 search，返回 [sql, params] */
    @SuppressWarnings("unchecked")
    private Object[] search(Map<String, Object> b)
    {
        hub.search(null, b);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map> ps = ArgumentCaptor.forClass(Map.class);
        verify(db).list(sql.capture(), ps.capture());
        return new Object[] { sql.getValue(), ps.getValue() };
    }

    @Test
    void explicitJobTypeFiltersByColumnInsteadOfTitleLike()
    {
        Object[] r = search(body("cities", Arrays.asList("大理"), "jobType", "fulltime"));
        String sql = (String) r[0];
        Map<?, ?> ps = (Map<?, ?>) r[1];
        assertTrue(sql.contains("j.job_type in (:jt)"), sql);
        assertFalse(sql.contains("j.job_type <> 'online'"), sql);
        assertFalse(sql.contains("j.title like"), sql);
        assertEquals(Collections.singletonList("fulltime"), ps.get("jt"));
    }

    @Test
    void legacyJobTypeCodeInsideKeywordTypesIsTreatedAsJobType()
    {
        // 旧版列表页 / 旧订阅把代码混在 types 里：仍然要按大类精确匹配，而不是 like '%fulltime%'
        Object[] r = search(body("types", Arrays.asList("fulltime")));
        String sql = (String) r[0];
        Map<?, ?> ps = (Map<?, ?>) r[1];
        assertEquals(Collections.singletonList("fulltime"), ps.get("jt"));
        assertFalse(sql.contains("j.title like"), sql);
        assertFalse(ps.containsKey("t0"));
    }

    @Test
    void keywordChipsStillMatchTitleAndTagsAndDefaultStaysOffline()
    {
        Object[] r = search(body("types", Arrays.asList("店员/助手", "摄影师")));
        String sql = (String) r[0];
        Map<?, ?> ps = (Map<?, ?>) r[1];
        assertTrue(sql.contains("j.job_type <> 'online'"), sql);
        assertTrue(sql.contains("j.title like :t0 or j.tags like :t0"), sql);
        assertEquals("%店员%", ps.get("t0"));
        assertEquals("%助手%", ps.get("t1"));
        assertEquals("%摄影师%", ps.get("t2"));
        assertFalse(ps.containsKey("jt"));
    }

    @Test
    void unknownJobTypeIsIgnored()
    {
        Object[] r = search(body("jobType", "' or 1=1 --"));
        assertTrue(((String) r[0]).contains("j.job_type <> 'online'"));
        assertFalse(((Map<?, ?>) r[1]).containsKey("jt"));
    }

    @Test
    void districtIsNormalizedToBusinessCityBeforeLike()
    {
        Object[] r = search(body("cities", Arrays.asList("思明区", "厦门", "大理")));
        String sql = (String) r[0];
        Map<?, ?> ps = (Map<?, ?>) r[1];
        // 以城市结尾（KgJobCity）：「云南大理」算大理，「五大连池」不算大连
        assertEquals("%厦门", ps.get("c0_0"));
        assertEquals("%大理", ps.get("c1_0"));
        assertTrue(sql.contains("((trim(j.city) like :c0_0) or (trim(j.city) like :c1_0))"), sql);
        // 思明区与厦门归一后是同一个城市，只拼一次
        assertFalse(ps.containsKey("c2_0"));
        assertFalse(sql.contains("j.city = '不限'"), "到店岗位不放开「不限」城市：" + sql);
    }

    @Test
    void onlineJobTypeMatchesEveryCityIncludingLegacyStoreCities()
    {
        // 城市既可能是「不限」也可能是历史门店城市；按线上分类放行，不能只豁免「不限」。
        Object[] r = search(body("cities", Arrays.asList("大理"), "jobType", "online"));
        String sql = (String) r[0];
        assertTrue(sql.contains("((trim(j.city) like :c0_0) or j.job_type = 'online')"), sql);
        assertFalse(sql.contains("j.city = '不限'"), sql);
        assertEquals(Collections.singletonList("online"), ((Map<?, ?>) r[1]).get("jt"));
    }

    @Test
    void onlineCategoryFilterMatchesTaskTypeExactly()
    {
        // 线上任务类型按 task_type 精确匹配，和列表筛选 chip 同一口径（10-13：「社群运营」两处结果不一致、还会带出别的类型）
        Object[] r = search(body("jobType", "online", "types", Arrays.asList("内容写作")));
        String sql = (String) r[0];
        assertTrue(sql.contains("exists (select 1 from kg_online_task ot where ot.job_id = j.job_id and ot.task_type in (:tt))"), sql);
        assertFalse(sql.contains("j.title like"), sql);
        assertEquals(Collections.singletonList("内容写作"), ((Map<?, ?>) r[1]).get("tt"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void citySubscriptionKeepsOfflineCityButIncludesOnlineTasksNationwide()
    {
        for (String kinds : Arrays.asList("online", "online,parttime", ""))
        {
            reset(db);
            Map<String,Object> sub = body("subId", 7L, "appUserId", 9L, "cities", "大理", "jobTypes", kinds,
                    "keyword", "", "createTime", "2026-10-01 00:00:00");
            when(db.list(anyString(), anyMap())).thenReturn(new ArrayList<Map<String,Object>>());
            when(db.list(startsWith("select * from kg_job_subscribe"), anyMap())).thenReturn(Arrays.asList(sub));
            when(db.one(anyString(), anyMap())).thenReturn(null);
            ReflectionTestUtils.invokeMethod(hub, "push", "instant");
            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(db, times(2)).list(sql.capture(), anyMap());
            String jobs = sql.getAllValues().get(1);
            assertTrue(jobs.contains("((trim(j.city) like :c0_0) or j.job_type = 'online')"), jobs);
            assertTrue(jobs.contains("j.status = 'published'"), jobs);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void subscriptionPushUsesSameJobTypeAndCitySemantics()
    {
        Map<String, Object> sub = new HashMap<String, Object>();
        sub.put("subId", 7L);
        sub.put("appUserId", 9L);
        sub.put("cities", "思明区");
        sub.put("jobTypes", "fulltime,店员/助手");
        sub.put("keyword", "");
        sub.put("createTime", "2026-10-01 00:00:00");
        List<Map<String, Object>> subs = new ArrayList<Map<String, Object>>();
        subs.add(sub);
        when(db.list(startsWith("select * from kg_job_subscribe"), anyMap())).thenReturn(subs);
        // 没有通知偏好记录 = 默认开启（Mockito 对 Map 返回值默认给空 Map，会被当成"已关闭"）
        when(db.one(anyString(), anyMap())).thenReturn(null);

        ReflectionTestUtils.invokeMethod(hub, "push", "instant");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map> ps = ArgumentCaptor.forClass(Map.class);
        verify(db, times(2)).list(sql.capture(), ps.capture());
        String jobSql = sql.getAllValues().get(1);
        Map<String, Object> jobPs = ps.getAllValues().get(1);
        assertTrue(jobSql.startsWith("select j.job_id, j.title, j.city from kg_job j"), jobSql);
        assertTrue(jobSql.contains("j.job_type in (:jt)"), jobSql);
        assertEquals(Collections.singletonList("fulltime"), jobPs.get("jt"));
        assertEquals("%厦门", jobPs.get("c0_0"));
        assertEquals("%店员%", jobPs.get("t0"));
        assertEquals("%助手%", jobPs.get("t1"));
        // 推送不默认排除线上任务（订阅没选大类时什么新岗位都算）
        assertFalse(jobSql.contains("j.job_type <> 'online'"), jobSql);
    }

    @Test
    @SuppressWarnings("unchecked")
    void coordinatorSubscriptionMatchesLocalOfflineJobsAcrossStores()
    {
        Map<String, Object> sub = body("subId", 7L, "appUserId", 9L, "cities", "平潭",
                "jobTypes", "volunteer,parttime,fulltime", "keyword", "", "createTime", "2026-10-28 00:00:00");
        when(db.list(startsWith("select * from kg_job_subscribe"), anyMap())).thenReturn(Arrays.asList(sub));
        when(db.one(anyString(), anyMap())).thenReturn(null);
        ReflectionTestUtils.invokeMethod(hub, "push", "instant");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map> params = ArgumentCaptor.forClass(Map.class);
        verify(db, times(2)).list(sql.capture(), params.capture());
        String query = sql.getAllValues().get(1);
        Map<String, Object> values = params.getAllValues().get(1);
        assertTrue(query.contains("j.status = 'published'"), query);
        assertTrue(query.contains("coalesce(j.publish_time, j.create_time) > :since"), query);
        assertTrue(query.contains("j.job_type in (:jt)"), query);
        assertFalse(query.contains("j.store_id = :st"), query);
        assertFalse(query.contains("or j.job_type = 'online'"), query);
        assertEquals(Arrays.asList("volunteer", "parttime", "fulltime"), values.get("jt"));
        assertEquals("%平潭", values.get("c0_0"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void cityAliasesAreSuffixMatchedAndKeywordWildcardsAreLiteral()
    {
        // 平潭的别名（平潭岛）也按「以它结尾」匹配，和岗位列表、「有岗位的城市」下拉同一口径
        KgCityService cities = (KgCityService) ReflectionTestUtils.getField(hub, "cityService");
        when(cities.jobCityTerms("平潭")).thenReturn(new ArrayList<String>(Arrays.asList("平潭岛", "平潭县")));
        Object[] r = search(body("cities", Arrays.asList("平潭")));
        String sql = (String) r[0];
        Map<?, ?> ps = (Map<?, ?>) r[1];
        assertTrue(sql.contains("((trim(j.city) like :c0_0 or trim(j.city) like :c0_1 or trim(j.city) like :c0_2))"), sql);
        assertEquals("%平潭", ps.get("c0_0"));
        assertEquals("%平潭岛", ps.get("c0_1"));
        assertEquals("%平潭县", ps.get("c0_2"));

        // 订阅的关键词：% _ \ 按字面找
        Map<String, Object> sub = body("subId", 8L, "appUserId", 9L, "cities", "", "jobTypes", "", "keyword", "100%_\\", "createTime", "2026-10-01 00:00:00");
        reset(db);
        when(db.list(anyString(), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        when(db.list(startsWith("select * from kg_job_subscribe"), anyMap())).thenReturn(Arrays.asList(sub));
        when(db.one(anyString(), anyMap())).thenReturn(null);
        ReflectionTestUtils.invokeMethod(hub, "push", "instant");
        ArgumentCaptor<Map> jps = ArgumentCaptor.forClass(Map.class);
        verify(db, times(2)).list(anyString(), jps.capture());
        assertEquals("%100\\%\\_\\\\%", jps.getAllValues().get(1).get("kw"));
    }
    @Test
    void expectedDailyPayAlsoMatchesHourlyWeeklyMonthlyJobs()
    {
        // 期望薪资 150–200 元/天：月薪 4000（≈183.9 元/天）、时薪 25（=200 元/天）都该进来。按国家口径折算，不再只认日薪
        Object[] r = search(body("cities", Arrays.asList("大理"), "salaryMin", 150, "salaryMax", 200));
        String sql = (String) r[0];
        assertFalse(sql.contains("j.salary_type = 'daily'"), sql);
        assertTrue(sql.contains("j.salary_type in ('hourly','daily','weekly','monthly')"), sql);
        assertTrue(sql.contains("when 'hourly' then j.salary_min * 8"), sql);
        assertTrue(sql.contains("when 'monthly' then coalesce(j.salary_max, j.salary_min) / 21.75"), sql);
        assertEquals(150L, ((Map<?, ?>) r[1]).get("smin"));
        assertEquals(200L, ((Map<?, ?>) r[1]).get("smax"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void monthBonusFiltersMarkedJobsAndOffersToRelax()
    {
        Object[] r = search(body("cities", Arrays.asList("大理"), "monthBonus", Boolean.TRUE));
        assertTrue(((String) r[0]).contains("and j.month_redpacket = '0'"), (String) r[0]);
        // 老版本小程序不带这个字段：不加条件
        reset(db);
        when(db.list(anyString(), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        assertFalse(((String) search(body("cities", Arrays.asList("大理")))[0]).contains("month_redpacket"));
        // 没结果时给出「不限是否有满月红包」的放宽建议
        reset(db);
        when(db.list(anyString(), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        Map<String, Object> res = hub.search(null, body("monthBonus", "1"));
        assertTrue(((List<String>) res.get("conflicts")).contains("只看满月红包岗位"));
        assertTrue(res.get("relax").toString().contains("key=monthBonus"), String.valueOf(res.get("relax")));
    }

    /** 打桩：岗位查询返回 n 条（dailyHours 交替 4 小时 / 8 小时），首页卡片原样给一个带 tags 的 Map */
    private void jobRows(int n)
    {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < n; i++)
        {
            rows.add(body("jobId", (long) i, "city", "大理", "dailyHours", i % 2 == 0 ? "4小时" : "8小时"));
        }
        when(db.list(startsWith("select j.*"), anyMap())).thenReturn(rows);
        KgHomeService home = mock(KgHomeService.class);
        when(home.jobCard(anyMap())).thenAnswer(inv -> body("tags", new ArrayList<String>()));
        ReflectionTestUtils.setField(hub, "home", home);
    }

    @Test
    void totalIsTheTrueCountNotTheListLimit()
    {
        // 生产 10-16：大理两百多个岗位，筛选页按钮停在「查看 100 个机会」
        jobRows(KgUserHubService.SEARCH_LIMIT);
        when(db.count(anyString(), anyMap())).thenReturn(237L);
        Map<String, Object> res = hub.search(null, body("cities", Arrays.asList("大理")));
        assertEquals(237L, ((Number) res.get("total")).longValue());
        assertEquals(KgUserHubService.SEARCH_LIMIT, ((List<?>) res.get("jobs")).size());
        ArgumentCaptor<String> list = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> count = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map> ps = ArgumentCaptor.forClass(Map.class);
        verify(db).list(list.capture(), anyMap());
        verify(db).count(count.capture(), ps.capture());
        // 数的时候条件和列表一模一样，只是不排序、不截断
        String where = list.getValue().substring(list.getValue().indexOf(" from kg_job j"), list.getValue().indexOf(" order by"));
        assertEquals("select count(*)" + where, count.getValue());
        assertTrue(list.getValue().endsWith(" limit " + KgUserHubService.SEARCH_LIMIT), list.getValue());
        assertEquals("%大理", ps.getValue().get("c0_0"));
    }

    @Test
    void underTheLimitTotalIsTheListedCountWithoutAnotherQuery()
    {
        jobRows(3);
        Map<String, Object> res = hub.search(null, body("cities", Arrays.asList("大理")));
        assertEquals(3L, ((Number) res.get("total")).longValue());
        verify(db, never()).count(anyString(), anyMap());
    }

    @Test
    void dailyHoursFilterIsCountedWithTheSameRuleAsTheList()
    {
        // 每天工时是自由文本，只能按 KgSkills.hoursOf 逐条比：列表里 4 小时的留下、8 小时的去掉，总数也照这个数
        jobRows(KgUserHubService.SEARCH_LIMIT);
        when(db.list(startsWith("select j.daily_hours"), anyMap())).thenReturn(Arrays.asList(
                body("dailyHours", "4小时"), body("dailyHours", "6-8小时"), body("dailyHours", "3.5"), body("dailyHours", null), body("dailyHours", "8")));
        Map<String, Object> res = hub.search(null, body("cities", Arrays.asList("大理"), "hours", 6));
        assertEquals(KgUserHubService.SEARCH_LIMIT / 2, ((List<?>) res.get("jobs")).size());
        assertEquals(3L, ((Number) res.get("total")).longValue());
        verify(db, never()).count(anyString(), anyMap());
    }
}
