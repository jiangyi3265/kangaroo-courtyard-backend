package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgCommunityGuard;
import com.ruoyi.kangaroo.ext.core.KgReviewStats;
import com.ruoyi.kangaroo.ext.core.KgVideoUploads;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;

/**
 * 写评价附视频（2026-10-17 改版 R3，住宿评价页）：只收本平台视频上传接口给的路径、文件要在、最多 1 段；封面只收平台图片；
 * 不带 videos 的旧版小程序照旧能提交，两列空着；视频路径不能混进图片里。
 */
class KgUserHubReviewVideosTest
{
    static final String V = "/profile/upload/videos/2026/10/17/0123456789abcdef0123456789abcdef.mp4";
    static final String V2 = "/profile/upload/videos/2026/10/17/fedcba9876543210fedcba9876543210.mov";
    static final String C = "/profile/upload/2026/10/17/c1c2c3c4.jpg";

    private final KgVideoUploads uploads = mock(KgVideoUploads.class);

    private KgUserHubService hub(KgJdbc db)
    {
        KgUserHubService s = new KgUserHubService();
        ReflectionTestUtils.setField(s, "db", db);
        ReflectionTestUtils.setField(s, "guard", mock(KgCommunityGuard.class));
        ReflectionTestUtils.setField(s, "reviewStats", mock(KgReviewStats.class));
        ReflectionTestUtils.setField(s, "support", mock(StaffSupport.class));
        ReflectionTestUtils.setField(s, "videoUploads", uploads);
        when(uploads.exists(anyString())).thenReturn(true);
        return s;
    }

    private void rejects(Object raw, String msg)
    {
        KgUserHubService s = hub(mock(KgJdbc.class));
        ServiceException e = assertThrows(ServiceException.class, () -> s.reviewVideos(raw));
        assertEquals(Integer.valueOf(422), e.getCode());
        assertTrue(e.getMessage().contains(msg), e.getMessage());
    }

    @Test
    void acceptsOnePlatformVideoWithOrWithoutCover()
    {
        KgUserHubService s = hub(mock(KgJdbc.class));
        assertNull(s.reviewVideos(null), "旧版小程序不带这个字段");
        assertNull(s.reviewVideos(new ArrayList<Object>()));
        assertNull(s.reviewVideos(Arrays.asList("", " ")));
        assertArrayEquals(new String[] { V, "" }, s.reviewVideos(Arrays.asList(" " + V + " ")));
        assertArrayEquals(new String[] { V, C }, s.reviewVideos(Arrays.asList(p("url", V, "cover", C))));
        assertArrayEquals(new String[] { V, "" }, s.reviewVideos(Arrays.asList(V, V)), "重复的只留一段");
        assertArrayEquals(new String[] { V, "" }, s.reviewVideos(V));
    }

    @Test
    void rejectsForeignMissingTooManyAndBadCovers()
    {
        rejects(Arrays.asList("https://evil.example.com/a.mp4"), "只能用本平台上传的视频");
        rejects(Arrays.asList(C), "只能用本平台上传的视频");
        rejects(Arrays.asList(V, V2), "最多附 1 段视频");
        rejects(Arrays.asList(p("url", V, "cover", "https://evil.example.com/a.jpg")), "视频封面只能用本平台上传的图片");
        rejects(Arrays.asList(p("url", V, "cover", V2)), "视频封面只能用本平台上传的图片");
        rejects(p("x", V), "格式不对");
        KgUserHubService s = hub(mock(KgJdbc.class));
        when(uploads.exists(V)).thenReturn(false);
        ServiceException e = assertThrows(ServiceException.class, () -> s.reviewVideos(Arrays.asList(V)));
        assertTrue(e.getMessage().contains("只能用本平台上传的视频"), "文件不在（编的路径）也不收");
        // 视频路径不能当图片交
        ServiceException img = assertThrows(ServiceException.class, () -> KgUserHubService.reviewImages(Arrays.asList(V)));
        assertTrue(img.getMessage().contains("只能用本平台上传的图片"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> inserted(Object videos)
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.one(contains("from kg_stay_order o where o.order_id = :id"), anyMap())).thenReturn(p("storeId", 3L, "targetRef", 7L));
        when(db.insert(eq("kg_review"), anyMap())).thenReturn(55L);
        Map<String, Object> body = p("bizType", "stay_order", "bizId", 9L, "dims", Arrays.asList(5, 4, 5, 4, 5), "content", "【卫生】很干净", "anonymous", true);
        if (videos != null)
        {
            body.put("videos", videos);
        }
        hub(db).writeReview(2L, body);
        ArgumentCaptor<Map<String, Object>> row = ArgumentCaptor.forClass(Map.class);
        verify(db).insert(eq("kg_review"), row.capture());
        return row.getValue();
    }

    @Test
    void stayReviewStoresTheVideoAndItsCover()
    {
        Map<String, Object> row = inserted(Arrays.asList(p("url", V, "cover", C)));
        assertEquals(V, row.get("videos"));
        assertEquals(C, row.get("video_covers"));
        Map<String, Object> old = inserted(null);
        assertFalse(old.containsKey("videos") || old.containsKey("video_covers"), "没带视频就不写这两列（保持 NULL）");
    }

    @Test
    void badVideoFailsBeforeAnythingIsSaved()
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.one(anyString(), anyMap())).thenReturn(p("storeId", 3L, "targetRef", 7L));
        Map<String, Object> body = p("bizType", "stay_order", "bizId", 9L, "dims", Arrays.asList(5, 5, 5, 5, 5), "content", "好",
                "videos", Arrays.asList("https://evil.example.com/a.mp4"));
        assertThrows(ServiceException.class, () -> hub(db).writeReview(2L, body));
        verify(db, never()).insert(anyString(), anyMap());
    }
}
