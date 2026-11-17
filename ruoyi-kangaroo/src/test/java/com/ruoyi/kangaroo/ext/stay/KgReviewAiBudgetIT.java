package com.ruoyi.kangaroo.ext.stay;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

/** Explicit local-only Redis integration. No production endpoint or actual model is involved. */
class KgReviewAiBudgetIT
{
    LettuceConnectionFactory connection;
    StringRedisTemplate redis;
    RedisScript<Long> script;
    List<String> keys = new ArrayList<>();
    ExecutorService executor = Executors.newFixedThreadPool(12);
    String prefix = "integration:review-ai:" + UUID.randomUUID() + ":";

    @BeforeEach @SuppressWarnings("unchecked") void start()
    {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration("127.0.0.1", 6379);
        config.setDatabase(9); connection = new LettuceConnectionFactory(config); connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection); redis.afterPropertiesSet();
        script = (RedisScript<Long>) ReflectionTestUtils.getField(KgReviewAiService.class, "BUDGET");
        keys.add(prefix + "day"); keys.add(prefix + "hour");
    }

    long compete(boolean sameStay, int daily, int hourly) throws Exception
    {
        List<Callable<Long>> requests = new ArrayList<>();
        for (int i = 0; i < 40; i++)
        {
            String slot = prefix + "stay:" + (sameStay ? 1 : i);
            keys.add(slot);
            requests.add(() -> redis.execute(script, Arrays.asList(prefix + "day", prefix + "hour", slot), String.valueOf(daily), String.valueOf(hourly)));
        }
        long accepted = 0;
        for (Future<Long> result : executor.invokeAll(requests)) accepted += result.get();
        return accepted;
    }

    @AfterEach void clean()
    {
        executor.shutdownNow();
        try { if (redis != null) redis.delete(keys); }
        finally { if (connection != null) connection.destroy(); }
    }

    @Test void concurrentRequestsAcrossInstancesCannotExceedHourBudget() throws Exception
    {
        assertEquals(5L, compete(false, 30, 5));
        assertEquals("5", redis.opsForValue().get(prefix + "hour"));
        assertTrue(redis.getExpire(prefix + "day") > 0);
    }

    @Test void concurrentChangesToOneStayReserveOnlyOneCall() throws Exception
    {
        assertEquals(1L, compete(true, 30, 5));
        assertEquals("1", redis.opsForValue().get(prefix + "day"));
        assertTrue(redis.getExpire(prefix + "stay:1") > 0);
    }

    @Test void dailyBudgetAndZeroBudgetFailClosed() throws Exception
    {
        redis.opsForValue().set(prefix + "day", "29");
        assertEquals(1L, compete(false, 30, 10));
        redis.delete(keys);
        assertEquals(0L, compete(false, 0, 10));
    }
}
