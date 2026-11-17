package com.ruoyi.kangaroo.ext.community;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.kangaroo.ext.KgJdbc;

/** 消息页每行的对方和来源（2026-10-31 主人反馈）：不拆 title，从会话现算；规则表每一行一个用例。 */
class KgChatInboxFactTest
{
    private static final Long ME = 7L;
    private static final Long APPLICANT = 8L;

    private static Map<String, Object> user(Long id, String nickname, String avatar)
    {
        return p("appUserId", id, "nickname", nickname, "avatar", avatar);
    }

    private static Map<String, Object> store(String pair, String source, String jobTitle)
    {
        return p("convType", "store", "pairKey", pair, "storeId", 102L, "source", source, "sourceId", 300L, "title", "平潭袋鼠小院", "storeCover", "/profile/store.jpg", "jobTitle", jobTitle);
    }

    private static void fact(Map<String, Object> f, String name, String avatar, String source)
    {
        assertEquals(name, f.get("peerName"));
        assertEquals(avatar, f.get("peerAvatar"));
        assertEquals(source, f.get("sourceLabel"));
    }

    @Test
    void offlineStoreConversation()
    {
        Map<String, Object> c = store("offline:102:u8", "progress", "平潭海岛体验官");
        List<Map<String, Object>> members = Collections.singletonList(user(APPLICANT, "小杨同学", "/profile/a.jpg"));
        fact(KgChatService.inboxFact(ME, "staff", c, members), "小杨同学", "/profile/a.jpg", "平潭海岛体验官");
        fact(KgChatService.inboxFact(APPLICANT, "user", c, members), "平潭袋鼠小院", "/profile/store.jpg", "平潭海岛体验官");
    }

    @Test
    void onlineStoreConversation()
    {
        Map<String, Object> c = store("o102-u8", "online_task", "小红书探店");
        List<Map<String, Object>> members = Collections.singletonList(user(APPLICANT, "", ""));
        fact(KgChatService.inboxFact(ME, "staff", c, members), "用户8", "", "小红书探店");
        fact(KgChatService.inboxFact(APPLICANT, "user", c, members), "平潭袋鼠小院", "/profile/store.jpg", "小红书探店");
    }

    @Test
    void storeConversationWhoseApplicationIsGoneFallsBack()
    {
        fact(KgChatService.inboxFact(ME, "staff", store("offline:102:u8", "progress", ""), Collections.singletonList(user(APPLICANT, "小杨", ""))), "小杨", "", "线下报名");
        fact(KgChatService.inboxFact(ME, "staff", store("o102-u8", "online_task", null), Collections.singletonList(user(APPLICANT, "小杨", ""))), "小杨", "", "线上任务");
    }

    @Test
    void legacyMixedPairSaysHistory()
    {
        Map<String, Object> c = store("s102-u8", "progress", "现在挂着的岗位");
        List<Map<String, Object>> members = Collections.singletonList(user(APPLICANT, "小杨", ""));
        fact(KgChatService.inboxFact(ME, "staff", c, members), "小杨", "", "历史沟通");
        fact(KgChatService.inboxFact(APPLICANT, "user", c, members), "平潭袋鼠小院", "/profile/store.jpg", "历史沟通");
    }

    @Test
    void brokenPairKeyFallsBackToTheConversationTitle()
    {
        fact(KgChatService.inboxFact(ME, "staff", store("broken", "progress", "岗位"), new ArrayList<Map<String, Object>>()), "平潭袋鼠小院", "", "岗位");
    }

    @Test
    void privateConversationShowsTheOtherPersonAndWhereItStarted()
    {
        List<Map<String, Object>> members = Arrays.asList(user(ME, "我自己", "/me.jpg"), user(APPLICANT, "同行小林", "/lin.jpg"));
        for (String[] row : new String[][] { { "post", "社区帖子" }, { "buddy", "找搭子" }, { "match", "同行匹配" }, { "activity", "活动" }, { "progress", "报名进度" }, { "online_task", "线上任务" },
                { "profile", "社区私信" }, { "", "社区私信" }, { "whatever", "社区私信" } })
        {
            Map<String, Object> c = p("convType", "private", "pairKey", "7-8", "source", row[0], "title", "私信");
            fact(KgChatService.inboxFact(ME, "user", c, members), "同行小林", "/lin.jpg", row[1]);
        }
        fact(KgChatService.inboxFact(ME, "user", p("convType", "private", "source", "post", "title", "私信"), Collections.singletonList(user(ME, "我自己", ""))), "私信", "", "社区帖子");
    }

    @Test
    void groupConversationDropsTheSuffix()
    {
        fact(KgChatService.inboxFact(ME, "user", p("convType", "group", "title", "周末海边徒步 活动群"), null), "周末海边徒步", "", "活动群");
        fact(KgChatService.inboxFact(ME, "user", p("convType", "group", "title", "活动群"), null), "活动群", "", "活动群");
    }

