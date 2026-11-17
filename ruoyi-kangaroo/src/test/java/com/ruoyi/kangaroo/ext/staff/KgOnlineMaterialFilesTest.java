package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * 线上任务素材附件的文件存放（V41）：扩展名白名单 + 文件头、文件名清洗、路径穿越、签名链接、下载响应头、没发出去的清理。不连库。
 */
class KgOnlineMaterialFilesTest
{
    private static final byte[] ZIP = { 0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00, 0x08, 0x00 };
    private static final byte[] EMPTY_ZIP = { 0x50, 0x4B, 0x05, 0x06, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 };
    private static final byte[] RAR = { 0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00, 0x00, 0x00 };
    private static final byte[] SEVEN_Z = { 0x37, 0x7A, (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C, 0x00, 0x04, 0x00, 0x00 };
    private static final byte[] PDF = "%PDF-1.7\n%".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] OLE = { (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0x00, 0x00 };
    private static final byte[] EXE = { 0x4D, 0x5A, (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00, 0x04, 0x00 };

    private final KgOnlineMaterialFiles files = new KgOnlineMaterialFiles("0123456789abcdef-test-secret");

    @Test
    void whitelistAndMagicNumbersMustAgree()
    {
        assertTrue(KgOnlineMaterialFiles.magicOk("zip", ZIP));
        assertTrue(KgOnlineMaterialFiles.magicOk("zip", EMPTY_ZIP));
        for (String ext : Arrays.asList("docx", "xlsx", "pptx"))
        {
            assertTrue(KgOnlineMaterialFiles.magicOk(ext, ZIP), ext);
        }
        assertTrue(KgOnlineMaterialFiles.magicOk("rar", RAR));
        assertTrue(KgOnlineMaterialFiles.magicOk("7z", SEVEN_Z));
        assertTrue(KgOnlineMaterialFiles.magicOk("pdf", PDF));
        for (String ext : Arrays.asList("doc", "xls", "ppt"))
        {
            assertTrue(KgOnlineMaterialFiles.magicOk(ext, OLE), ext);
        }
        // 改了扩展名的程序、互相冒充的格式、太短的内容一律不认
        assertFalse(KgOnlineMaterialFiles.magicOk("zip", EXE));
        assertFalse(KgOnlineMaterialFiles.magicOk("pdf", ZIP));
        assertFalse(KgOnlineMaterialFiles.magicOk("rar", SEVEN_Z));
        assertFalse(KgOnlineMaterialFiles.magicOk("doc", PDF));
        assertFalse(KgOnlineMaterialFiles.magicOk("exe", EXE));
        assertFalse(KgOnlineMaterialFiles.magicOk("zip", new byte[] { 0x50, 0x4B }));
        for (String ext : Arrays.asList("zip", "rar", "7z", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx"))
        {
            assertTrue(KgOnlineMaterialFiles.allowed(ext), ext);
        }
        for (String ext : Arrays.asList("exe", "apk", "js", "html", "svg", "jpg", "", "tar"))
        {
            assertFalse(KgOnlineMaterialFiles.allowed(ext), ext);
        }
    }

    @Test
    void displayNamesLoseDirectoriesControlCharactersAndReservedSymbols()
    {
        assertEquals("zip", KgOnlineMaterialFiles.extOf("素材.ZIP"));
        assertEquals("", KgOnlineMaterialFiles.extOf("没有扩展名"));
        assertEquals("", KgOnlineMaterialFiles.extOf("结尾是点."));
        assertEquals("passwd.zip", KgOnlineMaterialFiles.cleanName("../../etc/passwd.zip", "zip"));
        assertEquals("evil.pdf", KgOnlineMaterialFiles.cleanName("C:\\Users\\a\\evil.pdf", "pdf"));
        assertEquals("a bc.docx", KgOnlineMaterialFiles.cleanName("a\u0000 b\n\tc*?\"<>|.docx", "docx"));
        assertEquals("没有扩展名.zip", KgOnlineMaterialFiles.cleanName("没有扩展名", "zip"));
        // 把扩展名倒过来显示的不可见字符（U+202E）去掉，名字看着是什么就是什么
        assertEquals("报告fdp.zip", KgOnlineMaterialFiles.cleanName("报告\u202Efdp.zip", "zip"));
        // C1 控制字符（U+0085 等）和行 / 段分隔符（U+2028 / U+2029）也去掉，名字不会在页面上断成两行
        assertEquals("第1批原图.zip", KgOnlineMaterialFiles.cleanName("第1批\u0085原\u2028图\u2029.zip", "zip"));
        assertEquals("隐藏.7z", KgOnlineMaterialFiles.cleanName("...隐藏.7z", "7z"));
        assertEquals("文件.rar", KgOnlineMaterialFiles.cleanName("   ", "rar"));
        assertEquals("文件.rar", KgOnlineMaterialFiles.cleanName(".rar", "rar"));
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 150; i++)
        {
            longName.append('长');
        }
        String cut = KgOnlineMaterialFiles.cleanName(longName + ".PDF", "pdf");
        assertEquals(100, cut.length());
        assertTrue(cut.endsWith(".pdf"), cut);
    }

    @Test
    void sizeLabelsAreShortAndReadable()
    {
        assertEquals("512 B", KgOnlineMaterialFiles.sizeLabel(512));
        assertEquals("1.5 KB", KgOnlineMaterialFiles.sizeLabel(1536));
        assertEquals("10 MB", KgOnlineMaterialFiles.sizeLabel(10L * 1024 * 1024));
        assertEquals("2.3 MB", KgOnlineMaterialFiles.sizeLabel((long) (2.3 * 1024 * 1024)));
        assertEquals("120 KB", KgOnlineMaterialFiles.sizeLabel(120 * 1024));
    }

    @Test
    void checkRejectsWrongTypeMismatchedContentAndOversize()
    {
        assertEquals(422, code(() -> files.check(new MockMultipartFile("file", "a.zip", null, new byte[0]), "a.zip")));
        assertEquals(422, code(() -> files.check(new MockMultipartFile("file", "tmp_1.exe", null, EXE), "安装包.exe")));
        ServiceException e = assertThrows(ServiceException.class, () -> files.check(new MockMultipartFile("file", "tmp_1.zip", null, EXE), "素材.zip"));
        assertTrue(e.getMessage().contains("对不上"), e.getMessage());
        // 整个传（multipart）只到 10MB，更大的要分段上传
        byte[] big = new byte[(int) KgOnlineMaterialFiles.DIRECT_MAX_BYTES + 1];
        System.arraycopy(ZIP, 0, big, 0, ZIP.length);
        assertEquals(422, code(() -> files.check(new MockMultipartFile("file", "big.zip", null, big), "big.zip")));
        // 显示名优先（小程序上传时文件名是临时名）；没给显示名时用上传的文件名
        KgOnlineMaterialFiles.Checked ok = files.check(new MockMultipartFile("file", "wxfile_tmp_9.pdf", null, PDF), "../合同 v2.PDF");
        assertEquals("合同 v2.PDF", ok.name);
        assertEquals("pdf", ok.ext);
        assertEquals(PDF.length, ok.size);
        assertEquals("拍摄要点.7z", files.check(new MockMultipartFile("file", "拍摄要点.7z", null, SEVEN_Z), null).name);
    }

    @Test
    void storedUnderKgPrivateWithUuidNamesAndKeysCannotTraverse(@TempDir Path folder) throws Exception
    {
        String original = RuoYiConfig.getProfile();
        try
        {
            new RuoYiConfig().setProfile(folder.resolve("uploads").toString());
            String key = files.write(7L, new MockMultipartFile("file", "x.zip", null, ZIP), "zip");
            assertTrue(key.matches("omat/7/[0-9a-f]{32}\\.zip"), key);
            File f = files.resolve(key);
            assertNotNull(f);
            assertTrue(f.toPath().startsWith(folder.resolve("kg-private")), "在 profile 之外的 kg-private 下");
            assertArrayEquals(ZIP, Files.readAllBytes(f.toPath()));
            for (String bad : Arrays.asList("omat/7/../../uploads/x.zip", "omat/7/" + key.substring(7, 39) + ".exe", "../omat/7/a.zip",
                    "omat/0/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.zip", "omat/7/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA.zip", "arrival/7/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.zip", null))
            {
                assertNull(files.resolve(bad), String.valueOf(bad));
            }
            files.delete(key);
            assertNull(files.resolve(key));
        }
        finally
        {
            new RuoYiConfig().setProfile(original);
        }
    }

    /** 2026-10-16：超过 8MB 的分段上传（单个最多 100MB）。按顺序收段、重传已收的段算成功、跳段 / 段长不对拒收、传完核对文件头、只认自己的上传 */
    @Test
    void largeFilesUploadInOrderedPartsAndAreCheckedWhenFinished(@TempDir Path folder) throws Exception
    {
        String original = RuoYiConfig.getProfile();
        try
        {
            new RuoYiConfig().setProfile(folder.resolve("uploads").toString());
            int part = KgOnlineMaterialFiles.PART_BYTES;
            long size = 2L * part + 5;
            byte[] all = new byte[(int) size];
            System.arraycopy(ZIP, 0, all, 0, ZIP.length);
            all[(int) size - 1] = 7;
            assertEquals(422, code(() -> files.partBegin(7L, 3L, "原图.exe", size)));
            assertEquals(422, code(() -> files.partBegin(7L, 3L, "原图.zip", KgOnlineMaterialFiles.MAX_BYTES + 1)));
            assertEquals(422, code(() -> files.partBegin(7L, 3L, "原图.zip", 0)));
            Map<String, Object> b = files.partBegin(7L, 3L, "../六张原图.zip", size);
            String id = String.valueOf(b.get("uploadId"));
            assertEquals(3, b.get("parts"));
            assertEquals(part, b.get("partBytes"));
            // 别人的、别的接单的、编造的上传号都当不存在
            assertEquals(404, code(() -> files.partAppend(id, 7L, 4L, 0, slice(all, 0, part))));
            assertEquals(404, code(() -> files.partAppend(id, 8L, 3L, 0, slice(all, 0, part))));
            assertEquals(404, code(() -> files.partAppend("../../x", 7L, 3L, 0, slice(all, 0, part))));
            // 跳段、段长不对都不收
            assertEquals(409, code(() -> files.partAppend(id, 7L, 3L, 1, slice(all, part, part))));
            assertEquals(422, code(() -> files.partAppend(id, 7L, 3L, 0, slice(all, 0, part - 1))));
            assertEquals(422, code(() -> files.partAppend(id, 7L, 3L, 0, slice(all, 0, part + 1))));
            assertEquals(422, code(() -> files.partAppend(id, 7L, 3L, 3, slice(all, 0, 5))));
            files.partAppend(id, 7L, 3L, 0, slice(all, 0, part));
            // 网络重试：同一段又来一次，直接算成功，不会重复写
            assertEquals((long) part, files.partAppend(id, 7L, 3L, 0, slice(all, 0, part)).get("received"));
            files.partAppend(id, 7L, 3L, 1, slice(all, part, part));
            // 没传完不能结束
            assertEquals(409, code(() -> files.partFinish(id, 7L, 3L)));
            assertEquals(size, files.partAppend(id, 7L, 3L, 2, slice(all, 2 * part, 5)).get("received"));
            KgOnlineMaterialFiles.Assembled a = files.partFinish(id, 7L, 3L);
            assertEquals("六张原图.zip", a.checked.name);
            assertEquals(size, a.checked.size);
            String key = files.adopt(7L, a.file, a.checked.ext);
            assertArrayEquals(all, Files.readAllBytes(files.resolve(key).toPath()));
            assertFalse(a.file.exists(), "临时文件已挪走");
            // 结束过的上传号不能再用
            assertEquals(404, code(() -> files.partFinish(id, 7L, 3L)));

            // 内容和扩展名对不上：传完时拒收，临时文件删掉
            String bad = String.valueOf(files.partBegin(7L, 3L, "假的.zip", 10).get("uploadId"));
            byte[] exe = Arrays.copyOf(EXE, 10);
            files.partAppend(bad, 7L, 3L, 0, slice(exe, 0, 10));
            assertEquals(422, code(() -> files.partFinish(bad, 7L, 3L)));
            assertFalse(new File(files.partDir(), bad + ".part").exists());

            // 放弃：删掉临时文件；别人放弃不了
            String gone = String.valueOf(files.partBegin(7L, 3L, "a.zip", 10).get("uploadId"));
            files.partAbort(gone, 7L, 4L);
            assertTrue(new File(files.partDir(), gone + ".part").exists());
            files.partAbort(gone, 7L, 3L);
            assertFalse(new File(files.partDir(), gone + ".part").exists());

            // 同一个人同时最多 5 个
            for (int i = 0; i < KgOnlineMaterialFiles.PART_MAX_OPEN; i++)
            {
                files.partBegin(7L, 5L, "a" + i + ".zip", 10);
            }
            assertEquals(429, code(() -> files.partBegin(7L, 5L, "x.zip", 10)));
            files.partBegin(7L, 6L, "x.zip", 10);
        }
        finally
        {
            new RuoYiConfig().setProfile(original);
        }
    }

    private static java.io.InputStream slice(byte[] all, int from, int len)
    {
        return new java.io.ByteArrayInputStream(Arrays.copyOfRange(all, from, Math.min(all.length, from + len)));
    }

    @Test
    void signedLinksExpireAndCannotBeTamperedOrReusedAcrossFiles()
    {
        long exp = System.currentTimeMillis() / 1000 + 3600;
        String url = files.sign(42L, exp);
        assertTrue(url.startsWith("/kangaroo/file/material?f=42&e=" + exp + "&s="), url);
        String sig = url.substring(url.indexOf("&s=") + 3);
        assertTrue(files.verify(42L, exp, sig));
        assertFalse(files.verify(43L, exp, sig), "换一个文件 ID 不能用");
        assertFalse(files.verify(42L, exp + 1, sig), "改了有效期不能用");
        assertFalse(files.verify(42L, exp, sig.substring(0, 31) + (sig.charAt(31) == '0' ? '1' : '0')));
        assertFalse(files.verify(42L, exp, null));
        long past = System.currentTimeMillis() / 1000 - 1;
        String old = files.sign(42L, past);
        assertFalse(files.verify(42L, past, old.substring(old.indexOf("&s=") + 3)), "过期");
        // 另一个密钥签出来的不认
        assertFalse(new KgOnlineMaterialFiles("another-secret-0123456789").verify(42L, exp, sig));
    }

    @Test
    void downloadIsAlwaysAnAttachmentWithLengthAndNoSniffing(@TempDir Path folder) throws Exception
    {
        String original = RuoYiConfig.getProfile();
        try
        {
            new RuoYiConfig().setProfile(folder.resolve("uploads").toString());
            String key = files.write(7L, new MockMultipartFile("file", "x.zip", null, ZIP), "zip");
            MockHttpServletResponse res = new MockHttpServletResponse();
            files.serve(p("fileKey", key, "fileName", "店内全景 原图.zip", "fileExt", "zip"), res);
            assertEquals("application/zip", res.getContentType());
            assertTrue(res.getHeader("Content-Disposition").startsWith("attachment; filename=\"material.zip\"; filename*=UTF-8''"), res.getHeader("Content-Disposition"));
            assertTrue(res.getHeader("Content-Disposition").contains("%E5%BA%97%E5%86%85%E5%85%A8%E6%99%AF%20%E5%8E%9F%E5%9B%BE.zip"));
            assertEquals("nosniff", res.getHeader("X-Content-Type-Options"));
            assertEquals("private, no-store", res.getHeader("Cache-Control"));
            assertEquals(ZIP.length, res.getContentLengthLong());
            assertArrayEquals(ZIP, res.getContentAsByteArray());
            assertEquals(404, code(() -> {
                try
                {
                    files.serve(p("fileKey", "omat/7/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.zip", "fileName", "x.zip", "fileExt", "zip"), new MockHttpServletResponse());
                }
                catch (java.io.IOException e)
                {
                    throw new IllegalStateException(e);
                }
                return null;
            }));
        }
        finally
        {
            new RuoYiConfig().setProfile(original);
        }
    }

    @Test
    void viewTellsArchivesFromOpenableDocuments()
    {
        Map<String, Object> zip = KgOnlineMaterialFiles.view(p("fileId", 3L, "fileName", "原图.zip", "fileExt", "zip", "fileSize", 2048L));
        assertEquals(3L, zip.get("fileId"));
        assertEquals("archive", zip.get("kind"));
        assertEquals(false, zip.get("openable"));
        assertEquals("2 KB", zip.get("sizeLabel"));
        Map<String, Object> pdf = KgOnlineMaterialFiles.view(p("fileId", 4L, "fileName", "要求.pdf", "fileExt", "pdf", "fileSize", 10L));
        assertEquals("doc", pdf.get("kind"));
        assertEquals(true, pdf.get("openable"));
    }

    @Test
    void cleanupOnlyRemovesFilesWhoseRowWasStillUnsent(@TempDir Path folder) throws Exception
    {
        String original = RuoYiConfig.getProfile();
        try
        {
            new RuoYiConfig().setProfile(folder.resolve("uploads").toString());
            KgJdbc db = mock(KgJdbc.class);
            ReflectionTestUtils.setField(files, "db", db);
            String stale = files.write(7L, new MockMultipartFile("file", "a.zip", null, ZIP), "zip");
            String justSent = files.write(7L, new MockMultipartFile("file", "b.zip", null, ZIP), "zip");
            when(db.list(contains("material_id is null and del_flag = '0' and create_time < :before"), anyMap()))
                    .thenReturn(Arrays.asList(p("fileId", 1L, "fileKey", stale), p("fileId", 2L, "fileKey", justSent)));
            // 第 2 个文件在清理前一刻被发出去了：条件更新改不到它，文件必须留着
            when(db.update(contains("set del_flag = '2' where file_id = :id and material_id is null"), eq(p("id", 1L)))).thenReturn(1);
            when(db.update(contains("set del_flag = '2' where file_id = :id and material_id is null"), eq(p("id", 2L)))).thenReturn(0);
            files.cleanupUnsent();
            assertNull(files.resolve(stale));
            assertNotNull(files.resolve(justSent));
            // 数据库出错时不抛出（定时任务不能因此停掉）
            when(db.list(anyString(), anyMap())).thenThrow(new RuntimeException("db down"));
            assertDoesNotThrow(files::cleanupUnsent);
            ReflectionTestUtils.setField(files, "db", null);
            assertDoesNotThrow(files::cleanupUnsent);
            verify(db, never()).list(contains("material_id is not null"), anyMap());
        }
        finally
        {
            new RuoYiConfig().setProfile(original);
        }
    }

    private static int code(java.util.concurrent.Callable<?> fn)
    {
        ServiceException e = assertThrows(ServiceException.class, fn::call);
        return e.getCode();
    }
}
