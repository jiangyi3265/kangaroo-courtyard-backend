package com.ruoyi.kangaroo.ext.core;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * 签名下载链接的密钥 kangaroo.file.sign-secret（环境变量 KANGAROO_FILE_SIGN_SECRET），KgPrivateFiles 和 KgOnlineMaterialFiles 共用。
 *  · 没配（本地开发）：每次启动随机生成，链接只在本次进程内有效；
 *  · 配了但不到 16 个字符：照用配置的值（重启后链接仍有效、多实例一致），启动时写一条警告提醒换长一点。
 *    之前悄悄换成随机密钥，每次重启所有已发出的链接都失效；也不直接拒绝启动——线上现在的值看不到，不能因为它让服务起不来。
 */
public final class KgSignSecret
{
    public static final int MIN_LENGTH = 16;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KgSignSecret.class);

    private KgSignSecret()
    {
    }

    public static byte[] resolve(String configured)
    {
        if (configured == null || configured.isEmpty())
        {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            return random;
        }
        if (configured.length() < MIN_LENGTH)
        {
            log.warn("kangaroo.file.sign-secret（环境变量 KANGAROO_FILE_SIGN_SECRET）太短：现在 {} 个字符，建议至少 {} 个的随机串",
                    configured.length(), MIN_LENGTH);
        }
        return configured.getBytes(StandardCharsets.UTF_8);
    }
}
