package com.ruoyi.kangaroo.ext.community;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.Date;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;

/** Mock JDBC, not a real database test.
 * 私信交换联系方式（2026-10-11）：双方都同意之前，对方发来的手机号 / 微信号 / QQ 号显示成「已隐藏」，自己发的照常显示并提示。 */
class KgChatContactExchangeTest {
    static final String MEMBERS = "select app_user_id, contact_ok_time from kg_chat_member";
    static final String OTHER = "select app_user_id, muted, left_time from kg_chat_member";
    final KgJdbc db = mock(KgJdbc.class);
    final KgCommunityGuard guard = mock(KgCommunityGuard.class);
    final KgChatService service = new KgChatService();

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "db", db);
        when(db.one(startsWith("select config_value from sys_config"), anyMap())).thenReturn(p("configValue", "true"));
        ReflectionTestUtils.setField(service, "guard", guard);
        ReflectionTestUtils.setField(service, "grants", mock(IKgStaffGrantService.class));
        ReflectionTestUtils.setField(service, "reception", mock(KgChatReception.class));
        when(db.one(startsWith("select status, del_flag from kg_app_user"), anyMap())).thenReturn(p("status", "0", "delFlag", "0"));
        when(db.one(startsWith("select conv_type,store_id from kg_chat_conv"), anyMap())).thenReturn(p("convType", "private"));
        when(db.one(startsWith("select conv_id,conv_type,pair_key,source,store_id from kg_chat_conv"), anyMap())).thenReturn(p("convType", "private", "pairKey", "7-9"));
        when(db.one(startsWith("select * from kg_chat_member where conv_id = :c and app_user_id = :u and left_time is null"), anyMap())).thenReturn(p("role", "member", "muted", "0"));
        when(db.must(startsWith("select * from kg_chat_conv where conv_id = :c for update"), anyMap(), anyString()))
                .thenReturn(p("convId", 12L, "convType", "private", "pairKey", "7-9", "status", "active"));
        when(db.one(startsWith(OTHER), anyMap())).thenReturn(p("appUserId", 9L, "muted", "0"));
        when(db.one(startsWith("select user_id, nickname, avatar, phone_verified_time from kg_app_user"), anyMap())).thenReturn(p("nickname", "小林"));
    }

    void members(boolean me, boolean other) {
        when(db.list(startsWith(MEMBERS), anyMap())).thenReturn(Arrays.asList(
                p("appUserId", 7L, "contactOkTime", me ? new Date() : null), p("appUserId", 9L, "contactOkTime", other ? new Date() : null)));
    }

    @Test void masksPhoneWechatAndQqButNotOrdinaryNumbers() {
        assertEquals("电话 [手机号已隐藏] 随时联系", KgChatService.maskContacts("电话 13812345678 随时联系"));
        assertEquals("[手机号已隐藏]", KgChatService.maskContacts("138 1234 5678"));
        assertEquals("[手机号已隐藏]", KgChatService.maskContacts("138-1234-5678"));
        assertEquals("加我[微信号已隐藏]吧", KgChatService.maskContacts("加我微信：abc_12345吧"));
        assertEquals("[微信号已隐藏]", KgChatService.maskContacts("VX abcdef1"));
        assertEquals("我的[微信号已隐藏]", KgChatService.maskContacts("我的微信号是 hello2026"));
        assertEquals("[QQ号已隐藏]", KgChatService.maskContacts("QQ 12345678"));
        for (String plain : new String[] { "11月13号到", "订单号 2026101012345", "房费 1380 元", "明天 9 点见", "123813812345678", "" }) {
            assertEquals(plain, KgChatService.maskContacts(plain), plain);
        }
        assertNull(KgChatService.maskContacts(null));
    }

    @Test void stateIsOpenOnlyWhenBothAgreed() {
        members(true, false);
        assertEquals(p("mine", true, "theirs", false, "open", false), service.contactState(12L, 7L));
        assertEquals(p("mine", false, "theirs", true, "open", false), service.contactState(12L, 9L));
        members(true, true);
        assertEquals(Boolean.TRUE, service.contactState(12L, 7L).get("open"));
    }

    Map<String, Object> view(Map<String, Object> row, long me, boolean hide) {
        // 第 4 个参数是线上门店会话里的发布专员名单（2026-10-16 起，消息上方「专员 · 昵称」）；私信里是空的
        return ReflectionTestUtils.invokeMethod(service, "msgView", row, me, hide, java.util.Collections.<Long>emptyList());
    }

    @Test void otherSidesContactsAreHiddenUntilExchanged() {
        Map<String, Object> theirs = p("msgId", 1L, "senderId", 9L, "senderRole", "user", "nickname", "阿川", "content", "我电话 13812345678", "status", "visible", "createTime", new Date());
        assertEquals("我电话 [手机号已隐藏]", view(theirs, 7L, true).get("content"));
        assertNull(view(theirs, 7L, true).get("hiddenForOther"));
        assertEquals("我电话 13812345678", view(theirs, 7L, false).get("content"));
        Map<String, Object> mine = p("msgId", 2L, "senderId", 7L, "senderRole", "user", "nickname", "小林", "content", "加我 vx: lin_2026", "status", "visible", "createTime", new Date());
        Map<String, Object> mv = view(mine, 7L, true);
        assertEquals("加我 vx: lin_2026", mv.get("content"));
        assertEquals(Boolean.TRUE, mv.get("hiddenForOther"));
        Map<String, Object> plain = p("msgId", 3L, "senderId", 9L, "senderRole", "user", "content", "你好呀", "status", "visible", "createTime", new Date());
        assertEquals("你好呀", view(plain, 7L, true).get("content"));
        Map<String, Object> recalled = p("msgId", 4L, "senderId", 9L, "senderRole", "user", "content", "13812345678", "status", "recalled", "createTime", new Date());
        assertEquals("（消息已撤回）", view(recalled, 7L, true).get("content"));
    }

    @Test void agreeingRecordsMyConsentAndNotifiesTheOtherSide() {
        when(db.list(startsWith(MEMBERS), anyMap())).thenReturn(
                Arrays.asList(p("appUserId", 7L, "contactOkTime", null), p("appUserId", 9L, "contactOkTime", null)),
                Arrays.asList(p("appUserId", 7L, "contactOkTime", new Date()), p("appUserId", 9L, "contactOkTime", null)));
        Map<String, Object> state = service.exchangeContact(7L, 12L, true);
        assertEquals(p("mine", true, "theirs", false, "open", false), state);
        verify(db).update(eq("update kg_chat_member set contact_ok_time = now() where conv_id = :c and app_user_id = :u"), eq(p("c", 12L, "u", 7L)));
        verify(db).update(startsWith("insert into kg_message"), argThat((Map<String, Object> m) -> Long.valueOf(9L).equals(m.get("u"))
                && String.valueOf(m.get("b")).startsWith("想和你交换联系方式")));
        verify(guard).requireNotBlocked(7L, 9L);
    }

    @Test void secondAgreementOpensAndTellsTheFirst() {
        when(db.list(startsWith(MEMBERS), anyMap())).thenReturn(
                Arrays.asList(p("appUserId", 7L, "contactOkTime", null), p("appUserId", 9L, "contactOkTime", new Date())),
                Arrays.asList(p("appUserId", 7L, "contactOkTime", new Date()), p("appUserId", 9L, "contactOkTime", new Date())));
        assertEquals(Boolean.TRUE, service.exchangeContact(7L, 12L, true).get("open"));
        verify(db).update(startsWith("insert into kg_message"), argThat((Map<String, Object> m) -> String.valueOf(m.get("b")).startsWith("同意了交换联系方式")));
    }

    @Test void mutedOtherSideIsNotNotified() {
        when(db.one(startsWith(OTHER), anyMap())).thenReturn(p("appUserId", 9L, "muted", "1"));
        when(db.list(startsWith(MEMBERS), anyMap())).thenReturn(
                Arrays.asList(p("appUserId", 7L, "contactOkTime", null), p("appUserId", 9L, "contactOkTime", null)),
                Arrays.asList(p("appUserId", 7L, "contactOkTime", new Date()), p("appUserId", 9L, "contactOkTime", null)));
        service.exchangeContact(7L, 12L, true);
        verify(db, never()).update(startsWith("insert into kg_message"), anyMap());
        verify(db, never()).update(startsWith("update kg_message"), anyMap());
    }

    @Test void repeatedClickChangesNothing() {
        members(true, false);
        assertEquals(Boolean.TRUE, service.exchangeContact(7L, 12L, true).get("mine"));
        verify(db, never()).update(startsWith("update kg_chat_member"), anyMap());
    }

    @Test void withdrawBeforeOpenButNotAfter() {
        when(db.list(startsWith(MEMBERS), anyMap())).thenReturn(
                Arrays.asList(p("appUserId", 7L, "contactOkTime", new Date()), p("appUserId", 9L, "contactOkTime", null)),
                Arrays.asList(p("appUserId", 7L, "contactOkTime", null), p("appUserId", 9L, "contactOkTime", null)));
        assertEquals(Boolean.FALSE, service.exchangeContact(7L, 12L, false).get("mine"));
        verify(db).update(eq("update kg_chat_member set contact_ok_time = null where conv_id = :c and app_user_id = :u"), anyMap());
        verify(db, never()).update(startsWith("insert into kg_message"), anyMap());

        members(true, true);
        ServiceException e = assertThrows(ServiceException.class, () -> service.exchangeContact(7L, 12L, false));
        assertEquals(409, e.getCode());
    }

    @Test void onlyPrivateChatsCanExchange() {
        when(db.must(startsWith("select * from kg_chat_conv where conv_id = :c for update"), anyMap(), anyString()))
                .thenReturn(p("convId", 12L, "convType", "group", "status", "active"));
        ServiceException e = assertThrows(ServiceException.class, () -> service.exchangeContact(7L, 12L, true));
        assertEquals(422, e.getCode());
        verify(db, never()).update(startsWith("update kg_chat_member"), anyMap());
    }

    @Test void blockedPairCannotExchange() {
        members(false, false);
        doThrow(new ServiceException("存在拉黑关系", 403)).when(guard).requireNotBlocked(7L, 9L);
        assertEquals(403, assertThrows(ServiceException.class, () -> service.exchangeContact(7L, 12L, true)).getCode());
        verify(db, never()).update(startsWith("update kg_chat_member"), anyMap());
    }
}
