package com.ruoyi.kangaroo.ext.community;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.controller.app.AppCommunityController;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.ext.user.KgSubscribeService;
import com.ruoyi.kangaroo.mapper.KgMessageMapper;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppRateLimiter;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.service.IKgMessageService;
import com.ruoyi.kangaroo.service.impl.KgMessageServiceImpl;

/**
 * 社区的评论 / 点赞通知（2026-10-15 消息页改版，主人第 12 条「为啥没有点赞和评论」）。
 * 真实的 KgPostService / KgMessageServiceImpl 方法 + Mock JDBC：证明发给谁、标题怎么写、点赞怎么合并成一行、什么时候不发。
 * 并发点赞只插一行、互动通知随删评论 / 隐藏动态 / 拉黑消失，由 mapper/KgInboxMysqlIT 在一次性 MySQL 库上证明。
 */
class KgInteractionNoticeTest
{
    static final String ROUTE = "/pages/community/index?postId=11&view=comments";

    final KgJdbc db = mock(KgJdbc.class);
    final KgCommunityGuard guard = mock(KgCommunityGuard.class);
    final StaffSupport support = mock(StaffSupport.class);
    final IKgMessageService messages = mock(IKgMessageService.class);
    final KgPostService posts = new KgPostService();

    KgInteractionNoticeTest()
    {
        ReflectionTestUtils.setField(posts, "db", db);
        ReflectionTestUtils.setField(posts, "guard", guard);
        ReflectionTestUtils.setField(posts, "support", support);
        ReflectionTestUtils.setField(posts, "messages", messages);
        when(db.one(startsWith("select status, del_flag from kg_app_user"), anyMap())).thenReturn(p("status", "0", "delFlag", "0"));
        when(db.one(startsWith("select nickname from kg_app_user"), anyMap())).thenAnswer(i -> {
            Object u = ((Map<?, ?>) i.getArgument(1)).get("u");
            return Long.valueOf(7L).equals(u) ? p("nickname", "小七") : Long.valueOf(4L).equals(u) ? p("nickname", "阿四") : p("nickname", "");
        });
        when(db.update(anyString(), anyMap())).thenReturn(1);
        when(db.insert(eq("kg_post_comment"), anyMap())).thenReturn(33L);
        when(db.one(startsWith("select likes from kg_post"), anyMap())).thenReturn(p("likes", 1L));
        when(guard.check(any(String[].class))).thenReturn("pass");
        when(guard.initialStatus(any())).thenReturn("online");
    }

    @AfterEach
    void clear()
    {
        AppUserContext.remove();
    }

    /** 动态 11，作者 author */
    void post(long author)
    {
        when(db.one(startsWith("select p.* from kg_post"), anyMap()))
                .thenReturn(p("postId", 11L, "appUserId", author, "status", "online", "title", "大理三天", "content", "正文"));
    }

    /** 被回复的评论 22，评论人 owner */
    void parent(long owner)
    {
        when(db.one(startsWith("select comment_id, parent_id, app_user_id"), anyMap())).thenReturn(p("commentId", 22L, "parentId", 0L, "appUserId", owner));
    }

    void sent(long to, String title)
    {
        verify(messages).send(to, "user", "comment", title, "去古城怎么走", "comment", 33L, ROUTE, 7L);
    }

    void sentTimes(int n)
    {
        verify(messages, times(n)).send(anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString(), anyLong());
        verifyNoInteractions(support);
    }

    // ---------------------------------------------------------------- 评论：发给谁

    @Test
    void topLevelCommentNotifiesOnlyTheAuthor()
    {
        post(3L);
        posts.comment(11L, 7L, null, "去古城怎么走");
        sent(3L, "小七 评论了你的动态");
        sentTimes(1);
    }

    @Test
    void replyToTheAuthorNotifiesTheAuthorOnceAsAReply()
    {
        post(3L);
        parent(3L);
        posts.comment(11L, 7L, 22L, "去古城怎么走");
        sent(3L, "小七 回复了你的评论");
        sentTimes(1);
    }

