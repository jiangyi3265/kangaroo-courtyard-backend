package com.ruoyi.kangaroo.domain;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.testsupport.UploadedImageFixture;

/** 不连接数据库；针对真实上传文件而不是只验证路径字符串。 */
class JobImagesFilesTest
{
    private UploadedImageFixture files;
    @BeforeEach void setup() { files = new UploadedImageFixture(); }
    @AfterEach void cleanup() { files.close(); }

    private void rejected(String url)
    {
        assertEquals(Integer.valueOf(422), assertThrows(ServiceException.class, () -> JobImages.require(url)).getCode());
    }

    @Test void uploadedVideoAndAttachmentCannotMasqueradeAsJobPhoto() throws IOException
    {
        for (String name : Arrays.asList("clip.mp4", "document.pdf", "payload.html", "fake.jpg.exe", "no-extension"))
        {
            String url = "/profile/upload/2026/10/27/" + name;
            files.write(url, "not a photo".getBytes(StandardCharsets.UTF_8));
            rejected(url);
            assertFalse(JobImages.isUpload(url));
        }
    }

    @Test void imageSuffixAloneDoesNotAcceptMissingEmptyOrDisguisedFiles() throws IOException
    {
        rejected("/profile/upload/never-uploaded.jpg");
        files.write("/profile/upload/empty.jpg", new byte[0]);
        rejected("/profile/upload/empty.jpg");
        files.write("/profile/upload/renamed.jpg", "<html>not a photo</html>".getBytes(StandardCharsets.UTF_8));
        rejected("/profile/upload/renamed.jpg");
        Files.createDirectories(files.root().resolve("upload/directory.png"));
        rejected("/profile/upload/directory.png");
    }

    @Test void existingSupportedPhotosRemainValidIncludingBmpWebpAndUppercase() throws IOException
    {
        for (String format : Arrays.asList("jpg", "png", "gif", "bmp"))
        {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertTrue(ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), format, out));
            String url = "/profile/upload/2026/10/27/photo." + format.toUpperCase(java.util.Locale.ROOT);
            files.write(url, out.toByteArray());
            assertEquals(Collections.singletonList(url), JobImages.require(url));
        }
        String webp = "/profile/upload/old-photo.webp";
        files.write(webp, Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA"));
        assertEquals(Collections.singletonList(webp), JobImages.require(webp));
        // 手机相册可能改过扩展名，已有上传接口允许图片内容与后缀不同，仍应兼容。
        files.write("/profile/upload/photo.jpeg", Files.readAllBytes(files.root().resolve("upload/2026/10/27/photo.PNG")));
        assertEquals(Collections.singletonList("/profile/upload/photo.jpeg"), JobImages.require(" /profile/upload/photo.jpeg "));
    }

    @Test void malformedAndEscapingPathsCannotBeNormalizedIntoAcceptedUploads()
    {
        for (String url : Arrays.asList("/profile/upload/../outside.jpg", "/profile/upload/./photo.jpg", "/profile/upload//photo.jpg",
                "/profile/upload/%2e%2e/photo.jpg", "/profile/upload/a\\photo.jpg", "/profile/upload/photo.jpg?token=1"))
        {
            rejected(url);
        }
    }

    @Test void symlinkCannotReferenceImageOutsideUploadRoot() throws IOException
    {
        Path outside = files.root().resolve("outside.jpg");
        Files.write(outside, new byte[] {(byte)255,(byte)216,(byte)255,0});
        Files.createDirectories(files.root().resolve("upload"));
        try { Files.createSymbolicLink(files.root().resolve("upload/linked.jpg"), outside); }
        catch (UnsupportedOperationException | IOException | SecurityException ex)
        {
            Assumptions.assumeTrue(false, "本机不允许创建符号链接：" + ex.getClass().getSimpleName());
        }
        rejected("/profile/upload/linked.jpg");
    }

    @Test void readingHistoricalRowsDoesNotDependOnDiskAvailability()
    {
        String legacy = "/profile/upload/old-local-photo.jpg";
        assertEquals(Collections.singletonList(legacy), JobImages.read(legacy));
        assertEquals(Collections.singletonList(legacy), JobImages.read(legacy + ",/profile/upload/old-video.mp4"));
        rejected(legacy);
    }
}
