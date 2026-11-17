package com.ruoyi.kangaroo.ext.stay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.controller.app.AppJobController;
import com.ruoyi.kangaroo.domain.KgJob;
import com.ruoyi.kangaroo.domain.KgStore;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.service.IKgJobService;
import com.ruoyi.kangaroo.service.IKgStoreService;

class KgStoreDiaryTest
{
    final KgJdbc db = mock(KgJdbc.class);
    final KgStayMediaService media = new KgStayMediaService();
    final String a = "/profile/upload/a.jpg", b = "/profile/upload/b.jpg";
    final String video = "/profile/upload/videos/2026/10/24/0123456789abcdef0123456789abcdef.mp4";
    Map<String, Object> store, stay;
    List<Map<String, Object>> published;

    @BeforeEach void setup()
    {
        ReflectionTestUtils.setField(media, "db", db);
        store = p("storeId", 102L, "storeName", "平潭小院", "cover", a, "dailyImages", a + "," + b);
        stay = p("stayId", 9L, "stayName", "平潭旅居", "diaryTitle", "海边日记", "diarySubtitle", "把日子记下来", "diaryIntro", "一日三餐\n日常相伴");
        published = new ArrayList<>();
        when(db.one(startsWith("select store_id, store_name"), anyMap())).thenAnswer(c -> store);
        when(db.one(contains("from kg_stay s"), anyMap())).thenAnswer(c -> stay);
        when(db.list(contains("from kg_stay_diary d"), anyMap())).thenAnswer(c -> published);
    }

    @SuppressWarnings("unchecked") List<Map<String, Object>> rows(Map<String, Object> page)
    { return (List<Map<String, Object>>) page.get("rows"); }

    @Test void uploadedPhotosMergeWithPublishedDiaryWithoutLosingDatesOrRepeatingImages()
    {
        published.add(p("diaryId", 1L, "mediaType", "image", "mediaUrl", b, "caption", "早餐", "shotDate", "2026-10-24"));
        published.add(p("diaryId", 2L, "mediaType", "video", "mediaUrl", video, "coverUrl", a, "caption", "小院傍晚", "shotDate", "2026-10-23"));
        Map<String, Object> result = media.storeDiary(102L, Collections.singletonList("/profile/upload/job.jpg"), 1);
        List<Map<String, Object>> list = rows(result);
        assertEquals(3, list.size());
        assertEquals(a, list.get(0).get("url")); assertEquals("", list.get(0).get("date"));
        assertEquals("早餐", list.get(1).get("caption")); assertEquals("10.24", list.get(1).get("date"));
        assertEquals("video", list.get(2).get("type")); assertEquals(video, list.get(2).get("url"));
        Map<?, ?> header = (Map<?, ?>) result.get("header");
        assertEquals("海边日记", header.get("title")); assertEquals("平潭小院", header.get("storeName"));
        assertEquals(Arrays.asList("一日三餐", "日常相伴"), header.get("lines"));
        verify(db).one(contains("where s.store_id = :id"), eq(p("id", 102L)));
        verify(db).list(contains("d.del_flag = '0' and d.status = '0'"), eq(p("s", 9L)));
    }

    @Test void noStayStillShowsUploadedPhotosAndUsesCurrentStoreName()
    {
        stay = null;
        Map<String, Object> page = media.storeDiary(102L, Collections.emptyList(), 1);
        assertEquals(2, rows(page).size());
        assertTrue(((Map<?, ?>) page.get("header")).get("lines").toString().contains("平潭小院"));
        verify(db, never()).list(anyString(), anyMap());
        store.put("dailyImages", "");
        page = media.storeDiary(102L, Collections.singletonList(a), 1);
        assertEquals(a, rows(page).get(0).get("url"));
        assertEquals("", rows(page).get(0).get("date"));
        assertEquals(0, rows(media.storeDiary(102L, Collections.emptyList(), 1)).size());
    }

    @Test void paginationPreservesAllEntriesAndInvalidPagesAndDisabledStoresAreRejected()
    {
        store.put("dailyImages", "");
        for (int i = 1; i <= 25; i++) published.add(p("diaryId", (long) i, "mediaType", "image", "mediaUrl", "/profile/upload/" + i + ".jpg"));
        Map<String, Object> first = media.storeDiary(102L, Collections.emptyList(), 1);
        Map<String, Object> second = media.storeDiary(102L, Collections.emptyList(), 2);
        assertEquals(20, rows(first).size()); assertEquals(5, rows(second).size());
        assertEquals(true, first.get("hasMore")); assertEquals(false, second.get("hasMore"));
        assertEquals(21L, rows(second).get(0).get("diaryId"));
        assertThrows(ServiceException.class, () -> media.storeDiary(102L, Collections.emptyList(), 0));
        store = null;
        assertThrows(ServiceException.class, () -> media.storeDiary(102L, Collections.emptyList(), 1));
    }

    @Test void endpointUsesJobsOwnStoreAndRefusesClosedJobOrDisabledStore()
    {
        AppJobController controller = new AppJobController();
        IKgJobService jobs = mock(IKgJobService.class);
        IKgStoreService stores = mock(IKgStoreService.class);
        KgStayMediaService provider = mock(KgStayMediaService.class);
        ReflectionTestUtils.setField(controller, "kgJobService", jobs);
        ReflectionTestUtils.setField(controller, "kgStoreService", stores);
        ReflectionTestUtils.setField(controller, "stayMedia", provider);
        KgJob job = new KgJob(); job.setJobId(7L); job.setStoreId(102L); job.setStatus("published"); job.setCover(a);
        KgStore active = new KgStore(); active.setStatus("0");
        when(jobs.requireJob(7L)).thenReturn(job); when(stores.selectKgStoreByStoreId(102L)).thenReturn(active);
        controller.diary(7L, 1);
        verify(provider).storeDiary(102L, Collections.singletonList(a), 1);
        job.setStatus("closed"); assertThrows(ServiceException.class, () -> controller.diary(7L, 1));
        job.setStatus("published"); active.setStatus("1"); assertThrows(ServiceException.class, () -> controller.diary(7L, 1));
        verifyNoMoreInteractions(provider);
    }
}