    @Test
    void replyToSomeoneElseNotifiesBothThatPersonAndTheAuthor()
    {
        post(3L);
        parent(4L);
        posts.comment(11L, 7L, 22L, "去古城怎么走");
        sent(4L, "小七 回复了你的评论");
        sent(3L, "小七 评论了你的动态");
        sentTimes(2);
    }

    @Test
    void nobodyIsNotifiedAboutTheirOwnActions()
    {
        // 作者评论自己的动态
        post(7L);
        posts.comment(11L, 7L, null, "去古城怎么走");
        sentTimes(0);
        // 作者回复自己的评论
        parent(7L);
        posts.comment(11L, 7L, 22L, "去古城怎么走");
        sentTimes(0);
        // 作者回复别人：只通知被回复的人
        parent(4L);
        posts.comment(11L, 7L, 22L, "去古城怎么走");
        sent(4L, "小七 回复了你的评论");
        sentTimes(1);
    }

    @Test
    void replyingToMyOwnCommentOnSomeoneElsesPostOnlyTellsTheAuthor()
    {
        post(3L);
        parent(7L);
        posts.comment(11L, 7L, 22L, "去古城怎么走");
        sent(3L, "小七 评论了你的动态");
        sentTimes(1);
    }

    @Test
    void pendingCommentWaitsForModeration()
    {
        post(3L);
        when(guard.initialStatus(any())).thenReturn("pending");
        assertEquals("pending", posts.comment(11L, 7L, null, "去古城怎么走").get("status"));
        verifyNoInteractions(messages);
    }

    @Test
    void approvedReplyNotifiesRepliedUserAndAuthorWithTheCommenterAsActor()
    {
        when(db.must(startsWith("select comment_id, post_id, app_user_id"), anyMap(), anyString()))
                .thenReturn(p("commentId", 33L, "postId", 11L, "appUserId", 7L, "replyUserId", 4L, "content", "去古城怎么走", "status", "pending", "handleNote", null));
        when(db.one(startsWith("select app_user_id from kg_post"), anyMap())).thenReturn(p("appUserId", 3L));
        posts.auditComment(33L, true, "通过", "admin");
        sent(4L, "小七 回复了你的评论");
        sent(3L, "小七 评论了你的动态");
        // 审核结果仍是系统通知
        verify(support).notifyUser(eq(7L), eq("system"), eq("你的评论已通过审核"), eq("去古城怎么走"), eq("post"), eq(11L), anyString());
    }

    @Test
    void missingNicknameFallsBackToUserId()
    {
        post(3L);
        when(db.one(startsWith("select nickname from kg_app_user"), anyMap())).thenReturn(null);
        posts.comment(11L, 7L, null, "去古城怎么走");
        sent(3L, "用户7 评论了你的动态");
    }

    // ---------------------------------------------------------------- 点赞：在计数更新之后合并通知

    @Test
    void likeWritesTheMergedNoticeAfterTheCounterUpdate()
    {
        post(3L);
        assertEquals(true, posts.like(11L, 7L).get("liked"));
        InOrder o = inOrder(db, messages);
        o.verify(db).update(eq("update kg_post set likes = likes + 1 where post_id = :p"), eq(p("p", 11L)));
        o.verify(messages).upsertLike(3L, "post_like", 11L, 7L, "大理三天", ROUTE);
        verify(messages, never()).retractLike(anyLong(), anyString(), anyLong(), anyLong());
        verify(messages, never()).send(anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString(), anyLong());
    }

    @Test
    void unlikeRetractsOnlyWhenALikeWasRemoved()
    {
        post(3L);
        when(db.update(startsWith("insert ignore into kg_post_like"), anyMap())).thenReturn(0);
        assertEquals(false, posts.like(11L, 7L).get("liked"));
        InOrder o = inOrder(db, messages);
        o.verify(db).update(startsWith("update kg_post set likes = greatest(likes - 1, 0)"), anyMap());
        o.verify(messages).retractLike(3L, "post_like", 11L, 7L);
        // 没有赞可删（并发下已被删掉）：不动通知
        reset(messages);
        when(db.update(startsWith("delete from kg_post_like"), anyMap())).thenReturn(0);
        posts.like(11L, 7L);
        verifyNoInteractions(messages);
    }

