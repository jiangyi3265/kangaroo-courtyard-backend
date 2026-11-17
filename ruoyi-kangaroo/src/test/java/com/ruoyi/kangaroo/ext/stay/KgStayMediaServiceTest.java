package com.ruoyi.kangaroo.ext.stay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
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

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.core.KgVideoUploads;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.StaffGrantView;

/**
 * 2026-10-17 R3（V53）：员工端小院视频 / 房型视频 / 生活碎片的增删改排、上线中也能改、操作日志；游客端生活碎片页只给显示中的；详情页的视频和摘要（不连库）
 */
class KgStayMediaServiceTest
{
    static final String V = "/profile/upload/videos/2026/10/17/0123456789abcdef0123456789abcdef.mp4";
    static final String V2 = "/profile/upload/videos/2026/10/17/fedcba9876543210fedcba9876543210.mp4";
    static final String IMG = "/profile/upload/2026/10/17/abcdef0123456789.jpg";

    private final KgJdbc db = mock(KgJdbc.class);
    private final StaffSupport support = mock(StaffSupport.class);
    private final KgVideoUploads videos = mock(KgVideoUploads.class);
    private final KgStayMediaService media = new KgStayMediaService();
    private final StaffSupport.Ctx ctx = new StaffSupport.Ctx();

