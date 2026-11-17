package com.ruoyi.kangaroo.ext.community;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.KgCityService;

/**
 * 每个人看到的评论数（2026-10-15）：有拉黑关系时，详情角标 / 评论区总数 / 列表卡片只数我看得到的评论；
 * 2026-10-16 起和评论区一样，我自己待审核的评论也算。没登录时用 kg_post.comments，不多查。
 * 列表（信息流等）一页只查一次点赞、一次评论数（分组），不再每张卡片各查。真实 SQL 结果由 MySQL 探针另证。
 */
class KgPostCommentCountTest
{
    static final String VISIBLE_COUNT = "select count(*) from kg_post_comment c where c.post_id = :p";

    static final String PAGE_COUNT = "select c.post_id, count(*) n from kg_post_comment c where c.post_id in (:ids) and ";

    static final String PAGE_LIKED = "select post_id from kg_post_like where app_user_id = :me and post_id in (:ids)";

    static final String ONE_LIKED = "select count(*) from kg_post_like where post_id = :p and app_user_id = :u";

    static final String HAS_BLOCKS = "select count(*) from (select 1 from kg_user_block where user_id = :me or blocked_id = :me limit 1) x";

    /** 评论区口径：可见或我自己待审核的、没删、评论人和我没拉黑；回复还要挂在同样看得到的一级评论下 */
    static final String SEEN_C = "(c.status = 'visible' or (c.status = 'pending' and c.app_user_id = :me)) and c.del_flag = '0' and "
            + "not exists (select 1 from kg_user_block b where (b.user_id = :me and b.blocked_id = c.app_user_id) or (b.user_id = c.app_user_id and b.blocked_id = :me))";
    static final String SEEN_PARENT = "(c.parent_id = 0 or exists (select 1 from kg_post_comment pc where pc.comment_id = c.parent_id and "
            + "(pc.status = 'visible' or (pc.status = 'pending' and pc.app_user_id = :me)) and pc.del_flag = '0' and "
            + "not exists (select 1 from kg_user_block b where (b.user_id = :me and b.blocked_id = pc.app_user_id) or (b.user_id = pc.app_user_id and b.blocked_id = :me))))";

    final KgJdbc db = mock(KgJdbc.class);
    final KgActivityService activities = mock(KgActivityService.class);
    final KgCityService cities = mock(KgCityService.class);
    final KgPostService posts = new KgPostService();

    KgPostCommentCountTest()
    {
        ReflectionTestUtils.setField(posts, "db", db);
        ReflectionTestUtils.setField(posts, "activities", activities);
        ReflectionTestUtils.setField(posts, "cities", cities);
        when(cities.businessCity(any())).thenAnswer(i -> i.getArgument(0));
        when(db.one(startsWith("select p.* from kg_post p"), anyMap())).thenReturn(post(11L, 5L));
        when(db.count(startsWith(VISIBLE_COUNT), anyMap())).thenReturn(3L);
    }

    static Map<String, Object> post(long id, long comments)
    {
        return p("postId", id, "appUserId", 3L, "status", "online", "city", "大理", "title", "大理三天", "content", "正文", "comments", comments);
    }

    @Test
    void detailShowsTheCountThisViewerCanSeeIncludingMyPendingComments()
    {
        assertEquals(3L, posts.detail(11L, 7L).get("comments"));
        verify(db).count(eq(VISIBLE_COUNT + " and " + SEEN_C + " and " + SEEN_PARENT), eq(p("p", 11L, "me", 7L)));
    }

    /** 2026-10-16：评论区里我看得到自己待审核的评论，角标也要算上，否则点进去多一条 */
    @Test
    void myPendingCommentsCountLikeTheCommentListShowsThem()
    {
        // 和 comments() 列评论的条件同一口径
        assertTrue(KgPostService.COMMENT_VISIBLE_TO_ME.contains("(c.status = 'visible' or (c.status = 'pending' and c.app_user_id = :me))"));
        assertTrue(KgPostService.COMMENT_VISIBLE_TO_ME.contains("(pc.status = 'visible' or (pc.status = 'pending' and pc.app_user_id = :me))"));
        // 存的是 0（可见评论一条没有），只有我自己待审核的一条：也要数，不能直接回 0
        when(db.one(startsWith("select p.* from kg_post p"), anyMap())).thenReturn(post(11L, 0L));
        when(db.count(startsWith(VISIBLE_COUNT), anyMap())).thenReturn(1L);
        assertEquals(1L, posts.detail(11L, 7L).get("comments"));
        when(db.list(anyString(), anyMap())).thenReturn(Collections.<Map<String, Object>>emptyList());
        assertEquals(1L, posts.comments(11L, 7L, "new").get("total"));
    }

    @Test
    void commentsTotalShowsTheCountThisViewerCanSee()
    {
        when(db.list(anyString(), anyMap())).thenReturn(Collections.<Map<String, Object>>emptyList());
        assertEquals(3L, posts.comments(11L, 7L, "new").get("total"));
        verify(db).count(startsWith(VISIBLE_COUNT), eq(p("p", 11L, "me", 7L)));
    }

