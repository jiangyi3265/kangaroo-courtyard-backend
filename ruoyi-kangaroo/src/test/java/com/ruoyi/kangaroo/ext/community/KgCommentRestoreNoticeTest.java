package com.ruoyi.kangaroo.ext.community;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.service.IKgMessageService;

/**
 * 待审评论先被隐藏、再「通过 / 恢复」（2026-10-14 回归 R3-14）：这是它第一次展示，要像正常审核通过一样通知作者和评论人；
 * 待审时被隐藏要告诉评论人没通过；展示过又被隐藏的评论恢复时不重复通知
 */
class KgCommentRestoreNoticeTest
{
    final KgJdbc db = mock(KgJdbc.class);
    final StaffSupport support = mock(StaffSupport.class);
    final IKgMessageService messages = mock(IKgMessageService.class);
    final KgPostService service = new KgPostService();

    KgCommentRestoreNoticeTest()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "support", support);
        ReflectionTestUtils.setField(service, "messages", messages);
        when(db.update(anyString(), anyMap())).thenReturn(1);
        when(db.one(startsWith("select app_user_id from kg_post"), anyMap())).thenReturn(p("appUserId", 3L));
    }

    void comment(String status, String handleNote)
    {
        when(db.must(startsWith("select comment_id, post_id, app_user_id"), anyMap(), anyString()))
                .thenReturn(p("commentId", 311L, "postId", 367L, "appUserId", 4L, "content", "怎么去古城？", "status", status, "handleNote", handleNote));
    }

    @SuppressWarnings("unchecked")
    String savedNote()
    {
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(db).update(startsWith("update kg_post_comment set status"), args.capture());
        return String.valueOf(args.getValue().get("n"));
    }

    @Test
    void hidingPendingCommentMarksItAndTellsCommenter()
    {
        comment("pending", null);
        assertEquals("已隐藏", service.auditComment(311L, false, "含广告", "admin"));
        assertTrue(savedNote().endsWith(KgPostService.NEVER_SHOWN), savedNote());
        verify(support).notifyUser(eq(4L), eq("system"), eq("你的评论未通过审核"), eq("怎么去古城？：含广告"), eq("post"), eq(367L), anyString());
        verify(support, never()).notifyUser(eq(3L), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
        verifyNoInteractions(messages);
        verify(db, never()).update(contains("comments = greatest"), anyMap());
    }

    @Test
    void restoringNeverShownCommentNotifiesLikeApproval()
    {
        comment("hidden", "admin：误隐藏" + KgPostService.NEVER_SHOWN);
        assertEquals("已恢复展示", service.auditComment(311L, true, "审核通过", "admin"));
        assertFalse(savedNote().contains(KgPostService.NEVER_SHOWN));
        verify(db).update(contains("comments = comments + 1"), eq(p("p", 367L)));
        // 2026-10-15：作者收到的是「评论」分类的「{昵称} 评论了你的动态」，带评论人；评论人 4 没填昵称 → 「用户4」
        verify(messages).send(3L, "user", "comment", "用户4 评论了你的动态", "怎么去古城？", "comment", 311L,
                "/pages/community/index?postId=367&view=comments", 4L);
        verify(support).notifyUser(eq(4L), eq("system"), eq("你的评论已通过审核"), eq("怎么去古城？"), eq("post"), eq(367L), anyString());
    }

    @Test
    void restoringOnceShownCommentDoesNotNotifyAgain()
    {
        comment("hidden", "admin：举报成立");
        assertEquals("已恢复展示", service.auditComment(311L, true, "审核通过", "admin"));
        verify(db).update(contains("comments = comments + 1"), eq(p("p", 367L)));
        verifyNoInteractions(support);
        verifyNoInteractions(messages);
    }

    /** 2026-11-09 复查：已展示的评论被隐藏要告诉评论人（之前什么都不发），帖子作者不发 */
    @Test
    void hidingVisibleCommentIsNotMarkedAndTellsOnlyCommenter()
    {
        comment("visible", "admin：审核通过");
        assertEquals("已隐藏", service.auditComment(311L, false, "跑题", "admin"));
        assertFalse(savedNote().contains(KgPostService.NEVER_SHOWN));
        verify(db).update(contains("comments = greatest"), anyMap());
        verify(support).notifyUser(eq(4L), eq("system"), eq("你的评论已被隐藏"), eq("怎么去古城？：跑题"), eq("post"), eq(367L), anyString());
        verify(support, times(1)).notifyUser(anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
        verifyNoInteractions(messages);
    }

    @Test
    void pendingApprovalStillNotifiesOnce()
    {
        comment("pending", null);
        assertEquals("已恢复展示", service.auditComment(311L, true, "审核通过", "admin"));
        // 作者一条评论通知 + 评论人一条「已通过审核」
        verify(messages, times(1)).send(anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString(), anyLong());
        verify(support, times(1)).notifyUser(anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
    }
}
