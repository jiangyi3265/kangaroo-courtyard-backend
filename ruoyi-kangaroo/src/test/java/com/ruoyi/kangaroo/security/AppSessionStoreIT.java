package com.ruoyi.kangaroo.security;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.framework.config.FastJson2JsonRedisSerializer;
import static org.junit.jupiter.api.Assertions.*;

/** 独立执行的真实本机 Redis 原子性测试；只使用随机新会话 key，finally 删除。 */
class AppSessionStoreIT
{
    private LettuceConnectionFactory connection;
    private RedisTemplate<Object, Object> template;
    private AppSessionStore store;
    private String uuid;
    private String key;

    @BeforeEach
    void setup()
    {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration("127.0.0.1", 6379);
        config.setDatabase(9);
        connection = new LettuceConnectionFactory(config); connection.afterPropertiesSet();
        template = new RedisTemplate<Object, Object>(); template.setConnectionFactory(connection);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new FastJson2JsonRedisSerializer<Object>(Object.class)); template.afterPropertiesSet();
        RedisCache cache = new RedisCache(); cache.redisTemplate = template;
        store = new AppSessionStore(cache);
        uuid = "integration-" + UUID.randomUUID(); key = AppTokenService.APP_LOGIN_TOKEN_KEY + uuid;
        AppLoginUser user = new AppLoginUser(); user.setToken(uuid); user.setUserId(9007199254740993L);
        user.setPhone("test-original"); user.setExpireTime(1L);
        StaffGrantView grant = new StaffGrantView(); grant.setId(9007199254740995L); grant.setStoreId(9007199254740997L);
        grant.setRoleCode("manager"); grant.setActive(true); user.setStaffGrants(Collections.singletonList(grant));
        template.opsForValue().set(key, user, 60, TimeUnit.SECONDS);
    }

    @AfterEach
    void clean()
    {
        if (template != null && key != null) template.delete(key);
        if (connection != null) connection.destroy();
    }

    @Test
    void serializationAndRefreshKeepIdentityAndGrantPrecision()
    {
        assertTrue(store.update(uuid, 120000, current -> current.setLoginTime(1234L)));
        AppLoginUser saved = (AppLoginUser) template.opsForValue().get(key);
        assertEquals(9007199254740993L, saved.getUserId());
        assertEquals(9007199254740997L, saved.getStaffGrants().get(0).getStoreId());
        assertEquals("test-original", saved.getPhone());
        assertEquals(1234L, saved.getLoginTime());
        assertTrue(template.getExpire(key, TimeUnit.SECONDS) > 100);
    }

    @Test
    void revocationBetweenReadAndWriteCannotResurrectSession()
    {
        assertFalse(store.update(uuid, 120000, current -> {
            template.delete(key);
            current.setLoginTime(1234L);
        }));
        assertFalse(template.hasKey(key));
    }

    @Test
    void concurrentGrantChangeIsNotOverwrittenByRenewal()
    {
        AtomicBoolean revoke = new AtomicBoolean(true);
        assertTrue(store.update(uuid, 120000, current -> {
            if (revoke.getAndSet(false))
            {
                AppLoginUser newer = (AppLoginUser) template.opsForValue().get(key);
                newer.setStaffGrants(Collections.emptyList());
                template.opsForValue().set(key, newer, 60, TimeUnit.SECONDS);
            }
            current.setLoginTime(1234L);
        }));
        AppLoginUser saved = (AppLoginUser) template.opsForValue().get(key);
        assertTrue(saved.getStaffGrants().isEmpty());
        assertEquals(1234L, saved.getLoginTime());
    }

    @Test
    void grantRefreshKeepsRemainingTtlAndMissingKeyIsNeverCreated()
    {
        template.expire(key, 10000, TimeUnit.MILLISECONDS);
        assertTrue(store.update(uuid, 0, current -> current.setStaffGrants(Collections.emptyList())));
        Long ttl = template.getExpire(key, TimeUnit.MILLISECONDS);
        assertTrue(ttl > 0 && ttl <= 10000);
        template.delete(key);
        assertFalse(store.update(uuid, 60000, current -> fail("Missing sessions cannot be updated")));
        assertFalse(template.hasKey(key));
    }
}
