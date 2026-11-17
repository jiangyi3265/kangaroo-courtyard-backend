package com.ruoyi.kangaroo.ext.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ruoyi.common.exception.ServiceException;

/**
 * 2026-10-17 R3：公开视频的分段上传（KgVideoUploads）—— 文件头核对、扩展名 / 大小 / 时长、分段顺序、放弃、拼好后挪进 profile/upload/videos/。
 * profile 目录换成测试临时目录（覆盖 profileDir），不碰若依的真实上传目录。
 */
class KgVideoUploadsTest
{
    private File tmp;
    private KgVideoUploads up;

    @BeforeEach
    void setup() throws Exception
    {
        tmp = Files.createTempDirectory("kg-video-test").toFile();
        final File profile = new File(tmp, "uploadPath");
        profile.mkdirs();
        up = new KgVideoUploads()
        {
            @Override
            File profileDir()
            {
                return profile;
            }
        };
    }

    @AfterEach
    void cleanup()
    {
        delete(tmp);
    }

    private static void delete(File f)
    {
        File[] kids = f.listFiles();
        if (kids != null)
        {
            for (File k : kids)
            {
                delete(k);
            }
        }
        f.delete();
    }

    /** 一个最小的 mp4 开头：ftyp 盒子（长度 24、品牌 isom）+ 填充到 n 个字节 */
    static byte[] mp4(int n, String brand)
    {
        byte[] b = new byte[n];
        byte[] head = new byte[] { 0, 0, 0, 24, 'f', 't', 'y', 'p' };
        System.arraycopy(head, 0, b, 0, 8);
        byte[] br = brand.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(br, 0, b, 8, 4);
        for (int i = 12; i < n; i++)
        {
            b[i] = (byte) (i * 31);
        }
        return b;
    }

    @Test
    void twoHundredMegabytesUploadInTwentyFiveBoundedChunks() throws Exception
    {
        Map<String,Object> begin=up.partBegin(71L,"large.mp4",200L*1024*1024,60);
        String id=String.valueOf(begin.get("uploadId"));
        byte[] part=mp4(KgVideoUploads.PART_BYTES,"isom");
        for(int i=0;i<25;i++)up.partAppend(id,71L,i,new ByteArrayInputStream(part));
        Map<String,Object> saved=up.partFinish(id,71L);
        assertEquals(200L*1024*1024,up.sizeOf(String.valueOf(saved.get("url"))));
        assertThrows(ServiceException.class,()->up.partBegin(71L,"oversize.mp4",200L*1024*1024+1,60));
    }

