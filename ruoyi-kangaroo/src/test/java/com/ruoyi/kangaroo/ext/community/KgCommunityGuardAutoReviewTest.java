package com.ruoyi.kangaroo.ext.community;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.kangaroo.config.KangarooProperties;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.WxMsgSecCheck;

/**
 * review-mode=auto（2026-10-16 内测反馈 2.0）：只有本地检查 pass、并且微信内容安全接口判通过才放行；
 * 活动、行程这些照旧人工审核的内容写 review_mode 时记 manual（mode()），状态接口给配置原值（configuredMode()）。
 */
class KgCommunityGuardAutoReviewTest
{
    final KgJdbc db = mock(KgJdbc.class);
    final KangarooProperties props = new KangarooProperties();
    final WxMsgSecCheck sec = mock(WxMsgSecCheck.class);
    final KgCommunityGuard guard = new KgCommunityGuard();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(guard, "db", db);
        ReflectionTestUtils.setField(guard, "properties", props);
        ReflectionTestUtils.setField(guard, "secCheck", sec);
        props.setContentReviewMode("auto");
        when(db.one(startsWith("select openid from kg_app_user"), anyMap())).thenReturn(p("openid", "o-abc"));
        when(sec.passes(anyString(), anyInt(), any(), any())).thenReturn(true);
    }

    @Test
    void autoModeRecordsManualForEverythingElseButReportsAuto()
    {
        assertEquals("manual", guard.mode(), "活动 / 行程 / 找搭子在 auto 模式下仍是人工审核");
        assertEquals("auto", guard.configuredMode());
        assertTrue(guard.autoMode());
        assertEquals("pending", guard.initialStatus("pass"), "auto 模式不走 mock 的直接上线捷径");
        props.setContentReviewMode("mock");
        assertEquals("mock", guard.mode());
        assertEquals("online", guard.initialStatus("pass"));
        props.setContentReviewMode("manual");
        assertEquals("manual", guard.mode());
        assertFalse(guard.autoMode());
    }

    @Test
    void passesOnlyWhenLocalPassAndWechatPass()
    {
        assertTrue(guard.autoApprove("pass", 7L, WxMsgSecCheck.SCENE_COMMENT, "", "好看"));
        verify(db).one(eq("select openid from kg_app_user where user_id = :u and del_flag = '0' and status = '0'"), eq(p("u", 7L)));
        verify(sec).passes("o-abc", WxMsgSecCheck.SCENE_COMMENT, "", "好看");
    }

    @Test
    void wechatSayingNoKeepsItPending()
    {
        when(sec.passes(anyString(), anyInt(), any(), any())).thenReturn(false);
        assertFalse(guard.autoApprove("pass", 7L, WxMsgSecCheck.SCENE_FORUM, "标题", "正文"));
    }

    @Test
    void localReviewNeverAsksWechat()
    {
        assertFalse(guard.autoApprove("review", 7L, WxMsgSecCheck.SCENE_FORUM, "标题", "加我 13800138000"));
        verifyNoInteractions(sec);
    }

    @Test
    void notAutoModeNeverAsksWechat()
    {
        props.setContentReviewMode("manual");
        assertFalse(guard.autoApprove("pass", 7L, WxMsgSecCheck.SCENE_FORUM, "标题", "正文"));
        props.setContentReviewMode("mock");
        assertFalse(guard.autoApprove("pass", 7L, WxMsgSecCheck.SCENE_FORUM, "标题", "正文"));
        verifyNoInteractions(sec);
    }

    @Test
    void userWithoutOpenidPassesNullSoWechatCheckRefuses()
    {
        when(db.one(startsWith("select openid from kg_app_user"), anyMap())).thenReturn(null);
        when(sec.passes(isNull(), anyInt(), any(), any())).thenReturn(false);
        assertFalse(guard.autoApprove("pass", 7L, WxMsgSecCheck.SCENE_FORUM, "标题", "正文"));
        assertFalse(guard.autoApprove("pass", null, WxMsgSecCheck.SCENE_FORUM, "标题", "正文"));
    }

    @Test
    void missingSecCheckBeanKeepsPending()
    {
        ReflectionTestUtils.setField(guard, "secCheck", null);
        assertFalse(guard.autoApprove("pass", 7L, WxMsgSecCheck.SCENE_FORUM, "标题", "正文"));
    }
}
