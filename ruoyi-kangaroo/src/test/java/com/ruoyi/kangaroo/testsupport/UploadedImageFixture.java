package com.ruoyi.kangaroo.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.stream.Collectors;
import com.ruoyi.common.config.RuoYiConfig;

/** 为经过真实图片存在性校验的业务测试提供独立上传目录；关闭后恢复原配置。 */
public final class UploadedImageFixture implements AutoCloseable
{
    private final String original = RuoYiConfig.getProfile();
    private final Path root;
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6l0sAAAAASUVORK5CYII=");

    public UploadedImageFixture(String... urls)
    {
        try
        {
            root = Files.createTempDirectory("kg-upload-images-test-").toAbsolutePath().normalize();
            new RuoYiConfig().setProfile(root.toString());
            for (String url : urls) write(url, PNG);
        }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public Path root() { return root; }

    public Path write(String url, byte[] bytes) throws IOException
    {
        if (!url.startsWith("/profile/upload/")) throw new IllegalArgumentException("测试图片必须在上传目录");
        Path file = root.resolve(url.substring("/profile/".length())).normalize();
        if (!file.startsWith(root.resolve("upload"))) throw new IllegalArgumentException("测试路径越界");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
        return file;
    }

    @Override public void close()
    {
        new RuoYiConfig().setProfile(original);
        try (Stream<Path> paths = Files.walk(root))
        {
            for (Path file : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList()))
            {
                if (!file.toAbsolutePath().normalize().startsWith(root)) throw new IOException("测试清理路径越界");
                Files.deleteIfExists(file);
            }
        }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
