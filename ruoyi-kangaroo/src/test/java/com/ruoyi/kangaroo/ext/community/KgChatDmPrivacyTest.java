package com.ruoyi.kangaroo.ext.community;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;

/** Mock JDBC, not a real database test.
 * 私信规则（2026-10-11 内测建议）：「谁可以私信我」所有人 / 我关注的人 / 互相关注的人；互相关注不限条数，
 * 否则对方回复前只能先发 1 条（之前是 3 条）。只管用户之间的私信。 */
class KgChatDmPrivacyTest {
    static final String PRIVACY = "select dm_privacy from kg_app_user";
    static final String FOLLOW = "select count(*) from kg_user_follow where follower_id = :a and followee_id = :b";
    static final String MUTUAL = "select count(*) from kg_user_follow where (follower_id = :a";
    static final String THEIRS = "select count(*) from kg_chat_msg where conv_id = :c and sender_id <> :u and sender_role = 'user'";
    static final String MINE = "select count(*) from kg_chat_msg where conv_id = :c and sender_id = :u";
    static final String TALKED = "select count(*) from kg_chat_msg where conv_id = :c and sender_id = :o";

    // ---------------------------------------------------------------- 守门：谁可以私信我

    final KgJdbc gdb = mock(KgJdbc.class);
    final KgCommunityGuard realGuard = new KgCommunityGuard();

    void privacy(String v) { when(gdb.one(startsWith(PRIVACY), anyMap())).thenReturn(v == null ? null : p("dmPrivacy", v)); }
    /** a 关注了 b */
    void follow(long a, long b, boolean on) { when(gdb.count(eq(FOLLOW), eq(p("a", a, "b", b)))).thenReturn(on ? 1L : 0L); }
    void mutual(boolean on) { when(gdb.count(startsWith(MUTUAL), anyMap())).thenReturn(on ? 2L : 1L); }

    @Test void everyoneByDefaultAndForUnknownValues() {
        ReflectionTestUtils.setField(realGuard, "db", gdb);
        for (String v : new String[] { null, "all", "", "nobody" }) {
            privacy(v);
            assertEquals("all", realGuard.dmPrivacy(9L));
            assertNull(realGuard.dmRefusal(7L, 9L));
        }
    }

    @Test void followingOnlyLetsPeopleTheTargetFollows() {
        ReflectionTestUtils.setField(realGuard, "db", gdb);
        privacy("following");
        follow(9L, 7L, false);
        String reason = realGuard.dmRefusal(7L, 9L);
        assertNotNull(reason);
        assertTrue(reason.contains("TA 关注的人"));
        follow(9L, 7L, true);
        assertNull(realGuard.dmRefusal(7L, 9L));
    }

    @Test void mutualOnlyNeedsBothDirections() {
        ReflectionTestUtils.setField(realGuard, "db", gdb);
        privacy("mutual");
        mutual(false);
        assertTrue(realGuard.dmRefusal(7L, 9L).contains("互相关注"));
        mutual(true);
        assertNull(realGuard.dmRefusal(7L, 9L));
        assertFalse(realGuard.mutualFollow(7L, 7L));
    }

    @Test void savingRejectsUnknownSetting() {
        ReflectionTestUtils.setField(realGuard, "db", gdb);
        assertEquals(422, assertThrows(ServiceException.class, () -> realGuard.setDmPrivacy(7L, "nobody")).getCode());
        assertEquals(422, assertThrows(ServiceException.class, () -> realGuard.setDmPrivacy(7L, null)).getCode());
        verify(gdb, never()).update(anyString(), anyMap());
        realGuard.setDmPrivacy(7L, "mutual");
        verify(gdb).update(eq("update kg_app_user set dm_privacy = :v where user_id = :u"), eq(p("v", "mutual", "u", 7L)));
    }

    // ---------------------------------------------------------------- 私信：发起与发送

    final KgJdbc db = mock(KgJdbc.class);
    final KgCommunityGuard guard = mock(KgCommunityGuard.class);
    final KgChatService service = spy(new KgChatService());

