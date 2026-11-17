package com.ruoyi.kangaroo.security;

import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.ip.IpUtils;

/**
 * 小程序端接口的限流。
 *
 * 为什么不直接用框架的 {@code @RateLimiter} 注解：它只能按「全局」或「请求方 IP」限，
 * 而这里真正要限的主体是**账号**（谁在刷）与**被作用的对象**（哪个手机号、哪个岗位）。
 * 注解在方法签名层面拿不到这些，所以在方法里显式调用，key 由调用方说清楚。
 *
 * 计数用的是框架已有的那段 Lua（{@code limitScript}，INCR + 首次 EXPIRE），
 * 计数语义与后台接口保持一致，不另造一套。
 *
 * 为什么要有它：上线前的商用复核指出，/app/auth/wx-login 是匿名可调、
 * 同步阻塞外调微信服务器、且零限流的 —— Tomcat 配了 800 线程而数据库只有 20 个连接，
 * 持续打这一个接口就能把线程占满，整站（含管理后台）不可用。
 * 绑定手机号与提交报名同理：前者能把任意陌生人的号码绑到自己账号上并无限改绑，
 * 后者「报名→撤回」可以无限循环，每次都按 2×(1+员工数) 放大站内信。
 *
 * Redis 不可用时**放行**：限流是防滥用的，不该在缓存抖动时把正常用户挡在门外；
 * 但会记一条 WARN，免得"限流其实一直没生效"这件事悄无声息。
 */
@Component
public class AppRateLimiter
{
    private static final Logger log = LoggerFactory.getLogger(AppRateLimiter.class);

    private static final String PREFIX = "kg:rate:";

    @Autowired
    private RedisTemplate<Object, Object> redisTemplate;

    @Autowired
    private RedisScript<Long> limitScript;

    /**
     * 在 {@code seconds} 秒的窗口内，{@code bucket}+{@code subject} 最多允许 {@code count} 次。
     *
     * @param bucket  限流桶，用接口名，如 "wx-login"
     * @param subject 限流主体，如账号 ID、手机号、IP；为空时退回 "-"
     * @param count   窗口内允许的次数
     * @param seconds 窗口长度（秒）
     * @param message 超限时告诉用户的话。要说清楚"多久之后可以再试"，不要只说"操作频繁"
     */
    public void check(String bucket, String subject, int count, int seconds, String message)
    {
        String key = PREFIX + bucket + ":" + (subject == null || subject.isEmpty() ? "-" : subject);
        Long n;
        try
        {
            List<Object> keys = Collections.singletonList(key);
            n = redisTemplate.execute(limitScript, keys, Integer.valueOf(count), Integer.valueOf(seconds));
        }
        catch (Exception e)
        {
            // 放行，但要留痕：否则 Redis 出问题时限流静默失效，而没有人会知道
            log.warn("限流计数失败，本次放行。bucket={} subject={} err={}", bucket, maskSubject(subject), e.getMessage());
            return;
        }
        if (n == null || n.intValue() > count)
        {
            log.warn("触发限流 bucket={} subject={} 窗口={}s 上限={}", bucket, maskSubject(subject), seconds, count);
            throw new ServiceException(message, 429);  // 429：与业务失败的 500 区分开，前端与监控能认出是限流
        }
    }

    /** 日志里的主体脱敏：按手机号限流时 subject 就是完整手机号，不能明文进日志（Redis 键不受影响） */
    static String maskSubject(String subject)
    {
        if (subject != null && subject.matches("1[0-9]{10}"))
        {
            return subject.substring(0, 3) + "****" + subject.substring(7);
        }
        return subject;
    }

    /** 按请求方 IP 限流。匿名接口（登录）只有这一个可用的主体。 */
    public void checkByIp(String bucket, int count, int seconds, String message)
    {
        check(bucket, IpUtils.getIpAddr(), count, seconds, message);
    }
}
