package com.ruoyi.kangaroo.security;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.serializer.RedisSerializer;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.ServiceException;

/** 对现存会话做原始字节 CAS，不创建 key，不把请求中的旧授权对象写回 Redis。 */
@Component
public class AppSessionStore
{
    private final RedisCache cache;
    private static final byte[] CAS = ("if redis.call('get',KEYS[1]) ~= ARGV[1] then return 0 end "
            + "local ttl=tonumber(ARGV[3]); if ttl==0 then ttl=redis.call('pttl',KEYS[1]) end "
            + "if ttl<=0 then return 0 end redis.call('psetex',KEYS[1],ttl,ARGV[2]); return 1").getBytes(StandardCharsets.UTF_8);

    public AppSessionStore(RedisCache cache) { this.cache = cache; }

    @SuppressWarnings("unchecked")
    public boolean update(String uuid, long ttlMillis, Consumer<AppLoginUser> change)
    {
        RedisSerializer<Object> serializer = cache.redisTemplate.getValueSerializer();
        byte[] key = (AppTokenService.APP_LOGIN_TOKEN_KEY + uuid).getBytes(StandardCharsets.UTF_8);
        for (int attempt = 0; attempt < 5; attempt++)
        {
            byte[] before = (byte[]) cache.redisTemplate.execute((RedisCallback<byte[]>) c -> c.get(key));
            if (before == null) return false;
            AppLoginUser current = (AppLoginUser) serializer.deserialize(before);
            change.accept(current);
            byte[] after = serializer.serialize(current);
            Long changed = (Long) cache.redisTemplate.execute((RedisCallback<Long>) c -> c.eval(CAS, ReturnType.INTEGER, 1,
                    key, before, after, Long.toString(ttlMillis).getBytes(StandardCharsets.US_ASCII)));
            if (Long.valueOf(1L).equals(changed)) return true;
        }
        throw new ServiceException("登录状态正在更新，请稍后重试", 503);
    }
}