    @Test
    void anonymousViewerGetsTheStoredCountWithoutAnExtraQuery()
    {
        assertEquals(5L, posts.detail(11L, null).get("comments"));
        when(db.list(anyString(), anyMap())).thenReturn(Collections.<Map<String, Object>>emptyList());
        assertEquals(5L, posts.comments(11L, null, "new").get("total"));
        verify(db, never()).count(startsWith(VISIBLE_COUNT), anyMap());
        verify(db, never()).count(startsWith(HAS_BLOCKS), anyMap());
    }

    @Test
    void detailStillChecksLikedForTheSinglePost()
    {
        when(db.count(eq(ONE_LIKED), anyMap())).thenReturn(1L);
        assertEquals(true, posts.detail(11L, 7L).get("liked"));
        verify(db).count(eq(ONE_LIKED), eq(p("p", 11L, "u", 7L)));
        verify(db, never()).list(eq(PAGE_LIKED), anyMap());
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> feed(Long me)
    {
        return (List<Map<String, Object>>) posts.feed(me, null, null, "new", 1, null).get("rows");
    }

    /** 一页 3 张卡片：点赞、评论数各查一次（之前每张卡片各查一次点赞，有拉黑关系时再各数一次评论） */
    @Test
    void feedChecksLikesAndCommentCountsOncePerPage()
    {
        when(db.list(startsWith("select p.* from kg_post p"), anyMap())).thenReturn(Arrays.asList(post(11L, 5L), post(12L, 2L), post(13L, 0L)));
        when(db.list(eq(PAGE_LIKED), anyMap())).thenReturn(Collections.singletonList(p("postId", 12L)));
        // 我和谁都没有拉黑记录：只数我自己待审核的（13 上有一条），加到存的数上
        when(db.list(startsWith(PAGE_COUNT), anyMap())).thenReturn(Collections.singletonList(p("postId", 13L, "n", 1L)));
        List<Map<String, Object>> rows = feed(7L);
        assertEquals(Arrays.asList(false, true, false), Arrays.asList(rows.get(0).get("liked"), rows.get(1).get("liked"), rows.get(2).get("liked")));
        assertEquals(Arrays.asList(5L, 2L, 1L), Arrays.asList(rows.get(0).get("comments"), rows.get(1).get("comments"), rows.get(2).get("comments")));
        verify(db).list(eq(PAGE_LIKED), eq(p("me", 7L, "ids", Arrays.asList(11L, 12L, 13L))));
        verify(db).list(argThat(q -> q.startsWith(PAGE_COUNT) && q.contains("c.app_user_id = :me and c.status = 'pending' and c.del_flag = '0'")
                && q.endsWith(" group by c.post_id")), eq(p("me", 7L, "ids", Arrays.asList(11L, 12L, 13L))));
        verify(db, times(1)).list(startsWith(PAGE_COUNT), anyMap());
        verify(db, never()).count(eq(ONE_LIKED), anyMap());
        verify(db, never()).count(startsWith(VISIBLE_COUNT), anyMap());
        verify(db, times(1)).count(eq(HAS_BLOCKS), eq(p("me", 7L)));
    }

    @Test
    void feedWithBlockRowsCountsWhatThisViewerCanSeeInOneGroupedQuery()
    {
        when(db.list(startsWith("select p.* from kg_post p"), anyMap())).thenReturn(Arrays.asList(post(11L, 5L), post(12L, 2L)));
        when(db.count(eq(HAS_BLOCKS), anyMap())).thenReturn(1L);
        // 12 上我一条也看不到：分组结果里没有它，就是 0
        when(db.list(startsWith(PAGE_COUNT), anyMap())).thenReturn(Collections.singletonList(p("postId", 11L, "n", 3L)));
        List<Map<String, Object>> rows = feed(7L);
        assertEquals(3L, rows.get(0).get("comments"));
        assertEquals(0L, rows.get(1).get("comments"));
        verify(db).list(eq(PAGE_COUNT + SEEN_C + " and " + SEEN_PARENT + " group by c.post_id"), eq(p("me", 7L, "ids", Arrays.asList(11L, 12L))));
        verify(db, never()).count(startsWith(VISIBLE_COUNT), anyMap());
    }

    @Test
    void anonymousFeedUsesStoredCountsAndNoViewerQueries()
    {
        when(db.list(startsWith("select p.* from kg_post p"), anyMap())).thenReturn(Collections.singletonList(post(11L, 5L)));
        List<Map<String, Object>> rows = feed(null);
        assertEquals(5L, rows.get(0).get("comments"));
        assertEquals(false, rows.get(0).get("liked"));
        verify(db, never()).count(startsWith(HAS_BLOCKS), anyMap());
        verify(db, never()).list(startsWith(PAGE_COUNT), anyMap());
        verify(db, never()).list(eq(PAGE_LIKED), anyMap());
        // 空页：一次也不多查
        reset(db);
        when(db.list(startsWith("select p.* from kg_post p"), anyMap())).thenReturn(Collections.<Map<String, Object>>emptyList());
        assertTrue(feed(7L).isEmpty());
        verify(db, never()).count(startsWith(HAS_BLOCKS), anyMap());
        verify(db, never()).list(eq(PAGE_LIKED), anyMap());
    }
}
