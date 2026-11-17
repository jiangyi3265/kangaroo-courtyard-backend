package com.ruoyi.kangaroo.ext.community;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.KgCityService;

/** Real KgPostService body, Mock JDBC (no real SQL).
 * 社区内测建议（2026-10-11）：信息流「已关注」、旅居日常「伙伴说」、用户个人主页、我关注的人、我收藏的帖子。 */
class KgPostFollowProfileTest {
    final KgJdbc db = mock(KgJdbc.class);
    final KgCommunityGuard guard = mock(KgCommunityGuard.class);
    final KgCityService cities = mock(KgCityService.class);
    final KgActivityService activities = mock(KgActivityService.class);
    final KgPostService service = new KgPostService();

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "guard", guard);
        ReflectionTestUtils.setField(service, "cities", cities);
        ReflectionTestUtils.setField(service, "activities", activities);
        when(db.one(startsWith("select status, del_flag from kg_app_user"), anyMap())).thenReturn(p("status", "0", "delFlag", "0"));
        when(guard.check(any(String[].class))).thenReturn("pass");
        when(guard.initialStatus(any())).thenReturn("pending");
        when(guard.mode()).thenReturn("manual");
        when(db.insert(eq("kg_post"), anyMap())).thenReturn(42L);
        when(cities.businessCity(any())).thenAnswer(i -> i.getArgument(0));
    }

    String feedSql(Long me, String sort, String city) {
        service.feed(me, null, city, sort, 1, null);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db, atLeastOnce()).list(sql.capture(), anyMap());
        return sql.getValue();
    }

    // ---------------------------------------------------------------- 信息流「已关注」

    @Test void followFeedOnlyShowsPeopleIFollowInAllCities() {
        String sql = feedSql(7L, "follow", "大理");
        assertTrue(sql.contains("exists (select 1 from kg_user_follow f where f.follower_id = :me and f.followee_id = p.app_user_id)"));
        assertFalse(sql.contains("p.city = :c"), "已关注不分城市");
        assertTrue(sql.contains("order by p.post_id desc"));
    }

    @Test void latestAndHotFeedsKeepCityAndDoNotFilterByFollow() {
        String sql = feedSql(7L, "new", "大理");
        assertTrue(sql.contains("p.city = :c"));
        assertFalse(sql.contains("kg_user_follow"));
    }

    @Test void guestFollowFeedIsEmptyWithoutQuery() {
        Map<String, Object> res = service.feed(null, null, "大理", "follow", 1, null);
        assertEquals(Collections.emptyList(), res.get("rows"));
        assertEquals(Boolean.FALSE, res.get("hasMore"));
        verify(db, never()).list(anyString(), anyMap());
    }

    // ---------------------------------------------------------------- 伙伴说

    @Test void dailyPostStoresToneAndOtherKindsIgnoreIt() {
        service.publish(7L, p("kind", "daily", "title", "这家米线别去", "content", "", "city", "大理", "tone", "avoid"));
        verify(db).insert(eq("kg_post"), argThat(r -> "avoid".equals(r.get("tone"))));
        reset(db);
        when(db.one(startsWith("select status, del_flag from kg_app_user"), anyMap())).thenReturn(p("status", "0", "delFlag", "0"));
        service.publish(7L, p("kind", "daily", "title", "洱海日落", "content", "", "city", "大理"));
        verify(db).insert(eq("kg_post"), argThat(r -> !r.containsKey("tone")));
    }

    @Test void unknownToneIsRejected() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.publish(7L, p("kind", "daily", "title", "洱海日落", "content", "", "city", "大理", "tone", "great")));
        assertEquals(422, e.getCode());
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test void briefCarriesToneLabel() {
        when(db.list(anyString(), anyMap())).thenReturn(Arrays.asList(
                p("postId", 5L, "kind", "daily", "appUserId", 9L, "city", "大理", "title", "推荐这家", "status", "online", "tone", "recommend"),
                p("postId", 6L, "kind", "daily", "appUserId", 9L, "city", "大理", "title", "老帖子", "status", "online", "tone", "")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) service.feed(7L, null, "大理", "new", 1, null).get("rows");
        assertEquals("推荐", rows.get(0).get("toneLabel"));
        assertEquals("", rows.get(1).get("tone"));
    }

    // ---------------------------------------------------------------- 个人主页

    void user(String status) {
        when(db.one(startsWith("select user_id, nickname, avatar, phone_verified_time, status, del_flag, intro, city from kg_app_user"), anyMap()))
                .thenReturn(status == null ? null : p("userId", 9L, "nickname", "阿川", "avatar", "/profile/a.jpg", "phoneVerifiedTime", new java.util.Date(), "status", status, "delFlag", "0"));
    }

    @Test void profileShowsRelationAndPublicPosts() {
        user("0");
        when(guard.follows(7L, 9L)).thenReturn(true);
        when(guard.follows(9L, 7L)).thenReturn(true);
        when(db.list(startsWith("select p.* from kg_post p where p.app_user_id = :u"), anyMap()))
                .thenReturn(Arrays.asList(p("postId", 5L, "kind", "daily", "appUserId", 9L, "city", "大理", "title", "洱海", "status", "online")));
        Map<String, Object> m = service.userProfile(9L, 7L, 1);
        assertEquals("阿川", m.get("name"));
        assertEquals(Boolean.TRUE, m.get("phoneVerified"));
        assertEquals(Boolean.TRUE, m.get("mutual"));
        assertEquals(Boolean.TRUE, m.get("canMessage"));
        assertEquals(1, ((List<?>) m.get("posts")).size());
    }

    @Test void profileExplainsWhyICannotMessage() {
        user("0");
        when(guard.dmRefusal(7L, 9L)).thenReturn("对方设置了只接收互相关注的人的私信，互相关注后才能发起私信");
        Map<String, Object> m = service.userProfile(9L, 7L, 1);
        assertEquals(Boolean.FALSE, m.get("canMessage"));
        assertTrue(String.valueOf(m.get("dmReason")).contains("互相关注"));
    }

    @Test void missingDisabledOrBlockingUsersAre404() {
        user(null);
        assertEquals(404, assertThrows(ServiceException.class, () -> service.userProfile(9L, 7L, 1)).getCode());
        user("1");
        assertEquals(404, assertThrows(ServiceException.class, () -> service.userProfile(9L, 7L, 1)).getCode());
        user("0");
        when(db.count(eq("select count(*) from kg_user_block where user_id = :u and blocked_id = :m"), eq(p("u", 9L, "m", 7L)))).thenReturn(1L);
        assertEquals(404, assertThrows(ServiceException.class, () -> service.userProfile(9L, 7L, 1)).getCode());
    }

    @Test void blockedByMeShowsProfileWithoutPosts() {
        user("0");
        when(db.count(eq("select count(*) from kg_user_block where user_id = :m and blocked_id = :u"), eq(p("m", 7L, "u", 9L)))).thenReturn(1L);
        Map<String, Object> m = service.userProfile(9L, 7L, 1);
        assertEquals(Boolean.TRUE, m.get("blocked"));
        assertEquals(Boolean.FALSE, m.get("canMessage"));
        assertEquals(Collections.emptyList(), m.get("posts"));
        verify(db, never()).list(startsWith("select p.* from kg_post p"), anyMap());
    }

    // ---------------------------------------------------------------- 我关注的人 / 我收藏的帖子

    @Test void followingUsersListMutualFlag() {
        when(db.list(startsWith("select f.followee_id"), anyMap())).thenReturn(Arrays.asList(
                p("followeeId", 9L, "nickname", "阿川", "followsMe", 1L), p("followeeId", 10L, "nickname", "", "followsMe", 0L)));
        List<Map<String, Object>> rows = service.followingUsers(7L);
        assertEquals(Boolean.TRUE, rows.get(0).get("mutual"));
        assertEquals("用户10", rows.get(1).get("name"));
        assertEquals(Boolean.FALSE, rows.get(1).get("mutual"));
    }

    @Test void favoritesListOnlyMyVisiblePostFavorites() {
        service.favorites(7L, 2);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings({ "unchecked", "rawtypes" })
        ArgumentCaptor<Map<String, Object>> ps = ArgumentCaptor.forClass((Class) Map.class);
        verify(db).list(sql.capture(), ps.capture());
        assertTrue(sql.getValue().contains("f.target_type = 'post' and f.app_user_id = :me"));
        assertTrue(sql.getValue().contains("p.status = 'online'"));
        assertTrue(sql.getValue().contains("order by f.fav_id desc"));
        assertEquals(20, ps.getValue().get("off"));
    }
}
