package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgCommunityGuard;
import com.ruoyi.kangaroo.ext.core.KgReviewStats;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;

/**
 * 写评价附图（2026-10-17 改版 R2，住宿评价页先用）：只收平台上传的 /profile/upload/… 路径、最多 9 张、拼起来不超过 images 列宽；
 * 不带 images 的旧版小程序照旧能提交，images 列空着。
 */
class KgUserHubReviewImagesTest
{
    static final String A = "/profile/upload/2026/10/17/a1b2c3d4e5f6.jpg";
    static final String B = "/profile/upload/2026/10/17/b1b2c3d4e5f6.png";

    @Test
    void acceptsPlatformUploadsDedupesAndJoins()
    {
        assertNull(KgUserHubService.reviewImages(null), "旧版小程序不带这个字段");
        assertNull(KgUserHubService.reviewImages(new ArrayList<Object>()));
        assertNull(KgUserHubService.reviewImages(Arrays.asList("", " ")));
        assertEquals(A + "," + B, KgUserHubService.reviewImages(Arrays.asList(A, " " + B + " ", A)));
        assertEquals(A + "," + B, KgUserHubService.reviewImages(A + "," + B));
    }

    private static void rejects(Object raw, String msg)
    {
        ServiceException e = assertThrows(ServiceException.class, () -> KgUserHubService.reviewImages(raw));
        assertEquals(Integer.valueOf(422), e.getCode());
        assertTrue(e.getMessage().contains(msg), e.getMessage());
    }

    @Test
    void rejectsForeignUrlsTooManyAndTooLong()
    {
        rejects(Arrays.asList(A, "https://evil.example.com/x.jpg"), "只能用本平台上传的图片");
        rejects(Arrays.asList("/profile/upload/../../etc/passwd"), "只能用本平台上传的图片");
        rejects(Arrays.asList("/static/media/cover-empty.png"), "只能用本平台上传的图片");
        rejects(Arrays.asList("/profile/upload/中文名.jpg"), "只能用本平台上传的图片");
        rejects(p("x", A), "格式不对");
        List<Object> ten = new ArrayList<Object>();
        for (int i = 0; i < 10; i++)
        {
            ten.add("/profile/upload/2026/10/17/p" + i + ".jpg");
        }
        rejects(ten, "最多附 9 张");
        List<Object> longOnes = new ArrayList<Object>();
        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < 120; i++)
        {
            pad.append('x');
        }
        for (int i = 0; i < 9; i++)
        {
            longOnes.add("/profile/upload/2026/10/17/" + i + pad + ".jpg");
        }
        rejects(longOnes, "图片太多了");
    }

    private KgUserHubService hub(KgJdbc db)
    {
        KgUserHubService s = new KgUserHubService();
        // 敏感词检查的 mock 返回 null，等同「通过」（不是 "review"），评价直接显示
        KgCommunityGuard guard = mock(KgCommunityGuard.class);
        ReflectionTestUtils.setField(s, "db", db);
        ReflectionTestUtils.setField(s, "guard", guard);
        ReflectionTestUtils.setField(s, "reviewStats", mock(KgReviewStats.class));
        ReflectionTestUtils.setField(s, "support", mock(StaffSupport.class));
        return s;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> inserted(Object images)
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.one(contains("from kg_stay_order o where o.order_id = :id"), anyMap())).thenReturn(p("storeId", 3L, "targetRef", 7L));
        when(db.insert(eq("kg_review"), anyMap())).thenReturn(55L);
        Map<String, Object> body = p("bizType", "stay_order", "bizId", 9L, "dims", Arrays.asList(5, 4, 5, 4, 5), "content", "【卫生】很干净", "anonymous", true);
        if (images != null)
        {
            body.put("images", images);
        }
        hub(db).writeReview(2L, body);
        ArgumentCaptor<Map<String, Object>> row = ArgumentCaptor.forClass(Map.class);
        verify(db).insert(eq("kg_review"), row.capture());
        return row.getValue();
    }

    @Test
    void stayReviewStoresImagesCommaJoined()
    {
        Map<String, Object> row = inserted(Arrays.asList(A, B));
        assertEquals(A + "," + B, row.get("images"));
        assertEquals("room", row.get("target_type"));
        assertEquals(7L, row.get("target_id"));
        assertEquals("1", row.get("anonymous"));
    }

    @Test
    void oldClientsWithoutImagesStillWork()
    {
        Map<String, Object> row = inserted(null);
        assertFalse(row.containsKey("images"), "没带图片就不写 images 列（保持 NULL）");
        assertEquals(5, row.get("rating"));
    }

    @Test
    void badImagesFailBeforeAnythingIsSaved()
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.one(anyString(), anyMap())).thenReturn(p("storeId", 3L, "targetRef", 7L));
        Map<String, Object> body = p("bizType", "stay_order", "bizId", 9L, "dims", Arrays.asList(5, 5, 5, 5, 5), "content", "好",
                "images", Arrays.asList("https://evil.example.com/x.jpg"));
        ServiceException e = assertThrows(ServiceException.class, () -> hub(db).writeReview(2L, body));
        assertEquals(Integer.valueOf(422), e.getCode());
        verify(db, never()).insert(anyString(), anyMap());
    }
}
