package com.ruoyi.kangaroo.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.kangaroo.config.KangarooProperties;

/**
 * 微信文本内容安全检查（msgSecCheck v2）：只有明确判「通过」才放行；出错、超时、需复核、有风险、没 openid 一律不放行（调用方照旧待审）。
 * 不连真实微信：替换 post()，看发出去的请求体和对各种返回的判定。
 */
class WxMsgSecCheckTest
{
    static final String PASS = "{\"errcode\":0,\"errmsg\":\"ok\",\"result\":{\"suggest\":\"pass\",\"label\":100},\"trace_id\":\"t\"}";

    final KangarooProperties props = new KangarooProperties();
    final WxMiniAppClient wx = mock(WxMiniAppClient.class);
    final List<String> sentUrls = new ArrayList<String>();
    final List<String> sentBodies = new ArrayList<String>();
    volatile String reply = PASS;
    volatile long delayMs = 0;
    volatile RuntimeException fail;

    final WxMsgSecCheck check = new WxMsgSecCheck()
    {
        @Override
        protected String post(String url, String json, int timeoutMs) throws Exception
        {
            sentUrls.add(url);
            sentBodies.add(json);
            if (delayMs > 0)
            {
                Thread.sleep(delayMs);
            }
            if (fail != null)
            {
                throw fail;
            }
            return reply;
        }
    };

    @BeforeEach
    void setup() throws Exception
    {
        props.setWxAppId("wx-test-appid");
        props.setWxAppSecret("test-secret");
        props.setWxMockEnabled(false);
        props.setContentSecCheckTimeoutMs(500);
        ReflectionTestUtils.setField(check, "properties", props);
        ReflectionTestUtils.setField(check, "wx", wx);
        when(wx.sharedAccessToken()).thenReturn("TOKEN");
    }

    @AfterEach
    void down()
    {
        check.shutdown();
    }

    @Test
    void clearPassIsAccepted()
    {
        assertTrue(check.passes("o-abc", WxMsgSecCheck.SCENE_FORUM, "洱海日落", "傍晚的风很舒服"));
        assertEquals("https://api.weixin.qq.com/wxa/msg_sec_check?access_token=TOKEN", sentUrls.get(0));
        JSONObject body = JSON.parseObject(sentBodies.get(0));
        assertEquals(2, body.getIntValue("version"));
        assertEquals(3, body.getIntValue("scene"));
        assertEquals("o-abc", body.getString("openid"));
        assertEquals("傍晚的风很舒服", body.getString("content"));
        assertEquals("洱海日落", body.getString("title"));
    }

    @Test
    void titleOnlyPostSendsTheTitleAsContent()
    {
        assertTrue(check.passes("o-abc", WxMsgSecCheck.SCENE_FORUM, "洱海日落", ""));
        JSONObject body = JSON.parseObject(sentBodies.get(0));
        assertEquals("洱海日落", body.getString("content"));
        assertFalse(body.containsKey("title"));
    }

    @Test
    void commentUsesTheCommentScene()
    {
        assertTrue(check.passes("o-abc", WxMsgSecCheck.SCENE_COMMENT, "", "好看"));
        assertEquals(2, JSON.parseObject(sentBodies.get(0)).getIntValue("scene"));
    }

    @Test
    void riskyOrReviewOrOddAnswersAreRefused()
    {
        for (String r : new String[] {
                "{\"errcode\":0,\"result\":{\"suggest\":\"risky\",\"label\":20001}}",
                "{\"errcode\":0,\"result\":{\"suggest\":\"review\",\"label\":21000}}",
                "{\"errcode\":0,\"result\":{\"suggest\":\"pass\",\"label\":20001}}",
                "{\"errcode\":0,\"result\":{\"suggest\":\"pass\"}}",
                "{\"errcode\":0}",
                "{\"errcode\":61010,\"errmsg\":\"user not active\"}",
                "{\"errcode\":\"0\",\"result\":{\"suggest\":\"pass\",\"label\":100}}",
                "{}", "", "not json" })
        {
            reply = r;
            assertFalse(check.passes("o-abc", WxMsgSecCheck.SCENE_FORUM, "t", "c"), r);
        }
    }

    @Test
    void expiredTokenIsDroppedAndRefused()
    {
        reply = "{\"errcode\":40001,\"errmsg\":\"invalid credential\"}";
        assertFalse(check.passes("o-abc", WxMsgSecCheck.SCENE_FORUM, "t", "c"));
        verify(wx).invalidateAccessToken();
    }

    @Test
    void networkErrorIsRefused()
    {
        fail = new IllegalStateException("connect refused https://api.weixin.qq.com/?access_token=TOKEN");
        assertFalse(check.passes("o-abc", WxMsgSecCheck.SCENE_FORUM, "t", "c"));
    }

    @Test
    void tokenFailureIsRefused() throws Exception
    {
        when(wx.sharedAccessToken()).thenThrow(new RuntimeException("token down"));
        assertFalse(check.passes("o-abc", WxMsgSecCheck.SCENE_FORUM, "t", "c"));
        assertTrue(sentUrls.isEmpty());
    }

    @Test
    void slowAnswerTimesOutWithinTheBudget()
    {
        delayMs = 3000;
        long t0 = System.currentTimeMillis();
        assertFalse(check.passes("o-abc", WxMsgSecCheck.SCENE_FORUM, "t", "c"));
        long spent = System.currentTimeMillis() - t0;
        assertTrue(spent < 1500, "超时按配置的 500 毫秒算，实际等了 " + spent);
    }

    @Test
    void noOpenidOrNoTextOrNoWechatConfigNeverCalls()
    {
        assertFalse(check.passes(null, WxMsgSecCheck.SCENE_FORUM, "t", "c"));
        assertFalse(check.passes("", WxMsgSecCheck.SCENE_FORUM, "t", "c"));
        assertFalse(check.passes("o-abc", WxMsgSecCheck.SCENE_FORUM, " ", null));
        props.setWxAppSecret("");
        assertFalse(check.passes("o-abc", WxMsgSecCheck.SCENE_FORUM, "t", "c"));
        assertTrue(sentUrls.isEmpty());
    }

    @Test
    void localMockNeverCallsWechatAndHonoursTheMarkers()
    {
        props.setWxMockEnabled(true);
        props.setWxAppSecret("");
        assertTrue(check.passes("mock-openid", WxMsgSecCheck.SCENE_FORUM, "t", "正常内容"));
        assertFalse(check.passes("mock-openid", WxMsgSecCheck.SCENE_FORUM, "t", "内容 " + WxMsgSecCheck.MOCK_RISKY));
        long t0 = System.currentTimeMillis();
        assertFalse(check.passes("mock-openid", WxMsgSecCheck.SCENE_COMMENT, "", WxMsgSecCheck.MOCK_TIMEOUT));
        assertTrue(System.currentTimeMillis() - t0 < 1500);
        assertTrue(sentUrls.isEmpty());
    }

    @Test
    void verdictNeedsAllThreeSignals()
    {
        assertTrue(WxMsgSecCheck.verdictPass(JSON.parseObject(PASS)));
        assertFalse(WxMsgSecCheck.verdictPass(null));
    }
}
