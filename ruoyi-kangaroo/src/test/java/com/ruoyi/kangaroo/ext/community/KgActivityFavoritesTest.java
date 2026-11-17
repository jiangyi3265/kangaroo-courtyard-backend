package com.ruoyi.kangaroo.ext.community;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
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
 * 活动收藏（10-16 反馈：卡片上收藏、回头看报名截止时间）：不连库、不加迁移，沿用 kg_favorite（target_type = 'activity'）。
 * 列表卡片带 favorited（这一页一次查完）；GET /app/activities/favorites 按截止时间排、已结束的放后面标灰。
 */
class KgActivityFavoritesTest
{
    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static String at(int days, int hours)
    {
        return LocalDateTime.now().plusDays(days).plusHours(hours).withSecond(0).withNano(0).format(F);
    }

    private KgActivityService activities;
    private KgJdbc db;

    @BeforeEach
    void setUp()
    {
        activities = new KgActivityService();
        db = mock(KgJdbc.class);
        ReflectionTestUtils.setField(activities, "db", db);
    }

    private static Map<String, Object> act(long id, String status, String start, String end, String deadline)
    {
        Map<String, Object> a = new HashMap<String, Object>();
        a.put("activityId", id); a.put("title", "活动" + id); a.put("source", "yard"); a.put("status", status);
        a.put("startTime", start); a.put("endTime", end); a.put("deadline", deadline); a.put("signed", 2L); a.put("capacity", 10L);
        a.put("place", "门店"); a.put("city", "平潭");
        return a;
    }

    private static Map<String, Object> fav(long id)
    {
        Map<String, Object> r = new HashMap<String, Object>();
        r.put("targetId", id);
        return r;
    }

    @Test
    @SuppressWarnings("unchecked")
    void listMarksFavoritedCardsWithOneQuery()
    {
        List<Map<String, Object>> rows = Arrays.asList(act(1, "open", at(2, 0), at(2, 2), at(1, 0)), act(2, "open", at(3, 0), null, null));
        when(db.list(startsWith("select a.* from kg_activity a"), anyMap())).thenReturn(rows);
        when(db.list(contains("from kg_favorite where target_type = 'activity'"), anyMap())).thenReturn(new ArrayList<Map<String, Object>>(Arrays.asList(fav(2))));
        Map<String, Object> res = activities.list(7L, null, "全部城市", null);
        List<Map<String, Object>> groups = (List<Map<String, Object>>) res.get("groups");
        Map<Long, Object> flag = new HashMap<Long, Object>();
        for (Map<String, Object> g : groups)
        {
            for (Map<String, Object> m : (List<Map<String, Object>>) g.get("items"))
            {
                flag.put((Long) m.get("activityId"), m.get("favorited"));
                assertNotNull(m.get("deadlineText"), "卡片带报名截止");
            }
        }
        assertEquals(Boolean.FALSE, flag.get(1L));
        assertEquals(Boolean.TRUE, flag.get(2L));
        ArgumentCaptor<Map<String, Object>> ps = ArgumentCaptor.forClass(Map.class);
        verify(db, times(1)).list(contains("from kg_favorite where target_type = 'activity'"), ps.capture());
        assertEquals(Arrays.asList(1L, 2L), ps.getValue().get("ids"));
        assertEquals(7L, ps.getValue().get("u"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void guestListNeverQueriesFavorites()
    {
        when(db.list(startsWith("select a.* from kg_activity a"), anyMap())).thenReturn(Arrays.asList(act(1, "open", at(2, 0), null, null)));
        Map<String, Object> res = activities.list(null, null, null, null);
        Map<String, Object> card = ((List<Map<String, Object>>) ((List<Map<String, Object>>) res.get("groups")).get(0).get("items")).get(0);
        assertEquals(Boolean.FALSE, card.get("favorited"));
        verify(db, never()).list(contains("kg_favorite"), anyMap());
    }

    @Test
    void favoritesCarryDeadlineAndGreyOutEndedOnes()
    {
        Map<String, Object> open = act(1, "open", at(5, 0), at(5, 3), at(4, 0));
        Map<String, Object> noDeadline = act(2, "open", at(6, 0), null, null);
        Map<String, Object> paused = act(3, "paused", at(7, 0), null, null);
        Map<String, Object> ended = act(4, "ended", at(-3, 0), at(-3, 2), null);
        Map<String, Object> pastButOpen = act(5, "open", at(-2, 0), at(-2, 1), null);
        Map<String, Object> cancelled = act(6, "cancelled", at(2, 0), null, null);
        when(db.list(contains("from kg_favorite f join kg_activity a"), anyMap()))
                .thenReturn(Arrays.asList(open, noDeadline, paused, ended, pastButOpen, cancelled));
        List<Map<String, Object>> out = activities.favorites(7L);
        assertEquals(6, out.size());

        assertEquals(Boolean.FALSE, out.get(0).get("ended"));
        assertTrue(String.valueOf(out.get(0).get("deadlineNote")).startsWith("报名截止 "));
        assertEquals("报名中", out.get(0).get("state"));
        assertEquals(Boolean.TRUE, out.get(0).get("favorited"));

        assertEquals("开始前都可报名", out.get(1).get("deadlineNote"));
        assertEquals("暂停报名", out.get(2).get("deadlineNote"));

        assertEquals(Boolean.TRUE, out.get(3).get("ended"));
        assertEquals("已结束", out.get(3).get("state"));
        assertEquals("活动已结束", out.get(3).get("deadlineNote"));
        // 状态还是报名中、但时间已经过了：一样算结束
        assertEquals(Boolean.TRUE, out.get(4).get("ended"));
        assertEquals("已取消", out.get(5).get("state"));
        assertEquals("活动已取消", out.get(5).get("deadlineNote"));
    }

    @Test
    void favoritesQueryOnlyListsVisibleActivitiesEndedLast()
    {
        when(db.list(anyString(), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        activities.favorites(7L);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db).list(sql.capture(), anyMap());
        String q = sql.getValue();
        assertTrue(q.contains("f.target_type = 'activity' and f.app_user_id = :u"));
        assertTrue(q.contains("a.del_flag = '0'"));
        assertTrue(q.contains("a.status in ('open','paused','closed','ended','cancelled')"), "待审核 / 驳回 / 草稿不列");
        assertTrue(q.contains("order by (a.status in ('ended','cancelled') or"), "已结束的排后面");
        assertTrue(q.contains("(a.status <> 'open' or coalesce(a.deadline, a.start_time) <= now())"), "还能报名的排在报不了的前面");
        assertTrue(q.contains("coalesce(a.deadline, a.start_time) end"), "没结束的按截止（没设截止按开始）由近到远");
    }
}
