package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgActivityService;

/**
 * 首页「近期活动」固定露两格的补位（不连库：KgJdbc 与活动服务全部打桩）。
 *
 * 2026-10-15 线上一场「报名中」的活动都没有，首页只剩一行「近期活动」标题；原来只取后台精选 + 14 天内的热门。
 * 现在不足 2 场时先补本城市 14 天以后报名中的，选了城市还不够再补其他城市的；去重，置顶的在前。社区页的 hots 不变。
 */
class KgHomeFeedFillTest
{
    private KgHomeService home;
    private KgActivityService activities;
    private KgJdbc db;

    @BeforeEach
    void setUp()
    {
        db = mock(KgJdbc.class);
        KgCityService cities = mock(KgCityService.class);
        when(cities.businessCity(anyString())).thenAnswer(inv -> ((String) inv.getArgument(0)).trim());
        activities = mock(KgActivityService.class);
        home = new KgHomeService();
        ReflectionTestUtils.setField(home, "db", db);
        ReflectionTestUtils.setField(home, "cities", cities);
        ReflectionTestUtils.setField(home, "activities", activities);
    }

    private static Map<String, Object> act(long id)
    {
        return p("activityId", id, "title", "活动" + id);
    }

    @Test
    void stayRecommendationsUseCurrentStoreTypeIndependentlyOfTrustAndName()
    {
        Map<String, Object> direct = p("stayId", 1L, "stayName", "直营院子", "city", "平潭", "safeCert", "1", "opType", "direct");
        Map<String, Object> partner = p("stayId", 2L, "stayName", "袋鼠合作院子", "city", "丽江", "safeCert", "0", "opType", "franchise");
        when(db.list(contains("from kg_stay s join kg_store st"), anyMap())).thenReturn(Arrays.asList(direct, partner));
        List<Map<String, Object>> result = home.recommendedStays();
        assertEquals("direct", result.get(0).get("opType"));
        assertEquals(Boolean.FALSE, result.get(0).get("safe"));
        assertEquals("franchise", result.get(1).get("opType"));
        assertEquals(Boolean.TRUE, result.get(1).get("safe"));
        verify(db).list(contains("st.op_type"), anyMap());
        direct.put("opType", "partner");
        assertEquals("partner", home.recommendedStays().get(0).get("opType"), "后台改分类后首页下一次读取必须同步");
    }