    @Test
    void factsUseTwoInQueriesNotOnePerRow()
    {
        KgJdbc db = mock(KgJdbc.class);
        KgChatService service = new KgChatService();
        ReflectionTestUtils.setField(service, "db", db);
        when(db.list(contains("from kg_chat_conv c"), anyMap())).thenReturn(Arrays.asList(
                p("convId", 1L, "convType", "store", "pairKey", "offline:102:u8", "storeId", 102L, "source", "progress", "title", "平潭袋鼠小院", "storeCover", "/s.jpg", "jobTitle", "海岛体验官"),
                p("convId", 2L, "convType", "group", "title", "徒步 活动群")));
        when(db.list(contains("from kg_chat_member m"), anyMap())).thenReturn(Collections.singletonList(p("convId", 1L, "appUserId", 8L, "nickname", "小杨", "avatar", "/y.jpg")));
        Map<Long, Map<String, Object>> facts = service.inboxFacts(ME, "staff", Arrays.asList(1L, 2L));
        fact(facts.get(1L), "小杨", "/y.jpg", "海岛体验官");
        fact(facts.get(2L), "徒步", "", "活动群");
        verify(db, times(2)).list(anyString(), anyMap());
        verify(db, never()).one(anyString(), anyMap());
        assertTrue(service.inboxFacts(ME, "staff", Collections.<Long>emptyList()).isEmpty());
        assertTrue(service.inboxFacts(ME, "staff", null).isEmpty());
        verify(db, times(2)).list(anyString(), anyMap());
    }

    /** 2026-11-09：对方已注销写「已注销用户」、不带头像；成员行还在但 del_flag 不是 0 也算 */
    @Test
    void privatePeerDeactivatedSaysGoneUser()
    {
        Map<String, Object> c = p("convType", "private", "pairKey", "7-8", "source", "post", "title", "私信");
        Map<String, Object> gone = user(APPLICANT, "同行小林", "/lin.jpg");
        gone.put("delFlag", "2");
        fact(KgChatService.inboxFact(ME, "user", c, Arrays.asList(user(ME, "我", ""), gone)), "已注销用户", "", "社区帖子");
        Map<String, Object> alive = user(APPLICANT, "同行小林", "/lin.jpg");
        alive.put("delFlag", "0");
        fact(KgChatService.inboxFact(ME, "user", c, Arrays.asList(user(ME, "我", ""), alive)), "同行小林", "/lin.jpg", "社区帖子");
    }

    /** 对方注销后成员行被删：按 pair_key 一次 in 查询找回对方（和会话详情一样）；pair_key 也找不到对方的仍写「私信」 */
    @Test
    void factsFindDeletedPrivatePeerByPairKey()
    {
        KgJdbc db = mock(KgJdbc.class);
        KgChatService service = new KgChatService();
        ReflectionTestUtils.setField(service, "db", db);
        when(db.list(contains("from kg_chat_conv c"), anyMap())).thenReturn(Arrays.asList(
                p("convId", 1L, "convType", "private", "pairKey", "7-9", "source", "post", "title", "私信"),
                p("convId", 2L, "convType", "private", "pairKey", "7-10", "source", "buddy", "title", "私信"),
                p("convId", 3L, "convType", "private", "pairKey", null, "source", "post", "title", "私信"),
                p("convId", 4L, "convType", "private", "pairKey", "7-8", "source", "match", "title", "私信")));
        when(db.list(contains("from kg_chat_member m"), anyMap())).thenReturn(Arrays.asList(
                p("convId", 1L, "appUserId", ME, "nickname", "我", "avatar", "", "delFlag", "0"),
                p("convId", 4L, "appUserId", 8L, "nickname", "小杨", "avatar", "/y.jpg", "delFlag", "0")));
        when(db.list(contains("from kg_app_user where user_id in"), anyMap())).thenReturn(Arrays.asList(
                p("userId", 9L, "nickname", "旧昵称", "avatar", "/old.jpg", "delFlag", "2"),
                p("userId", 10L, "nickname", "小周", "avatar", "/z.jpg", "delFlag", "0")));
        Map<Long, Map<String, Object>> facts = service.inboxFacts(ME, "user", Arrays.asList(1L, 2L, 3L, 4L));
        fact(facts.get(1L), "已注销用户", "", "社区帖子");
        fact(facts.get(2L), "小周", "/z.jpg", "找搭子");
        fact(facts.get(3L), "私信", "", "社区帖子");
        fact(facts.get(4L), "小杨", "/y.jpg", "同行匹配");
        verify(db, times(3)).list(anyString(), anyMap());
        verify(db).list(contains("from kg_app_user where user_id in"), argThat(m -> Arrays.asList(9L, 10L).equals(m.get("ids"))));
        verify(db, never()).one(anyString(), anyMap());
    }
}
