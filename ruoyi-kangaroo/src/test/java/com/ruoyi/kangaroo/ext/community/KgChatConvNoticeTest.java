package com.ruoyi.kangaroo.ext.community;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;

/**
 * 站内信「私信」= 会话列表（2026-10-15）：每人每个身份下每个会话只有一行，发消息的人自己也有一行已读的「我：…」，
 * 标题按收件人这一侧（员工看到「报名人昵称 · 岗位」，报名人看到门店名，私信是对方昵称）。
 * 真实 service 方法 + Mock JDBC：只证明写了哪些语句、带什么参数；SQL 在 MySQL 上的效果（只更新最新一行、
 * 列表隐藏历史旧行、按时间排序）由 e2e/test_ext_community.py 的 T4 和验收库上的核对证明。
 */
class KgChatConvNoticeTest
{
    static final String UPDATE = "update kg_message set title";
    static final String INSERT = "insert into kg_message";
    final KgJdbc db = mock(KgJdbc.class);
    final KgCommunityGuard guard = mock(KgCommunityGuard.class);
    final KgChatReception reception = mock(KgChatReception.class);
    final KgChatService service = new KgChatService();
    // 会话 12：门店 22 的线上任务沟通，报名人 7（小林），接待员工 8（阿川）、9（老周）
    final Map<String, Object> store = p("convId", 12L, "convType", "store", "status", "active", "title", "海边小院", "storeId", 22L,
            "pairKey", "o22-u7", "source", "online_task", "sourceId", 300L);
    final Map<Long, String> names = new HashMap<Long, String>();
    final Map<Long, String> seats = new HashMap<Long, String>();
    List<Long> receptionStaff = Arrays.asList(8L, 9L);

