package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.service.IKgMessageService;
import com.ruoyi.system.service.ISysConfigService;

/** 邀请新人：邀请码、只给 24 小时内没报名下单的新账号绑定一次、不能自己邀请自己 / 互相邀请、邀请奖励每位朋友只发一次。 */
class KgInviteServiceTest
{
    private final KgJdbc db = mock(KgJdbc.class);

    private final ISysConfigService configs = mock(ISysConfigService.class);

    private final IKgMessageService messages = mock(IKgMessageService.class);

    private final KgInviteService service = new KgInviteService();

    private Map<String, Object> me;

    private Map<String, Object> inviter;

    @BeforeEach
    void setup()
    {
        KgPointLedger ledger = new KgPointLedger();
        ReflectionTestUtils.setField(ledger, "db", db);
        ReflectionTestUtils.setField(ledger, "configs", configs);
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "ledger", ledger);
        ReflectionTestUtils.setField(service, "messages", messages);
        me = new HashMap<String, Object>(p("userId", 7L, "status", "0", "delFlag", "0"));
        me.put("invitedBy", null);
        inviter = new HashMap<String, Object>(p("userId", 5L, "status", "0", "delFlag", "0"));
        inviter.put("invitedBy", null);
        when(db.one(contains("from kg_app_user where user_id = :u for update"), anyMap())).thenReturn(me);
        when(db.one(contains("fresh"), anyMap())).thenAnswer(i -> {
            Map<String, Object> r = new HashMap<String, Object>(p("fresh", 1L));
            r.put("invitedBy", me.get("invitedBy"));
            return r;
        });
        when(db.one(contains("where invite_code = :c"), anyMap())).thenReturn(inviter);
        when(db.update(contains("set invited_by = :i"), anyMap())).thenReturn(1);
        when(db.one(contains("from kg_badge_violation"), anyMap())).thenReturn(null);
    }

    @Test
    void codesUseAnUnambiguousAlphabetAndInputIsNormalized()
    {
        String code = KgInviteService.newCode(new Random(42));
        assertTrue(code.matches("[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{6}"), code);
        assertEquals("ABCDEF", KgInviteService.normalize(" ab cd ef "));
        assertNull(KgInviteService.normalize("ABC0O1"), "0 / O / 1 不在字符表里");
        assertNull(KgInviteService.normalize("ABC"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void freshAccountWithoutOrdersBindsOnce()
    {
        Map<String, Object> r = service.bind(7L, "abcdef");
        assertEquals(true, r.get("bound"));
        verify(db).update(contains("set invited_by = :i, invited_time = now() where user_id = :u and invited_by is null"),
                argThat(m -> Long.valueOf(5).equals(((Map<String, Object>) m).get("i")) && Long.valueOf(7).equals(((Map<String, Object>) m).get("u"))));
        when(db.update(contains("set invited_by = :i"), anyMap())).thenReturn(0);
        assertEquals(false, service.bind(7L, "ABCDEF").get("bound"), "并发第二次条件更新落空");
    }

    @Test
    void notEligibleAccountsAreIgnoredWithoutWriting()
    {
        assertEquals(false, service.bind(7L, "not-a-code").get("bound"));
        me.put("invitedBy", 3L);
        assertEquals(false, service.bind(7L, "ABCDEF").get("bound"), "已经绑过，不能改");
        me.put("invitedBy", null);
        when(db.one(contains("fresh"), anyMap())).thenReturn(p("fresh", 0L));
        assertEquals(false, service.bind(7L, "ABCDEF").get("bound"), "注册超过 24 小时");
        when(db.one(contains("fresh"), anyMap())).thenReturn(p("fresh", 1L));
        when(db.count(contains("kg_stay_order"), anyMap())).thenReturn(1L);
        assertEquals(false, service.bind(7L, "ABCDEF").get("bound"), "已经报过名或下过单");
        verify(db, never()).update(anyString(), anyMap());
    }

    @Test
    void selfMutualAndDisabledInvitersAreRefused()
    {
        inviter.put("userId", 7L);
        assertEquals(false, service.bind(7L, "ABCDEF").get("bound"), "不能填自己的码");
        inviter.put("userId", 5L);
        inviter.put("invitedBy", 7L);
        assertEquals(false, service.bind(7L, "ABCDEF").get("bound"), "对方是我邀请来的，不能互相邀请");
        inviter.put("invitedBy", null);
        inviter.put("delFlag", "2");
        assertEquals(false, service.bind(7L, "ABCDEF").get("bound"), "邀请人已注销");
        when(db.one(contains("where invite_code = :c"), anyMap())).thenReturn(null);
        assertEquals(false, service.bind(7L, "ABCDEF").get("bound"), "没有这个码");
        verify(db, never()).update(anyString(), anyMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    void inviterIsRewardedOncePerInviteeWithAMessage()
    {
        when(db.one(contains("select invited_by, nickname from kg_app_user"), anyMap())).thenReturn(p("invitedBy", 5L, "nickname", "小王同学"));
        when(db.one(contains("select status, del_flag from kg_app_user"), anyMap())).thenReturn(p("status", "0", "delFlag", "0"));
        when(db.update(startsWith("insert ignore into kg_point_entry"), anyMap())).thenReturn(1, 0);
        assertTrue(service.rewardInviter(7L, "volunteer", 102L, 1L));
        assertFalse(service.rewardInviter(7L, "online", 103L, null), "第一次线上任务在第一次换工之后：不再发");
        verify(db, times(2)).update(startsWith("insert ignore into kg_point_entry"), argThat(m -> "invite".equals(((Map<String, Object>) m).get("t"))
                && Long.valueOf(7).equals(((Map<String, Object>) m).get("b")) && Long.valueOf(5).equals(((Map<String, Object>) m).get("u"))
                && Integer.valueOf(100).equals(((Map<String, Object>) m).get("d"))));
        verify(messages, times(1)).send(eq(5L), eq("user"), eq("system"), anyString(), eq("你邀请的朋友完成了第一次换工，获得 100 积分"), eq("invite"), eq(7L), anyString());
    }

    @Test
    void noInviterOrPausedRewardSendsNothing()
    {
        when(db.one(contains("select invited_by, nickname from kg_app_user"), anyMap())).thenReturn(p("nickname", "小王"));
        assertFalse(service.rewardInviter(7L, "volunteer", 102L, 1L));
        when(db.one(contains("select invited_by, nickname from kg_app_user"), anyMap())).thenReturn(p("invitedBy", 5L, "nickname", "小王"));
        when(db.one(contains("select status, del_flag from kg_app_user"), anyMap())).thenReturn(p("status", "0", "delFlag", "0"));
        when(configs.selectConfigByKey(KgPointLedger.KEY_INVITE_REWARD)).thenReturn("0");
        assertFalse(service.rewardInviter(7L, "online", 102L, 1L), "运营把邀请奖励设为 0 = 暂停");
        verify(db, never()).update(anyString(), anyMap());
        verifyNoInteractions(messages);
    }

    private void invitedBy5()
    {
        when(db.one(contains("select invited_by, nickname from kg_app_user"), anyMap())).thenReturn(p("invitedBy", 5L, "nickname", "小王同学"));
        when(db.one(contains("select status, del_flag from kg_app_user"), anyMap())).thenReturn(p("status", "0", "delFlag", "0"));
        when(db.update(startsWith("insert ignore into kg_point_entry"), anyMap())).thenReturn(1);
    }

    @Test
    void inviterWhoConfirmedTheWorkHimselfGetsNothing()
    {
        invitedBy5();
        assertFalse(service.rewardInviter(7L, "volunteer", 102L, 5L), "确认换工完成的员工就是邀请人");
        assertFalse(service.rewardInviter(7L, "online", 102L, 5L), "验收线上任务的员工就是邀请人");
        verify(db, never()).update(anyString(), anyMap());
        verifyNoInteractions(messages);
    }

    @Test
    void inviterWhoIsStaffOfThatStoreGetsNothingButAnotherStoreLaterStillCounts()
    {
        invitedBy5();
        // 邀请人是 102 店的在职员工（任何角色），由同事确认 / 系统自动完成也不发
        when(db.count(contains("from kg_staff_grant"), eq(p("u", 5L, "s", 102L)))).thenReturn(1L);
        assertFalse(service.rewardInviter(7L, "volunteer", 102L, 1L));
        assertFalse(service.rewardInviter(7L, "volunteer", 102L, null));
        verify(db, times(2)).count(contains("where app_user_id = :u and store_id = :s and status = '0' and del_flag = '0'"), eq(p("u", 5L, "s", 102L)));
        verify(db, never()).update(anyString(), anyMap());
        // 这次没发不算用掉名额：朋友后来在别的门店（邀请人不是那里的员工）完成、由别人确认，照常发一次
        assertTrue(service.rewardInviter(7L, "online", 103L, 9L));
        verify(messages, times(1)).send(eq(5L), eq("user"), eq("system"), anyString(), eq("你邀请的朋友完成了第一次任务，获得 100 积分"), eq("invite"), eq(7L), anyString());
    }

    @Test
    void unknownStoreGetsNothing()
    {
        invitedBy5();
        assertFalse(service.rewardInviter(7L, "volunteer", null, 1L), "门店不明时宁可不发");
        verify(db, never()).update(anyString(), anyMap());
    }

    @Test
    void confirmedFraudBlocksOnlyTheRelatedInvitee()
    {
        invitedBy5();
        when(db.one(contains("from kg_badge_violation"), eq(p("u", 5L, "i", 7L)))).thenReturn(p("violationId", 91L));
        assertFalse(service.rewardInviter(7L, "volunteer", 102L, 1L));
        assertTrue(service.rewardInviter(8L, "volunteer", 102L, 1L));
        verify(db, times(1)).update(startsWith("insert ignore into kg_point_entry"), argThat(values -> Long.valueOf(8).equals(values.get("b"))));
    }

    @Test
    void inviteCodeIsCreatedLazilyAndRetriesOnCollision()
    {
        Map<String, Object> none = new HashMap<String, Object>();
        none.put("inviteCode", null);
        when(db.one(contains("select invite_code from kg_app_user where user_id = :u and del_flag"), anyMap())).thenReturn(none);
        when(db.update(contains("set invite_code = :c"), anyMap())).thenThrow(new DuplicateKeyException("uk_app_user_invite_code")).thenReturn(1);
        String code = service.ensureCode(7L);
        assertTrue(code.matches("[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{6}"), code);
        verify(db, times(2)).update(contains("set invite_code = :c where user_id = :u and invite_code is null"), anyMap());
        when(db.one(contains("select invite_code from kg_app_user where user_id = :u and del_flag"), anyMap())).thenReturn(p("inviteCode", "K7M2PQ"));
        assertEquals("K7M2PQ", service.ensureCode(7L), "已有的码直接用");
    }
}
