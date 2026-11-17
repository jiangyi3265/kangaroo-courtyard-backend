package com.ruoyi.kangaroo.ext.stay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 2026-10-17 R3：评价视频（kg_review.videos / video_covers）进「住过的家人评价」列表，「有图·视频」页签也算只带视频的评价（不连库） */
class KgStayReviewVideosTest
{
    static final String V = "/profile/upload/videos/2026/10/17/0123456789abcdef0123456789abcdef.mp4";
    static final String V2 = "/profile/upload/videos/2026/10/17/fedcba9876543210fedcba9876543210.mov";
    static final String C = "/profile/upload/2026/10/17/c1c2c3c4.jpg";

    private static Map<String, Object> row(Object... kv)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2)
        {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    @Test
    void videosPairUpWithCoversAndDropForeignUrls()
    {
        List<Map<String, Object>> v = KgStayReviewService.videos(V + "," + V2, C + ",");
        assertEquals(2, v.size());
        assertEquals(V, v.get(0).get("url"));
        assertEquals(C, v.get(0).get("cover"));
        assertEquals("", v.get(1).get("cover"), "没封面的给空串，页面画占位底色");
        assertEquals(Collections.emptyList(), KgStayReviewService.videos(null, null));
        assertEquals(Collections.emptyList(), KgStayReviewService.videos("https://evil.example.com/a.mp4", C), "手改库塞进来的外链不往页面上放");
        assertEquals("", KgStayReviewService.videos(V, "https://evil.example.com/c.jpg").get(0).get("cover"));
        assertEquals("", KgStayReviewService.videos(V, V2).get(0).get("cover"), "封面不能是视频");
        assertEquals(1, KgStayReviewService.videos(V + "," + V, "").size());
        // 视频路径不会被当成图片画出来
        assertEquals(Collections.singletonList(C), KgStayReviewService.images(C + "," + V));
    }

    @Test
    void videoOnlyReviewsCountAsMediaAndCardsCarryVideos()
    {
        Map<String, Object> withVideo = row("reviewId", 1L, "rating", 5, "content", "好", "videos", V, "videoCovers", C, "anonymous", "1", "createTime", "2026-10-17 08:00:00");
        Map<String, Object> plain = row("reviewId", 2L, "rating", 2, "content", "一般", "anonymous", "1", "createTime", "2026-10-16 08:00:00");
        Map<String, Object> photo = row("reviewId", 3L, "rating", 4, "content", "不错", "images", C, "anonymous", "1", "createTime", "2026-10-15 08:00:00");
        assertTrue(KgStayReviewService.hasMedia(withVideo));
        assertTrue(KgStayReviewService.hasMedia(photo));
        assertFalse(KgStayReviewService.hasMedia(plain));
        List<Map<String, Object>> rows = Arrays.asList(withVideo, plain, photo);
        @SuppressWarnings("unchecked")
        Map<String, Object> tabs = (Map<String, Object>) KgStayReviewService.summarize(rows).get("tabs");
        assertEquals(2L, tabs.get("media"));
        List<Map<String, Object>> media = KgStayReviewService.filter(rows, "media", null, null, null);
        assertEquals(Arrays.asList(1L, 3L), Arrays.asList(media.get(0).get("reviewId"), media.get(1).get("reviewId")));
        Map<String, Object> card = KgStayReviewService.card(withVideo);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> vids = (List<Map<String, Object>>) card.get("videos");
        assertEquals(1, vids.size());
        assertEquals(V, vids.get(0).get("url"));
        assertEquals(C, vids.get(0).get("cover"));
        assertEquals(Collections.emptyList(), KgStayReviewService.card(plain).get("videos"));
    }
}
