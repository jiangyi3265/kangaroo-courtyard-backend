package com.ruoyi.kangaroo.ext.community;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;

/**
 * 线上任务「联系专员」（2026-10-16 主人拍板）：专员发布的线上任务，发布的专员一定在「门店 × 接单人」这段会话里——
 * 即使门店设的线上接待账号里没有 TA；同事里有接待资格的照旧能看到、能回复；接单人那边顶栏写「专员 · 昵称」。
 * 真实 service 方法 + Mock JDBC / 接待名单：只证明席位怎么算、写了哪些成员和提醒；SQL 本身另有 KgChatReception 的语句断言。
 */
class KgChatSpecialistSeatTest
{
    final KgJdbc db = mock(KgJdbc.class);
    final KgCommunityGuard guard = mock(KgCommunityGuard.class);
    final KgChatReception reception = mock(KgChatReception.class);
    final KgChatService service = new KgChatService();
    // 会话 12：门店 22（海边小院）的线上任务沟通，接单人 7（小林）；门店设的接待账号只有 8（阿川）；10（阿青）是发布任务的专员
    final Map<String, Object> store = p("convId", 12L, "convType", "store", "status", "active", "title", "海边小院", "storeId", 22L,
            "pairKey", "o22-u7", "source", "online_task", "sourceId", 300L);
    final Map<Long, String> names = new HashMap<Long, String>();
    final Map<Long, String> seats = new HashMap<Long, String>();
    List<Long> receptionStaff = Arrays.asList(8L);
    List<Long> publishers = Arrays.asList(10L);

