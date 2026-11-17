package com.ruoyi.kangaroo.ext.stay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgCommunityGuard;
import com.ruoyi.kangaroo.ext.core.KgVideoUploads;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;

/** 小院评论（V65，2026-10-31 主人拍板）：一律先审、身份由服务端算、媒体校验、限额、审核流转和通知（不连库；真库真接口在 e2e/test_stay_comment.py） */
class KgStayCommentServiceTest
{
    private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13, 'I', 'H', 'D', 'R', 0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 0x1f, 0x15, (byte) 0xc4 };
    private static final String TODAY = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
    private static final String VIDEO = "/profile/upload/videos/" + TODAY + "/0123456789abcdef0123456789abcdef.mp4";

    @TempDir
    Path folder;

    private String originalProfile;
    private final KgJdbc db = mock(KgJdbc.class);
    private final StaffSupport support = mock(StaffSupport.class);
    private final KgCommunityGuard guard = mock(KgCommunityGuard.class);
    private final KgVideoUploads videos = mock(KgVideoUploads.class);
    private final KgStayCommentService svc = new KgStayCommentService();

    private static Map<String, Object> row(Object... kv)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2)
        {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static void rejects(int code, String word, org.junit.jupiter.api.function.Executable run)
    {
        ServiceException e = assertThrows(ServiceException.class, run);
        assertEquals(Integer.valueOf(code), e.getCode(), e.getMessage());
        assertTrue(e.getMessage().contains(word), e.getMessage());
    }

    @BeforeEach
    void setup()
    {
        originalProfile = RuoYiConfig.getProfile();
        new RuoYiConfig().setProfile(folder.toString());
        ReflectionTestUtils.setField(svc, "db", db);
        ReflectionTestUtils.setField(svc, "support", support);
        ReflectionTestUtils.setField(svc, "guard", guard);
        ReflectionTestUtils.setField(svc, "videos", videos);
        when(videos.exists(anyString())).thenReturn(true);
        when(guard.check(ArgumentMatchers.<String>any())).thenReturn("pass");
        // 账号正常、小院在架，其余查询默认没有
        when(db.one(anyString(), anyMap())).thenReturn(null);
        when(db.one(contains("from kg_app_user where user_id=:u for update"), anyMap())).thenReturn(row("status", "0", "delFlag", "0"));
        when(db.one(contains("from kg_stay s where"), anyMap())).thenReturn(row("stayId", 6L, "stayName", "平潭小院", "storeId", 102L));
        when(db.insert(eq("kg_stay_comment"), anyMap())).thenReturn(9L);
        when(db.one(contains("where c.comment_id = :id"), anyMap())).thenAnswer(i -> row("commentId", 9L, "appUserId", 7L, "status", "pending", "sourceType", "unverified", "content", "x"));
    }

    @AfterEach
    void back()
    {
        new RuoYiConfig().setProfile(originalProfile);
    }

    private String image(String name) throws IOException
    {
        Path f = folder.resolve("upload").resolve(TODAY).resolve(name);
        Files.createDirectories(f.getParent());
        Files.write(f, PNG);
        return "/profile/upload/" + TODAY + "/" + name;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> inserted()
    {
        ArgumentCaptor<Map<String, Object>> c = ArgumentCaptor.forClass(Map.class);
        verify(db).insert(eq("kg_stay_comment"), c.capture());
        return c.getValue();
    }

    // ---------------------------------------------------------------- 提交

    @Test
    void submitIsAlwaysPendingWhateverTheReviewMode()
    {
        svc.submit(6L, 7L, row("content", "  院子很好  "));
        Map<String, Object> r = inserted();
        assertEquals("pending", r.get("status"));
        assertEquals("院子很好", r.get("content"));
        assertEquals("none", r.get("risk_flag"));
        assertEquals(102L, r.get("store_id"));
        assertEquals("平潭小院", r.get("stay_name"));
        assertEquals("unverified", r.get("source_type"));
        assertEquals("0", r.get("portrait_confirmed"));
        // 不走现成的状态判断和机审，所以 mock、auto 模式都不会直接公开
        verify(guard, never()).initialStatus(anyString());
        verify(guard, never()).autoApprove(any(), any(), anyInt(), any(), any());
        verifyNoInteractions(support);
    }

    @Test
    void writingNeedsAnActiveAccountAVerifiedPhoneAndConsent()
    {
        when(db.one(contains("from kg_app_user where user_id=:u for update"), anyMap())).thenReturn(row("status", "1", "delFlag", "0"));
        rejects(409, "不可用", () -> svc.submit(6L, 7L, row("content", "x")));
        when(db.one(contains("from kg_app_user where user_id=:u for update"), anyMap())).thenReturn(row("status", "0", "delFlag", "0"));
        doThrow(new ServiceException("请先验证手机号", 428)).when(guard).requirePhone(7L);
        rejects(428, "手机号", () -> svc.submit(6L, 7L, row("content", "x")));
        doNothing().when(guard).requirePhone(7L);
        doThrow(new ServiceException("CONSENT:community:请先阅读并同意社区公约", 423)).when(guard).requireConsent(7L, "community");
        rejects(423, "CONSENT:community", () -> svc.submit(6L, 7L, row("content", "x")));
        when(db.one(contains("from kg_stay s where"), anyMap())).thenReturn(null);
        rejects(404, "下架", () -> svc.submit(6L, 7L, row("content", "x")));
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    void textIsOneToFiveHundredCharacters()
    {
        rejects(422, "1–500", () -> svc.submit(6L, 7L, row("content", "   ")));
        rejects(422, "1–500", () -> svc.submit(6L, 7L, row()));
        rejects(422, "1–500", () -> svc.submit(6L, 7L, null));
        rejects(422, "1–500", () -> svc.submit(6L, 7L, row("content", new String(new char[501]).replace('\0', '好'))));
        svc.submit(6L, 7L, row("content", new String(new char[500]).replace('\0', '好')));
        assertEquals(500, ((String) inserted().get("content")).length());
    }

    @Test
    void blockedWordsAreRefusedContactsAndReviewWordsWaitAndAreFlagged()
    {
        when(guard.check(ArgumentMatchers.<String>any())).thenThrow(new ServiceException("内容包含不允许发布的信息", 422));
        rejects(422, "不允许", () -> svc.submit(6L, 7L, row("content", "x")));
        verify(db, never()).insert(anyString(), anyMap());

        reset(guard);
        when(guard.check(ArgumentMatchers.<String>any())).thenReturn("review");
        when(guard.hasContact(ArgumentMatchers.<String>any())).thenReturn(true);
        svc.submit(6L, 7L, row("content", "加我 13800138000"));
        Map<String, Object> r = inserted();
        assertEquals("pending", r.get("status"));
        assertEquals("contact", r.get("risk_flag"));

        reset(guard, db);
        when(db.one(anyString(), anyMap())).thenReturn(null);
        when(guard.check(ArgumentMatchers.<String>any())).thenReturn("review");
        when(db.one(contains("from kg_app_user where user_id=:u for update"), anyMap())).thenReturn(row("status", "0", "delFlag", "0"));
        when(db.one(contains("from kg_stay s where"), anyMap())).thenReturn(row("stayId", 6L, "stayName", "平潭小院", "storeId", 102L));
        when(db.one(contains("where c.comment_id = :id"), anyMap())).thenReturn(row("commentId", 9L, "appUserId", 7L, "status", "pending", "sourceType", "unverified", "content", "x"));
        svc.submit(6L, 7L, row("content", "复核词"));
        assertEquals("word", inserted().get("risk_flag"));
    }

    @Test
    void imagesUpToNineWithPortraitConfirmation() throws IOException
    {
        String a = image("a.png"), b = image("b.png");
        rejects(422, "肖像", () -> svc.submit(6L, 7L, row("content", "x", "images", Arrays.asList(a))));
        svc.submit(6L, 7L, row("content", "x", "images", Arrays.asList(a, b, a), "portraitConfirmed", true));
        Map<String, Object> r = inserted();
        assertEquals(a + "," + b, r.get("images"), "重复的只留一张");
        assertEquals("1", r.get("portrait_confirmed"));
        assertEquals("", r.get("video_url"));
        String[] ten = new String[10];
        for (int i = 0; i < 10; i++)
        {
            ten[i] = "/profile/upload/" + TODAY + "/i" + i + ".png";
        }
        rejects(422, "最多附 9 张", () -> svc.submit(6L, 7L, row("content", "x", "images", Arrays.asList(ten), "portraitConfirmed", true)));
    }

    @Test
    void imagesMustBeRealPlatformFiles() throws IOException
    {
        rejects(422, "本平台", () -> svc.submit(6L, 7L, row("content", "x", "images", Arrays.asList("https://evil.example/a.jpg"), "portraitConfirmed", true)));
        rejects(422, "本平台", () -> svc.submit(6L, 7L, row("content", "x", "images", Arrays.asList(VIDEO), "portraitConfirmed", true)));
        rejects(422, "重新上传", () -> svc.submit(6L, 7L, row("content", "x", "images", Arrays.asList("/profile/upload/" + TODAY + "/missing.png"), "portraitConfirmed", true)));
        Path fake = folder.resolve("upload").resolve(TODAY).resolve("fake.png");
        Files.createDirectories(fake.getParent());
        Files.write(fake, "<html>不是图片</html>".getBytes(StandardCharsets.UTF_8));
        rejects(422, "重新上传", () -> svc.submit(6L, 7L, row("content", "x", "images", Arrays.asList("/profile/upload/" + TODAY + "/fake.png"), "portraitConfirmed", true)));
        rejects(422, "格式", () -> svc.submit(6L, 7L, row("content", "x", "images", 12, "portraitConfirmed", true)));
    }

    @Test
    void oneVideoOrImagesNotBoth() throws IOException
    {
        String a = image("a.png"), cover = image("c.png");
        rejects(422, "选一种", () -> svc.submit(6L, 7L, row("content", "x", "images", Arrays.asList(a), "videos", Arrays.asList(row("url", VIDEO)), "portraitConfirmed", true)));
        String v2 = VIDEO.replace("0123", "9999");
        rejects(422, "最多附 1 段", () -> svc.submit(6L, 7L, row("content", "x", "videos", Arrays.asList(row("url", VIDEO), row("url", v2)), "portraitConfirmed", true)));
        rejects(422, "肖像", () -> svc.submit(6L, 7L, row("content", "x", "videos", Arrays.asList(row("url", VIDEO)))));
        svc.submit(6L, 7L, row("content", "x", "videos", Arrays.asList(row("url", VIDEO, "cover", cover)), "portraitConfirmed", true));
        Map<String, Object> r = inserted();
        assertEquals(VIDEO, r.get("video_url"));
        assertEquals(cover, r.get("video_cover"));
        assertEquals("", r.get("images"));
        assertEquals("1", r.get("portrait_confirmed"));
    }

    @Test
    void videoRulesAreCheckedAgainAtSubmit() throws IOException
    {
        rejects(422, "本平台", () -> svc.submit(6L, 7L, row("content", "x", "videos", Arrays.asList(row("url", "/profile/upload/a.png")), "portraitConfirmed", true)));
        when(videos.exists(VIDEO)).thenReturn(false);
        rejects(422, "本平台", () -> svc.submit(6L, 7L, row("content", "x", "videos", Arrays.asList(row("url", VIDEO)), "portraitConfirmed", true)));
        when(videos.exists(VIDEO)).thenReturn(true);
        when(videos.sizeOf(VIDEO)).thenReturn(KgVideoUploads.MAX_BYTES + 1);
        rejects(422, "50MB", () -> svc.submit(6L, 7L, row("content", "x", "videos", Arrays.asList(row("url", VIDEO)), "portraitConfirmed", true)));
        when(videos.sizeOf(VIDEO)).thenReturn(KgVideoUploads.MAX_BYTES);
        rejects(422, "封面", () -> svc.submit(6L, 7L, row("content", "x", "videos", Arrays.asList(row("url", VIDEO, "cover", "http://x/a.png")), "portraitConfirmed", true)));
    }

    @Test
    void textMustHaveSomethingVisible()
    {
        for (String blank : Arrays.asList("\u3000\u3000", "\u200b\u200b", "\u00a0", "\ufe0f", "\u2060\ufeff", "\u3164", "\u2800", "\u0301", "\udb40\udd00", "\n\t", " \u3000\u200b\n"))
        {
            rejects(422, "1–500", () -> svc.submit(6L, 7L, row("content", blank)));
        }
        verify(db, never()).insert(anyString(), anyMap());
        // 有字就算：单独一个表情（含带变体符、Unicode 新表情、键帽），字旁边带零宽字符的也算；落库内容不动
        for (String ok : Arrays.asList("好", "a", "1", "\ud83d\ude00", "\u2764\ufe0f", "1\ufe0f\u20e3", "\ud83e\udee0", "\u597d\u200b"))
        {
            assertTrue(KgStayCommentService.hasVisibleText(ok), ok);
        }
        svc.submit(6L, 7L, row("content", "\u2764\ufe0f\u200b"));
        assertEquals("\u2764\ufe0f\u200b", inserted().get("content"));
    }

    @Test
    void mediaMustBeFreshAndNotUsedByOthers() throws IOException
    {
        LocalDate d = LocalDate.of(2026, 10, 31);
        assertTrue(KgStayCommentService.fresh("/profile/upload/2026/10/24/a.jpg", d));
        assertFalse(KgStayCommentService.fresh("/profile/upload/2026/10/23/a.jpg", d));
        assertFalse(KgStayCommentService.fresh("/profile/upload/videos/2025/01/01/a.mp4", d));
        // 审查后收紧：正常上传的路径都带日期，没有日期、日期不成立的不再放行
        assertFalse(KgStayCommentService.fresh("/profile/upload/a.jpg", d));
        assertFalse(KgStayCommentService.fresh("/profile/upload/2026/99/99/a.jpg", d));
        rejects(422, "太久", () -> svc.submit(6L, 7L, row("content", "x", "images", Arrays.asList("/profile/upload/2020/01/01/a.png"), "portraitConfirmed", true)));
        rejects(422, "太久", () -> svc.submit(6L, 7L, row("content", "x", "images", Arrays.asList("/profile/upload/legacy-a.png"), "portraitConfirmed", true)));

        String a = image("a.png");
        when(db.count(contains("app_user_id <> :u"), anyMap())).thenReturn(1L);
        rejects(422, "别人", () -> svc.submit(6L, 7L, row("content", "x", "images", Arrays.asList(a), "portraitConfirmed", true)));
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    void limitsThreePendingPerStayAndTenPerHour()
    {
        when(db.count(contains("status = 'pending'"), anyMap())).thenReturn(3L);
        rejects(429, "审核中", () -> svc.submit(6L, 7L, row("content", "x")));
        when(db.count(contains("status = 'pending'"), anyMap())).thenReturn(2L);
        when(db.count(contains("create_time > :since"), anyMap())).thenReturn(10L);
        rejects(429, "一小时", () -> svc.submit(6L, 7L, row("content", "x")));
        when(db.count(contains("create_time > :since"), anyMap())).thenReturn(9L);
        svc.submit(6L, 7L, row("content", "x"));
        verify(db).insert(eq("kg_stay_comment"), anyMap());
    }

    // ---------------------------------------------------------------- 身份

    @Test
    void identityIsComputedOnTheServerGuestBeforeVolunteer()
    {
        when(db.one(contains("from kg_app_user where user_id = :u and phone_verified_time"), anyMap())).thenReturn(row("phone", "13800138000"));
        assertArrayEquals(new Object[] { "unverified", null }, svc.identity(6L, 102L, 7L));

        when(db.one(contains("from kg_application a"), anyMap())).thenReturn(row("appId", 44L));
        assertArrayEquals(new Object[] { "volunteer", 44L }, svc.identity(6L, 102L, 7L));

        when(db.one(contains("from kg_stay_order o"), anyMap())).thenReturn(row("orderId", 55L));
        assertArrayEquals(new Object[] { "guest", 55L }, svc.identity(6L, 102L, 7L));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db, atLeastOnce()).one(sql.capture(), anyMap());
        String order = sql.getAllValues().stream().filter(s -> s.contains("from kg_stay_order o")).findFirst().get();
        assertTrue(order.contains("o.status in ('checked_in','completed')") && order.contains("o.app_user_id = :u") && order.contains("o.guest_phone = :ph"), order);
        String app = sql.getAllValues().stream().filter(s -> s.contains("from kg_application a")).findFirst().get();
        assertTrue(app.contains("a.status in ('onboarded','completed')") && app.contains("j.job_type <> 'online'") && app.contains("coalesce(j.recruit_store_id, j.store_id) = :st"), app);
    }

    @Test
    void clientCannotClaimAnIdentity()
    {
        svc.submit(6L, 7L, row("content", "x", "sourceType", "guest", "identity", "guest", "status", "online"));
        Map<String, Object> r = inserted();
        assertEquals("unverified", r.get("source_type"));
        assertEquals("pending", r.get("status"));
    }

    // ---------------------------------------------------------------- 给小程序的卡片

    @Test
    void cardHidesInternalsAndMasksTheNameForOthers()
    {
        Map<String, Object> raw = row("commentId", 9L, "appUserId", 7L, "nickname", "晨光小院", "avatar", "/a.png", "sourceType", "guest", "content", "好", "images", "/1.jpg,/2.jpg",
                "videoUrl", "", "status", "online", "auditReason", null, "createTime", "2026-10-31 08:00:00", "riskFlag", "contact", "reported", 3, "sourceId", 55L);
        Map<String, Object> other = svc.card(raw, 8L);
        assertEquals("晨**", other.get("name"));
        assertEquals("已核实住客", other.get("identityText"));
        assertEquals(Arrays.asList("/1.jpg", "/2.jpg"), other.get("images"));
        assertNull(other.get("video"));
        assertEquals("2026.10.31", other.get("dateText"));
        assertEquals(Boolean.FALSE, other.get("isMe"));
        assertEquals(Boolean.TRUE, other.get("canReport"));
        for (String k : Arrays.asList("riskFlag", "reported", "sourceId", "appUserId"))
        {
            assertFalse(other.containsKey(k), k + " 只给后台");
        }
        Map<String, Object> mine = svc.card(raw, 7L);
        assertEquals("晨光小院", mine.get("name"));
        assertEquals(Boolean.TRUE, mine.get("isMe"));
        assertEquals(Boolean.FALSE, mine.get("canReport"));
        assertEquals(Boolean.FALSE, svc.card(raw, null).get("canReport"), "游客不能直接举报");

        raw.put("status", "rejected");
        raw.put("auditReason", "有联系方式");
        assertEquals("有联系方式", svc.card(raw, 7L).get("auditReason"));
        assertEquals("未通过", svc.card(raw, 7L).get("statusText"));
        assertEquals("", svc.card(raw, 8L).get("auditReason"), "理由只给本人");

        raw.put("anonymous", "1");
        assertEquals("已注销用户", svc.card(raw, 8L).get("name"));
        assertEquals("", svc.card(raw, 8L).get("avatar"));

        assertEquals("小伙伴", KgStayCommentService.maskName(" "));
        assertEquals("😀**", KgStayCommentService.maskName("😀好"), "表情不能切成两半");
    }

    @Test
    void listPublicRowsAreOnlineOnlyAndMineOnlyOnFirstPageForLoggedIn()
    {
        Map<String, Object> page = row("rows", Collections.emptyList(), "total", 0L, "hasMore", false);
        when(db.page(anyString(), anyMap(), anyInt(), anyInt())).thenReturn(page);
        when(db.list(contains("c.status <> 'online'"), anyMap())).thenReturn(Arrays.asList(row("commentId", 1L, "appUserId", 7L, "status", "pending", "sourceType", "unverified", "content", "审核中")));

        @SuppressWarnings("unchecked") List<Object> guest = (List<Object>) svc.list(6L, 1, null).get("mine");
        assertTrue(guest.isEmpty(), "游客没有 mine");
        verify(db, never()).list(anyString(), anyMap());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db).page(sql.capture(), anyMap(), eq(1), eq(KgStayCommentService.PAGE_SIZE));
        assertTrue(sql.getValue().contains("c.status = 'online' and c.del_flag = '0'"), sql.getValue());
        assertFalse(sql.getValue().contains("kg_user_block"), "游客没有拉黑关系");

        @SuppressWarnings("unchecked") List<Map<String, Object>> mine = (List<Map<String, Object>>) svc.list(6L, 1, 7L).get("mine");
        assertEquals("审核中", mine.get(0).get("statusText"));
        @SuppressWarnings("unchecked") List<Object> second = (List<Object>) svc.list(6L, 2, 7L).get("mine");
        assertTrue(second.isEmpty());
        rejects(422, "页码", () -> svc.list(6L, 0, null));
    }

    // ---------------------------------------------------------------- 删除和举报

    @Test
    void onlyTheAuthorCanRemove()
    {
        when(db.update(contains("set del_flag = '2'"), anyMap())).thenReturn(0);
        rejects(404, "不存在", () -> svc.remove(9L, 8L));
        verify(db, never()).update(contains("update kg_message"), anyMap());
        when(db.update(contains("set del_flag = '2'"), anyMap())).thenReturn(1);
        svc.remove(9L, 7L);
        verifyWithdrawn("这条评论已删除");
    }

    /** 小院主理人那条抹掉再软删，作者那条只换正文，都按这条评论的业务关联 */
    private void verifyWithdrawn(String text)
    {
        verify(db).update(argThat(q -> q != null && q.startsWith("update kg_message set title = '小院评论已撤回', content = :c, route = null, del_flag = '2' where") && q.endsWith("biz_type = :t and biz_id = :id and mode = 'staff'")),
                eq(row("c", text, "t", "stay_comment", "id", 9L)));
        verify(db).update(eq("update kg_message set content = :c where biz_type = :t and biz_id = :id and mode = 'user'"), eq(row("c", text, "t", "stay_comment", "id", 9L)));
    }

    @Test
    void reportOnlyOthersOnlineCommentsOnce()
    {
        when(db.one(contains("status = 'online' and del_flag = '0'"), anyMap())).thenReturn(null);
        rejects(404, "不存在", () -> svc.report(9L, 8L, null, null));
        when(db.one(contains("status = 'online' and del_flag = '0'"), anyMap())).thenReturn(row("appUserId", 7L));
        rejects(422, "自己", () -> svc.report(9L, 7L, null, null));
        when(db.update(contains("insert ignore into kg_report"), anyMap())).thenReturn(1);
        assertTrue(svc.report(9L, 8L, "骚扰", "细节").contains("已提交"));
        verify(db).update(contains("reported = reported + 1"), anyMap());
        when(db.update(contains("insert ignore into kg_report"), anyMap())).thenReturn(0);
        assertTrue(svc.report(9L, 8L, null, null).contains("已经举报过"));
        verify(db).update(contains("reported = reported + 1"), anyMap());
        assertEquals("stay_comment", svc.targetType());
    }

    // ---------------------------------------------------------------- 审核

    private void stored(String status)
    {
        Map<String, Object> c = row("commentId", 9L, "stayId", 6L, "storeId", 102L, "stayName", "平潭小院", "appUserId", 7L, "sourceType", "guest", "content", "院子很好", "status", status);
        when(db.must(contains("from kg_stay_comment where comment_id = :id and del_flag = '0' for update"), anyMap(), anyString())).thenReturn(c);
        when(db.one(contains("from kg_stay_comment where comment_id = :id and del_flag = '0' for update"), anyMap())).thenReturn(c);
        when(db.update(contains("update kg_stay_comment set status = :to"), anyMap())).thenReturn(1);
    }

    @Test
    void approveNotifiesAuthorAndOnlyKeepersNeverTheWholeStore()
    {
        stored("pending");
        assertEquals("已通过", svc.approve(9L, "admin"));
        String route = "/pages/stays/diary?stayId=6&tab=comment";
        verify(support).notifyUser(eq(7L), anyString(), contains("已通过"), contains("院子很好"), eq("stay_comment"), eq(9L), eq(route));
        verify(support).notifyKeepers(eq(102L), eq(7L), anyString(), anyString(), contains("已核实住客"), eq("stay_comment"), eq(9L), eq(route));
        verify(support, never()).notifyStore(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectNeedsAReasonAndTellsOnlyTheAuthor()
    {
        stored("pending");
        rejects(422, "驳回理由", () -> svc.reject(9L, "  ", "admin"));
        assertEquals("已驳回", svc.reject(9L, " 含联系方式 ", "admin"));
        verify(support).notifyUser(eq(7L), anyString(), contains("未通过"), contains("含联系方式"), eq("stay_comment"), eq(9L), anyString());
        // 带消息管理权限的后台账号读得到正文：没公开过的评论，原文不能写进消息
        verify(support, never()).notifyUser(any(), any(), any(), contains("院子很好"), any(), any(), any());
        verify(support, never()).notifyKeepers(any(), any(), any(), any(), any(), any(), any(), any());
        verify(support, never()).notifyStore(any(), any(), any(), any(), any(), any(), any());
        verify(db, never()).update(contains("update kg_message"), anyMap());
    }

    @Test
    void offlineNeedsAReasonAndOnlyFromOnline()
    {
        stored("online");
        rejects(422, "下架理由", () -> svc.offline(9L, "", "admin"));
        assertEquals("已下架", svc.offline(9L, "违规", "admin"));
        verify(support).notifyUser(eq(7L), anyString(), contains("下架"), contains("违规"), eq("stay_comment"), eq(9L), anyString());
        verify(support, never()).notifyUser(any(), any(), any(), contains("院子很好"), any(), any(), any());
        verify(support, never()).notifyKeepers(any(), any(), any(), any(), any(), any(), any(), any());
        verifyWithdrawn("这条评论已下架");
    }

    @Test
    void statusMovesAreGuarded()
    {
        stored("online");
        rejects(409, "待审核", () -> svc.approve(9L, "admin"));
        rejects(409, "待审核", () -> svc.reject(9L, "x", "admin"));
        stored("pending");
        rejects(409, "已通过", () -> svc.offline(9L, "x", "admin"));
        // 查到之后状态被别人改了
        when(db.update(contains("update kg_stay_comment set status = :to"), anyMap())).thenReturn(0);
        rejects(409, "状态已变化", () -> svc.approve(9L, "admin"));
        verifyNoInteractions(support);
    }

    @Test
    void upheldReportTakesTheCommentDown()
    {
        stored("online");
        assertEquals("评论已下架", svc.uphold(9L, "admin"));
        verify(support).notifyUser(eq(7L), anyString(), contains("下架"), contains("举报成立"), eq("stay_comment"), eq(9L), anyString());
        verifyWithdrawn("这条评论已下架");
        stored("offline");
        assertEquals("评论已经不在展示了", svc.uphold(9L, "admin"));
        when(db.one(contains("from kg_stay_comment where comment_id = :id and del_flag = '0' for update"), anyMap())).thenReturn(null);
        assertTrue(svc.uphold(9L, "admin").contains("已被作者删除"));
    }

    // ---------------------------------------------------------------- 边界：不碰评价和碎片，不依赖角色阶段

    @Test
    void neverTouchesReviewsDiaryPointsOrRolePhase() throws IOException
    {
        String src = new String(Files.readAllBytes(Paths.get("src/main/java/com/ruoyi/kangaroo/ext/stay/KgStayCommentService.java")), StandardCharsets.UTF_8);
        // 只看代码，注释行不算（注释里会写「绝不用 notifyStore」）
        StringBuilder code = new StringBuilder();
        for (String line : src.substring(src.indexOf("@Service")).split("\n"))
        {
            if (!line.trim().startsWith("//") && !line.trim().startsWith("/*") && !line.trim().startsWith("*"))
            {
                code.append(line).append('\n');
            }
        }
        for (String banned : Arrays.asList("update kg_review", "insert into kg_review", "kg_stay_diary", "RolePhase", "pointRewards", "KgPointsService", "refreshByReview", "notifyStore", "autoApprove", "initialStatus"))
        {
            assertFalse(code.toString().contains(banned), banned);
        }
    }
}
