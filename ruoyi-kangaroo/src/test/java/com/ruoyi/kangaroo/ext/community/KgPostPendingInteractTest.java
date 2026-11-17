package com.ruoyi.kangaroo.ext.community;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.service.IKgMessageService;

/**
 * 2026-10-17 主人反馈「发帖审核中时点赞和收藏还有评论都不能用」：
 * 帖子不是 online（pending 审核中 / rejected 驳回 / hidden 隐藏）时，点赞、收藏、评论、给评论点赞一律 409，不加计数、不发通知；
 * 取消已有的赞 / 收藏照旧放行（只减不加）；online 的帖子一切照旧。
 */
class KgPostPendingInteractTest
{
    static final String PENDING = "帖子还在审核中，通过后才能点赞、收藏和评论";
    static final String REJECTED = "帖子未通过审核，不能点赞、收藏和评论";
    static final String HIDDEN = "帖子已下架，不能点赞、收藏和评论";

    final KgJdbc db = mock(KgJdbc.class);
    final KgCommunityGuard guard = mock(KgCommunityGuard.class);
    final StaffSupport support = mock(StaffSupport.class);
    final IKgMessageService messages = mock(IKgMessageService.class);
    final KgPostService service = new KgPostService();
    String postStatus = "pending";
    /** 取消时 delete 删掉的行数：0 = 原来没点过 / 没收藏过 */
    int deleted = 0;