    @Test
    void headerMustBeAnIsoMediaFtypBox()
    {
        assertTrue(KgVideoUploads.magicOk(mp4(32, "isom")));
        assertTrue(KgVideoUploads.magicOk(mp4(32, "mp42")));
        assertTrue(KgVideoUploads.magicOk(mp4(32, "qt  ")), "mov 的品牌是「qt  」（带两个空格）");
        assertFalse(KgVideoUploads.magicOk("<html><body>hi</body></html>".getBytes(StandardCharsets.US_ASCII)), "改了扩展名的网页");
        assertFalse(KgVideoUploads.magicOk(new byte[] { 'P', 'K', 3, 4, 0, 0, 0, 0, 0, 0, 0, 0 }), "压缩包");
        assertFalse(KgVideoUploads.magicOk(new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm' }), "盒子长度不合理");
        assertFalse(KgVideoUploads.magicOk(new byte[] { 0, 0, 0, 24, 'f', 't', 'y', 'p', 0, 1, 2, 3 }), "品牌不是可打印字符");
        assertFalse(KgVideoUploads.magicOk(new byte[] { 0, 0, 0, 24, 'f', 't', 'y', 'p' }), "不足 12 个字节");
        assertFalse(KgVideoUploads.magicOk(null));
        byte[] moovFirst = mp4(32, "isom");
        moovFirst[4] = 'm'; moovFirst[5] = 'o'; moovFirst[6] = 'o'; moovFirst[7] = 'v';
        assertFalse(KgVideoUploads.magicOk(moovFirst), "老式 mov 没有 ftyp 的不收（请导出成 mp4）");
    }

    @Test
    void platformVideoPathsAndExtensions()
    {
        assertTrue(KgVideoUploads.isPlatformVideo("/profile/upload/videos/2026/10/17/0123456789abcdef0123456789abcdef.mp4"));
        assertTrue(KgVideoUploads.isPlatformVideo("/profile/upload/videos/2026/10/17/0123456789abcdef0123456789abcdef.mov"));
        for (String bad : Arrays.asList(null, "", "https://evil.example.com/a.mp4", "/profile/upload/2026/10/17/a.mp4",
                "/profile/upload/videos/2026/10/17/../../../etc/passwd.mp4", "/profile/upload/videos/2026/10/17/0123456789ABCDEF0123456789abcdef.mp4",
                "/profile/upload/videos/2026/10/17/0123456789abcdef0123456789abcdef.jpg", "/profile/upload/videos/2026/10/17/0123456789abcdef0123456789abcdef.mp4?x=1"))
        {
            assertFalse(KgVideoUploads.isPlatformVideo(bad), String.valueOf(bad));
        }
        assertEquals("mp4", KgVideoUploads.extOf("wxfile://tmp_abc.mp4"));
        assertEquals("mov", KgVideoUploads.extOf("http://tmp/IMG_0001.MOV"));
        assertEquals("mp4", KgVideoUploads.extOf("blob:a.mp4?v=1"));
        assertEquals("", KgVideoUploads.extOf("a.mp4/x"));
        assertEquals("", KgVideoUploads.extOf(null));
        assertTrue(KgVideoUploads.allowedExt("mp4") && KgVideoUploads.allowedExt("mov"));
        assertFalse(KgVideoUploads.allowedExt("avi") || KgVideoUploads.allowedExt("webm") || KgVideoUploads.allowedExt("html"));
    }

    private static void rejects(Runnable r, int code, String msg)
    {
        ServiceException e = assertThrows(ServiceException.class, r::run);
        assertEquals(Integer.valueOf(code), e.getCode(), e.getMessage());
        assertTrue(e.getMessage().contains(msg), e.getMessage());
    }

    @Test
    void beginChecksTypeSizeAndDuration()
    {
        rejects(() -> up.partBegin(1L, "a.avi", 100, 10), 422, "mp4 / mov");
        rejects(() -> up.partBegin(1L, "a.mp4", 0, 10), 422, "空的");
        rejects(() -> up.partBegin(1L, "a.mp4", "abc", 10), 422, "空的");
        rejects(() -> up.partBegin(1L, "a.mp4", KgVideoUploads.MAX_BYTES + 1, 10), 422, "200MB");
        rejects(() -> up.partBegin(1L, "a.mp4", 1000, 61.5), 422, "60 秒");
        // 正好 200MB、60.9 秒、没带时长都能开始
        Map<String, Object> m = up.partBegin(1L, "a.mp4", KgVideoUploads.MAX_BYTES, 60.9);
        assertEquals(25, m.get("parts"), "200MB / 8MB = 25 段");
        assertEquals(KgVideoUploads.PART_BYTES, m.get("partBytes"));
        assertTrue(String.valueOf(m.get("uploadId")).matches("[0-9a-f]{32}"));
        up.partBegin(1L, "b.MOV", 10, null);
        up.partBegin(1L, "c.mp4", 10, "");
        // 每人同时最多 3 个
        rejects(() -> up.partBegin(1L, "d.mp4", 10, 1), 429, "太多");
        assertNotNull(up.partBegin(2L, "d.mp4", 10, 1).get("uploadId"), "别人不受影响");
    }

    /** 2026-10-17：小院介绍视频（「3分钟看小院」，kind=intro）放宽到 3 分钟、200MB；其他视频仍是 1 分钟、200MB */
    @Test
    void introVideoAllowsThreeMinutesAndHundredMegabytes()
    {
        Map<String, Object> m = up.partBegin(3L, "intro.mp4", KgVideoUploads.INTRO_MAX_BYTES, 180.4, true);
        assertEquals(25, m.get("parts"), "200MB / 8MB = 25 段");
        rejects(() -> up.partBegin(3L, "intro.mp4", KgVideoUploads.INTRO_MAX_BYTES + 1, 10, true), 422, "200MB");
        rejects(() -> up.partBegin(3L, "intro.mp4", 1000, 181.5, true), 422, "3 分钟");
        // 不是介绍视频时照旧
        rejects(() -> up.partBegin(4L, "room.mp4", 1000, 120, false), 422, "60 秒");
        rejects(() -> up.partBegin(4L, "room.mp4", KgVideoUploads.MAX_BYTES + 1, 10, false), 422, "200MB");
    }

    /** 采访多短都能传（2026-10-31 主人拍板去掉 60 秒下限）、时长要是有效正数、无业务时长上限；后台与小程序身份隔离，文件上限保持300MB。 */
    @Test
    void interviewVideoNeedsAValidPositiveDurationWithoutLowerOrUpperLimit() throws Exception
    {
        Long admin = KgVideoUploads.adminOwner(3L);
        assertEquals(Long.valueOf(-3L), admin);
        assertEquals(Long.valueOf(-3L), KgVideoUploads.adminOwner(-3L));
        rejects(() -> KgVideoUploads.adminOwner(null), 401, "登录");
        Map<String, Object> m = up.partBeginInterview(admin, "interview.mp4", KgVideoUploads.INTERVIEW_MAX_BYTES, 300.9);
        assertEquals(38, m.get("parts"), "300MB / 8MB = 38 段");
        rejects(() -> up.partBeginInterview(admin, "interview.mp4", KgVideoUploads.INTERVIEW_MAX_BYTES + 1, 10), 422, "300MB");
        for (Object seconds : new Object[] { 0, -1, null, "", "bad", Double.NaN, Double.POSITIVE_INFINITY })
            rejects(() -> up.partBeginInterview(admin, "interview.mp4", 1000, seconds), 422, "秒数");
        for (double seconds : new double[] { 0.5, 5, 59, 59.99, 60, 301.5, 3600 })
        {
            Map<String, Object> longer = up.partBeginInterview(admin, "interview.mp4", 1000, seconds);
            up.partAbort(String.valueOf(longer.get("uploadId")), admin);
        }
        rejects(() -> up.partBeginInterview(admin, "interview.avi", 1000, 10), 422, "mp4 / mov");
        // 小程序里同号的用户（正数 3）看不到、动不了后台的上传
        String id = String.valueOf(m.get("uploadId"));
        rejects(() -> up.partAppend(id, 3L, 0, new ByteArrayInputStream(mp4(16, "isom"))), 404, "失效");
        up.partAbort(id, 3L);
        assertEquals(1, up.openCount());
        // 后台自己的能传完
        Map<String, Object> small = up.partBeginInterview(admin, "b.mov", 64, 60);
        String sid = String.valueOf(small.get("uploadId"));
        up.partAppend(sid, admin, 0, new ByteArrayInputStream(mp4(64, "qt  ")));
        String url = String.valueOf(up.partFinish(sid, admin).get("url"));
        assertTrue(KgVideoUploads.isPlatformVideo(url) && url.endsWith(".mov") && up.exists(url), url);
        // 别的视频的上限照旧
        rejects(() -> up.partBegin(4L, "room.mp4", 1000, 120), 422, "60 秒");
        rejects(() -> up.partBegin(4L, "intro.mp4", 1000, 200, true), 422, "小院视频不能超过 3 分钟");
        rejects(() -> up.partBegin(4L, "intro.mp4", KgVideoUploads.INTRO_MAX_BYTES + 1, 10, true), 422, "200MB");
    }

    @Test
    void chunkedUploadInOrderThenFinishMovesItUnderProfileVideos() throws Exception
    {
        int size = KgVideoUploads.PART_BYTES + 1234;
        byte[] data = mp4(size, "isom");
        Map<String, Object> b = up.partBegin(7L, "wxfile://tmp_x.mp4", size, 12.3);
        String id = String.valueOf(b.get("uploadId"));
        assertEquals(2, b.get("parts"));
        byte[] p0 = Arrays.copyOfRange(data, 0, KgVideoUploads.PART_BYTES);
        byte[] p1 = Arrays.copyOfRange(data, KgVideoUploads.PART_BYTES, size);
        // 跳段：409
        rejects(() -> up.partAppend(id, 7L, 1, new ByteArrayInputStream(p1)), 409, "顺序");
        // 别人的上传号：404
        rejects(() -> up.partAppend(id, 8L, 0, new ByteArrayInputStream(p0)), 404, "失效");
        // 没传完就 finish：409
        assertEquals((long) KgVideoUploads.PART_BYTES, up.partAppend(id, 7L, 0, new ByteArrayInputStream(p0)).get("received"));
        rejects(() -> up.partFinish(id, 7L), 409, "还没传完");
        // 网络重试重发第 0 段：直接算成功，不重复写
        assertEquals((long) KgVideoUploads.PART_BYTES, up.partAppend(id, 7L, 0, new ByteArrayInputStream(p0)).get("received"));
        // 最后一段多一个字节：不收
        byte[] longer = Arrays.copyOf(p1, p1.length + 1);
        rejects(() -> up.partAppend(id, 7L, 1, new ByteArrayInputStream(longer)), 422, "没传完整");
        rejects(() -> up.partAppend(id, 7L, 2, new ByteArrayInputStream(p1)), 422, "分段编号");
        assertEquals((long) size, up.partAppend(id, 7L, 1, new ByteArrayInputStream(p1)).get("received"));
        Map<String, Object> done = up.partFinish(id, 7L);
        String url = String.valueOf(done.get("url"));
        assertTrue(KgVideoUploads.isPlatformVideo(url), url);
        assertTrue(url.endsWith(".mp4"));
        assertEquals((long) size, done.get("size"));
        File saved = new File(up.profileDir(), url.substring("/profile/".length()));
        assertTrue(saved.isFile());
        assertArrayEquals(data, Files.readAllBytes(saved.toPath()), "拼好的文件和原视频逐字节相同");
        assertTrue(up.exists(url));
        assertFalse(up.exists(url.replace(".mp4", ".mov")));
        assertFalse(up.exists("/profile/upload/2026/10/17/a.jpg"));
        assertEquals(0, up.openCount(), "传完从内存里拿掉");
        assertFalse(new File(up.partDir(), id + ".part").exists(), "临时文件挪走了");
        rejects(() -> up.partFinish(id, 7L), 404, "失效");
    }

    @Test
    void movKeepsItsExtension() throws Exception
    {
        byte[] data = mp4(100, "qt  ");
        String id = String.valueOf(up.partBegin(7L, "IMG_1.MOV", 100, 3).get("uploadId"));
        up.partAppend(id, 7L, 0, new ByteArrayInputStream(data));
        String url = String.valueOf(up.partFinish(id, 7L).get("url"));
        assertTrue(url.endsWith(".mov"), url);
    }

    @Test
    void notAVideoIsRejectedOnTheFirstPartAndForgotten()
    {
        byte[] html = new byte[200];
        byte[] text = "<!doctype html><script>alert(1)</script>".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(text, 0, html, 0, text.length);
        String id = String.valueOf(up.partBegin(7L, "evil.mp4", 200, 3).get("uploadId"));
        rejects(() -> up.partAppend(id, 7L, 0, new ByteArrayInputStream(html)), 422, "不是能播放的");
        assertEquals(0, up.openCount());
        assertFalse(new File(up.partDir(), id + ".part").exists());
        rejects(() -> up.partFinish(id, 7L), 404, "失效");
        File videos = new File(up.profileDir(), "upload/videos");
        assertFalse(videos.exists(), "什么都没存进公开目录");
    }

    @Test
    void abortDeletesThePartialFileButOnlyForTheOwner() throws Exception
    {
        int size = KgVideoUploads.PART_BYTES + 10;
        String id = String.valueOf(up.partBegin(7L, "a.mp4", size, 3).get("uploadId"));
        up.partAppend(id, 7L, 0, new ByteArrayInputStream(Arrays.copyOf(mp4(size, "isom"), KgVideoUploads.PART_BYTES)));
        File part = new File(up.partDir(), id + ".part");
        assertTrue(part.isFile());
        up.partAbort(id, 8L);
        assertTrue(part.isFile(), "别人放弃不了");
        assertEquals(1, up.openCount());
        up.partAbort(id, 7L);
        assertFalse(part.exists());
        assertEquals(0, up.openCount());
        up.partAbort(id, 7L);
        up.partAbort(null, 7L);
        rejects(() -> up.partAppend(id, 7L, 1, new ByteArrayInputStream(new byte[10])), 404, "失效");
    }
}
