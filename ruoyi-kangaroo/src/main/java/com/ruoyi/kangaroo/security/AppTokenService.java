package com.ruoyi.kangaroo.security;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.ip.IpUtils;
import com.ruoyi.common.utils.uuid.IdUtils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

/**
 * 小程序端令牌服务。
 *
 * 与管理后台 TokenService 的区别：
 * 1. 请求头不同：小程序使用 X-App-Token，管理后台使用 Authorization。
 * 2. Redis key 前缀不同：kangaroo:app_login_tokens: vs login_tokens:。
 * 3. JWT 中写入 typ=app 声明，管理后台令牌无此声明，互相无法冒用。
 */
@Component
public class AppTokenService
{
    private static final Logger log = LoggerFactory.getLogger(AppTokenService.class);

    /** 小程序登录态 Redis key 前缀 */
    public static final String APP_LOGIN_TOKEN_KEY = "kangaroo:app_login_tokens:";

    /**
     * 每个账号名下有哪些会话：Redis Set，成员是会话 uuid。
     *
     * 为什么要有它：授权变更与账号停用都要找出"这个账号现在的全部会话"。
     * 原先的做法是 KEYS kangaroo:app_login_tokens:* 全库扫描再逐个 GET ——
     * KEYS 在 Redis 里是阻塞整个实例的 O(N) 命令，后台每点一次授予/撤销/停用，
     * 所有用户（含管理后台的登录态）都要等它扫完，且随会话总数线性恶化。
     * 有了这个索引，只需要读这一个账号自己的会话集合。
     */
    public static final String APP_USER_SESSIONS_KEY = "kangaroo:app_user_sessions:";

    /** 小程序令牌请求头 */
    public static final String APP_TOKEN_HEADER = "X-App-Token";

    /** JWT 中标识令牌类型的 claim，用于阻断与后台令牌互用 */
    private static final String CLAIM_TYPE = "typ";

    private static final String CLAIM_TYPE_APP = "app";

    private static final String CLAIM_UUID = "app_login_uuid";

    private static final long MILLIS_MINUTE = 60 * 1000L;

    /** 剩余不足 20 分钟时自动续期 */
    private static final long MILLIS_MINUTE_TWENTY = 20 * MILLIS_MINUTE;

    @Value("${token.secret}")
    private String secret;

    /** 小程序令牌有效期（分钟），默认 7 天 */
    @Value("${kangaroo.token.expireTime:10080}")
    private int expireTime;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private AppSessionStore sessionStore;

    @Autowired
    private AppIdentityLoader identities;


    /**
     * 创建登录态并返回下发给小程序的 JWT
     */
    public String createToken(AppLoginUser loginUser, HttpServletRequest request)
    {
        // 不用 IdUtils.fastUUID()：它底层是 ThreadLocalRandom，不是密码学安全的随机源。
        // 这个 uuid 是会话在 Redis 里的键，而它就明文躺在用户自己的 JWT 里（base64 可解），
        // 拿到足够多的输出去反推序列、进而猜别人的会话键，理论上可行。
        // UUID.randomUUID() 走 SecureRandom，代价只有一次随机数生成。
        String uuid = UUID.randomUUID().toString();
        loginUser.setToken(uuid);
        loginUser.setCreateTime(Long.valueOf(System.currentTimeMillis()));
        if (request != null)
        {
            loginUser.setLoginIp(IpUtils.getIpAddr(request));
            loginUser.setDevice(deviceOf(request.getHeader("User-Agent")));
        }
        createSession(loginUser);

        Map<String, Object> claims = new HashMap<String, Object>();
        claims.put(CLAIM_UUID, uuid);
        claims.put(CLAIM_TYPE, CLAIM_TYPE_APP);
        return Jwts.builder().setClaims(claims).signWith(SignatureAlgorithm.HS512, secret).compact();
    }

