package com.ruoyi.kangaroo.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.core.KgPrivateFiles;
import com.ruoyi.kangaroo.security.AppRateLimiter;

/** 私有文件签名下载：nosniff、inline 文件名只用 uuid 名、按 IP 限流；/profile/** 一律 nosniff（不连库） */
class KgPrivateFileControllerTest
{
    @TempDir
    Path tmp;

    private final KgPrivateFileController controller = new KgPrivateFileController();
    private final KgPrivateFiles files = mock(KgPrivateFiles.class);
    private final AppRateLimiter limiter = mock(AppRateLimiter.class);

    private KgPrivateFileController wired()
    {
        ReflectionTestUtils.setField(controller, "files", files);
        ReflectionTestUtils.setField(controller, "limiter", limiter);
        return controller;
    }

    @Test
    void downloadIsInlineNoSniffAndNamedByTheUuidOnly() throws Exception
    {
        File f = tmp.resolve("0123456789abcdef0123456789abcdef.png").toFile();
        Files.write(f.toPath(), new byte[] { (byte) 0x89, 'P', 'N', 'G' });
        String key = "cert/20261016/0123456789abcdef0123456789abcdef.png";
        when(files.verify(eq(key), anyLong(), eq("sig"))).thenReturn(true);
        when(files.resolve(key)).thenReturn(f);
        MockHttpServletResponse res = new MockHttpServletResponse();
        wired().download(key, 9999999999L, "sig", res);
        assertEquals(200, res.getStatus());
        assertEquals("image/png", res.getContentType());
        assertEquals("nosniff", res.getHeader("X-Content-Type-Options"));
        assertEquals("inline; filename=\"0123456789abcdef0123456789abcdef.png\"", res.getHeader("Content-Disposition"));
        verify(limiter).checkByIp(eq("file-private"), anyInt(), anyInt(), anyString());
    }

    @Test
    void tooManyRequestsFromOneIpGet429BeforeTheSignatureIsChecked() throws Exception
    {
        doThrow(new ServiceException("访问太频繁了，请稍后再试", 429)).when(limiter).checkByIp(anyString(), anyInt(), anyInt(), anyString());
        MockHttpServletResponse res = new MockHttpServletResponse();
        wired().download("cert/20261016/0123456789abcdef0123456789abcdef.png", 9999999999L, "sig", res);
        assertEquals(429, res.getStatus());
        verifyNoInteractions(files);
    }

    @Test
    void profileResponsesAreNoSniff() throws Exception
    {
        Object interceptor = new com.ruoyi.kangaroo.config.KangarooWebMvcConfig.NoSniffInterceptor();
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertTrue(((org.springframework.web.servlet.HandlerInterceptor) interceptor).preHandle(new MockHttpServletRequest("GET", "/profile/upload/a.jpg"), res, new Object()));
        assertEquals("nosniff", res.getHeader("X-Content-Type-Options"));
    }
}