    @BeforeEach void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "guard", guard);
        ReflectionTestUtils.setField(service, "grants", mock(IKgStaffGrantService.class));
        ReflectionTestUtils.setField(service, "reception", reception);
        names.put(7L, "小林");
        names.put(8L, "阿川");
        names.put(9L, "老周");
        names.put(10L, "小美");
        names.put(11L, "大刘");
        seats.put(7L, "member");
        seats.put(8L, "store");
        seats.put(9L, "store");
        seats.put(10L, "store");
        seats.put(11L, "store");
        when(db.one(startsWith("select status, del_flag from kg_app_user"), anyMap())).thenReturn(p("status", "0", "delFlag", "0"));
        // 发送人名字（userBrief）和报名人名字（KgChatReception.applicantName）两条查询都以这一段开头
        when(db.one(startsWith("select user_id, nickname"), anyMap()))
                .thenAnswer(i -> p("userId", ((Map<?, ?>) i.getArgument(1)).get("u"), "nickname", names.get(((Map<?, ?>) i.getArgument(1)).get("u"))));
        when(db.one(startsWith("select * from kg_chat_member"), anyMap()))
                .thenAnswer(i -> p("role", seats.get(((Map<?, ?>) i.getArgument(1)).get("u")), "muted", "0"));
        when(db.one(startsWith("select j.title from kg_application"), anyMap())).thenReturn(p("title", "民宿文案"));
        when(db.one(startsWith("select j.title, j.city from kg_application"), anyMap())).thenReturn(p("title", "民宿文案", "city", "大理"));
        conv(store);
        when(reception.isOnline(anyMap())).thenReturn(true);
        when(reception.staff(eq(22L), anyBoolean())).thenAnswer(i -> receptionStaff);
        when(db.insert(eq("kg_chat_msg"), anyMap())).thenReturn(73L);
    }

    void conv(Map<String, Object> c)
    {
        when(db.one(startsWith("select conv_type,store_id from kg_chat_conv"), anyMap())).thenReturn(c);
        when(db.one(startsWith("select conv_id,conv_type,pair_key,source,store_id from kg_chat_conv"), anyMap())).thenReturn(c);
        when(db.one(startsWith("select c.conv_type, c.store_id"), anyMap())).thenReturn(c);
        when(db.must(startsWith("select * from kg_chat_conv"), anyMap(), anyString())).thenReturn(c);
    }

    /** 除发送人外、在席且没开免打扰的成员 */
    void others(Long... uids)
    {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Long uid : uids) rows.add(p("appUserId", uid, "role", seats.get(uid)));
        when(db.list(startsWith("select app_user_id, role from kg_chat_member"), anyMap())).thenReturn(rows);
    }

    /** 某个收件人那一行最后写入时的参数（先 update，没有才 insert，两次参数相同） */
    Map<String, Object> row(long uid)
    {
        @SuppressWarnings({ "unchecked", "rawtypes" })
        ArgumentCaptor<Map<String, Object>> v = (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
        verify(db, atLeast(0)).update(startsWith(INSERT), v.capture());
        for (Map<String, Object> m : v.getAllValues()) if (Long.valueOf(uid).equals(m.get("u"))) return m;
        return null;
    }

    @Test void staffContactWritesReadRowForSenderAndUnreadRowsForOthers()
    {
        others(7L, 9L);
        assertEquals(73L, service.send(8L, 12L, "你好，作品收到了").get("msgId"));
        Map<String, Object> mine = row(8L), applicant = row(7L), colleague = row(9L);
        // 员工自己：员工身份、员工侧标题、已读的「我：…」
        assertEquals(p("m", "staff", "t", "小林 · 民宿文案", "b", "我：你好，作品收到了", "f", "1"), pick(mine));
        // 报名人：用户身份、门店名、正文直接是消息、未读
        assertEquals(p("m", "user", "t", "海边小院", "b", "你好，作品收到了", "f", "0"), pick(applicant));
        // 同事：员工身份、员工侧标题、正文前写是哪位同事发的、未读
        assertEquals(p("m", "staff", "t", "小林 · 民宿文案", "b", "阿川（接待）：你好，作品收到了", "f", "0"), pick(colleague));
        for (Map<String, Object> m : Arrays.asList(mine, applicant, colleague))
        {
            assertEquals("chat", m.get("cat"));
            assertEquals("/pages/community/chat?convId=12", m.get("r"));
        }
        verify(db).update(argThat(q -> q.startsWith(INSERT) && q.contains(":f, now(), '0', now())")), argThat(m -> Long.valueOf(8).equals(m.get("u"))));
        verify(db, times(2)).update(argThat(q -> q.startsWith(INSERT) && q.contains(":f, null, '0', now())")), anyMap());
    }

    Map<String, Object> pick(Map<String, Object> m)
    {
        assertNotNull(m);
        return p("m", m.get("m"), "t", m.get("t"), "b", m.get("b"), "f", m.get("f"));
    }

    @Test void existingRowIsUpdatedInPlaceRegardlessOfReadStateAndNeverDuplicated()
    {
        others(7L);
        when(db.update(startsWith(UPDATE), anyMap())).thenReturn(1);
        service.send(8L, 12L, "第二条");
        verify(db, never()).update(startsWith(INSERT), anyMap());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings({ "unchecked", "rawtypes" })
        ArgumentCaptor<Map<String, Object>> v = (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
        verify(db, atLeastOnce()).update(sql.capture(), v.capture());
        List<String> notices = new ArrayList<String>();
        for (int i = 0; i < sql.getAllValues().size(); i++)
        {
            String q = sql.getAllValues().get(i);
            if (!q.startsWith(UPDATE)) continue;
            notices.add(q);
            // 只更新这个人、这个身份、这个会话最新的那一行；不再只找未读行（之前读掉后另插新行，同一会话越积越多）
            assertTrue(q.endsWith("where recipient_id = :u and mode = :m and biz_type = 'chat_conv' and biz_id = :c and del_flag = '0' order by msg_id desc limit 1"), q);
            assertFalse(q.contains("read_flag = '0' and"), q);
            boolean unread = "0".equals(v.getAllValues().get(i).get("f"));
            assertTrue(q.contains(unread ? "read_time = null" : "read_time = now()"), q);
            assertTrue(q.contains("create_time = now()"), q);
        }
        assertEquals(2, notices.size());
    }

    @Test void applicantReplyFlipsStaffRowsBackToUnreadWithApplicantTitle()
    {
        others(8L, 9L);
        service.send(7L, 12L, "改好了");
        assertEquals(p("m", "staff", "t", "小林 · 民宿文案", "b", "改好了", "f", "0"), pick(row(8L)));
        assertEquals(p("m", "staff", "t", "小林 · 民宿文案", "b", "改好了", "f", "0"), pick(row(9L)));
        assertEquals(p("m", "user", "t", "海边小院", "b", "我：改好了", "f", "1"), pick(row(7L)));
    }

    @Test void rowBodyIsJustTheLatestMessageWithoutNewCountPrefix()
    {
        // 不再写「（N 条新消息）」：那个数按已读位置算，「全部已读」不推进已读位置，会把已经清掉的也算进去
        others(7L);
        service.send(8L, 12L, "在吗");
        assertEquals("在吗", row(7L).get("b"));
        assertEquals("我：在吗", row(8L).get("b"));
        verify(db, never()).count(argThat(q -> q.contains("cm.last_read_id")), anyMap());
    }

    @Test void revokedColleagueAndMutedMembersGetNothingButSenderStillDoes()
    {
        receptionStaff = Arrays.asList(8L);
        others(9L);
        service.send(8L, 12L, "你好");
        verify(db, never()).update(anyString(), argThat(m -> Long.valueOf(9).equals(m.get("u"))));
        assertEquals("我：你好", row(8L).get("b"));
    }

    @Test void privateChatTitlesAreTheOtherPersonAndHiddenContactsStayHidden()
    {
        conv(p("convId", 12L, "convType", "private", "status", "active", "title", "私信", "pairKey", "7-9"));
        seats.put(9L, "member");
        when(db.one(startsWith("select app_user_id from kg_chat_member"), anyMap())).thenReturn(p("appUserId", 9L));
        others(9L);
        service.send(7L, 12L, "电话 13812345678");
        assertEquals(p("m", "user", "t", "小林", "b", "电话 [手机号已隐藏]", "f", "0"), pick(row(9L)));
        assertEquals(p("m", "user", "t", "老周", "b", "我：电话 [手机号已隐藏]", "f", "1"), pick(row(7L)));
    }

    @Test void groupRowsKeepGroupNameAndSpeaker()
    {
        conv(p("convId", 12L, "convType", "group", "status", "active", "title", "扎染体验 活动群"));
        seats.put(9L, "member");
        seats.put(7L, "member");
        others(9L);
        service.send(7L, 12L, "大家好");
        assertEquals(p("m", "user", "t", "扎染体验 活动群", "b", "小林：大家好", "f", "0"), pick(row(9L)));
        assertEquals(p("m", "user", "t", "扎染体验 活动群", "b", "我：大家好", "f", "1"), pick(row(7L)));
        assertEquals("group", row(7L).get("cat"));
        verify(db, never()).count(startsWith("select count(*) from kg_chat_msg m join kg_chat_member cm"), anyMap());
    }

    @Test void storeMembersSeeApplicantNameWhileApplicantStillSeesStoreName()
    {
        Map<String, Object> staffView = service.conversation(8L, 12L);
        assertEquals("小林", staffView.get("title"));
        assertEquals("线上任务 · 民宿文案 · 大理", staffView.get("otherMeta"));
        assertEquals("store", staffView.get("myRole"));
        Map<String, Object> applicantView = service.conversation(7L, 12L);
        assertEquals("海边小院", applicantView.get("title"));
        assertEquals("民宿文案 · 大理", applicantView.get("otherMeta"));
    }

    @Test void unlimitedOrEmptyCityIsLeftOutSoItDoesNotRunIntoTheReceptionNotice()
    {
        // 线上任务的城市是「不限」：之前写成「线上任务 · 民宿文案 · 不限」，页面上和接待说明连成「不限线上任务接待」
        when(db.one(startsWith("select j.title, j.city from kg_application"), anyMap())).thenReturn(p("title", "民宿文案", "city", "不限"));
        assertEquals("线上任务 · 民宿文案", service.conversation(8L, 12L).get("otherMeta"));
        assertEquals("民宿文案", service.conversation(7L, 12L).get("otherMeta"));
        when(db.one(startsWith("select j.title, j.city from kg_application"), anyMap())).thenReturn(p("title", "民宿文案", "city", ""));
        assertEquals("线上任务 · 民宿文案", service.conversation(8L, 12L).get("otherMeta"));
    }

    @Test void offlineConversationIsLabelledAsOfflineSignup()
    {
        conv(p("convId", 12L, "convType", "store", "status", "active", "title", "海边小院", "storeId", 22L,
                "pairKey", "offline:22:u7", "source", "progress", "sourceId", 300L));
        when(reception.isOnline(anyMap())).thenReturn(false);
        assertEquals("线下报名 · 民宿文案 · 大理", service.conversation(8L, 12L).get("otherMeta"));
    }

    // ---- 顶部接待说明（2026-10-15 主人反馈：员工以为是「接单人都在的群聊」）：写清楚是和谁的单独沟通、还有谁能看到

    @Test void staffSideNoticeNamesTheTakerAndTheColleaguesWhoCanAlsoSeeIt()
    {
        // 阿川（8）打开：同事 = 接待名单去掉自己；接单人小林不算同事
        assertEquals("这是和 小林 的单独沟通，其他接单人看不到；本店接待 老周 也能看到并回复。",
                service.conversation(8L, 12L).get("receptionNotice"));
        receptionStaff = Arrays.asList(8L, 9L, 10L);
        assertEquals("这是和 小林 的单独沟通，其他接单人看不到；本店接待 老周、小美 也能看到并回复。",
                service.conversation(8L, 12L).get("receptionNotice"));
        assertEquals("这是和 小林 的单独沟通，其他接单人看不到；本店接待 阿川、小美 也能看到并回复。",
                service.conversation(9L, 12L).get("receptionNotice"));
    }

    @Test void moreThanTwoColleaguesAreCountedInsteadOfNamed()
    {
        receptionStaff = Arrays.asList(8L, 9L, 10L, 11L);
        assertEquals("这是和 小林 的单独沟通，其他接单人看不到；本店 3 位同事也能看到并回复。",
                service.conversation(8L, 12L).get("receptionNotice"));
        // 只写人数时不去查同事的昵称
        verify(db, never()).one(startsWith("select user_id, nickname"), argThat(m -> Long.valueOf(10).equals(m.get("u"))));
    }

    @Test void noColleagueLeavesOutTheSecondHalfAndTheTakerIsNeverCountedAsColleague()
    {
        // 只有自己在接待名单里；接单人本人也是本店员工（同一个微信号）时同样不算同事
        receptionStaff = Arrays.asList(7L, 8L);
        assertEquals("这是和 小林 的单独沟通，其他接单人看不到。", service.conversation(8L, 12L).get("receptionNotice"));
    }

    @Test void applicantSideNoticeSaysStoreRepliesHereAndOtherTakersCannotSee()
    {
        assertEquals("门店接待会在这里回复你，其他接单人看不到。", service.conversation(7L, 12L).get("receptionNotice"));
        // 没有可用接待账号：两边都照旧提示「暂无可用接待账号」
        receptionStaff = Arrays.asList(7L);
        assertEquals("线上任务接待：当前暂无可用接待账号，请联系门店调整。", service.conversation(7L, 12L).get("receptionNotice"));
    }

    @Test void offlineSignupNoticeSaysApplicantInsteadOfTaker()
    {
        conv(p("convId", 12L, "convType", "store", "status", "active", "title", "海边小院", "storeId", 22L,
                "pairKey", "offline:22:u7", "source", "progress", "sourceId", 300L));
        when(reception.isOnline(anyMap())).thenReturn(false);
        // 线下报名的接待名单是本店全部在职员工（不去掉报名人），说明里同样不把报名人算成同事
        receptionStaff = Arrays.asList(7L, 8L, 9L);
        assertEquals("这是和 小林 的单独沟通，其他报名人看不到；本店接待 老周 也能看到并回复。",
                service.conversation(8L, 12L).get("receptionNotice"));
        assertEquals("门店接待会在这里回复你，其他报名人看不到。", service.conversation(7L, 12L).get("receptionNotice"));
    }

    @Test void archivedLegacyNoticeIsUnchanged()
    {
        conv(p("convId", 12L, "convType", "store", "status", "active", "title", "海边小院", "storeId", 22L,
                "pairKey", "s22-u7", "source", "online_task", "sourceId", 300L));
        assertEquals("历史沟通已归档；请从相应报名或线上任务重新联系。", service.conversation(8L, 12L).get("receptionNotice"));
        assertEquals("历史沟通已归档；请从相应报名或线上任务重新联系。", service.conversation(7L, 12L).get("receptionNotice"));
    }

    @Test void takerWithoutNicknameIsShownAsUserNumberInTitleAndNotice()
    {
        names.remove(7L);
        Map<String, Object> staffView = service.conversation(8L, 12L);
        assertEquals("用户7", staffView.get("title"));
        assertEquals("这是和 用户7 的单独沟通，其他接单人看不到；本店接待 老周 也能看到并回复。", staffView.get("receptionNotice"));
    }
}
