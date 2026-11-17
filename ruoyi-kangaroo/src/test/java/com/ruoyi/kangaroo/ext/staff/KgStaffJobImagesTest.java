package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.testsupport.UploadedImageFixture;

/**
 * 岗位照片（V49）：门店发布时带照片（最多 9 张，第一张做封面，不传照旧用门店封面）、已发岗位换照片（整组覆盖、清空退回门店封面、
 * 只改照片和封面、线上任务 422、已关闭 409、别家门店 403）、员工端列表每行带上当前照片（不连库）
 */
class KgStaffJobImagesTest
{
    private static final String A = "/profile/upload/2026/10/16/a1.jpg";
    private static final String B = "/profile/upload/2026/10/16/b2.jpg";
    private static final String STORE_COVER = "/profile/upload/2026/10/01/store.jpg";

    private final KgJdbc db = mock(KgJdbc.class);
    private final StaffSupport support = mock(StaffSupport.class);
    private final KgStaffJobService service = new KgStaffJobService();
    private final StaffSupport.Ctx ctx = new StaffSupport.Ctx();
    private final AtomicReference<Map<String, Object>> updated = new AtomicReference<>();
    private UploadedImageFixture imageFiles;
    @AfterEach void cleanUploads() { imageFiles.close(); }

    @BeforeEach
    void setup()
    {
        imageFiles = new UploadedImageFixture(A, B);
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "support", support);
        ctx.storeId = 107L; ctx.role = "manager"; ctx.me = new AppLoginUser(); ctx.me.setUserId(9001L); ctx.me.setNickname("店长");
        when(db.updateRow(eq("kg_job"), eq("job_id"), any(), anyMap())).thenAnswer(inv -> { updated.set(inv.getArgument(3)); return 1; });
        when(db.one(contains("select cover from kg_store"), anyMap())).thenReturn(p("cover", STORE_COVER));
        when(db.must(contains("from kg_store"), anyMap(), anyString())).thenReturn(p("storeName", "平潭袋鼠小院", "city", "平潭", "address", "东湖山庄98号",
                "cover", STORE_COVER, "latitude", null, "longitude", null));
        when(db.insert(eq("kg_job"), anyMap())).thenReturn(88L);
        job(107L, "volunteer", "published");
    }

    private void job(Long storeId, String type, String status)
    {
        when(db.must(contains("from kg_job"), anyMap(), anyString())).thenReturn(p("jobId", 15057L, "storeId", storeId, "title", "古城咖啡馆帮手",
                "jobType", type, "status", status));
    }

    private int code(Runnable r)
    {
        ServiceException e = assertThrows(ServiceException.class, r::run);
        return e.getCode() == null ? 500 : e.getCode();
    }

    // ---------------- 发布 ----------------

    private static Map<String, Object> publish(Object images)
    {
        Map<String, Object> in = p("jobType", "volunteer", "title", "海边青旅前台换工", "startDate", "2026-11-01", "hours", "每天 4 小时",
                "pay", "board", "headcount", 2, "work", "前台接待");
        if (images != null)
        {
            in.put("images", images);
        }
        return in;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> insertedRow()
    {
        ArgumentCaptor<Map<String, Object>> c = ArgumentCaptor.forClass(Map.class);
        verify(db).insert(eq("kg_job"), c.capture());
        return c.getValue();
    }

    @Test
    void publishWithPhotosUsesTheFirstAsCover()
    {
        service.create(ctx, publish(Arrays.asList(B, A, B)));
        Map<String, Object> row = insertedRow();
        assertEquals("[\"" + B + "\",\"" + A + "\"]", row.get("images"), "按选的顺序存，重复的只留一张");
        assertEquals(B, row.get("cover"));
    }

    @Test
    void publishWithoutPhotosIsRejectedEvenWithStoreCover()
    {
        for (String type : Arrays.asList("volunteer", "parttime", "fulltime")) {
            Map<String, Object> in = publish(null); in.put("jobType", type);
            assertEquals(422, code(() -> service.create(ctx, in)));
            in.put("images", Collections.emptyList());
            assertEquals(422, code(() -> service.create(ctx, in)));
        }
        verify(db, never()).insert(eq("kg_job"), anyMap());
    }

    @Test
    void createReplaceAndBatchResumeRejectMissingOrFakePhotosBeforeWriting() throws java.io.IOException
    {
        String fake = "/profile/upload/2026/10/27/renamed.jpg";
        imageFiles.write(fake, "not a picture".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (String photo : Arrays.asList("/profile/upload/missing.jpg", "/profile/upload/video.mp4", fake)) {
            assertEquals(422, code(() -> service.create(ctx, publish(Collections.singletonList(photo)))));
            assertEquals(422, code(() -> service.images(ctx, 15057L, p("images", Collections.singletonList(photo)))));
            when(db.list(contains("for update"), anyMap())).thenReturn(Arrays.asList(
                    p("jobId", 1L, "jobType", "parttime", "status", "paused", "images", A),
                    p("jobId", 2L, "jobType", "fulltime", "status", "paused", "images", photo)));
            assertEquals(422, code(() -> service.batch(ctx, Arrays.asList(1L, 2L), "resume")));
        }
        verify(db, never()).insert(eq("kg_job"), anyMap());
        verify(db, never()).updateRow(anyString(), anyString(), any(), anyMap());
        verify(db, never()).update(anyString(), anyMap());
    }

    @Test
    void publishRejectsForeignOrTooManyPhotosBeforeSaving()
    {
        assertEquals(422, code(() -> service.create(ctx, publish(Arrays.asList("https://example.com/x.jpg")))));
        List<String> ten = new ArrayList<String>();
        for (int i = 0; i < 10; i++)
        {
            ten.add("/profile/upload/2026/10/16/p" + i + ".jpg");
        }
        assertEquals(422, code(() -> service.create(ctx, publish(ten))));
        verify(db, never()).insert(eq("kg_job"), anyMap());
    }

    // ---------------- 换照片 ----------------

    @Test
    @SuppressWarnings("unchecked")
    void replacingPhotosWritesOnlyPhotosAndCover()
    {
        Map<String, Object> out = service.images(ctx, 15057L, p("images", Arrays.asList(A, B)));
        Map<String, Object> cols = updated.get();
        assertEquals(Arrays.asList("images", "cover", "update_by", "update_time"), new ArrayList<>(cols.keySet()), "不动状态（不用重新审核）");
        assertEquals("[\"" + A + "\",\"" + B + "\"]", cols.get("images"));
        assertEquals(A, cols.get("cover"));
        assertEquals("staff:9001", cols.get("update_by"));
        assertEquals(Arrays.asList(A, B), out.get("images"));
        assertEquals(A, out.get("cover"));
        verify(db).must(contains("for update"), anyMap(), anyString());
        verify(support).op(same(ctx), eq("job_images"), eq("更换岗位照片"), eq("general"), eq("古城咖啡馆帮手 → 2 张照片"), eq(true), isNull());
        // 调换顺序：第一张跟着变
        service.images(ctx, 15057L, p("images", Arrays.asList(B, A)));
        assertEquals(B, updated.get().get("cover"));
    }

    @Test
    void clearingAllPhotosIsRejected()
    {
        assertEquals(422, code(() -> service.images(ctx, 15057L, p("images", Collections.emptyList()))));
        assertNull(updated.get());
        verify(db, never()).updateRow(anyString(), anyString(), any(), anyMap());
    }

    @Test
    void bulkResumeChecksEveryJobBeforeWriting()
    {
        when(db.list(contains("for update"), anyMap())).thenReturn(Arrays.asList(
                p("jobId", 1L, "jobType", "parttime", "status", "paused", "images", A),
                p("jobId", 2L, "jobType", "fulltime", "status", "paused", "images", "")));
        assertEquals(422, code(() -> service.batch(ctx, Arrays.asList(1L, 2L), "resume")));
        verify(db, never()).update(anyString(), anyMap());
        when(db.list(contains("for update"), anyMap())).thenReturn(Collections.singletonList(
                p("jobId", 1L, "jobType", "parttime", "status", "paused", "images", A)));
        when(db.update(anyString(), anyMap())).thenReturn(1);
        assertEquals(1, service.batch(ctx, Collections.singletonList(1L), "resume"));
    }

    @Test
    void invalidRequestsAreRejectedAndNothingIsSaved()
    {
        assertEquals(422, code(() -> service.images(ctx, 15057L, p("images", Arrays.asList("/static/media/cover-empty.png")))));
        assertEquals(422, code(() -> service.images(ctx, 15057L, p())), "没带照片");
        assertEquals(422, code(() -> service.images(ctx, 15057L, null)));
        job(107L, "online", "published");
        ServiceException online = assertThrows(ServiceException.class, () -> service.images(ctx, 15057L, p("images", Arrays.asList(A))));
        assertEquals(Integer.valueOf(422), online.getCode());
        assertEquals("线上任务没有岗位照片", online.getMessage());
        job(107L, "volunteer", "closed");
        assertEquals(409, code(() -> service.images(ctx, 15057L, p("images", Arrays.asList(A)))), "已关闭，和改工作地点一致");
        job(200L, "volunteer", "published");
        assertEquals(403, code(() -> service.images(ctx, 15057L, p("images", Arrays.asList(A)))), "别家门店的岗位");
        verify(db, never()).updateRow(anyString(), anyString(), any(), anyMap());
        verify(support, never()).op(any(), anyString(), anyString(), anyString(), anyString(), anyBoolean(), any());
    }

    // ---------------- 员工端列表 ----------------

    @Test
    void staffListRowsCarryTheirPhotos()
    {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(p("jobId", 1L, "jobType", "volunteer", "status", "published", "boardType", "all", "genderPref", "", "restMode", "5-2", "roomType", "single",
                "minDays", null, "cover", A, "images", "[\"" + A + "\",\"" + B + "\"]", "address", ""));
        rows.add(p("jobId", 2L, "jobType", "parttime", "status", "published", "boardType", "none", "genderPref", "", "restMode", "5-2", "roomType", "",
                "minDays", null, "cover", "", "images", "", "address", ""));
        when(db.list(anyString(), anyMap())).thenReturn(rows);
        when(db.one(contains("count(*) total"), anyMap())).thenReturn(p("total", 2));
        service.list(ctx, null, null, null);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db).list(sql.capture(), anyMap());
        assertTrue(sql.getValue().contains("j.images, "), sql.getValue());
        assertEquals(Arrays.asList(A, B), rows.get(0).get("images"));
        assertEquals(A, rows.get(0).get("img"));
        assertEquals(Collections.emptyList(), rows.get(1).get("images"));
        assertTrue(String.valueOf(rows.get(1).get("img")).startsWith("/static/media/rooms-cover-"));
    }
}