    @BeforeEach void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "guard", guard);
        ReflectionTestUtils.setField(service, "grants", mock(IKgStaffGrantService.class));
        ReflectionTestUtils.setField(service, "reception", reception);
        names.put(7L, "小林");
        names.put(8L, "阿川");
        names.put(10L, "阿青");
        seats.put(7L, "member");
        seats.put(8L, "store");
        seats.put(10L, "store");
        when(db.one(startsWith("select status, del_flag from kg_app_user"), anyMap())).thenReturn(p("status", "0", "delFlag", "0"));
        when(db.one(startsWith("select user_id, nickname"), anyMap()))
                .thenAnswer(i -> p("userId", ((Map<?, ?>) i.getArgument(1)).get("u"), "nickname", names.get(((Map<?, ?>) i.getArgument(1)).get("u"))));
        when(db.one(startsWith("select * from kg_chat_member"), anyMap()))
                .thenAnswer(i -> { String role = seats.get(((Map<?, ?>) i.getArgument(1)).get("u")); return role == null ? null : p("role", role, "muted", "0"); });
        when(db.one(startsWith("select j.title from kg_application"), anyMap())).thenReturn(p("title", "民宿文案"));
        when(db.one(startsWith("select j.title, j.city from kg_application"), anyMap())).thenReturn(p("title", "民宿文案", "city", "大理"));
        when(db.one(startsWith("select conv_type,store_id from kg_chat_conv"), anyMap())).thenReturn(store);
        when(db.one(startsWith("select conv_id,conv_type,pair_key,source,store_id from kg_chat_conv"), anyMap())).thenReturn(store);
        when(db.one(startsWith("select c.conv_type, c.store_id"), anyMap())).thenReturn(store);
        when(db.one(startsWith("select conv_id, conv_type, store_id, pair_key, source from kg_chat_conv"), anyMap())).thenReturn(store);
        when(db.must(startsWith("select * from kg_chat_conv"), anyMap(), anyString())).thenReturn(store);
        when(reception.isOnline(anyMap())).thenAnswer(i -> "online_task".equals(((Map<?, ?>) i.getArgument(0)).get("source")));
        when(reception.staff(eq(22L), anyBoolean())).thenAnswer(i -> receptionStaff);
        when(reception.publishers(eq(22L), eq(7L))).thenAnswer(i -> publishers);
        when(db.insert(eq("kg_chat_msg"), anyMap())).thenReturn(73L);
    }

    void application()
    {
        when(db.one(startsWith("select a.app_id, a.app_user_id, a.store_id"), anyMap()))
                .thenReturn(p("appId", 300L, "appUserId", 7L, "storeId", 22L, "title", "民宿文案", "jobType", "online", "storeName", "海边小院"));
        when(db.one(startsWith("select conv_id from kg_chat_conv where pair_key"), anyMap())).thenReturn(p("convId", 12L));
    }

    boolean seated(long uid, String role)
    {
        try
        {
            verify(db, atLeastOnce()).update(startsWith("insert into kg_chat_member"), eq(p("c", 12L, "u", uid, "r", role)));
            return true;
        }
        catch (AssertionError e)
        {
            return false;
        }
    }

    @Test void publishingSpecialistOutsideTheReceptionListStillOpensTheTakerConversation()
    {
        // 员工这边「联系接单人」：专员不在接待名单里，但这位接单人报的是 TA 发布的任务 → 打开的是同一段会话，专员、接待都在席
        application();
        Map<String, Object> view = service.openStore(10L, 300L);
        assertEquals(12L, view.get("convId"));
        assertEquals("小林", view.get("title"));
        assertTrue(seated(10L, "store"), "发布专员在席");
        assertTrue(seated(8L, "store"), "接待同事照旧在席");
        assertTrue(seated(7L, "member"));
        assertEquals("这是和 小林 的单独沟通，其他接单人看不到；本店接待 阿川 也能看到并回复。", view.get("receptionNotice"));
    }

    @Test void otherSpecialistWhoDidNotPublishTheTakersTaskIsStillRefused()
    {
        application();
        publishers = Collections.emptyList();
        ServiceException e = assertThrows(ServiceException.class, () -> service.openStore(10L, 300L));
        assertEquals(403, e.getCode());
        verify(db, never()).update(startsWith("insert into kg_chat_member"), anyMap());
    }

    @Test void takerOpeningSeatsBothReceptionAndPublishingSpecialist()
    {
        application();
        service.openStore(7L, 300L);
        assertTrue(seated(8L, "store"));
        assertTrue(seated(10L, "store"));
    }

    @Test void takerMessageSeatsAndNotifiesTheSpecialistEvenWhenReceptionExcludesThem()
    {
        when(db.list(startsWith("select app_user_id, role from kg_chat_member"), anyMap()))
                .thenReturn(Arrays.asList(p("appUserId", 8L, "role", "store"), p("appUserId", 10L, "role", "store")));
        assertEquals(73L, service.send(7L, 12L, "作品交了，请看看").get("msgId"));
        assertTrue(seated(10L, "store"));
        verify(db).update(startsWith("insert into kg_message"), argThat(m -> Long.valueOf(10).equals(m.get("u")) && "staff".equals(m.get("m"))
                && "小林 · 民宿文案".equals(m.get("t")) && "作品交了，请看看".equals(m.get("b")) && "0".equals(m.get("f"))));
        verify(db).update(startsWith("insert into kg_message"), argThat(m -> Long.valueOf(8).equals(m.get("u")) && "staff".equals(m.get("m"))));
    }

    @Test void specialistCanReplyAndTheTakerIsNotified()
    {
        when(db.list(startsWith("select app_user_id, role from kg_chat_member"), anyMap())).thenReturn(Arrays.asList(p("appUserId", 7L, "role", "member")));
        assertEquals(73L, service.send(10L, 12L, "收到，我看一下").get("msgId"));
        verify(db).insert(eq("kg_chat_msg"), argThat(m -> "store".equals(m.get("sender_role")) && Long.valueOf(10).equals(m.get("sender_id"))));
        verify(db).update(startsWith("insert into kg_message"), argThat(m -> Long.valueOf(7).equals(m.get("u")) && "user".equals(m.get("m")) && "海边小院".equals(m.get("t"))));
    }

    @Test void takerHeaderShowsTheSpecialistAndTheNoticeSaysTheyReplyHere()
    {
        when(reception.specialistOf(22L, 300L)).thenReturn(10L);
        Map<String, Object> view = service.conversation(7L, 12L);
        assertEquals("专员 · 阿青", view.get("title"));
        assertEquals("海边小院 · 民宿文案 · 大理", view.get("otherMeta"));
        assertEquals("specialist", view.get("contactRole"));
        assertEquals("发布任务的专员和门店接待会在这里回复你，其他接单人看不到。", view.get("receptionNotice"));
        // 员工这边不变：顶栏是接单人昵称，同事里点名专员
        Map<String, Object> staffView = service.conversation(8L, 12L);
        assertEquals("小林", staffView.get("title"));
        assertEquals("这是和 小林 的单独沟通，其他接单人看不到；本店接待 阿青 也能看到并回复。", staffView.get("receptionNotice"));
        assertNull(staffView.get("contactRole"));
    }

    @Test void taskByManagerOrRevokedSpecialistKeepsTheStoreNameHeader()
    {
        // 不是专员发布的（主理人发的）：和以前一样，顶栏是门店名
        Map<String, Object> view = service.conversation(7L, 12L);
        assertEquals("海边小院", view.get("title"));
        assertEquals("民宿文案 · 大理", view.get("otherMeta"));
        assertEquals("门店接待会在这里回复你，其他接单人看不到。", view.get("receptionNotice"));
        assertNull(view.get("contactRole"));
        // 专员授权已撤销（不在席位里）：也不再写「专员」
        when(reception.specialistOf(22L, 300L)).thenReturn(10L);
        publishers = Collections.emptyList();
        assertEquals("海边小院", service.conversation(7L, 12L).get("title"));
    }

    @Test void revokedSpecialistLosesTheSeat()
    {
        publishers = Collections.emptyList();
        ServiceException e = assertThrows(ServiceException.class, () -> service.conversation(10L, 12L));
        assertEquals(403, e.getCode());
    }

    @Test void offlineAndArchivedConversationsNeverAddSpecialists()
    {
        Map<String, Object> offline = p("convId", 12L, "convType", "store", "status", "active", "title", "海边小院", "storeId", 22L,
                "pairKey", "offline:22:u7", "source", "progress", "sourceId", 300L);
        when(db.one(startsWith("select conv_id,conv_type,pair_key,source,store_id from kg_chat_conv"), anyMap())).thenReturn(offline);
        when(db.one(startsWith("select c.conv_type, c.store_id"), anyMap())).thenReturn(offline);
        when(db.must(startsWith("select * from kg_chat_conv"), anyMap(), anyString())).thenReturn(offline);
        assertThrows(ServiceException.class, () -> service.conversation(10L, 12L));
        Map<String, Object> legacy = p("convId", 12L, "convType", "store", "status", "active", "title", "海边小院", "storeId", 22L,
                "pairKey", "s22-u7", "source", "online_task", "sourceId", 300L);
        when(db.one(startsWith("select c.conv_type, c.store_id"), anyMap())).thenReturn(legacy);
        when(db.must(startsWith("select * from kg_chat_conv"), anyMap(), anyString())).thenReturn(legacy);
        when(reception.isOnline(anyMap())).thenReturn(true);
        assertThrows(ServiceException.class, () -> service.conversation(10L, 12L));
        verify(reception, never()).publishers(anyLong(), anyLong());
    }

    @Test void specialistMessagesAreLabelledSpecialistOthersStayReception()
    {
        when(db.must(startsWith("select conv_id, conv_type from kg_chat_conv"), anyMap(), anyString())).thenReturn(p("convId", 12L, "convType", "store"));
        when(db.list(startsWith("select * from (select m.*"), anyMap())).thenReturn(Arrays.asList(
                p("msgId", 1L, "senderId", 10L, "senderRole", "store", "nickname", "阿青", "content", "你好，我是发布任务的专员", "status", "visible", "createTime", new Date()),
                p("msgId", 2L, "senderId", 8L, "senderRole", "store", "nickname", "阿川", "content", "我是接待", "status", "visible", "createTime", new Date()),
                p("msgId", 3L, "senderId", 7L, "senderRole", "user", "nickname", "小林", "content", "好的", "status", "visible", "createTime", new Date())));
        when(db.must(startsWith("select last_read_id"), anyMap(), anyString())).thenReturn(p("lastReadId", 3L));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) service.messages(7L, 12L, 0L).get("rows");
        assertEquals("专员", rows.get(0).get("seatLabel"));
        assertNull(rows.get(1).get("seatLabel"));
        assertNull(rows.get(2).get("seatLabel"));
        // 专员名单每次拉消息最多查一次
        verify(reception, times(1)).publishers(22L, 7L);
    }

    // ---------------------------------------------------------------- KgChatReception 本身

    KgChatReception realReception(KgJdbc jdbc)
    {
        KgChatReception r = new KgChatReception();
        ReflectionTestUtils.setField(r, "db", jdbc);
        return r;
    }

    @Test void publishersAreCurrentOnlineSpecialistsWhoPublishedATaskThisTakerAppliedTo()
    {
        KgJdbc jdbc = mock(KgJdbc.class);
        when(jdbc.list(anyString(), anyMap())).thenReturn(Arrays.asList(p("appUserId", 10L), p("appUserId", 10L), p("appUserId", 11L)));
        assertEquals(Arrays.asList(10L, 11L), realReception(jdbc).publishers(22L, 7L));
        verify(jdbc).list(argThat(q -> q.startsWith("select distinct g.app_user_id from kg_staff_grant g")
                && q.contains("g.store_id=:s and g.status='0' and g.del_flag='0'") && q.contains("u.status='0' and u.del_flag='0'")
                && q.contains("g.role_code='online_staff'") && q.contains("a.store_id=g.store_id and a.app_user_id=:a and a.del_flag='0'")
                && q.contains("j.job_type='online'") && q.contains("j.create_by=concat('staff:',g.app_user_id)")), eq(p("s", 22L, "a", 7L)));
        assertTrue(realReception(jdbc).publishers(null, 7L).isEmpty());
        assertTrue(realReception(jdbc).publishers(22L, null).isEmpty());
    }

    @Test void specialistOfLooksAtThisApplicationsTaskOnly()
    {
        KgJdbc jdbc = mock(KgJdbc.class);
        when(jdbc.one(anyString(), anyMap())).thenReturn(p("appUserId", 10L));
        assertEquals(Long.valueOf(10L), realReception(jdbc).specialistOf(22L, 300L));
        verify(jdbc).one(argThat(q -> q.contains("g.role_code='online_staff'") && q.contains("a.app_id=:a and a.store_id=g.store_id")
                && q.contains("j.create_by=concat('staff:',g.app_user_id)") && q.contains("j.job_type='online'")), eq(p("s", 22L, "a", 300L)));
        when(jdbc.one(anyString(), anyMap())).thenReturn(null);
        assertNull(realReception(jdbc).specialistOf(22L, 300L));
        assertNull(realReception(jdbc).specialistOf(null, 300L));
    }

    @Test void seatsMergeReceptionAndPublishersOnlyForCurrentOnlineConversations()
    {
        KgJdbc jdbc = mock(KgJdbc.class);
        when(jdbc.list(argThat(q -> q != null && q.startsWith("select g.app_user_id")), anyMap())).thenReturn(Arrays.asList(p("appUserId", 8L)));
        when(jdbc.list(argThat(q -> q != null && q.startsWith("select distinct g.app_user_id")), anyMap()))
                .thenReturn(Arrays.asList(p("appUserId", 8L), p("appUserId", 10L)));
        KgChatReception r = realReception(jdbc);
        assertEquals(Arrays.asList(8L, 10L), r.seats(22L, true, 7L));
        assertEquals(Arrays.asList(8L, 10L), r.seats(store));
        assertEquals(Arrays.asList(8L), r.seats(22L, false, 7L));
        assertEquals(Arrays.asList(8L), r.seats(p("convId", 12L, "convType", "store", "storeId", 22L, "pairKey", "offline:22:u7", "source", "progress")));
        clearInvocations(jdbc);
        assertEquals(Arrays.asList(8L), r.seats(p("convId", 12L, "convType", "store", "storeId", 22L, "pairKey", "s22-u7", "source", "online_task")));
        verify(jdbc, never()).list(argThat(q -> q != null && q.startsWith("select distinct g.app_user_id")), anyMap());
    }

    @Test void storeIdentityAcceptsThePublishingSpecialistButNotOtherStaff()
    {
        KgJdbc jdbc = mock(KgJdbc.class);
        when(jdbc.count(anyString(), anyMap())).thenReturn(1L);
        when(jdbc.list(argThat(q -> q != null && q.startsWith("select g.app_user_id")), anyMap())).thenReturn(Arrays.asList(p("appUserId", 8L)));
        when(jdbc.list(argThat(q -> q != null && q.startsWith("select distinct g.app_user_id")), anyMap())).thenReturn(Arrays.asList(p("appUserId", 10L)));
        KgChatReception r = realReception(jdbc);
        assertDoesNotThrow(() -> r.requireStoreIdentity(10L, store));
        assertDoesNotThrow(() -> r.requireStoreIdentity(8L, store));
        assertDoesNotThrow(() -> r.requireStoreIdentity(7L, store));
        assertEquals(403, assertThrows(ServiceException.class, () -> r.requireStoreIdentity(11L, store)).getCode());
    }
}
