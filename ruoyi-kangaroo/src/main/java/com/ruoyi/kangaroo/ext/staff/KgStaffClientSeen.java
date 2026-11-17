package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.AppLoginUser;

/**
 * 记下有员工授权的账号用的是哪个版本的小程序（请求头 X-App-Client: release/2026.10.30.2），给后台切换页按人显示「已用新版 / 还在老版 / 没打开过」。
 * 老版本不带这个头，记成 channel = none。
 *
 * 只用于显示和「至少见过正式版或体验版新版」这道门槛，绝不参与授权：请求头能伪造，也能不带。
 * 内存去重（同一人同一渠道、同一版本 6 小时写一次）；任何失败都吞掉，不能影响请求。
 */
@Component
public class KgStaffClientSeen
{
    private static final Logger log = LoggerFactory.getLogger(KgStaffClientSeen.class);

    public static final String HEADER = "X-App-Client";
    public static final String NO_HEADER = "none";

    /** 版本号只认数字和点，每段最多 9 位，总长不超过列宽：超长数字进了库，切换页按版本比大小时不能出事（KgRoleSwitchService.atLeast 另有兜底） */
    private static final Pattern VALUE = Pattern.compile("(develop|trial|release)/((?=.{1,32}$)\\d{1,9}(?:\\.\\d{1,9})*)");
    private static final long REWRITE_MS = 6 * 3600_000L;
    private static final int MAX_KEYS = 20000;

    @Autowired
    private KgJdbc db;

    /** 账号 + 渠道 → {版本, 上次写库时间} */
    private final Map<String, Object[]> recent = new ConcurrentHashMap<String, Object[]>();

    public void record(AppLoginUser user, String header)
    {
        try
        {
            if (user == null || user.getStaffGrants() == null || user.getStaffGrants().isEmpty())
            {
                return;
            }
            String channel = NO_HEADER;
            String version = "";
            if (header != null)
            {
                Matcher m = VALUE.matcher(header.trim());
                if (!m.matches())
                {
                    // 格式不对的头当没带：不让任意字符串进库
                    header = null;
                }
                else
                {
                    channel = m.group(1);
                    version = m.group(2);
                }
            }
            String key = user.getUserId() + "|" + channel;
            long now = System.currentTimeMillis();
            Object[] last = recent.get(key);
            if (last != null && version.equals(last[0]) && now - (Long) last[1] < REWRITE_MS)
            {
                return;
            }
            if (recent.size() > MAX_KEYS)
            {
                recent.clear();
            }
            db.update("insert into kg_staff_client_seen (app_user_id, channel, version, first_seen_time, last_seen_time) values (:u, :c, :v, now(), now()) "
                    + "on duplicate key update version = values(version), last_seen_time = now()", p("u", user.getUserId(), "c", channel, "v", version));
            recent.put(key, new Object[] { version, now });
        }
        catch (RuntimeException e)
        {
            log.debug("记录小程序版本失败：{}", e.getMessage());
        }
    }
}
