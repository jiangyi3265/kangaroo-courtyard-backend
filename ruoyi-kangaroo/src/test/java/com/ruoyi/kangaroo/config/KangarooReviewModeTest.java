package com.ruoyi.kangaroo.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * kangaroo.content.review-mode（2026-10-16 内测反馈 2.0）：新增 auto，生产可用（不要求本地联调模式）；
 * 默认仍是 manual；mock 照旧只许本地联调；不认识的值启动失败。没配微信凭证时 auto 不拦启动（机审调不通，一律转人工）。
 */
class KangarooReviewModeTest
{
    static KangarooProperties props(String reviewMode, boolean wxMock, boolean wxConfigured)
    {
        KangarooProperties p = new KangarooProperties();
        p.setPayMode("off");
        ReflectionTestUtils.setField(p, "smsMode", "none");
        ReflectionTestUtils.setField(p, "realNameMode", "none");
        ReflectionTestUtils.setField(p, "subscribeMode", "none");
        p.setWxMockEnabled(wxMock);
        p.setWxAppId(wxConfigured ? "wx-appid" : "");
        p.setWxAppSecret(wxConfigured ? "secret" : "");
        p.setContentReviewMode(reviewMode);
        return p;
    }

    @Test
    void autoIsAllowedInProductionLikeSetup()
    {
        KangarooProperties p = props("auto", false, true);
        assertDoesNotThrow(p::check);
        assertEquals("auto", p.getContentReviewMode());
    }

    @Test
    void autoWithoutWechatCredentialsStillStarts()
    {
        assertDoesNotThrow(props("auto", false, false)::check);
    }

    @Test
    void manualStaysValidAndMockStaysLocalOnly()
    {
        assertDoesNotThrow(props("manual", false, true)::check);
        IllegalStateException e = assertThrows(IllegalStateException.class, props("mock", false, true)::check);
        assertTrue(e.getMessage().contains("mock-enabled=true"), e.getMessage());
    }

    @Test
    void unknownModeFailsStartupAndNamesAuto()
    {
        IllegalStateException e = assertThrows(IllegalStateException.class, props("machine", false, true)::check);
        assertTrue(e.getMessage().contains("manual / mock / auto"), e.getMessage());
    }

    @Test
    void defaultsAreManualAndTwoSeconds() throws Exception
    {
        java.lang.reflect.Field f = KangarooProperties.class.getDeclaredField("contentReviewMode");
        assertEquals("${kangaroo.content.review-mode:manual}", f.getAnnotation(org.springframework.beans.factory.annotation.Value.class).value());
        java.lang.reflect.Field t = KangarooProperties.class.getDeclaredField("contentSecCheckTimeoutMs");
        assertEquals("${kangaroo.content.sec-check-timeout-ms:2000}", t.getAnnotation(org.springframework.beans.factory.annotation.Value.class).value());
        assertEquals(2000L, new KangarooProperties().getContentSecCheckTimeoutMs());
    }
}