    private static Map<String, Object> row(Object... kv)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2)
        {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(media, "db", db);
        ReflectionTestUtils.setField(media, "support", support);
        ReflectionTestUtils.setField(media, "videos", videos);
        when(videos.exists(anyString())).thenReturn(true);
        ctx.storeId = 102L; ctx.storeName = "测试门店"; ctx.role = "manager";
        StaffGrantView g = new StaffGrantView();
        g.setStoreId(102L); g.setRoleCode("manager"); g.setActive(true);
        ctx.me = new AppLoginUser(); ctx.me.setUserId(1L); ctx.me.setStaffGrants(Collections.singletonList(g));
        // 上线中的房源（主人拍板：视频和生活碎片不用下架就能改）
        Map<String, Object> stay = row("stayId", 9L, "storeId", 102L, "stayName", "测试小院", "status", "online", "cover", IMG,
                "introVideo", "", "introVideoCover", "", "diaryBg", "", "diaryTitle", "", "diaryIntro", "");
        when(db.one(contains("select * from kg_stay where stay_id = :id"), anyMap())).thenReturn(stay);
        when(db.must(contains("select * from kg_stay where stay_id = :id"), anyMap(), anyString())).thenReturn(stay);
        when(db.list(anyString(), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
    }

    private static void rejects(Runnable r, String msg)
    {
        ServiceException e = assertThrows(ServiceException.class, r::run);
        assertTrue(e.getMessage().contains(msg), e.getMessage());
    }

    // ---------------------------------------------------------------- 校验

    @Test
    void pureValidators()
    {
        assertEquals("", KgStayMediaService.image(" ", "封面"));
        assertEquals(IMG, KgStayMediaService.image(" " + IMG + " ", "封面"));
        rejects(() -> KgStayMediaService.image("https://evil.example.com/a.jpg", "封面"), "封面只能用本平台上传的图片");
        rejects(() -> KgStayMediaService.image(V, "封面"), "只能用本平台上传的图片");
        assertEquals("火烧云 晚霞", KgStayMediaService.oneLine(" 火烧云\n晚霞 ", 32, "一句话"));
        rejects(() -> KgStayMediaService.oneLine(new String(new char[33]).replace('\0', '字'), 32, "一句话"), "一句话最多 32 个字");
        assertEquals("花开有信，晚风有约\n路过的人，都会留下一点故事", KgStayMediaService.introLines(" 花开有信，晚风有约 \r\n\n 路过的人，都会留下一点故事\n"));
        rejects(() -> KgStayMediaService.introLines("一\n二\n三\n四"), "最多 3 行");
        rejects(() -> KgStayMediaService.introLines(new String(new char[256]).replace('\0', '字')), "最多 255 个字");
        assertEquals(LocalDate.now().toString(), KgStayMediaService.shotDate(""));
        assertEquals("2026-10-12", KgStayMediaService.shotDate("2026-10-12"));
        rejects(() -> KgStayMediaService.shotDate("2026-02-30"), "格式不对");
        rejects(() -> KgStayMediaService.shotDate(LocalDate.now().plusDays(3).toString()), "今天或以前");
        assertEquals("10.12", KgStayMediaService.mmdd("2026-10-12"));
        assertEquals(Collections.singletonList("这里是测试小院的真实日常"), KgStayMediaService.lines("", "测试小院"));
        assertEquals(Arrays.asList("花开有信", "晚风有约"), KgStayMediaService.lines("花开有信\n\n晚风有约", "x"));
    }

    @Test
    void videosMustBePlatformUploadsThatExist()
    {
        assertEquals(V, media.video(V));
        assertEquals("", media.video(""));
        rejects(() -> media.video("https://evil.example.com/a.mp4"), "视频地址不对");
        rejects(() -> media.video(IMG), "视频地址不对");
        when(videos.exists(V2)).thenReturn(false);
        rejects(() -> media.video(V2), "视频地址不对");
    }

    // ---------------------------------------------------------------- 员工端

    @Test
    void introVideoCanBeSetOnAnOnlineStayAndIsLogged()
    {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("url", V);
        body.put("cover", IMG);
        media.setIntroVideo(ctx, 9L, body);
        verify(db).update(contains("update kg_stay set intro_video = :v, intro_video_cover = :c"), argThat(m -> V.equals(m.get("v")) && IMG.equals(m.get("c"))
                && Long.valueOf(9L).equals(m.get("s"))));
        verify(db, never()).update(contains("status"), anyMap());
        verify(support).op(eq(ctx), eq("stay_media"), eq("更新小院视频"), eq("general"), eq("测试小院"), eq(true), isNull());
        // 删掉：封面一起清
        Map<String, Object> clear = new HashMap<String, Object>();
        clear.put("url", "");
        clear.put("cover", IMG);
        media.setIntroVideo(ctx, 9L, clear);
        verify(db).update(contains("intro_video = :v"), argThat(m -> "".equals(m.get("v")) && "".equals(m.get("c"))));
        verify(support).op(eq(ctx), eq("stay_media"), eq("删除小院视频"), anyString(), anyString(), eq(true), isNull());
    }

    @Test
    void otherStoresStaysAreForbidden()
    {
        when(db.one(contains("select * from kg_stay where stay_id = :id"), anyMap())).thenReturn(row("stayId", 9L, "storeId", 999L, "status", "online"));
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("url", V);
        ServiceException e = assertThrows(ServiceException.class, () -> media.setIntroVideo(ctx, 9L, body));
        assertEquals(Integer.valueOf(403), e.getCode());
        verify(db, never()).update(anyString(), anyMap());
    }

    @Test
    void roomVideoChecksTheRoomBelongsToTheStore()
    {
        when(db.one(contains("from kg_stay_room_type where room_type_id = :r"), anyMap())).thenReturn(row("roomTypeId", 5L, "stayId", 9L, "typeName", "单人间"));
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("url", V);
        media.setRoomVideo(ctx, 5L, body);
        verify(db).update(contains("update kg_stay_room_type set video_url = :v, video_cover = :c"), argThat(m -> V.equals(m.get("v")) && "".equals(m.get("c"))));
        verify(support).op(eq(ctx), eq("stay_media"), eq("更新房型视频"), anyString(), eq("测试小院 · 单人间"), eq(true), isNull());
        when(db.one(contains("from kg_stay_room_type where room_type_id = :r"), anyMap())).thenReturn(null);
        rejects(() -> media.setRoomVideo(ctx, 6L, body), "房型不存在");
    }

    @Test
    @SuppressWarnings("unchecked")
    void diaryHeaderUpdatesOnlyWhatWasSent()
    {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("title", " 小院\n日记 ");
        body.put("intro", "花开有信，晚风有约\n这里是丽江袋鼠小院的真实日常");
        media.setDiaryHeader(ctx, 9L, body);
        ArgumentCaptor<Map<String, Object>> cols = ArgumentCaptor.forClass(Map.class);
        verify(db).updateRow(eq("kg_stay"), eq("stay_id"), eq(9L), cols.capture());
        assertEquals("小院 日记", cols.getValue().get("diary_title"));
        assertEquals("花开有信，晚风有约\n这里是丽江袋鼠小院的真实日常", cols.getValue().get("diary_intro"));
        assertFalse(cols.getValue().containsKey("diary_bg"), "没带背景图就不动它");
        Map<String, Object> bad = new HashMap<String, Object>();
        bad.put("bg", "https://evil.example.com/a.jpg");
        rejects(() -> media.setDiaryHeader(ctx, 9L, bad), "背景图只能用本平台上传的图片");
    }

    @Test
    @SuppressWarnings("unchecked")
    void diarySubtitleIsEditableAndPreservesOtherFields()
    {
        media.setDiaryHeader(ctx, 9L, row("subtitle", " 一起\n记录旅居日常 "));
        ArgumentCaptor<Map<String, Object>> cols = ArgumentCaptor.forClass(Map.class);
        verify(db).updateRow(eq("kg_stay"), eq("stay_id"), eq(9L), cols.capture());
        assertEquals("一起 记录旅居日常", cols.getValue().get("diary_subtitle"));
        assertFalse(cols.getValue().containsKey("diary_title"));
        assertFalse(cols.getValue().containsKey("diary_intro"));
        assertFalse(cols.getValue().containsKey("diary_bg"));
        rejects(() -> media.setDiaryHeader(ctx, 9L, row("subtitle", String.join("", Collections.nCopies(81, "字")))), "副标题最多 80 个字");
        clearInvocations(db);
        media.setDiaryHeader(ctx, 9L, row("subtitle", ""));
        verify(db).updateRow(eq("kg_stay"), eq("stay_id"), eq(9L), cols.capture());
        assertEquals("", cols.getValue().get("diary_subtitle"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publicDiaryReadsAllHeaderCopyFromCurrentStay()
    {
        when(db.one(contains("s.diary_subtitle"), anyMap())).thenReturn(row("stayId", 9L, "stayName", "测试小院", "cover", IMG,
                "diaryBg", "/profile/upload/custom-bg.jpg", "diaryTitle", "门店日记", "diarySubtitle", "主理人写的副标题", "diaryIntro", "第一行\n第二行"));
        when(db.page(anyString(), anyMap(), anyInt(), anyInt())).thenReturn(row("rows", Collections.emptyList(), "total", 0L, "hasMore", false));
        Map<String, Object> header = (Map<String, Object>) media.diary(9L, 1).get("header");
        assertEquals("/profile/upload/custom-bg.jpg", header.get("bg"));
        assertEquals("门店日记", header.get("title"));
        assertEquals("主理人写的副标题", header.get("subtitle"));
        assertEquals(Arrays.asList("第一行", "第二行"), header.get("lines"));
        verify(db).one(contains("s.diary_subtitle"), eq(row("id", 9L)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void addDiaryValidatesAndInsertsAVisibleRow()
    {
        when(db.count(contains("from kg_stay_diary where stay_id = :s and del_flag = '0'"), anyMap())).thenReturn(3L);
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("mediaType", "video");
        body.put("url", V);
        body.put("cover", IMG);
        body.put("caption", "火烧云");
        body.put("shotDate", "2026-10-12");
        media.addDiary(ctx, 9L, body);
        ArgumentCaptor<Map<String, Object>> row = ArgumentCaptor.forClass(Map.class);
        verify(db).insert(eq("kg_stay_diary"), row.capture());
        Map<String, Object> r = row.getValue();
        assertEquals(9L, r.get("stay_id"));
        assertEquals(102L, r.get("store_id"));
        assertEquals("video", r.get("media_type"));
        assertEquals(V, r.get("media_url"));
        assertEquals(IMG, r.get("cover_url"));
        assertEquals("火烧云", r.get("caption"));
        assertEquals("2026-10-12", r.get("shot_date"));
        assertEquals("0", r.get("status"));
        assertEquals("0", r.get("del_flag"));
        verify(support).op(eq(ctx), eq("stay_diary"), eq("添加生活碎片"), anyString(), eq("测试小院 · 火烧云"), eq(true), isNull());
        // 照片：url 必须是图片；类型不认识；空 url；视频 url 当照片
        Map<String, Object> photo = new HashMap<String, Object>();
        photo.put("mediaType", "image");
        photo.put("url", V);
        rejects(() -> media.addDiary(ctx, 9L, photo), "照片只能用本平台上传的图片");
        photo.put("url", "");
        rejects(() -> media.addDiary(ctx, 9L, photo), "请先上传照片");
        photo.put("mediaType", "gif");
        rejects(() -> media.addDiary(ctx, 9L, photo), "请选择照片或视频");
        // 满 200 条
        when(db.count(contains("from kg_stay_diary where stay_id = :s and del_flag = '0'"), anyMap())).thenReturn(200L);
        rejects(() -> media.addDiary(ctx, 9L, body), "最多 200 条");
        verify(db, times(1)).insert(eq("kg_stay_diary"), anyMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    void editAndDeleteDiaryOnlyTheirOwnRows()
    {
        when(db.one(contains("from kg_stay_diary where diary_id = :id and del_flag = '0'"), anyMap()))
                .thenReturn(row("diaryId", 3L, "stayId", 9L, "storeId", 102L, "mediaType", "image", "caption", "早餐"));
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("caption", "露台");
        body.put("shotDate", "2026-10-11");
        body.put("cover", "https://x/y.jpg");
        media.editDiary(ctx, 3L, body);
        ArgumentCaptor<Map<String, Object>> cols = ArgumentCaptor.forClass(Map.class);
        verify(db).updateRow(eq("kg_stay_diary"), eq("diary_id"), eq(3L), cols.capture());
        assertEquals("露台", cols.getValue().get("caption"));
        assertEquals("2026-10-11", cols.getValue().get("shot_date"));
        assertFalse(cols.getValue().containsKey("cover_url"), "照片没有封面，cover 忽略");
        media.deleteDiary(ctx, 3L);
        verify(db).update(contains("update kg_stay_diary set del_flag = '2'"), argThat(m -> Long.valueOf(3L).equals(m.get("id"))));
        verify(support).op(eq(ctx), eq("stay_diary"), eq("删除生活碎片"), anyString(), eq("3 · 早餐"), eq(true), isNull());
        when(db.one(contains("from kg_stay_diary where diary_id = :id"), anyMap())).thenReturn(null);
        rejects(() -> media.deleteDiary(ctx, 4L), "不存在或已删除");
    }

    @Test
    void orderDiaryPutsTheGivenIdsFirstAndKeepsTheRest()
    {
        when(db.list(contains("select d.diary_id from kg_stay_diary d where d.stay_id = :s and d.del_flag = '0'"), anyMap()))
                .thenReturn(Arrays.asList(row("diaryId", 1L), row("diaryId", 2L), row("diaryId", 3L)));
        media.orderDiary(ctx, 9L, Arrays.asList(3, 1));
        verify(db).update(contains("update kg_stay_diary set sort = :n"), argThat(m -> Integer.valueOf(1).equals(m.get("n")) && Long.valueOf(3L).equals(m.get("id"))));
        verify(db).update(contains("update kg_stay_diary set sort = :n"), argThat(m -> Integer.valueOf(2).equals(m.get("n")) && Long.valueOf(1L).equals(m.get("id"))));
        verify(db).update(contains("update kg_stay_diary set sort = :n"), argThat(m -> Integer.valueOf(3).equals(m.get("n")) && Long.valueOf(2L).equals(m.get("id"))));
        rejects(() -> media.orderDiary(ctx, 9L, Arrays.asList(3, 3)), "排序参数不对");
        rejects(() -> media.orderDiary(ctx, 9L, Arrays.asList(4)), "已经被删了");
        rejects(() -> media.orderDiary(ctx, 9L, "1,2"), "排序参数不对");
    }

    @Test
    void staffScreenShowsHiddenEntriesWithTheirFlag()
    {
        when(db.list(contains("select d.* from kg_stay_diary d where d.stay_id = :s and d.del_flag = '0'"), anyMap())).thenReturn(Arrays.asList(
                row("diaryId", 1L, "mediaType", "video", "mediaUrl", V, "coverUrl", IMG, "caption", "星空", "shotDate", "2026-10-12", "status", "1", "sort", 0L)));
        when(db.list(contains("from kg_stay_room_type"), anyMap())).thenReturn(Arrays.asList(row("roomTypeId", 5L, "typeName", "单人间", "cover", "", "videoUrl", V, "videoCover", "", "status", "0")));
        Map<String, Object> out = media.media(ctx, 9L);
        @SuppressWarnings("unchecked")
        Map<String, Object> e = ((List<Map<String, Object>>) out.get("entries")).get(0);
        assertEquals(Boolean.TRUE, e.get("hidden"));
        assertEquals("video", e.get("type"));
        assertEquals(IMG, e.get("cover"));
        assertEquals("10.12", e.get("date"));
        @SuppressWarnings("unchecked")
        Map<String, Object> rt = ((List<Map<String, Object>>) out.get("roomTypes")).get(0);
        assertEquals(V, rt.get("videoUrl"));
        @SuppressWarnings("unchecked")
        Map<String, Object> limits = (Map<String, Object>) out.get("limits");
        assertEquals(50L, limits.get("videoMb"));
        assertEquals(60, limits.get("videoSeconds"));
    }

    // ---------------------------------------------------------------- 游客端

    @Test
    void publicDiaryOnlyShowsVisibleEntriesOfOnlineStays()
    {
        when(db.one(contains("s.diary_bg, s.diary_title, s.diary_intro from kg_stay s"), anyMap()))
                .thenReturn(row("stayId", 9L, "stayName", "丽江袋鼠小院", "cover", IMG, "diaryBg", "", "diaryTitle", "", "diaryIntro", ""));
        Map<String, Object> page = row("rows", Arrays.asList(row("diaryId", 1L, "mediaType", "image", "mediaUrl", IMG, "coverUrl", "", "caption", "早餐", "shotDate", "2026-10-12",
                "status", "0", "sort", 0L)), "total", 1L, "hasMore", false);
        when(db.page(anyString(), anyMap(), anyInt(), anyInt())).thenReturn(page);
        Map<String, Object> out = media.diary(9L, 1);
        verify(db).one(argThat((String s) -> s.contains(KgStayBrowseService.ONLINE)), anyMap());
        verify(db).page(argThat((String s) -> s.contains("d.del_flag = '0' and d.status = '0'") && s.contains("order by d.sort, d.shot_date desc, d.diary_id desc")),
                anyMap(), eq(1), eq(20));
        @SuppressWarnings("unchecked")
        Map<String, Object> header = (Map<String, Object>) out.get("header");
        assertEquals(IMG, header.get("bg"), "没传背景图用房源封面");
        assertEquals("小院日记", header.get("title"));
        assertEquals("把日子过成一段可以翻看的记忆", header.get("subtitle"));
        assertEquals(Collections.singletonList("这里是丽江袋鼠小院的真实日常"), header.get("lines"));
        @SuppressWarnings("unchecked")
        Map<String, Object> e = ((List<Map<String, Object>>) out.get("rows")).get(0);
        assertFalse(e.containsKey("hidden"), "游客端不带员工端字段");
        assertEquals("10.12", e.get("date"));
        assertEquals(IMG, e.get("cover"), "照片的缩略图就是它自己");
        // 房源不在架：404
        when(db.one(contains("s.diary_bg, s.diary_title, s.diary_intro from kg_stay s"), anyMap())).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class, () -> media.diary(9L, 1));
        assertEquals(Integer.valueOf(404), ex.getCode());
        rejects(() -> media.diary(null, 1), "房源不存在");
    }

    @Test
    void diarySummaryAndDetailVideoBlocks()
    {
        when(db.count(contains("from kg_stay_diary d where d.stay_id = :s and d.del_flag = '0' and d.status = '0'"), anyMap())).thenReturn(5L);
        when(db.list(contains("select d.media_type, d.media_url, d.cover_url from kg_stay_diary d"), anyMap())).thenReturn(Arrays.asList(
                row("mediaType", "video", "mediaUrl", V, "coverUrl", ""), row("mediaType", "image", "mediaUrl", IMG, "coverUrl", ""),
                row("mediaType", "video", "mediaUrl", V2, "coverUrl", "/profile/upload/2026/10/17/c.jpg"), row("mediaType", "image", "mediaUrl", "/profile/upload/2026/10/17/d.jpg", "coverUrl", "")));
        Map<String, Object> sum = media.diarySummary(9L);
        assertEquals(5L, sum.get("count"));
        assertEquals(Arrays.asList(IMG, "/profile/upload/2026/10/17/c.jpg", "/profile/upload/2026/10/17/d.jpg"), sum.get("thumbs"), "没封面的视频跳过，最多 3 张");
        when(db.count(contains("from kg_stay_diary d"), anyMap())).thenReturn(0L);
        assertEquals(0L, media.diarySummary(9L).get("count"));
        // 详情页的小院视频
        assertNull(KgStayMediaService.introVideo(row("introVideo", "", "cover", IMG)));
        assertNull(KgStayMediaService.introVideo(row("introVideo", "https://evil.example.com/a.mp4", "cover", IMG)), "手改库塞进来的外链不往页面上放");
        Map<String, Object> iv = KgStayMediaService.introVideo(row("introVideo", V, "introVideoCover", "", "cover", IMG));
        assertEquals(V, iv.get("url"));
        assertEquals(IMG, iv.get("cover"), "没封面用房源封面");
        assertEquals(Boolean.FALSE, iv.get("hasCover"));
        Map<String, Object> out = new HashMap<String, Object>();
        KgStayMediaService.roomVideo(row("videoUrl", V, "videoCover", IMG), out);
        assertEquals(V, out.get("videoUrl"));
        assertEquals(IMG, out.get("videoCover"));
        KgStayMediaService.roomVideo(row("videoUrl", "x.mp4", "videoCover", IMG), out);
        assertEquals("", out.get("videoUrl"));
        assertEquals("", out.get("videoCover"));
    }
}
