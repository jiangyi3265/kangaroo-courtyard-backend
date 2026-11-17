package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgCommunityGuard;
import com.ruoyi.kangaroo.security.AppLoginUser;

/**
 * 退回修改（V41）：可以附最多 9 张截图、说明放宽到 5000 字（验收通过的评语仍是 200 字），截图存进 review_images，
 * 接单人和门店都能在 view() 里看到；通知只放简短预览 + 截图数 + 截止提醒。KgJdbc 打桩，不连库。
 */
class KgOnlineReturnRevisionTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final StaffSupport support = mock(StaffSupport.class);
    private final KgOnlineTaskService service = spy(new KgOnlineTaskService());
    private final StaffSupport.Ctx ctx = new StaffSupport.Ctx();
    private final Map<String, Object> user = p("status", "0", "delFlag", "0");
    private final Map<String, Object> app = p("appUserId", 2L, "jobId", 8L, "storeId", 3L, "status", "review_passed", "delFlag", "0");
    private Map<String, Object> assignment;

    @BeforeEach
    void setup()
    {
        ctx.me = new AppLoginUser(); ctx.me.setUserId(1L); ctx.me.setNickname("本地主理人");
        ctx.storeId = 3L; ctx.role = "manager";
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "support", support);
        // 截止时间还早：退回后不用另给期限
        assignment = p("title", "探店笔记", "commission", "¥50", "commissionFen", 5000L, "deadlineTs", System.currentTimeMillis() + 10L * 86400000L);
        doAnswer(i -> assignment).when(service).assignment(ctx, 7L);
        when(db.one(anyString(), anyMap())).thenAnswer(i -> {
            String sql = i.getArgument(0);
            if (sql.contains("kg_app_user") && sql.contains("for update")) return user;
            if (sql.contains("kg_application where")) return app;
            if (sql.contains("kg_online_submission") && sql.contains("for update")) return p("submissionId", 6L, "status", "submitted");
            return null;
        });
    }

    private static String chars(int n)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append('改');
        return sb.toString();
    }

    private static List<String> shots(int n)
    {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < n; i++) out.add(String.format("/profile/upload/2026/10/15/%032d.png", i));
        return out;
    }

    private int rejected(boolean pass, String text, Object images)
    {
        ServiceException e = assertThrows(ServiceException.class, () -> service.review(ctx, 7L, pass, text, images));
        verify(db, never()).update(contains("update kg_online_submission"), anyMap());
        return e.getCode();
    }

    @Test
    void returnNeedsTextOrScreenshots()
    {
        assertEquals(422, rejected(false, "  ", null));
        assertEquals(422, rejected(false, null, Collections.emptyList()));
        assertEquals(422, rejected(false, "", Arrays.asList("", "  ")));
    }

    @Test
    void screenshotsOnlyAcceptPlatformImagesAtMostNine()
    {
        assertEquals(422, rejected(false, "看图", Collections.singletonList("https://evil.example.com/a.png")));
        assertEquals(422, rejected(false, "看图", Collections.singletonList("/profile/upload/a.png,/profile/upload/b.png")));
        assertEquals(422, rejected(false, "看图", Collections.singletonList("/profile/../etc/passwd")));
        assertEquals(422, rejected(false, "看图", shots(10)));
        assertEquals(422, rejected(false, "看图", "/profile/upload/a.png"));
    }

    @Test
    void returnReasonUpToFiveThousandCharsButPassCommentStaysTwoHundred()
    {
        assertEquals(422, rejected(false, chars(5001), null));
        assertEquals(422, rejected(true, chars(201), null));
        service.review(ctx, 7L, false, chars(5000), null);
        verify(db).update(contains("review_comment = :c, review_images = :imgs"), argThat(m -> chars(5000).equals(m.get("c")) && "".equals(m.get("imgs"))
                && "returned".equals(m.get("s"))));
    }

    @Test
    void screenshotsAreStoredDedupedAndCountedInTheNoticeAndLog()
    {
        List<String> nine = shots(9);
        List<String> withDup = new ArrayList<String>(nine);
        withDup.add(nine.get(0));
        withDup.add(" ");
        service.review(ctx, 7L, false, "  封面标题换成店名。\n第 2 张图偏暗  ", withDup);
        verify(db).update(contains("review_images = :imgs"), argThat(m -> String.join(",", nine).equals(m.get("imgs"))
                && "封面标题换成店名。\n第 2 张图偏暗".equals(m.get("c"))));
        verify(support).notifyUser(eq(2L), eq("apply"), eq("成果被退回修改"),
                eq("「探店笔记」：封面标题换成店名。 第 2 张图偏暗（附 9 张截图）。点开查看完整原因和截图，改好后重新提交"),
                eq("application"), eq(7L), eq("/pages/jobs/deliver?appId=7"));
        verify(support).op(same(ctx), eq("online_return"), eq("线上任务退回修改"), eq("general"), eq("探店笔记（附 9 张图）"), eq(true), isNull());
    }

    @Test
    void screenshotsOnlyReturnIsAllowed()
    {
        service.review(ctx, 7L, false, "", shots(2));
        verify(db).update(contains("review_images = :imgs"), argThat(m -> m.get("c") == null && String.valueOf(m.get("imgs")).split(",").length == 2));
        verify(support).notifyUser(eq(2L), eq("apply"), eq("成果被退回修改"), contains("门店附了 2 张截图，标出了要改的地方"), anyString(), anyLong(), anyString());
    }

    @Test
    void passingIgnoresScreenshotsAndClearsTheColumn()
    {
        service.review(ctx, 7L, true, "做得好", shots(3));
        verify(db).update(contains("review_images = :imgs"), argThat(m -> "".equals(m.get("imgs")) && "accepted".equals(m.get("s"))));
    }

    @Test
    void blockedWordsInTheReasonAreRefusedWhenTheGuardIsPresent()
    {
        KgCommunityGuard guard = mock(KgCommunityGuard.class);
        when(guard.check(anyString())).thenThrow(new ServiceException("内容包含不允许发布的信息，请修改后再发", 422));
        ReflectionTestUtils.setField(service, "guard", guard);
        assertEquals(422, rejected(false, "违禁内容", null));
        // 只有截图没有文字时不查词库
        reset(guard);
        service.review(ctx, 7L, false, "", shots(1));
        verifyNoInteractions(guard);
    }

    @Test
    void lateReturnKeepsTheDeadlineEvenWithALongReason()
    {
        // 截止前后才退回：正文只放前 60 字预览，「请在 … 前修改后重新提交」不会被站内信的 500 字截掉
        assignment.put("deadlineTs", System.currentTimeMillis() - 1000L);
        service.review(ctx, 7L, false, chars(3000), shots(1));
        verify(support).notifyUser(eq(2L), eq("apply"), eq("成果被退回修改"), argThat(s -> s.length() < 140 && s.contains(chars(60) + "…（附 1 张截图）。请在 ")
                && s.endsWith(" 前修改后重新提交")), anyString(), anyLong(), anyString());
    }

    @Test
    void returnNoticeIsShortAndHasNoDoubledFullStop()
    {
        assertEquals("「任务」：字数不够。点开查看完整原因，改好后重新提交", KgOnlineTaskService.returnNotice("任务", "字数不够。", 0, null));
        Date until = new Date(System.currentTimeMillis() + 48 * 3600000L);
        String late = KgOnlineTaskService.returnNotice("任务", "字数不够！！", 0, until);
        assertEquals("「任务」：字数不够。请在 " + StaffSupport.fmt(until, "MM-dd HH:mm") + " 前修改后重新提交", late);
        assertFalse(late.contains("。。"));
    }

    @Test
    void oldFourArgumentReviewStillWorksWithoutScreenshots()
    {
        service.review(ctx, 7L, false, "补一张截图");
        verify(db).update(contains("review_images = :imgs"), argThat(m -> "".equals(m.get("imgs")) && "补一张截图".equals(m.get("c"))));
    }

    @Test
    void viewReturnsScreenshotsForBothSidesAndTheTipMentionsThem()
    {
        KgOnlineTaskService s = new KgOnlineTaskService();
        ReflectionTestUtils.setField(s, "db", db);
        when(db.must(anyString(), anyMap(), anyString())).thenReturn(p("jobId", 8L, "jobStore", 3L, "title", "探店笔记", "appUserId", 2L,
                "status", "review_passed", "workerStatus", "0", "workerDelFlag", "0", "commissionFen", 5000L, "storeName", "平潭袋鼠小院",
                "deadlineTime", "2099-01-01 00:00:00"));
        when(db.list(contains("from kg_online_submission"), anyMap())).thenReturn(Arrays.asList(
                p("round", 2, "status", "returned", "reviewComment", "再改一下", "reviewImages", "/profile/a.png, /profile/b.png", "createTime", "2026-10-14 10:00:00",
                        "reviewedTime", "2026-10-14 12:00:00"),
                p("round", 1, "status", "returned", "reviewComment", "老数据没有截图列", "createTime", "2026-10-13 10:00:00")));
        when(db.list(contains("from kg_online_material"), anyMap())).thenReturn(Collections.emptyList());
        Map<String, Object> v = ReflectionTestUtils.invokeMethod(s, "view", 7L, false);
        List<?> subs = (List<?>) v.get("submissions");
        assertEquals(Arrays.asList("/profile/a.png", "/profile/b.png"), ((Map<?, ?>) subs.get(0)).get("reviewImages"));
        assertEquals(Collections.emptyList(), ((Map<?, ?>) subs.get(1)).get("reviewImages"));
        assertTrue(String.valueOf(((Map<?, ?>) v.get("flow")).get("tip")).contains("看看原因和截图"), String.valueOf(((Map<?, ?>) v.get("flow")).get("tip")));
        Map<String, Object> staff = ReflectionTestUtils.invokeMethod(s, "view", 7L, true);
        assertEquals(2, ((List<?>) ((Map<?, ?>) ((List<?>) staff.get("submissions")).get(0)).get("reviewImages")).size());
    }
}