    @Test
    void postWithoutTitleUsesTheFirst30Characters()
    {
        when(db.one(startsWith("select p.* from kg_post"), anyMap()))
                .thenReturn(p("postId", 11L, "appUserId", 3L, "status", "online", "title", "", "content", "一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十多出来的"));
        posts.like(11L, 7L);
        verify(messages).upsertLike(eq(3L), eq("post_like"), eq(11L), eq(7L), eq("一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十"), eq(ROUTE));
    }

    @Test
    void likeTogglesRunReadCommittedSoTheNextLikerSeesTheMergedRow() throws Exception
    {
        for (String name : new String[] { "like", "likeComment" })
        {
            Method m = KgPostService.class.getMethod(name, Long.class, Long.class);
            assertEquals(Isolation.READ_COMMITTED, m.getAnnotation(Transactional.class).isolation(), name);
        }
    }

    // ---------------------------------------------------------------- 点赞合并（KgMessageServiceImpl）

    final KgJdbc jdbc = mock(KgJdbc.class);
    final KgSubscribeService subscribe = mock(KgSubscribeService.class);
    final KgMessageMapper mapper = mock(KgMessageMapper.class);

    KgMessageServiceImpl likes(Map<String, Object> row, long count)
    {
        KgMessageServiceImpl s = new KgMessageServiceImpl();
        ReflectionTestUtils.setField(s, "jdbc", jdbc);
        ReflectionTestUtils.setField(s, "subscribe", subscribe);
        ReflectionTestUtils.setField(s, "kgMessageMapper", mapper);
        when(jdbc.one(startsWith("select msg_id, read_flag, read_time from kg_message"), anyMap())).thenReturn(row);
        when(jdbc.count(startsWith("select count(*) from kg_post"), anyMap())).thenReturn(count);
        when(jdbc.one(startsWith("select nickname from kg_app_user"), anyMap())).thenAnswer(i -> {
            Object u = ((Map<?, ?>) i.getArgument(1)).get("u");
            return Long.valueOf(7L).equals(u) ? p("nickname", "晨光") : p("nickname", "阿四");
        });
        return s;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> written(String sqlStart)
    {
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        verify(jdbc).update(startsWith(sqlStart), args.capture());
        return args.getValue();
    }

    @Test
    void firstLikeInsertsOneRowWithoutWechatPush()
    {
        likes(null, 1L).upsertLike(3L, "post_like", 11L, 7L, "大理三天", ROUTE);
        Map<String, Object> v = written("insert into kg_message (recipient_id, mode, category, title, content, biz_type, biz_id, actor_id, route, read_flag, del_flag, create_time) "
                + "values (:r, 'user', 'like', :title, :c, :t, :b, :a, :route, '0', '0', now())");
        assertEquals("晨光 赞了你的动态", v.get("title"));
        assertEquals(3L, v.get("r"));
        assertEquals(7L, v.get("a"));
        assertEquals("post_like", v.get("t"));
        verifyNoInteractions(subscribe);
        verifyNoInteractions(mapper);
        // 第一行没有上次读的时间：since 为空 = 从头算；刚点赞的人一定算上
        verify(jdbc).count(contains("from kg_post_like l where l.post_id = :b and l.app_user_id <> :r and (:since is null or l.create_time > :since or l.app_user_id = :a)"),
                argThat(m -> m.get("since") == null && Long.valueOf(7L).equals(m.get("a"))));
    }

    @Test
    void laterLikesMergeIntoTheSameRowCountingSinceItWasRead()
    {
        likes(p("msgId", 900L, "readFlag", "1", "readTime", "2026-10-15 09:00:00"), 3L).upsertLike(3L, "post_like", 11L, 7L, "大理三天", ROUTE);
        Map<String, Object> v = written("update kg_message set category = 'like', title = :title, content = :c, actor_id = :a, route = :route, read_flag = '0', del_flag = '0', create_time = now() where msg_id = :id");
        assertEquals("晨光 等 3 人赞了你的动态", v.get("title"));
        assertEquals(900L, v.get("id"));
        // read_time 不在 set 里：它是这一轮的起点
        verify(jdbc, never()).update(contains("read_time"), anyMap());
        verify(jdbc).count(anyString(), argThat(m -> "2026-10-15 09:00:00".equals(m.get("since"))));
        verify(jdbc, never()).update(startsWith("insert"), anyMap());
        // 拉黑关系的人不算
        verify(jdbc).count(contains("not exists (select 1 from kg_user_block k where (k.user_id = :r and k.blocked_id = l.app_user_id) or (k.user_id = l.app_user_id and k.blocked_id = :r))"), anyMap());
    }

    @Test
    void commentLikesSayComment()
    {
        likes(null, 2L).upsertLike(4L, "comment_like", 22L, 7L, "怎么去古城", ROUTE);
        assertEquals("晨光 等 2 人赞了你的评论", written("insert into kg_message").get("title"));
        verify(jdbc).count(contains("from kg_post_comment_like l where l.comment_id = :b"), anyMap());
    }

    @Test
    void selfLikesUnknownTargetsAndPausedUsersWriteNothing()
    {
        KgMessageServiceImpl s = likes(null, 1L);
        s.upsertLike(7L, "post_like", 11L, 7L, "x", ROUTE);
        s.upsertLike(3L, "post", 11L, 7L, "x", ROUTE);
        s.retractLike(7L, "post_like", 11L, 7L);
        verifyNoInteractions(jdbc);
        // 收件人在「我的提醒」暂停了社区互动
        when(jdbc.one(startsWith("select enabled from kg_notify_pref"), anyMap())).thenReturn(p("enabled", "0"));
        s.upsertLike(3L, "comment_like", 22L, 7L, "x", ROUTE);
        s.retractLike(3L, "comment_like", 22L, 7L);
        verify(jdbc, times(2)).one(startsWith("select enabled from kg_notify_pref"), argThat(m -> "community".equals(m.get("k"))));
        verify(jdbc, never()).update(anyString(), anyMap());
    }

    @Test
    void retractOnlyTouchesAnUnreadRow()
    {
        likes(p("msgId", 900L, "readFlag", "1", "readTime", "2026-10-15 09:00:00"), 2L).retractLike(3L, "post_like", 11L, 7L);
        verify(jdbc, never()).update(anyString(), anyMap());
        verify(jdbc).one(contains("and biz_id = :b and del_flag = '0' order by msg_id desc limit 1"), anyMap());
    }

    @Test
    void retractToZeroDeletesTheRow()
    {
        likes(p("msgId", 900L, "readFlag", "0", "readTime", null), 0L).retractLike(3L, "post_like", 11L, 7L);
        assertEquals(900L, written("update kg_message set del_flag = '2' where msg_id = :id").get("id"));
    }

    @Test
    void retractKeepsTheTimeAndNamesTheLatestRemainingLiker()
    {
        KgMessageServiceImpl s = likes(p("msgId", 900L, "readFlag", "0", "readTime", "2026-10-15 09:00:00"), 2L);
        when(jdbc.one(startsWith("select l.app_user_id from kg_post_like l"), anyMap())).thenReturn(p("appUserId", 4L));
        s.retractLike(3L, "post_like", 11L, 7L);
        Map<String, Object> v = written("update kg_message set title = :title, actor_id = :a where msg_id = :id");
        assertEquals("阿四 等 2 人赞了你的动态", v.get("title"));
        assertEquals(4L, v.get("a"));
        verify(jdbc).one(contains("order by l.create_time desc, l.like_id desc limit 1"), anyMap());
        verify(jdbc, never()).update(contains("create_time"), anyMap());
    }

    static final String ACTIVE_LIKER = "and exists (select 1 from kg_app_user au where au.user_id = l.app_user_id and au.status = '0' and au.del_flag = '0')";

    @Test
    void onlyActiveAccountsCountAsLikers()
    {
        // 合并点赞：人数只数还在用的账号
        likes(null, 2L).upsertLike(3L, "post_like", 11L, 7L, "大理三天", ROUTE);
        verify(jdbc).count(argThat(q -> q.startsWith("select count(*) from kg_post_like l where l.post_id = :b") && q.contains(ACTIVE_LIKER)), anyMap());
        // 取消点赞：人数和「最近点赞的人」都只看还在用的账号
        reset(jdbc);
        KgMessageServiceImpl s = likes(p("msgId", 900L, "readFlag", "0", "readTime", null), 2L);
        when(jdbc.one(startsWith("select l.app_user_id from kg_post_comment_like l"), anyMap())).thenReturn(p("appUserId", 4L));
        when(jdbc.count(startsWith("select count(*) from kg_post_comment_like"), anyMap())).thenReturn(2L);
        s.retractLike(3L, "comment_like", 22L, 7L);
        verify(jdbc).count(argThat(q -> q.startsWith("select count(*) from kg_post_comment_like l where l.comment_id = :b") && q.contains(ACTIVE_LIKER)), anyMap());
        verify(jdbc).one(argThat(q -> q.startsWith("select l.app_user_id from kg_post_comment_like l where l.comment_id = :b") && q.contains(ACTIVE_LIKER)
                && q.endsWith(" order by l.create_time desc, l.id desc limit 1")), anyMap());
    }

    static final String RELINK_ROWS = "select msg_id, recipient_id, biz_type, biz_id, read_time from kg_message where actor_id = :u";

    @Test
    void relinkRepointsAnUnreadMergedRowToTheLatestRemainingLiker()
    {
        KgMessageServiceImpl s = likes(null, 2L);
        when(jdbc.list(startsWith(RELINK_ROWS), anyMap())).thenReturn(java.util.Collections.singletonList(
                p("msgId", 900L, "recipientId", 3L, "bizType", "post_like", "bizId", 11L, "readTime", "2026-10-15 09:00:00")));
        when(jdbc.one(startsWith("select l.app_user_id from kg_post_like l"), anyMap())).thenReturn(p("appUserId", 4L));
        s.relinkLikesOf(7L);
        Map<String, Object> v = written("update kg_message set title = :title, actor_id = :a where msg_id = :id");
        assertEquals("阿四 等 2 人赞了你的动态", v.get("title"));
        assertEquals(4L, v.get("a"));
        assertEquals(900L, v.get("id"));
        // 这一轮的起点和收件人都用这一行自己的
        verify(jdbc).count(anyString(), argThat(m -> "2026-10-15 09:00:00".equals(m.get("since")) && Long.valueOf(3L).equals(m.get("r")) && Long.valueOf(11L).equals(m.get("b"))));
        // 时间不动、不删
        verify(jdbc, never()).update(contains("create_time"), anyMap());
        verify(jdbc, never()).update(contains("del_flag"), anyMap());
        // 不受「我的提醒」开关影响：注销清理不能因为对方暂停了社区互动就把本人留在通知上
        verify(jdbc, never()).one(startsWith("select enabled from kg_notify_pref"), anyMap());
    }

    @Test
    void relinkDeletesTheRowWhenNobodyIsLeft()
    {
        KgMessageServiceImpl s = likes(null, 0L);
        when(jdbc.list(startsWith(RELINK_ROWS), anyMap())).thenReturn(java.util.Collections.singletonList(
                p("msgId", 901L, "recipientId", 4L, "bizType", "comment_like", "bizId", 22L, "readTime", null)));
        when(jdbc.count(startsWith("select count(*) from kg_post_comment_like"), anyMap())).thenReturn(0L);
        s.relinkLikesOf(7L);
        assertEquals(901L, written("update kg_message set del_flag = '2' where msg_id = :id").get("id"));
        verify(jdbc, never()).update(startsWith("update kg_message set title"), anyMap());
    }

    @Test
    void relinkOnlyLooksAtUnreadLikeRowsOfThatActor()
    {
        KgMessageServiceImpl s = likes(null, 2L);
        // 读过的行不在查询结果里（read_flag = '0'），什么也不改
        s.relinkLikesOf(7L);
        verify(jdbc).list(argThat(q -> q.startsWith(RELINK_ROWS) && q.contains("biz_type in ('post_like', 'comment_like')")
                && q.contains("read_flag = '0'") && q.contains("del_flag = '0'")), eq(p("u", 7L)));
        verify(jdbc, never()).update(anyString(), anyMap());
        verify(jdbc, never()).count(anyString(), anyMap());
        // 空参数什么也不查
        reset(jdbc);
        s.relinkLikesOf(null);
        verifyNoInteractions(jdbc);
    }

    // ---------------------------------------------------------------- 拉黑后改指合并的点赞通知（2026-10-16）

    static final String BETWEEN_ROWS = "select msg_id, recipient_id, biz_type, biz_id, read_time from kg_message where recipient_id = :r and actor_id = :a";

    /** 「阿四 等 2 人赞了你的动态」最近点赞的人被拉黑：改指剩下的人（点赞人口径本来就不算有拉黑关系的人） */
    @Test
    void relinkBetweenRepointsTheRowOfThisRecipientAwayFromTheBlockedLiker()
    {
        KgMessageServiceImpl s = likes(null, 1L);
        when(jdbc.list(startsWith(BETWEEN_ROWS), anyMap())).thenReturn(java.util.Collections.singletonList(
                p("msgId", 902L, "recipientId", 3L, "bizType", "post_like", "bizId", 11L, "readTime", null)));
        when(jdbc.one(startsWith("select l.app_user_id from kg_post_like l"), anyMap())).thenReturn(p("appUserId", 7L));
        s.relinkLikesBetween(3L, 4L);
        verify(jdbc).list(argThat(q -> q.startsWith(BETWEEN_ROWS) && q.contains("biz_type in ('post_like', 'comment_like')")
                && q.contains("read_flag = '0'") && q.contains("del_flag = '0'")), eq(p("r", 3L, "a", 4L)));
        // 数剩下的人时不算和收件人有拉黑关系的、不算停用注销的
        verify(jdbc).count(argThat(q -> q.contains("from kg_post_like l where l.post_id = :b and l.app_user_id <> :r")
                && q.contains("not exists (select 1 from kg_user_block k where (k.user_id = :r and k.blocked_id = l.app_user_id) or (k.user_id = l.app_user_id and k.blocked_id = :r))")
                && q.contains("au.status = '0' and au.del_flag = '0'")), anyMap());
        Map<String, Object> v = written("update kg_message set title = :title, actor_id = :a where msg_id = :id");
        assertEquals("晨光 赞了你的动态", v.get("title"));
        assertEquals(7L, v.get("a"));
        verify(jdbc, never()).update(contains("create_time"), anyMap());
    }

    @Test
    void relinkBetweenDeletesTheRowWhenOnlyTheBlockedLikerWasLeft()
    {
        KgMessageServiceImpl s = likes(null, 0L);
        when(jdbc.list(startsWith(BETWEEN_ROWS), anyMap())).thenReturn(java.util.Collections.singletonList(
                p("msgId", 903L, "recipientId", 3L, "bizType", "comment_like", "bizId", 22L, "readTime", null)));
        when(jdbc.count(startsWith("select count(*) from kg_post_comment_like"), anyMap())).thenReturn(0L);
        s.relinkLikesBetween(3L, 4L);
        assertEquals(903L, written("update kg_message set del_flag = '2' where msg_id = :id").get("id"));
        // 空参数、自己和自己：什么也不查
        reset(jdbc);
        s.relinkLikesBetween(null, 4L);
        s.relinkLikesBetween(3L, null);
        s.relinkLikesBetween(3L, 3L);
        verifyNoInteractions(jdbc);
    }

    /** 拉黑：写入拉黑记录之后，双方各自收到的、由对方触发的未读点赞合并行都改指；取消拉黑不动 */
    @Test
    void blockRelinksMergedLikesBetweenThePairAfterWritingTheBlock()
    {
        when(db.update(startsWith("delete from kg_user_block"), anyMap())).thenReturn(0);
        assertEquals(true, posts.block(4L, 3L).get("blocked"));
        org.mockito.InOrder order = inOrder(db, messages);
        order.verify(db).update(startsWith("insert ignore into kg_user_block"), eq(p("m", 3L, "t", 4L)));
        order.verify(messages).relinkLikesBetween(3L, 4L);
        order.verify(messages).relinkLikesBetween(4L, 3L);
        // 取消拉黑
        reset(messages);
        when(db.update(startsWith("delete from kg_user_block"), anyMap())).thenReturn(1);
        assertEquals(false, posts.block(4L, 3L).get("blocked"));
        verifyNoInteractions(messages);
    }

    @Test
    void commentsAndLikesFollowTheCommunitySwitch()
    {
        for (String[] c : new String[][] { { "like", "post_like" }, { "like", "comment_like" }, { "comment", "comment" }, { "comment", "post" } })
        {
            assertEquals("community", ReflectionTestUtils.invokeMethod(KgMessageServiceImpl.class, "prefKeyOf", c[0], c[1]), c[0] + "/" + c[1]);
        }
    }

    @Test
    void sendKeepsTheActor()
    {
        KgMessageServiceImpl s = likes(null, 0L);
        when(jdbc.one(startsWith("select enabled from kg_notify_pref"), anyMap())).thenReturn(null);
        s.send(3L, "user", "comment", "小七 评论了你的动态", "去古城怎么走", "comment", 33L, ROUTE, 7L);
        ArgumentCaptor<com.ruoyi.kangaroo.domain.KgMessage> m = ArgumentCaptor.forClass(com.ruoyi.kangaroo.domain.KgMessage.class);
        verify(mapper).insertKgMessage(m.capture());
        assertEquals(7L, m.getValue().getActorId());
        assertEquals("comment", m.getValue().getCategory());
        // 评论照旧走微信订阅消息（社区互动）
        verify(subscribe).push(eq(3L), eq("community"), eq("小七 评论了你的动态"), eq("去古城怎么走"), eq(ROUTE), any());
        // 老入口不带触发人
        s.send(3L, "user", "system", "t", "c", "job", 1L, "/r");
        verify(mapper, times(2)).insertKgMessage(m.capture());
        assertNull(m.getValue().getActorId());
    }

    // ---------------------------------------------------------------- 点赞限频

    @Test
    void likeEndpointsAreRateLimitedPerAccount()
    {
        AppCommunityController c = new AppCommunityController();
        AppRateLimiter limiter = mock(AppRateLimiter.class);
        KgPostService svc = mock(KgPostService.class);
        ReflectionTestUtils.setField(c, "limiter", limiter);
        ReflectionTestUtils.setField(c, "posts", svc);
        AppLoginUser me = new AppLoginUser();
        me.setUserId(7L);
        AppUserContext.set(me);
        c.like(11L);
        c.likeComment(22L);
        verify(limiter, times(2)).check("community:like", "7", 60, 60, "操作太频繁了，稍后再试");
        verify(svc).like(11L, 7L);
        verify(svc).likeComment(22L, 7L);
        doThrow(new ServiceException("操作太频繁了，稍后再试", 429)).when(limiter).check(eq("community:like"), anyString(), anyInt(), anyInt(), anyString());
        assertEquals(429, assertThrows(ServiceException.class, () -> c.like(11L)).getCode());
        assertEquals(429, assertThrows(ServiceException.class, () -> c.likeComment(22L)).getCode());
        verifyNoMoreInteractions(svc);
    }
}
