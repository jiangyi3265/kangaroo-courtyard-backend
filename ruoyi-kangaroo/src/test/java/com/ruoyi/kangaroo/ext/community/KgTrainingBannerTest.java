package com.ruoyi.kangaroo.ext.community;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.kangaroo.ext.KgJdbc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 「全职旅行」列表的培训活动横幅（2026-10-18）：后台「首页内容管理」栏目选「全职培训横幅」（kg_home_pick.section = 'training'），
 * 只出开放报名、还没开始的活动；展示城市留空 = 全国，填了城市要用户选了它才出，选了的城市排在全国的前面；没有就是 null（页面不出横幅）。
 */
class KgTrainingBannerTest
{
    private KgActivityService activities;
    private KgJdbc db;

    @BeforeEach
    void setUp()
    {
        activities = new KgActivityService();
        db = mock(KgJdbc.class);
        ReflectionTestUtils.setField(activities, "db", db);
    }

    private static Map<String, Object> act(long id)
    {
        String start = LocalDateTime.now().plusDays(5).withSecond(0).withNano(0).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Map<String, Object> a = new HashMap<String, Object>();
        a.put("activityId", id); a.put("title", "民宿管家培训营"); a.put("source", "yard"); a.put("status", "open");
        a.put("startTime", start); a.put("signed", 2L); a.put("capacity", 20L); a.put("place", "大理古城"); a.put("city", "大理");
        a.put("cover", "/profile/upload/train.jpg");
        return a;
    }

    /** 卡片还会查报名头像（avatars），取查 kg_home_pick 的那一次 */
    private static int pickCall(ArgumentCaptor<String> sql)
    {
        for (int i = 0; i < sql.getAllValues().size(); i++)
        {
            if (sql.getAllValues().get(i).contains("kg_home_pick"))
            {
                return i;
            }
        }
        fail("没有查 kg_home_pick");
        return -1;
    }

    @SuppressWarnings("unchecked")
    @Test void picksTheTrainingSectionAndCityFirst()
    {
        when(db.list(contains("kg_home_pick"), anyMap())).thenReturn(Collections.singletonList(act(7L)));
        Map<String, Object> card = activities.trainingBanner(Arrays.asList("大理", " 丽江 ", "大理", ""));
        assertNotNull(card);
        assertEquals(7L, card.get("activityId"));
        assertEquals("民宿管家培训营", card.get("title"));
        assertEquals("/profile/upload/train.jpg", card.get("img"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> ps = ArgumentCaptor.forClass(Map.class);
        verify(db, atLeastOnce()).list(sql.capture(), ps.capture());
        int at = pickCall(sql);
        String q = sql.getAllValues().get(at);
        assertTrue(q.contains("h.section='training'"));
        assertTrue(q.contains("a.status='open'") && q.contains("a.start_time>now()"));
        assertTrue(q.contains("h.city='' or h.city in (:c0,:c1)"), q);
        // 选了的城市排在全国（留空）的前面，同一档按显示顺序
        assertTrue(q.contains("order by case when h.city='' then 1 else 0 end, h.sort"));
        assertEquals("大理", ps.getAllValues().get(at).get("c0"));
        assertEquals("丽江", ps.getAllValues().get(at).get("c1"));
        assertFalse(ps.getAllValues().get(at).containsKey("c2"));
    }

    @Test void noCityOnlyMatchesNationwidePicks()
    {
        when(db.list(contains("kg_home_pick"), anyMap())).thenReturn(Collections.singletonList(act(8L)));
        assertNotNull(activities.trainingBanner(null));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db, atLeastOnce()).list(sql.capture(), anyMap());
        assertTrue(sql.getAllValues().get(pickCall(sql)).contains("and h.city='' and"));
    }

    @Test void nothingConfiguredMeansNoBanner()
    {
        when(db.list(contains("kg_home_pick"), anyMap())).thenReturn(Collections.<Map<String, Object>>emptyList());
        assertNull(activities.trainingBanner(Arrays.asList("大理")));
    }
}