    KgPostPendingInteractTest()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "guard", guard);
        ReflectionTestUtils.setField(service, "support", support);
        ReflectionTestUtils.setField(service, "messages", messages);
        when(db.one(anyString(), anyMap())).thenAnswer(i -> {
            String sql = i.getArgument(0);
            Map<String, Object> p = i.getArgument(1);
            if (sql.startsWith("select p.* from kg_post"))
                return KgJdbc.p("postId", 11L, "appUserId", 7L, "status", postStatus, "title", "大理三天", "content", "正文");
            if (sql.startsWith("select comment_id, post_id, app_user_id"))
                return KgJdbc.p("commentId", 22L, "postId", 11L, "appUserId", 4L, "content", "好看");
            if (sql.startsWith("select status, del_flag from kg_app_user"))
                return KgJdbc.p("status", "0", "delFlag", "0");
            if (sql.startsWith("select likes from kg_post"))
                return KgJdbc.p("likes", 1L);
            if (sql.startsWith("select user_id"))
                return KgJdbc.p("userId", 8L);
            if (sql.startsWith("select nickname from kg_app_user"))
                return KgJdbc.p("nickname", "小八");
            throw new AssertionError("Unexpected query contract: " + sql + " " + p);
        });
        when(db.update(anyString(), anyMap())).thenAnswer(i -> {
            String sql = i.getArgument(0);
            if (sql.startsWith("delete from"))
                return deleted;
            return 1;
        });
        when(db.insert(eq("kg_post_comment"), anyMap())).thenReturn(33L);
        when(guard.check(any(String[].class))).thenReturn("pass");
        when(guard.initialStatus(any())).thenReturn("online");
    }

    void conflict(String msg, org.junit.jupiter.api.function.Executable call)
    {
        ServiceException e = assertThrows(ServiceException.class, call);
        assertEquals(409, e.getCode());
        assertEquals(msg, e.getMessage());
    }

    /** 没有新增任何赞 / 收藏 / 评论，计数没加，也没发通知 */
    void nothingAdded()
    {
        verify(db, never()).update(startsWith("insert"), anyMap());
        verify(db, never()).insert(anyString(), anyMap());
        verify(db, never()).update(contains("+ 1"), anyMap());
        verifyNoInteractions(messages);
        verifyNoInteractions(support);
    }

    // ---------------------------------------------------------------- 提示语

    @Test
    void messagesByStatus()
    {
        assertNull(KgPostService.interactBlockedMessage("online"));
        assertEquals(PENDING, KgPostService.interactBlockedMessage("pending"));
        assertEquals(REJECTED, KgPostService.interactBlockedMessage("rejected"));
        assertEquals(HIDDEN, KgPostService.interactBlockedMessage("hidden"));
        assertEquals(HIDDEN, KgPostService.interactBlockedMessage(null), "认不出来的状态按下架处理，不放行");
    }

    // ---------------------------------------------------------------- 点赞

    @Test
    void likePendingPostIs409()
    {
        conflict(PENDING, () -> service.like(11L, 7L));
        nothingAdded();
    }

    @Test
    void likeRejectedPostIs409()
    {
        postStatus = "rejected";
        conflict(REJECTED, () -> service.like(11L, 7L));
        nothingAdded();
    }

    @Test
    void likeHiddenPostIs409()
    {
        postStatus = "hidden";
        conflict(HIDDEN, () -> service.like(11L, 7L));
        nothingAdded();
    }

    @Test
    void unlikePendingPostStillAllowed()
    {
        deleted = 1;
        Map<String, Object> m = service.like(11L, 7L);
        assertEquals(false, m.get("liked"));
        verify(db).update(contains("likes = greatest(likes - 1, 0)"), anyMap());
        verify(messages).retractLike(7L, "post_like", 11L, 7L);
        verify(db, never()).update(startsWith("insert"), anyMap());
        verify(messages, never()).upsertLike(anyLong(), anyString(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void likeOnlinePostStillWorks()
    {
        postStatus = "online";
        assertEquals(true, service.like(11L, 7L).get("liked"));
        verify(db).update(startsWith("insert ignore into kg_post_like"), anyMap());
        verify(db).update(contains("likes = likes + 1"), anyMap());
        verify(messages).upsertLike(eq(7L), eq("post_like"), eq(11L), eq(7L), any(), any());
    }

    // ---------------------------------------------------------------- 收藏

    @Test
    void favoritePendingPostIs409()
    {
        conflict(PENDING, () -> service.favorite(11L, 7L));
        nothingAdded();
    }

    @Test
    void favoriteRejectedPostIs409()
    {
        postStatus = "rejected";
        conflict(REJECTED, () -> service.favorite(11L, 7L));
        nothingAdded();
    }

    @Test
    void unfavoritePendingPostStillAllowed()
    {
        deleted = 1;
        assertEquals(false, service.favorite(11L, 7L).get("favorited"));
        verify(db).update(contains("favorites = greatest(favorites - 1, 0)"), anyMap());
        verify(db, never()).update(startsWith("insert"), anyMap());
    }

    @Test
    void favoriteOnlinePostStillWorks()
    {
        postStatus = "online";
        assertEquals(true, service.favorite(11L, 7L).get("favorited"));
        verify(db).update(contains("favorites = favorites + 1"), anyMap());
    }

    // ---------------------------------------------------------------- 评论

    @Test
    void commentPendingPostIs409()
    {
        conflict(PENDING, () -> service.comment(11L, 8L, null, "去古城怎么走"));
        nothingAdded();
    }

    @Test
    void commentRejectedPostIs409()
    {
        postStatus = "rejected";
        conflict(REJECTED, () -> service.comment(11L, 8L, null, "去古城怎么走"));
        nothingAdded();
    }

    @Test
    void commentHiddenPostIs409()
    {
        postStatus = "hidden";
        conflict(HIDDEN, () -> service.comment(11L, 8L, null, "去古城怎么走"));
        nothingAdded();
    }

    @Test
    void commentOnlinePostStillWorks()
    {
        postStatus = "online";
        assertEquals("visible", service.comment(11L, 8L, null, "去古城怎么走").get("status"));
        verify(db).insert(eq("kg_post_comment"), anyMap());
        verify(db).update(contains("comments = comments + 1"), anyMap());
    }

    // ---------------------------------------------------------------- 评论点赞（帖子没发布时评论区同样不能新点赞）

    @Test
    void likeCommentOnPendingPostIs409()
    {
        conflict(PENDING, () -> service.likeComment(22L, 8L));
        nothingAdded();
    }

    @Test
    void unlikeCommentOnPendingPostStillAllowed()
    {
        deleted = 1;
        assertEquals(false, service.likeComment(22L, 8L).get("liked"));
        verify(messages).retractLike(4L, "comment_like", 22L, 8L);
        verify(db, never()).update(startsWith("insert"), anyMap());
    }

    @Test
    void likeCommentOnOnlinePostStillWorks()
    {
        postStatus = "online";
        assertEquals(true, service.likeComment(22L, 8L).get("liked"));
        verify(db).update(contains("likes = likes + 1"), anyMap());
        verify(messages).upsertLike(eq(4L), eq("comment_like"), eq(22L), eq(8L), any(), any());
    }
}