    @BeforeEach void setup() {
        // 本组检查开启私信后的隐私规则；功能开关另有专项测试。
        when(db.one(eq("select config_value from sys_config where config_key=:k"), eq(p("k", KgCommunityFeature.PRIVATE_KEY))))
                .thenReturn(p("configValue", "true"));
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "guard", guard);
        ReflectionTestUtils.setField(service, "grants", mock(IKgStaffGrantService.class));
        ReflectionTestUtils.setField(service, "reception", mock(KgChatReception.class));
        when(db.one(startsWith("select status, del_flag from kg_app_user"), anyMap())).thenReturn(p("status", "0", "delFlag", "0"));
        doReturn(p("convId", 12L)).when(service).conversation(anyLong(), anyLong());
        // 发送用的会话与成员
        when(db.one(startsWith("select conv_type,store_id from kg_chat_conv"), anyMap())).thenReturn(p("convType", "private"));
        when(db.one(startsWith("select conv_id,conv_type,pair_key,source,store_id from kg_chat_conv"), anyMap())).thenReturn(p("convType", "private", "pairKey", "7-9"));
        when(db.one(startsWith("select * from kg_chat_member where conv_id = :c and app_user_id = :u and left_time is null"), anyMap())).thenReturn(p("role", "member", "muted", "0"));
        when(db.must(startsWith("select * from kg_chat_conv where conv_id = :c for update"), anyMap(), anyString()))
                .thenReturn(p("convId", 12L, "convType", "private", "pairKey", "7-9", "status", "active"));
        when(db.one(startsWith("select app_user_id from kg_chat_member"), anyMap())).thenReturn(p("appUserId", 9L));
        when(db.one(startsWith("select user_id, nickname, avatar, phone_verified_time from kg_app_user"), anyMap())).thenReturn(p("nickname", "小林"));
        when(db.insert(eq("kg_chat_msg"), anyMap())).thenReturn(88L);
    }

    @Test void openRefusedWith403WhenTargetDoesNotAllowMe() {
        when(guard.dmRefusal(7L, 9L)).thenReturn("对方设置了只接收互相关注的人的私信，互相关注后才能发起私信");
        ServiceException e = assertThrows(ServiceException.class, () -> service.openPrivate(7L, 9L, "profile", null));
        assertEquals(403, e.getCode());
        assertTrue(e.getMessage().contains("互相关注"));
        verify(db, never()).insert(anyString(), anyMap());
        verify(db, never()).update(anyString(), anyMap());
    }

    @Test void openAllowedWhenTargetAllows() {
        when(db.one(startsWith("select conv_id from kg_chat_conv where pair_key"), anyMap())).thenReturn(null);
        when(guard.dmRefusal(7L, 9L)).thenReturn(null);
        assertEquals(12L, service.openPrivate(7L, 9L, "profile", null).get("convId"));
        verify(db).insert(eq("kg_chat_conv"), argThat(row -> "private".equals(row.get("conv_type")) && "7-9".equals(row.get("pair_key"))));
    }

    @Test void existingChatWhereTargetAlreadyRepliedIsNotRefused() {
        when(db.one(startsWith("select conv_id from kg_chat_conv where pair_key"), anyMap())).thenReturn(p("convId", 12L));
        when(db.count(startsWith(TALKED), anyMap())).thenReturn(2L);
        when(guard.dmRefusal(7L, 9L)).thenReturn("对方设置了只接收 TA 关注的人的私信，暂时不能发起私信");
        assertEquals(12L, service.openPrivate(7L, 9L, "profile", null).get("convId"));
        verify(guard, never()).dmRefusal(anyLong(), anyLong());
    }

    @Test void strangerMaySendOnlyOneMessageBeforeReply() {
        assertEquals(1, KgChatService.GREET_LIMIT);
        when(db.count(startsWith(THEIRS), anyMap())).thenReturn(0L);
        when(db.count(startsWith(MINE), anyMap())).thenReturn(0L);
        assertEquals(88L, service.send(7L, 12L, "你好").get("msgId"));
        when(db.count(startsWith(MINE), anyMap())).thenReturn(1L);
        ServiceException e = assertThrows(ServiceException.class, () -> service.send(7L, 12L, "在吗"));
        assertEquals(409, e.getCode());
        assertTrue(e.getMessage().contains("只能先发 1 条"));
        verify(db, times(1)).insert(eq("kg_chat_msg"), anyMap());
    }

    @Test void mutualFollowersAreUnlimited() {
        when(db.count(startsWith(THEIRS), anyMap())).thenReturn(0L);
        when(db.count(startsWith(MINE), anyMap())).thenReturn(5L);
        when(guard.mutualFollow(7L, 9L)).thenReturn(true);
        assertEquals(88L, service.send(7L, 12L, "第六条").get("msgId"));
    }

    @Test void afterReplyThereIsNoLimit() {
        when(db.count(startsWith(THEIRS), anyMap())).thenReturn(1L);
        when(db.count(startsWith(MINE), anyMap())).thenReturn(9L);
        assertEquals(88L, service.send(7L, 12L, "继续聊").get("msgId"));
        verify(guard, never()).dmRefusal(anyLong(), anyLong());
    }

    @Test void settingChangedAfterOpenStillStopsUnrepliedStranger() {
        when(db.count(startsWith(THEIRS), anyMap())).thenReturn(0L);
        when(db.count(startsWith(MINE), anyMap())).thenReturn(0L);
        when(guard.dmRefusal(7L, 9L)).thenReturn("对方设置了只接收 TA 关注的人的私信，暂时不能发起私信");
        assertEquals(403, assertThrows(ServiceException.class, () -> service.send(7L, 12L, "你好")).getCode());
        verify(db, never()).insert(eq("kg_chat_msg"), anyMap());
    }

    @Test void storeChatsAreNotLimited() {
        when(db.must(startsWith("select * from kg_chat_conv where conv_id = :c for update"), anyMap(), anyString()))
                .thenReturn(p("convId", 12L, "convType", "group", "status", "active", "title", "活动群"));
        when(db.count(startsWith(MINE), anyMap())).thenReturn(9L);
        when(guard.dmRefusal(anyLong(), anyLong())).thenReturn("不该被调用");
        assertEquals(88L, service.send(7L, 12L, "大家好").get("msgId"));
        verify(guard, never()).dmRefusal(anyLong(), anyLong());
    }
}
