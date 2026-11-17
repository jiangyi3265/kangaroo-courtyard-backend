package com.ruoyi.kangaroo.ext.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import com.ruoyi.common.exception.ServiceException;

/** 上传图片的文件头核对（KgImageMagic）与签名密钥长度（KgSignSecret），不连库 */
class KgUploadGuardTest
{
    static final byte[] JPG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1 };
    static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D };
    static final byte[] GIF = { 'G', 'I', 'F', '8', '9', 'a', 1, 0, 1, 0, 0, 0 };
    static final byte[] WEBP = { 'R', 'I', 'F', 'F', 0x24, 0, 0, 0, 'W', 'E', 'B', 'P' };

    private static MockMultipartFile file(String name, byte[] body)
    {
        return new MockMultipartFile("file", name, "image/jpeg", body);
    }

    @Test
    void recognisesRealImages()
    {
        assertEquals("jpg", KgImageMagic.require(file("a.jpg", JPG), "jpg", "jpeg", "png", "gif", "webp"));
        assertEquals("png", KgImageMagic.require(file("a.png", PNG), "jpg", "png"));
        assertEquals("gif", KgImageMagic.require(file("a.gif", GIF), "gif"));
        assertEquals("webp", KgImageMagic.require(file("a.webp", WEBP), "webp"));
        // jpeg 扩展名按 jpg 算
        assertEquals("jpg", KgImageMagic.require(file("a.jpeg", JPG), "jpeg"));
    }

    @Test
    void rejectsDisguisedFilesWith422()
    {
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        ServiceException e = assertThrows(ServiceException.class, () -> KgImageMagic.require(file("x.jpg", html), "jpg", "png"));
        assertEquals(Integer.valueOf(422), e.getCode());
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8);
        assertThrows(ServiceException.class, () -> KgImageMagic.require(file("x.png", svg), "jpg", "png"));
        // gif 内容不在允许的类型里（私有文件只收 jpg / png）
        assertThrows(ServiceException.class, () -> KgImageMagic.require(file("x.jpg", GIF), "jpg", "png"));
        // 太短 / 空
        assertThrows(ServiceException.class, () -> KgImageMagic.require(file("x.jpg", new byte[] { (byte) 0xFF }), "jpg"));
        assertNull(KgImageMagic.detect(new byte[0], 0));
        assertNull(KgImageMagic.detect(null, 0));
    }

    @Test
    void signSecretMustBeLongEnoughWhenConfigured()
    {
        assertEquals(32, KgSignSecret.resolve(null).length);
        assertEquals(32, KgSignSecret.resolve("").length);
        assertArrayEquals("0123456789abcdef".getBytes(StandardCharsets.UTF_8), KgSignSecret.resolve("0123456789abcdef"));
        // 太短也照用配置的值（只告警），不随机替换、也不让服务起不来
        assertArrayEquals("short".getBytes(StandardCharsets.UTF_8), KgSignSecret.resolve("short"));
        assertNotNull(new KgPrivateFiles("123456789012345"));
        assertNotNull(new com.ruoyi.kangaroo.ext.staff.KgOnlineMaterialFiles("123456789012345"));
        assertNotNull(new KgPrivateFiles(""));
    }
}
