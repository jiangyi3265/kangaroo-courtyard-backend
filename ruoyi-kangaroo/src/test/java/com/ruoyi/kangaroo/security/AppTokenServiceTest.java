package com.ruoyi.kangaroo.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;

import com.ruoyi.common.core.redis.RedisCache;

/**
 * 小程序会话索引：授权变更与账号停用不能再靠 KEYS 全库扫描。
 *
 * KEYS 在 Redis 里阻塞整个实例；原先后台每点一次授予 / 撤销 / 停用都会扫一遍全部会话。
 * 这里用一个内存里的假 Redis 断言三件事：
 *   1. refreshGrants / kickOut 一次都不调 keys()；
 *   2. 它们只动这个账号自己的会话，别人的会话原样不动；
 *   3. 升级前就存在、没有索引项的老会话，经 indexExisting 补齐后同样能被找到 ——
 *      否则上线后撤销一个老会话员工的授权，他在令牌过期前仍握着员工权限。
 */
class AppTokenServiceTest
{
    private final Map<String, Object> values = new HashMap<String, Object>();

    private final Map<String, Set<Object>> sets = new HashMap<String, Set<Object>>();

    private RedisCache redisCache;

    private AppTokenService service;
    private AppSessionStore sessionStore;
    private AppIdentityLoader identities;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception
    {
        redisCache = mock(RedisCache.class);
        RedisTemplate<Object, Object> template = mock(RedisTemplate.class);
        SetOperations<Object, Object> ops = mock(SetOperations.class);
        redisCache.redisTemplate = template;
        when(template.opsForSet()).thenReturn(ops);

        doAnswer(inv -> {
            values.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(redisCache).setCacheObject(anyString(), any(), any(Integer.class), any(TimeUnit.class));
        when(redisCache.getCacheObject(anyString())).thenAnswer(inv -> values.get(inv.getArgument(0)));
        when(redisCache.deleteObject(anyString())).thenAnswer(inv -> values.remove(inv.getArgument(0)) != null);
        when(redisCache.expire(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        when(ops.add(any(), any())).thenAnswer(inv -> {
            Object[] args = inv.getArguments();
            Set<Object> s = sets.computeIfAbsent((String) args[0], k -> new HashSet<Object>());
            long added = 0;
            for (int i = 1; i < args.length; i++)
            {
                Object a = args[i];
                if (a instanceof Object[])
                {
                    for (Object x : (Object[]) a)
                    {
                        added += s.add(x) ? 1 : 0;
                    }
                }
                else
                {
                    added += s.add(a) ? 1 : 0;
                }
            }
            return added;
        });
        when(ops.remove(any(), any())).thenAnswer(inv -> {
            Object[] args = inv.getArguments();
            Set<Object> s = sets.get(args[0]);
            if (s == null)
            {
                return 0L;
            }
            long removed = 0;
            for (int i = 1; i < args.length; i++)
            {
                Object a = args[i];
                if (a instanceof Object[])
                {
                    for (Object x : (Object[]) a)
                    {
                        removed += s.remove(x) ? 1 : 0;
                    }
                }
                else
                {
                    removed += s.remove(a) ? 1 : 0;
                }
            }
            return removed;
        });
        when(ops.members(any())).thenAnswer(inv -> {
            Set<Object> s = sets.get(inv.getArgument(0));
            return s == null ? Collections.emptySet() : new HashSet<Object>(s);
        });

        service = new AppTokenService();
        set(service, "redisCache", redisCache);
        set(service, "expireTime", 10080);
        sessionStore = mock(AppSessionStore.class);
        identities = mock(AppIdentityLoader.class);
        set(service, "sessionStore", sessionStore);
        set(service, "identities", identities);
        when(identities.grants(anyLong())).thenAnswer(inv -> oneGrant());
        when(sessionStore.update(anyString(), anyLong(), any())).thenAnswer(inv -> {
            AppLoginUser current = session(inv.getArgument(0));
            if (current == null) return false;
            ((java.util.function.Consumer<AppLoginUser>) inv.getArgument(2)).accept(current);
            return true;
        });
    }

    private static void set(Object target, String field, Object value) throws Exception
    {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private AppLoginUser login(long userId, String uuid)
    {
        AppLoginUser u = new AppLoginUser();
        u.setUserId(userId);
        u.setToken(uuid);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "createSession", u);
        return u;
    }

    private AppLoginUser session(String uuid)
    {
        return (AppLoginUser) values.get(AppTokenService.APP_LOGIN_TOKEN_KEY + uuid);
    }

    private List<StaffGrantView> oneGrant()
    {
        StaffGrantView g = new StaffGrantView();
        g.setStoreId(7L);
        return Collections.singletonList(g);
    }

    @Test
    @DisplayName("授权变更只刷新这个账号的全部会话，别人的不动，且不调 KEYS")
    void refreshGrantsTouchesOnlyThatUser()
    {
        login(1L, "a1");
        login(1L, "a2");
        login(2L, "b1");

        service.refreshGrants(1L, oneGrant());

        assertEquals(1, session("a1").getStaffGrants().size(), "账号 1 的第一个会话没刷新授权");
        assertEquals(1, session("a2").getStaffGrants().size(), "账号 1 的第二个会话没刷新授权（多端登录只刷了一个）");
        // AppLoginUser 的授权列表默认是空列表（不是 null）：没被动过就还是空的
        assertTrue(session("b1").getStaffGrants().isEmpty(), "账号 2 的会话被顺带改了");
        verify(redisCache, never()).keys(anyString());
    }

    @Test
    @DisplayName("停用账号踢掉它的全部会话、清掉索引，别人的会话还在，且不调 KEYS")
    void kickOutRemovesAllSessionsOfUser()
    {
        login(1L, "a1");
        login(1L, "a2");
        login(2L, "b1");

        service.kickOut(1L);

        assertNull(session("a1"), "被停用账号的会话还在");
        assertNull(session("a2"), "被停用账号的第二个会话还在");
        assertNotNull(session("b1"), "别人的会话被一起踢掉了");
        Set<Object> left = sets.get(AppTokenService.APP_USER_SESSIONS_KEY + 1L);
        assertTrue(left == null || left.isEmpty(), "踢下线之后索引里还留着已删除的会话");
        verify(redisCache, never()).keys(anyString());
    }

    @Test
    @DisplayName("索引里指向已过期会话的条目会被顺手清掉，不会越积越多")
    void staleIndexEntryIsDropped()
    {
        login(1L, "a1");
        values.remove(AppTokenService.APP_LOGIN_TOKEN_KEY + "a1");   // 模拟会话自然过期

        service.refreshGrants(1L, oneGrant());

        assertFalse(sets.get(AppTokenService.APP_USER_SESSIONS_KEY + 1L).contains("a1"),
                "会话已过期，索引项却没清掉");
    }

    @Test
    @DisplayName("主动登出把会话从索引里摘掉")
    void deleteTokenUnindexes()
    {
        login(1L, "a1");
        service.deleteToken("a1");
        assertNull(session("a1"));
        assertFalse(sets.get(AppTokenService.APP_USER_SESSIONS_KEY + 1L).contains("a1"), "登出后索引还留着");
    }

    @Test
    @DisplayName("升级前就存在、没有索引的老会话，补索引之后授权变更同样能刷到它")
    void legacySessionsAreBackfilled()
    {
        // 老会话：直接写进会话存储，不经过 refreshToken，所以没有索引项
        AppLoginUser legacy = new AppLoginUser();
        legacy.setUserId(3L);
        legacy.setToken("old1");
        values.put(AppTokenService.APP_LOGIN_TOKEN_KEY + "old1", legacy);

        service.refreshGrants(3L, oneGrant());
        assertTrue(session("old1").getStaffGrants().isEmpty(), "前提不成立：没补索引时老会话本不该被找到");

        int n = service.indexExisting(Arrays.asList(AppTokenService.APP_LOGIN_TOKEN_KEY + "old1"));
        assertEquals(1, n, "补索引没有补进这一个老会话");

        service.refreshGrants(3L, oneGrant());
        assertEquals(1, session("old1").getStaffGrants().size(), "补了索引之后授权变更仍刷不到老会话");
        verify(redisCache, never()).keys(anyString());
    }

    @Test
    void staleRenewalCannotResurrectRevokedSession()
    {
        AppLoginUser old = login(1L, "old");
        old.setExpireTime(0L);
        service.kickOut(1L);
        org.junit.jupiter.api.Assertions.assertThrows(com.ruoyi.common.exception.ServiceException.class, () -> service.verifyToken(old));
        assertNull(session("old"));
        assertFalse(sets.get(AppTokenService.APP_USER_SESSIONS_KEY + 1L).contains("old"));
    }

    @Test
    void renewalOnlyUpdatesTimesAndCannotOverwriteNewPermissions()
    {
        AppLoginUser persisted = login(1L, "old");
        persisted.setStaffGrants(Collections.emptyList());
        AppLoginUser stale = new AppLoginUser();
        stale.setToken("old"); stale.setUserId(1L); stale.setStaffGrants(oneGrant()); stale.setExpireTime(0L);
        service.verifyToken(stale);
        assertTrue(session("old").getStaffGrants().isEmpty());
        assertTrue(session("old").getExpireTime() > System.currentTimeMillis());
    }

    @Test
    void committedGrantSurvivesSessionIndexMaintenanceFailure()
    {
        when(redisCache.redisTemplate.opsForSet().members(anyString())).thenThrow(new IllegalStateException("injected Redis failure"));
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try
        {
            service.refreshGrants(1L, oneGrant());
            for (org.springframework.transaction.support.TransactionSynchronization sync : org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations())
                org.junit.jupiter.api.Assertions.assertDoesNotThrow(sync::afterCommit);
            verify(identities, never()).grants(anyLong());
        }
        finally
        {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void grantCacheIsNotPublishedBeforeTransactionCommit()
    {
        AppLoginUser u = login(1L, "old");
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try
        {
            service.refreshGrants(1L, oneGrant());
            assertTrue(u.getStaffGrants().isEmpty());
            // A rollback invokes no afterCommit callback: no cache write should occur.
            verify(identities, never()).grants(anyLong());
            for (org.springframework.transaction.support.TransactionSynchronization sync : org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()) sync.afterCommit();
            assertEquals(1, u.getStaffGrants().size());
        }
        finally
        {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}
