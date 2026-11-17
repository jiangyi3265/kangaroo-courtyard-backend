package com.ruoyi.kangaroo.ext.user;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.config.KangarooProperties;
import com.ruoyi.kangaroo.security.AppRateLimiter;

/**
 * 短信验证码：可插拔通道。
 *
 * mode=none（默认，也是正式环境未接通时的状态）：不假装发送，直接告诉调用方"短信通道未开通"。
 * mode=mock（仅本地联调，KangarooProperties 已限制只有开了微信模拟登录时才允许）：本地生成验证码，
 * 在返回体里带上 simulated=true 和验证码本身，页面会明确写"本地模拟通道"。
 *
 * 真实通道接入时，只需在 send() 里补一个分支调用运营商网关，上面两条约束保持不变。
 */
@Service
public class KgSmsService
{
    /** 验证码存放位置：kangaroo:sms:<场景>:<手机号> */
    private static final String KEY = "kangaroo:sms:";

    /** 验证码有效期（分钟） */
    private static final int TTL_MINUTES = 5;

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private KangarooProperties properties;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private AppRateLimiter rateLimiter;

    public boolean enabled()
    {
        return !"none".equals(properties.getSmsMode());
    }

    public boolean simulated()
    {
        return "mock".equals(properties.getSmsMode());
    }

    static void checkPhone(String phone)
    {
        if (phone == null || !phone.matches("1[3-9]\\d{9}"))
        {
            throw new ServiceException("请填写正确的手机号", 422);
        }
    }

    /**
     * 发送验证码
     *
     * @param scene 场景（bind 绑定 / rebind 换绑），不同场景的验证码互不通用
     */
    public Map<String, Object> send(String phone, String scene, Long userId)
    {
        checkPhone(phone);
        if (!enabled())
        {
            throw new ServiceException("短信通道尚未开通，请用微信一键授权绑定手机号", 503);
        }
        rateLimiter.check("sms:phone", phone, 5, 600, "验证码发送太频繁了，请 10 分钟后再试");
        rateLimiter.check("sms:user", String.valueOf(userId), 8, 600, "验证码发送太频繁了，请 10 分钟后再试");
        String code = String.format("%06d", RANDOM.nextInt(1000000));
        redisCache.setCacheObject(KEY + scene + ":" + phone, code, Integer.valueOf(TTL_MINUTES), TimeUnit.MINUTES);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("simulated", Boolean.valueOf(simulated()));
        m.put("ttl", Integer.valueOf(TTL_MINUTES * 60));
        if (simulated())
        {
            // 本地模拟通道：没有任何短信真的发出去，验证码只能在这里看到
            m.put("code", code);
            m.put("message", "本地模拟通道，验证码 " + code + "（不会真的发短信）");
        }
        else
        {
            m.put("message", "验证码已发送，" + TTL_MINUTES + " 分钟内有效");
        }
        return m;
    }

    /** 校验验证码；成功后立即作废，防止重放 */
    public void verify(String phone, String scene, String code)
    {
        checkPhone(phone);
        if (!enabled())
        {
            throw new ServiceException("短信通道尚未开通", 503);
        }
        if (code == null || !code.matches("\\d{6}"))
        {
            throw new ServiceException("请输入 6 位验证码", 422);
        }
        String key = KEY + scene + ":" + phone;
        Object saved = redisCache.getCacheObject(key);
        rateLimiter.check("sms:verify", phone, 10, 600, "验证次数过多，请 10 分钟后再试");
        if (saved == null)
        {
            throw new ServiceException("验证码已过期，请重新获取", 422);
        }
        if (!code.equals(String.valueOf(saved)))
        {
            throw new ServiceException("验证码不正确", 422);
        }
        redisCache.deleteObject(key);
    }
}
