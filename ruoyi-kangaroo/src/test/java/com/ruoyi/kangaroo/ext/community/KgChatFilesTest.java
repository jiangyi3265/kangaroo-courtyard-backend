package com.ruoyi.kangaroo.ext.community;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.staff.KgOnlineMaterialFiles;

/**
 * 私信文件的存放（V46）：格式和文件头沿用素材附件、内容 sha256、私有目录 + uuid 名、路径穿越、认人的签名链接、下载响应头、
 * 旧版本小程序看到的文字。不连库。
 */
class KgChatFilesTest
{
    static final byte[] PDF = "%PDF-1.7\n%测试".getBytes(StandardCharsets.UTF_8);
    static final byte[] ZIP = { 0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00, 0x08, 0x00 };
    static final byte[] EXE = { 0x4D, 0x5A, (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00, 0x04, 0x00 };

    @TempDir
    Path folder;
    String original;
    final KgChatFiles files = files("0123456789abcdef-chat-secret");

    static KgChatFiles files(String secret)
    {
        KgChatFiles f = new KgChatFiles(secret);
        ReflectionTestUtils.setField(f, "materialFiles", new KgOnlineMaterialFiles(secret));
        return f;
    }

    @BeforeEach void profile()
    {
        original = RuoYiConfig.getProfile();
        new RuoYiConfig().setProfile(folder.resolve("uploads").toString());
    }

    @AfterEach void restore()
    {
        new RuoYiConfig().setProfile(original);
    }

    static int code(Runnable r)
    {
        return assertThrows(ServiceException.class, r::run).getCode();
    }

    @Test void oldClientsSeeAnUpgradeHintAndListsShowTheFileName()
    {
        assertEquals("[文件] 报名材料.pdf", KgChatFiles.preview("报名材料.pdf"));
        assertEquals("[文件] 报名材料.pdf，请升级小程序查看", KgChatFiles.fallback("报名材料.pdf"));
    }

    @Test void viewTellsArchivesFromOpenableDocuments()
    {
        assertEquals(p("name", "作品集.zip", "size", 1536L, "sizeLabel", "1.5 KB", "ext", "zip", "kind", "archive", "openable", false),
                KgChatFiles.view("作品集.zip", "zip", 1536L));
        Map<String, Object> doc = KgChatFiles.view("合同.docx", "docx", 10485760L);
        assertEquals("10 MB", doc.get("sizeLabel"));
        assertEquals("doc", doc.get("kind"));
        assertEquals(true, doc.get("openable"));
    }

    @Test void checkUsesTheMaterialWhitelistMagicNumbersAndTenMegabyteLimit()
    {
        assertEquals(10L * 1024 * 1024, KgChatFiles.MAX_BYTES);
        assertEquals(422, code(() -> files.check(new MockMultipartFile("file", "tmp.exe", null, EXE), "安装包.exe")));
        assertEquals(422, code(() -> files.check(new MockMultipartFile("file", "tmp.pdf", null, EXE), "简历.pdf")));
        byte[] big = new byte[(int) KgChatFiles.MAX_BYTES + 1];
        System.arraycopy(ZIP, 0, big, 0, ZIP.length);
        assertEquals(422, code(() -> files.check(new MockMultipartFile("file", "big.zip", null, big), "big.zip")));
        KgChatFiles.Upload ok = files.check(new MockMultipartFile("file", "wxfile_tmp.pdf", null, PDF), "../报名材料 v2.PDF");
        assertEquals("报名材料 v2.PDF", ok.name);
        assertEquals("pdf", ok.ext);
        assertEquals(PDF.length, ok.size);
        assertTrue(ok.sha.matches("[0-9a-f]{64}"), ok.sha);
        assertEquals(ok.sha, files.check(new MockMultipartFile("file", "x.pdf", null, PDF), "另一个名字.pdf").sha, "同样内容同样的 sha");
        assertNotEquals(ok.sha, files.check(new MockMultipartFile("file", "x.zip", null, ZIP), "x.zip").sha);
    }

    @Test void storedUnderKgPrivateChatWithUuidNamesAndKeysCannotTraverse() throws Exception
    {
        String key = files.write(12L, new MockMultipartFile("file", "x.pdf", null, PDF), "pdf");
        assertTrue(key.matches("chat/12/[0-9a-f]{32}\\.pdf"), key);
        File f = files.resolve(key);
        assertNotNull(f);
        assertTrue(f.toPath().startsWith(folder.resolve("kg-private")), "在 profile 之外的 kg-private 下");
        assertArrayEquals(PDF, Files.readAllBytes(f.toPath()));
        for (String bad : Arrays.asList("chat/12/../../uploads/x.pdf", "chat/12/" + key.substring(8, 40) + ".exe", "../chat/12/a.pdf",
                "chat/0/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.pdf", "omat/12/" + key.substring(8), "chat/12/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA.pdf", null))
        {
            assertNull(files.resolve(bad), String.valueOf(bad));
        }
        files.delete(key);
        assertNull(files.resolve(key));
    }

    @Test void signedLinksAreBoundToMessageUserAndExpiry()
    {
        long exp = System.currentTimeMillis() / 1000 + 3600;
        String url = files.sign(73L, 7L, exp);
        assertTrue(url.startsWith("/kangaroo/file/chat?m=73&u=7&e=" + exp + "&s="), url);
        String sig = url.substring(url.indexOf("&s=") + 3);
        assertTrue(files.verify(73L, 7L, exp, sig));
        assertFalse(files.verify(74L, 7L, exp, sig), "换一条消息不能用");
        assertFalse(files.verify(73L, 8L, exp, sig), "换一个人不能用");
        assertFalse(files.verify(73L, 7L, exp + 1, sig), "改了有效期不能用");
        assertFalse(files.verify(73L, 7L, exp, null));
        assertFalse(files.verify(73L, 0L, exp, sig));
        long past = System.currentTimeMillis() / 1000 - 1;
        String old = files.sign(73L, 7L, past);
        assertFalse(files.verify(73L, 7L, past, old.substring(old.indexOf("&s=") + 3)), "过期");
        assertFalse(files("another-secret-0123456789").verify(73L, 7L, exp, sig), "另一个密钥签的不认");
        // 素材附件的签名不能拿来下私信文件
        String material = new KgOnlineMaterialFiles("0123456789abcdef-chat-secret").sign(73L, exp);
        assertFalse(files.verify(73L, 7L, exp, material.substring(material.indexOf("&s=") + 3)));
    }

    @Test void downloadIsAlwaysAnAttachmentWithLengthAndNoSniffing() throws Exception
    {
        String key = files.write(12L, new MockMultipartFile("file", "x.pdf", null, PDF), "pdf");
        MockHttpServletResponse res = new MockHttpServletResponse();
        files.serve(p("fileKey", key, "fileName", "报名材料 v2.pdf", "fileExt", "pdf"), res);
        assertEquals("application/pdf", res.getContentType());
        assertTrue(res.getHeader("Content-Disposition").startsWith("attachment; filename=\"chat-file.pdf\"; filename*=UTF-8''"), res.getHeader("Content-Disposition"));
        assertTrue(res.getHeader("Content-Disposition").contains("%20v2.pdf"));
        assertEquals("nosniff", res.getHeader("X-Content-Type-Options"));
        assertEquals("private, no-store", res.getHeader("Cache-Control"));
        assertEquals(PDF.length, res.getContentLengthLong());
        assertArrayEquals(PDF, res.getContentAsByteArray());
        assertEquals(404, code(() -> {
            try
            {
                files.serve(p("fileKey", "chat/12/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.pdf", "fileName", "x.pdf", "fileExt", "pdf"), new MockHttpServletResponse());
            }
            catch (java.io.IOException e)
            {
                throw new IllegalStateException(e);
            }
        }));
    }
}
