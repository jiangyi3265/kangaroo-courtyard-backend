package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * 小程序公告：弹窗只弹没看过当前版本的、按人群过滤（游客只有面向所有人的）、「我知道了」按版本只增不减（不连库）。
 */
class KgAppNoticeServiceTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final KgAppNoticeService service = new KgAppNoticeService();

    @BeforeEach
    void setUp()
    {
        ReflectionTestUtils.setField(service, "db", db);
    }

    private static Map<String, Object> notice(long id, long version, Long seenVersion)
    {
        Map<String, Object> m = new HashMap<String, Object>(p("noticeId", id, "title", "公告" + id, "content", "第一行\n第二行", "noticeKey", null,
                "version", version, "popup", "1", "shownTime", "2026-10-10 09:00:00"));
        m.put("seenVersion", seenVersion);
        return m;
    }

    private void interestedInOnline(boolean yes)
    {
        when(db.count(contains("kg_job_subscribe"), anyMap())).thenReturn(yes ? 1L : 0L);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private Object[] lastList()
    {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map> ps = ArgumentCaptor.forClass(Map.class);
        verify(db).list(sql.capture(), ps.capture());
        return new Object[] { sql.getValue(), ps.getValue() };
    }

    @Test
    void guestsOnlySeeNoticesForEveryoneAndNeverHitTheSeenTable()
    {
        when(db.list(anyString(), anyMap())).thenReturn(Collections.singletonList(notice(3, 1, null)));
        Map<String, Object> n = service.popup(null, null);
        assertEquals(3L, n.get("noticeId"));
        assertEquals("第一行\n第二行", n.get("content"));
        Object[] r = lastList();
        assertFalse(((String) r[0]).contains("kg_app_notice_seen"), (String) r[0]);
        assertTrue(((String) r[0]).contains("n.popup = '1'"));
        assertEquals(Collections.singletonList("all"), ((Map<?, ?>) r[1]).get("aud"));
        // 游客不查「是否关注线上任务」
        verify(db, never()).count(anyString(), anyMap());
    }

    @Test
    void peopleInterestedInOnlineTasksAlsoGetOnlineNotices()
    {
        interestedInOnline(true);
        when(db.list(anyString(), anyMap())).thenReturn(Collections.singletonList(notice(8, 1, null)));
        service.popup(9001L, null);
        Object[] r = lastList();
        assertEquals(Arrays.asList("all", "online"), ((Map<?, ?>) r[1]).get("aud"));
        assertTrue(((String) r[0]).contains("left join kg_app_notice_seen s on s.notice_id = n.notice_id and s.user_id = :u"));
        assertEquals(9001L, ((Map<?, ?>) r[1]).get("u"));
    }

    @Test
    void othersOnlyGetNoticesForEveryone()
    {
        interestedInOnline(false);
        when(db.list(anyString(), anyMap())).thenReturn(Collections.<Map<String, Object>>emptyList());
        assertNull(service.popup(9001L, null));
        assertEquals(Collections.singletonList("all"), ((Map<?, ?>) lastList()[1]).get("aud"));
    }

    @Test
    void popupSkipsNoticesAlreadySeenAtTheCurrentVersion()
    {
        interestedInOnline(false);
        when(db.list(anyString(), anyMap())).thenReturn(Arrays.asList(notice(1, 2, 2L), notice(2, 1, null)));
        assertEquals(2L, service.popup(9001L, null).get("noticeId"));
    }

    @Test
    void editedNoticePopsUpAgainForPeopleWhoSawTheOldVersion()
    {
        interestedInOnline(false);
        when(db.list(anyString(), anyMap())).thenReturn(Collections.singletonList(notice(1, 3, 2L)));
        Map<String, Object> n = service.popup(9001L, null);
        assertEquals(1L, n.get("noticeId"));
        assertEquals(3L, n.get("version"));
    }

    @Test
    void guestLocalRecordsAreHonouredPerVersion()
    {
        when(db.list(anyString(), anyMap())).thenReturn(Arrays.asList(notice(1, 2, null), notice(2, 1, null)));
        // 本地记着 1 号看过第 2 版：跳过，弹 2 号
        assertEquals(2L, service.popup(null, "1:2").get("noticeId"));
        // 本地只看过 1 号的第 1 版：1 号改过了，再弹
        assertEquals(1L, service.popup(null, "1:1,2:1").get("noticeId"));
        // 都看过：不弹
        assertNull(service.popup(null, "1:2,2:1"));
    }

    @Test
    void malformedLocalRecordsAreIgnored()
    {
        Map<Long, Long> seen = KgAppNoticeService.parseSeen("1:2, x:3 ,4:,5:9,5:1,;drop table,7:0");
        assertEquals(Long.valueOf(2), seen.get(1L));
        assertEquals(Long.valueOf(9), seen.get(5L));
        assertEquals(Long.valueOf(0), seen.get(7L));
        assertEquals(3, seen.size());
        assertTrue(KgAppNoticeService.parseSeen(null).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void seenIsCappedAtTheCurrentVersionAndNeverGoesBackwards()
    {
        when(db.one(contains("from kg_app_notice where notice_id = :id"), anyMap())).thenReturn(p("noticeId", 5L, "version", 3L));
        Map<String, Object> out = service.seen(9001L, 5L, 9);
        assertEquals(3L, out.get("version"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map> ps = ArgumentCaptor.forClass(Map.class);
        verify(db).update(sql.capture(), ps.capture());
        assertTrue(sql.getValue().contains("on duplicate key update version = greatest(version, :v)"), sql.getValue());
        assertEquals(3L, ps.getValue().get("v"));
        assertEquals(9001L, ps.getValue().get("u"));
        assertEquals(5L, ps.getValue().get("n"));
    }

    @Test
    void seenAcceptsAnOlderVersionSoTheNewContentPopsUpLater()
    {
        when(db.one(contains("from kg_app_notice where notice_id = :id"), anyMap())).thenReturn(p("noticeId", 5L, "version", 3L));
        assertEquals(2L, service.seen(9001L, 5L, "2").get("version"));
    }

    @Test
    void seenRejectsBadInput()
    {
        when(db.one(contains("from kg_app_notice where notice_id = :id"), anyMap())).thenReturn(p("noticeId", 5L, "version", 3L));
        assertEquals(Integer.valueOf(422), assertThrows(ServiceException.class, () -> service.seen(9001L, 5L, 0)).getCode());
        assertEquals(Integer.valueOf(422), assertThrows(ServiceException.class, () -> service.seen(9001L, 5L, "abc")).getCode());
        when(db.one(contains("from kg_app_notice where notice_id = :id"), anyMap())).thenReturn(null);
        assertEquals(Integer.valueOf(404), assertThrows(ServiceException.class, () -> service.seen(9001L, 6L, 1)).getCode());
        verify(db, never()).update(anyString(), anyMap());
    }

    @Test
    void detailOpensByKeyOrIdForEveryoneButRejectsJunk()
    {
        when(db.one(contains("n.notice_key = :k"), anyMap())).thenReturn(notice(4, 1, null));
        Map<String, Object> byKey = service.detail(null, "online-guide");
        assertEquals("公告4", byKey.get("title"));
        assertEquals(false, byKey.get("read"));
        when(db.one(contains("n.notice_id = :id"), anyMap())).thenReturn(notice(4, 1, null));
        assertEquals(4L, service.detail(null, "4").get("noticeId"));
        assertEquals(Integer.valueOf(404), assertThrows(ServiceException.class, () -> service.detail(null, "../etc")).getCode());
        assertEquals(Integer.valueOf(404), assertThrows(ServiceException.class, () -> service.detail(null, "Online Guide")).getCode());
    }

    @Test
    void detailIsReadOnceTheCurrentVersionWasSeen()
    {
        when(db.one(contains("n.notice_key = :k"), anyMap())).thenReturn(notice(4, 2, null));
        when(db.one(contains("from kg_app_notice_seen"), anyMap())).thenReturn(p("version", 2L));
        assertEquals(true, service.detail(9001L, "online-guide").get("read"));
        when(db.one(contains("from kg_app_notice_seen"), anyMap())).thenReturn(p("version", 1L));
        assertEquals(false, service.detail(9001L, "online-guide").get("read"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listGivesShortSummaryAndReadFlag()
    {
        interestedInOnline(false);
        Map<String, Object> longOne = notice(1, 2, 2L);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 20; i++) text.append("这是一段很长的说明\n");
        longOne.put("content", text.toString());
        when(db.list(anyString(), anyMap())).thenReturn(Arrays.asList(longOne, notice(2, 1, null)));
        List<Map<String, Object>> list = service.list(9001L);
        assertEquals(true, list.get(0).get("read"));
        assertEquals(false, list.get(1).get("read"));
        String summary = (String) list.get(0).get("summary");
        assertTrue(summary.endsWith("…") && summary.length() == 61 && !summary.contains("\n"), summary);
        assertFalse(list.get(0).containsKey("content"));
        assertEquals("2026-10-10", list.get(0).get("date"));
    }
}