    /**
     * 从请求中解析登录态；无令牌或令牌无效返回 null（由调用方决定是否放行）
     */
    public AppLoginUser getLoginUser(HttpServletRequest request)
    {
        String token = getTokenFromRequest(request);
        if (StringUtils.isEmpty(token))
        {
            return null;
        }
        try
        {
            Claims claims = parseToken(token);
            if (!CLAIM_TYPE_APP.equals(claims.get(CLAIM_TYPE)))
            {
                // 后台管理令牌不得用于小程序接口
                return null;
            }
            Object uuid = claims.get(CLAIM_UUID);
            if (uuid == null)
            {
                return null;
            }
            return redisCache.getCacheObject(APP_LOGIN_TOKEN_KEY + uuid);
        }
        catch (Exception e)
        {
            log.debug("解析小程序令牌失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 校验有效期，不足 20 分钟自动续期
     */
    public void verifyToken(AppLoginUser loginUser)
    {
        long expire = loginUser.getExpireTime() == null ? 0L : loginUser.getExpireTime().longValue();
        long current = System.currentTimeMillis();
        if (expire - current <= MILLIS_MINUTE_TWENTY)
        {
            refreshToken(loginUser);
        }
    }

    /**
     * 刷新登录态（同时刷新 Redis 过期时间）
     */
    private void createSession(AppLoginUser loginUser)
    {
        long now = System.currentTimeMillis();
        if (loginUser.getCreateTime() == null)
        {
            // 升级前签发的会话没有这个字段，按首次续期时间兜底，不假装知道它真正的登录时间
            loginUser.setCreateTime(Long.valueOf(now));
        }
        loginUser.setLoginTime(Long.valueOf(now));
        loginUser.setExpireTime(Long.valueOf(now + expireTime * MILLIS_MINUTE));
        redisCache.setCacheObject(APP_LOGIN_TOKEN_KEY + loginUser.getToken(), loginUser,
                Integer.valueOf(expireTime), TimeUnit.MINUTES);
        indexAdd(loginUser.getUserId(), loginUser.getToken());
    }

    /** 只更新 Redis 当前存续对象的时间字段，不保存调用者的资料/角色快照。 */
    public void refreshToken(AppLoginUser loginUser)
    {
        boolean alive = sessionStore.update(loginUser.getToken(), expireTime * MILLIS_MINUTE, current -> {
            long now = System.currentTimeMillis();
            if (current.getCreateTime() == null) current.setCreateTime(now);
            current.setLoginTime(now);
            current.setExpireTime(now + expireTime * MILLIS_MINUTE);
        });
        if (!alive) throw new com.ruoyi.common.exception.ServiceException("未登录或登录已失效", 401);
        redisCache.expire(APP_USER_SESSIONS_KEY + loginUser.getUserId(), expireTime, TimeUnit.MINUTES);
    }

    /**
     * 主动登出
     */
    public void deleteToken(String uuid)
    {
        if (StringUtils.isNotEmpty(uuid))
        {
            AppLoginUser user = redisCache.getCacheObject(APP_LOGIN_TOKEN_KEY + uuid);
            redisCache.deleteObject(APP_LOGIN_TOKEN_KEY + uuid);
            if (user != null)
            {
                indexRemove(user.getUserId(), uuid);
            }
        }
    }

    /**
     * 授权变更后强制刷新缓存中的登录态（供管理后台授权/撤销员工时调用）
     */
    public void refreshGrants(Long appUserId, java.util.List<StaffGrantView> grants)
    {
        // 不捕获事务内的授权快照；提交后才读库。安全判权还由请求入口重读 DB 保证。
        Runnable refresh = () -> {
            java.util.Collection<String> sessions;
            try { sessions = sessionsOf(appUserId); }
            catch (RuntimeException e) {
                // DB 已提交，缓存维护失败不能向调用者谎报授权未生效。后续每个请求仍强制查 DB。
                log.warn("授权已提交但会话索引读取失败 user={}，后续请求将按数据库权限恢复", appUserId, e);
                return;
            }
            for (String uuid : sessions)
            {
                try
                {
                    boolean alive = sessionStore.update(uuid, 0, user -> {
                        if (appUserId.equals(user.getUserId())) user.setStaffGrants(identities.grants(appUserId));
                    });
                    if (!alive) indexRemove(appUserId, uuid);
                }
                catch (RuntimeException e)
                {
                    log.warn("会话授权缓存刷新失败 user={}，请求仍按数据库鉴权", appUserId);
                }
            }
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                && org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())
        {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { refresh.run(); }
                });
        }
        else refresh.run();
    }

    /**
     * 账号被停用时踢下线
     */
    public void kickOut(Long appUserId)
    {
        for (String uuid : sessionsOf(appUserId))
        {
            String key = APP_LOGIN_TOKEN_KEY + uuid;
            AppLoginUser user = redisCache.getCacheObject(key);
            if (user == null || appUserId.equals(user.getUserId()))
            {
                redisCache.deleteObject(key);
                indexRemove(appUserId, uuid);
            }
        }
    }

    /**
     * "设备与登录"列表：本账号当前有效的会话，当前这台排在最前。
     *
     * 只读 Redis 里真实存在的会话，过期的会话顺手从索引里清掉，所以列表里不会出现已经失效的设备。
     */
    public java.util.List<Map<String, Object>> listSessions(Long userId, String currentUuid)
    {
        java.util.List<Map<String, Object>> rows = new java.util.ArrayList<Map<String, Object>>();
        for (String uuid : sessionsOf(userId))
        {
            AppLoginUser user = redisCache.getCacheObject(APP_LOGIN_TOKEN_KEY + uuid);
            if (user == null)
            {
                indexRemove(userId, uuid);
                continue;
            }
            if (!userId.equals(user.getUserId()))
            {
                continue;
            }
            boolean current = uuid.equals(currentUuid);
            Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
            m.put("id", uuid);
            m.put("current", Boolean.valueOf(current));
            m.put("device", StringUtils.isEmpty(user.getDevice()) ? "未知设备" : user.getDevice());
            m.put("ip", maskIp(user.getLoginIp()));
            m.put("loginTime", user.getCreateTime());
            m.put("activeTime", user.getLoginTime());
            m.put("expireTime", user.getExpireTime());
            rows.add(m);
        }
        rows.sort((a, b) -> {
            boolean ca = Boolean.TRUE.equals(a.get("current"));
            boolean cb = Boolean.TRUE.equals(b.get("current"));
            if (ca != cb)
            {
                return ca ? -1 : 1;
            }
            long ta = a.get("activeTime") == null ? 0L : ((Number) a.get("activeTime")).longValue();
            long tb = b.get("activeTime") == null ? 0L : ((Number) b.get("activeTime")).longValue();
            return Long.compare(tb, ta);
        });
        return rows;
    }

    /**
     * 退出除当前会话外的其他设备
     *
     * @return 被退出的设备数
     */
    public int revokeOthers(Long userId, String currentUuid)
    {
        int n = 0;
        for (String uuid : sessionsOf(userId))
        {
            if (uuid.equals(currentUuid))
            {
                continue;
            }
            AppLoginUser user = redisCache.getCacheObject(APP_LOGIN_TOKEN_KEY + uuid);
            if (user != null && !userId.equals(user.getUserId()))
            {
                continue;
            }
            redisCache.deleteObject(APP_LOGIN_TOKEN_KEY + uuid);
            indexRemove(userId, uuid);
            if (user != null)
            {
                n++;
            }
        }
        return n;
    }

    /** 退出指定的某一台设备（不允许退出当前这台，前端用返回值提示） */
    public boolean revokeOne(Long userId, String uuid)
    {
        AppLoginUser user = redisCache.getCacheObject(APP_LOGIN_TOKEN_KEY + uuid);
        if (user == null || !userId.equals(user.getUserId()))
        {
            indexRemove(userId, uuid);
            return false;
        }
        redisCache.deleteObject(APP_LOGIN_TOKEN_KEY + uuid);
        indexRemove(userId, uuid);
        return true;
    }

    /** 从 User-Agent 粗略判断登录端；判断不出来就说"未知设备"，不编造机型 */
    static String deviceOf(String ua)
    {
        if (StringUtils.isEmpty(ua))
        {
            return "未知设备";
        }
        String s = ua.toLowerCase();
        String os = s.contains("android") ? "Android" : (s.contains("iphone") || s.contains("ipad") || s.contains("ios")) ? "iOS"
                : s.contains("windows") ? "Windows" : s.contains("mac os") ? "Mac" : "";
        String client = s.contains("miniprogram") || s.contains("micromessenger") ? "微信小程序" : "网页端";
        return os.isEmpty() ? client : client + "（" + os + "）";
    }

    /** IP 只展示到网段，避免在页面上完整暴露用户所在网络 */
    static String maskIp(String ip)
    {
        if (StringUtils.isEmpty(ip))
        {
            return "";
        }
        int i = ip.lastIndexOf('.');
        if (i > 0)
        {
            return ip.substring(0, i) + ".*";
        }
        i = ip.lastIndexOf(':');
        return i > 0 ? ip.substring(0, i) + ":*" : ip;
    }

    /**
     * 把"升级前就已登录"的会话补进索引。
     *
     * 这一步不能省：升级前签发的会话没有索引项，上线后管理员撤销一个老会话员工的授权，
     * refreshGrants 会找不到他的会话 —— 他在令牌过期前（默认 7 天）仍然握着员工权限。
     * 所以启动时用 SCAN（游标式、不阻塞实例，与 KEYS 不同）把现存会话过一遍，一次性补齐。
     * 之后新签发的会话都在 refreshToken 里自带索引。
     *
     * @return 补进索引的会话数
     */
    public int backfillSessionIndex()
    {
        java.util.List<String> keys = new java.util.ArrayList<String>();
        try
        {
            org.springframework.data.redis.core.RedisCallback<Void> scan = connection -> {
                org.springframework.data.redis.core.ScanOptions options =
                        org.springframework.data.redis.core.ScanOptions.scanOptions()
                                .match(APP_LOGIN_TOKEN_KEY + "*").count(500).build();
                try (org.springframework.data.redis.core.Cursor<byte[]> cursor = connection.scan(options))
                {
                    while (cursor.hasNext())
                    {
                        keys.add(new String(cursor.next(), java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
                return null;
            };
            redisCache.redisTemplate.execute(scan);
        }
        catch (Exception e)
        {
            log.warn("扫描现存小程序会话失败，老会话将不会随授权变更刷新，直到它们自然过期: {}", e.getMessage());
            return 0;
        }
        return indexExisting(keys);
    }

    /** 应用就绪后补一次索引（见 backfillSessionIndex）。失败只告警，不影响启动。 */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void onApplicationReady()
    {
        int n = backfillSessionIndex();
        log.info("小程序会话索引已补齐：{} 个现存会话", n);
    }

    /** backfillSessionIndex 的落地部分：给定一批会话 key，把它们记进各自账号的索引 */
    int indexExisting(java.util.Collection<String> sessionKeys)
    {
        int n = 0;
        for (String key : sessionKeys)
        {
            AppLoginUser user = redisCache.getCacheObject(key);
            if (user != null && user.getUserId() != null && key.startsWith(APP_LOGIN_TOKEN_KEY))
            {
                indexAdd(user.getUserId(), key.substring(APP_LOGIN_TOKEN_KEY.length()));
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ 会话索引

    @SuppressWarnings("unchecked")
    private void indexAdd(Long userId, String uuid)
    {
        if (userId == null || StringUtils.isEmpty(uuid))
        {
            return;
        }
        String key = APP_USER_SESSIONS_KEY + userId;
        redisCache.redisTemplate.opsForSet().add(key, uuid);
        // 索引活得和该账号最新的那个会话一样久；会话都过期后它也随之过期，不会无限堆积
        redisCache.expire(key, expireTime, TimeUnit.MINUTES);
    }

    @SuppressWarnings("unchecked")
    private void indexRemove(Long userId, String uuid)
    {
        if (userId == null || StringUtils.isEmpty(uuid))
        {
            return;
        }
        redisCache.redisTemplate.opsForSet().remove(APP_USER_SESSIONS_KEY + userId, uuid);
    }

    @SuppressWarnings("unchecked")
    private java.util.Set<String> sessionsOf(Long userId)
    {
        if (userId == null)
        {
            return java.util.Collections.emptySet();
        }
        java.util.Set<Object> members = redisCache.redisTemplate.opsForSet().members(APP_USER_SESSIONS_KEY + userId);
        java.util.Set<String> out = new java.util.HashSet<String>();
        if (members != null)
        {
            for (Object m : members)
            {
                if (m != null)
                {
                    out.add(String.valueOf(m));
                }
            }
        }
        return out;
    }

    private String getTokenFromRequest(HttpServletRequest request)
    {
        String token = request.getHeader(APP_TOKEN_HEADER);
        if (StringUtils.isNotEmpty(token) && token.startsWith("Bearer "))
        {
            token = token.replaceFirst("Bearer ", "");
        }
        return token;
    }

    private Claims parseToken(String token)
    {
        return Jwts.parser().setSigningKey(secret).parseClaimsJws(token).getBody();
    }
}
