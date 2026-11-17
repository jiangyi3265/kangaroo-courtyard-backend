package com.ruoyi.kangaroo.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * 正在运行的这份代码的指纹。
 *
 * 为什么需要它：验收脚本会先 mvn package 再跑端到端测试。如果那台机器上还留着
 * 一个用旧 jar 启动的进程，"构建通过"和"端到端 185/185 通过"这两件事之间
 * 其实没有因果关系 —— 测的是旧代码。这个坑本项目已经踩过一次
 * （spring-boot:repackage 被跳过，fat jar 里还是上一轮的业务模块）。
 *
 * 所以让服务把自己是从哪个文件、什么指纹启动的报出来，
 * 验收脚本拿它和刚构建出来的 jar 对一次，对不上就判失败而不是继续往下跑。
 *
 * 只取 sha256 的前 12 位十六进制：足够区分不同构建，又不会把完整哈希
 * 暴露在一个不需要登录的探活接口上。
 */
public final class BuildFingerprint
{
    private static final String UNKNOWN = "unknown";

    private static volatile String cached;

    private BuildFingerprint()
    {
    }

    /** 运行中代码的短指纹；取不到时返回 "unknown"（调用方应据此判定为不可校验，而不是当成通过）。 */
    public static String current()
    {
        String value = cached;
        if (value == null)
        {
            synchronized (BuildFingerprint.class)
            {
                if (cached == null)
                {
                    cached = compute();
                }
                value = cached;
            }
        }
        return value;
    }

    private static String compute()
    {
        try
        {
            File self = locateSelf();
            if (self == null || !self.isFile())
            {
                // 在 IDE 里以 classes 目录运行时没有单一文件可取，属于预期情况
                return UNKNOWN;
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            InputStream in = new FileInputStream(self);
            try
            {
                int n;
                while ((n = in.read(buf)) > 0)
                {
                    md.update(buf, 0, n);
                }
            }
            finally
            {
                in.close();
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++)
            {
                sb.append(String.format("%02x", Byte.valueOf(digest[i])));
            }
            return sb.toString();
        }
        catch (Exception e)
        {
            return UNKNOWN;
        }
    }

    /**
     * 找到启动这个进程的 jar 文件。
     *
     * fat jar 运行时 ProtectionDomain 的 location 指向 jar 内部路径，
     * 所以优先用 java.class.path —— 用 java -jar 启动时它就是那个 jar 本身。
     */
    private static File locateSelf()
    {
        String cp = System.getProperty("java.class.path");
        if (cp != null && !cp.isEmpty() && cp.indexOf(File.pathSeparatorChar) < 0)
        {
            File f = new File(cp);
            if (f.isFile())
            {
                return f.getAbsoluteFile();
            }
        }
        try
        {
            java.net.URL url = BuildFingerprint.class.getProtectionDomain()
                    .getCodeSource().getLocation();
            String path = url.getPath();
            int bang = path.indexOf("!/");
            if (bang > 0)
            {
                path = path.substring(0, bang);
            }
            if (path.startsWith("file:"))
            {
                path = path.substring("file:".length());
            }
            File f = new File(java.net.URLDecoder.decode(path, "UTF-8"));
            return f.isFile() ? f.getAbsoluteFile() : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }
}