    private static List<Map<String, Object>> acts(long... ids)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (long id : ids)
        {
            out.add(act(id));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Long> feedIds(String city)
    {
        List<Long> ids = new ArrayList<Long>();
        for (Map<String, Object> a : (List<Map<String, Object>>) home.feed(null, city).get("activities"))
        {
            ids.add((Long) a.get("activityId"));
        }
        return ids;
    }

    @Test
    void activityTwentyDaysOutFillsTheHomeWhenNothingStartsWithinFourteenDays()
    {
        // 精选、14 天内热门都是空的；20 天后那场只有 upcoming 查得到
        when(activities.upcoming("", 3)).thenReturn(acts(7L));
        assertEquals(Arrays.asList(7L), feedIds(null));
        // 没选城市时不会再去「其他城市」补一次（本来就是全部城市）
        verify(activities, times(1)).upcoming(anyString(), anyInt());
    }

    @Test
    void otherCitiesFillWhenTheChosenCityHasFewerThanTwo()
    {
        when(activities.hots("大理")).thenReturn(acts(1L));
        when(activities.upcoming("大理", 3)).thenReturn(acts(1L));
        when(activities.upcoming("", 3)).thenReturn(acts(1L, 2L, 3L));
        List<Long> ids = feedIds("大理");
        assertEquals(Arrays.asList(1L, 2L, 3L), ids, "本城市的在前，外地的补在后面，同一场只出现一次");
        verify(activities).upcoming("大理", 3);
        verify(activities).upcoming("", 3);
    }

    @Test
    void picksAndHotsStayFirstAndDuplicatesCollapse()
    {
        when(activities.homePicks("厦门")).thenReturn(acts(5L));
        when(activities.hots("厦门")).thenReturn(acts(5L));
        when(activities.upcoming("厦门", 3)).thenReturn(acts(6L, 5L));
        assertEquals(Arrays.asList(5L, 6L), feedIds("厦门"));
        // 本城市补够两场了，不再拿外地的
        verify(activities, never()).upcoming("", 3);
    }

    @Test
    void noFillWhenTwoAlreadyStartWithinFourteenDays()
    {
        when(activities.homePicks("")).thenReturn(acts(9L));
        when(activities.hots("")).thenReturn(acts(8L, 9L, 10L, 11L));
        assertEquals(Arrays.asList(9L, 8L, 10L), feedIds(""), "精选在前、按 activityId 去重，最多 3 场（旧版小程序一行放 3 张）");
        verify(activities, never()).upcoming(anyString(), anyInt());
    }

    @Test
    void chosenCityWithNothingAnywhereTriesItsOwnCityThenAllCities()
    {
        assertEquals(Collections.emptyList(), feedIds("丽江"));
        verify(activities).upcoming("丽江", 3);
        verify(activities).upcoming("", 3);
    }

    // ---------------------------------------------------------------- upcoming 的查询口径

    private static String at(int days)
    {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(System.currentTimeMillis() + days * 86_400_000L));
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void upcomingDropsTheFourteenDayCapPutsPinnedFirstAndReturnsTheHomeCardFields()
    {
        KgActivityService service = new KgActivityService();
        KgJdbc db = mock(KgJdbc.class);
        ReflectionTestUtils.setField(service, "db", db);
        Map<String, Object> row = p("activityId", 7L, "title", "苍山徒步", "cover", "/upload/a.jpg", "source", "yard", "city", "大理", "place", "古城南门",
                "startTime", at(20), "endTime", at(21), "capacity", 10, "signed", 8, "feeFen", 0, "status", "open", "pinned", "1");
        when(db.list(startsWith("select a.* from kg_activity a"), anyMap())).thenReturn(Collections.singletonList(row));

        List<Map<String, Object>> cards = service.upcoming("大理", 3);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map> ps = ArgumentCaptor.forClass(Map.class);
        // 第一条是活动查询，后面是卡片上的报名头像
        verify(db, atLeastOnce()).list(sql.capture(), ps.capture());
        String q = sql.getAllValues().get(0);
        assertFalse(q.contains("interval 14 day"), q);
        assertTrue(q.contains("a.status = 'open'") && q.contains("a.del_flag = '0'") && q.contains("a.start_time > now()"), q);
        assertTrue(q.contains("order by a.pinned desc, a.start_time"), "置顶的在前，其余按开始时间：" + q);
        assertTrue(q.contains("a.city = :c"), q);
        assertEquals("大理", ps.getAllValues().get(0).get("c"));
        assertTrue(q.endsWith("limit 3"), q);

        // 首页大卡用到的字段都在（前端 pages/home/index.vue 直接读这些）
        Map<String, Object> card = cards.get(0);
        assertEquals(7L, card.get("activityId"));
        for (String key : Arrays.asList("title", "img", "dateText", "time", "city", "place", "priceText", "feeFen", "few", "full"))
        {
            assertTrue(card.containsKey(key), key);
        }
        assertEquals("免费", card.get("priceText"));
        assertEquals(true, card.get("few"));
        assertEquals(false, card.get("full"));
        assertTrue(String.valueOf(card.get("dateText")).contains("月"));
    }

    @Test
    @SuppressWarnings("rawtypes")
    void upcomingWithoutCityHasNoCityFilterAndCommunityHotsKeepsItsCap()
    {
        KgActivityService service = new KgActivityService();
        KgJdbc db = mock(KgJdbc.class);
        ReflectionTestUtils.setField(service, "db", db);
        service.upcoming("全部城市", 50);
        service.hots("");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map> ps = ArgumentCaptor.forClass(Map.class);
        verify(db, times(2)).list(sql.capture(), ps.capture());
        String upcoming = sql.getAllValues().get(0);
        assertFalse(upcoming.contains("a.city = :c"), upcoming);
        assertFalse(ps.getAllValues().get(0).containsKey("c"));
        assertTrue(upcoming.endsWith("limit 20"), "一次最多取 20 场：" + upcoming);
        // 社区页的热门不受这次改动影响：还是 14 天内
        assertTrue(sql.getAllValues().get(1).contains("interval 14 day"), sql.getAllValues().get(1));
    }
}
