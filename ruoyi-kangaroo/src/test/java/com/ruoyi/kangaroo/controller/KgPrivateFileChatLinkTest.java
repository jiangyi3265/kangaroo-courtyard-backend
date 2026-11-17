package com.ruoyi.kangaroo.controller;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.community.KgChatFiles;
import com.ruoyi.kangaroo.ext.community.KgChatService;
import com.ruoyi.kangaroo.security.AppRateLimiter;

/** 私信文件的签名链接（V46）：按 IP 限流；签名不对、复制链接的人已不能看、文件没了都回 403；通过时按附件下载 */
class KgPrivateFileChatLinkTest
{
    final KgPrivateFileController controller = new KgPrivateFileController();
    final KgChatService chats = mock(KgChatService.class);
    final KgChatFiles files = mock(KgChatFiles.class);
    final AppRateLimiter limiter = mock(AppRateLimiter.class);

    KgPrivateFileController wired()
    {
        ReflectionTestUtils.setField(controller, "chats", chats);
        ReflectionTestUtils.setField(controller, "chatFiles", files);
        ReflectionTestUtils.setField(controller, "limiter", limiter);
        return controller;
    }

    @Test void validLinkServesTheFileForThatPerson() throws Exception
    {
        when(chats.signedFile(73L, 7L, 9999999999L, "sig")).thenReturn(p("fileKey", "chat/12/x.pdf", "fileName", "a.pdf", "fileExt", "pdf"));
        when(files.resolve("chat/12/x.pdf")).thenReturn(new File("x.pdf"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        wired().chat(73L, 7L, 9999999999L, "sig", res);
        verify(files).serve(argThat(m -> "chat/12/x.pdf".equals(m.get("fileKey"))), same(res));
        verify(limiter).checkByIp(eq("file-chat"), eq(120), eq(3600), anyString());
    }

    @Test void badSignatureRevokedPersonOrMissingFileAre403() throws Exception
    {
        MockHttpServletResponse bad = new MockHttpServletResponse();
        wired().chat(73L, 7L, 9999999999L, "bad", bad);
        assertEquals(403, bad.getStatus());
        when(chats.signedFile(73L, 7L, 9999999999L, "sig")).thenThrow(new ServiceException("你不在这个会话中", 403));
        MockHttpServletResponse revoked = new MockHttpServletResponse();
        wired().chat(73L, 7L, 9999999999L, "sig", revoked);
        assertEquals(403, revoked.getStatus());
        reset(chats);
        when(chats.signedFile(73L, 7L, 9999999999L, "sig")).thenReturn(p("fileKey", "chat/12/x.pdf"));
        MockHttpServletResponse gone = new MockHttpServletResponse();
        wired().chat(73L, 7L, 9999999999L, "sig", gone);
        assertEquals(403, gone.getStatus());
        verify(files, never()).serve(anyMap(), any());
    }

    @Test void tooManyRequestsFromOneIpGet429BeforeTheSignatureIsChecked() throws Exception
    {
        doThrow(new ServiceException("下载太频繁了，请稍后再试", 429)).when(limiter).checkByIp(anyString(), anyInt(), anyInt(), anyString());
        MockHttpServletResponse res = new MockHttpServletResponse();
        wired().chat(73L, 7L, 9999999999L, "sig", res);
        assertEquals(429, res.getStatus());
        verifyNoInteractions(chats);
    }
}
