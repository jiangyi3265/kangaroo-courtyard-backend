package com.ruoyi.kangaroo.security;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.kangaroo.config.KangarooProperties;

/**
 * 微信文本内容安全检查（msgSecCheck v2，POST https://api.weixin.qq.com/wxa/msg_sec_check）。
 *
 * 只给社区「机审直接发布」（kangaroo.content.review-mode=auto）用：只有微信明确判「通过」（errcode=0、suggest=pass、label=100）
 * 才返回 true；没有 openid、没配微信、取 token 失败、接口报错、超时（默认 2 秒）、判「需复核 / 有风险」一律返回 false，
 * 调用方照旧把内容放进人工审核，不会因为接口出问题就放出没审过的内容，也不会让用户的发布报错。
 * access_token 复用 WxMiniAppClient 的那一份。URL 带 token、正文是用户内容，都不写日志。
 *
 * 本地联调模式（kangaroo.wx.mock-enabled=true）不连微信：文字里带「#机审不通过#」判不通过，带「#机审超时#」模拟超时，其余判通过。
 */
@Component
public class WxMsgSecCheck
{
    private static final Logger log = LoggerFactory.getLogger(WxMsgSecCheck.class);

    private static final String URL_BASE = "https://api.weixin.qq.com/wxa/msg_sec_check";

    /** 微信场景值：2 评论，3 论坛（社区动态） */
    public static final int SCENE_COMMENT = 2;
    public static final int SCENE_FORUM = 3;

    /** 本地联调用的模拟标记 */
    public static final String MOCK_RISKY = "#机审不通过#";
    public static final String MOCK_TIMEOUT = "#机审超时#";

    @Autowired
    private KangarooProperties properties;

    @Autowired
    private WxMiniAppClient wx;

    /** 小线程池：限住总等待时间（token 获取 + 请求一起算）；排满了就直接转人工，不让发布请求排队等 */
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(32), new java.util.concurrent.ThreadFactory()
    {
        private final AtomicInteger n = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r)
        {
            Thread t = new Thread(r, "kg-msgsec-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    @PreDestroy
    public void shutdown()
    {
        pool.shutdownNow();
    }

    /**
     * 微信是否明确判这段文字「通过」。
     *
     * @param openid  发布人的小程序 openid（kg_app_user.openid）；微信要求是近 2 小时访问过小程序的用户
     * @param scene   {@link #SCENE_COMMENT} / {@link #SCENE_FORUM}
     * @param title   标题（可空）
     * @param content 正文（可空；标题、正文都空时直接返回 false）
     */
    public boolean passes(final String openid, final int scene, final String title, final String content)
    {
        final String t = title == null ? "" : title.trim();
        final String c = content == null ? "" : content.trim();
        if (StringUtils.isEmpty(openid) || (t.isEmpty() && c.isEmpty()))
        {
            return false;
        }
        final boolean mock = properties.isWxMockEnabled();
        if (!mock && !properties.isWxConfigured())
        {
            return false;
        }
        long timeout = Math.max(200L, properties.getContentSecCheckTimeoutMs());
        Future<Boolean> f;
        try
        {
            f = pool.submit(() -> mock ? mockVerdict(t + "\n" + c, timeout) : callWechat(openid, scene, t, c, timeout));
        }
        catch (RuntimeException e)
        {
            log.warn("内容安全检查排队已满，本条转人工审核");
            return false;
        }
        try
        {
            return Boolean.TRUE.equals(f.get(timeout, TimeUnit.MILLISECONDS));
        }
        catch (java.util.concurrent.TimeoutException e)
        {
            f.cancel(true);
            log.warn("内容安全检查超时（{} 毫秒），本条转人工审核", timeout);
            return false;
        }
        catch (InterruptedException e)
        {
            f.cancel(true);
            Thread.currentThread().interrupt();
            return false;
        }
        catch (Exception e)
        {
            // 异常信息里可能带 URL（含 access_token），只记类型
            log.warn("内容安全检查失败（{}），本条转人工审核", e.getCause() == null ? e.getClass().getSimpleName() : e.getCause().getClass().getSimpleName());
            return false;
        }
    }

    static boolean mockVerdict(String text, long timeout) throws InterruptedException
    {
        if (text.contains(MOCK_TIMEOUT))
        {
            Thread.sleep(timeout + 1000L);
            return true;
        }
        return !text.contains(MOCK_RISKY);
    }

    private boolean callWechat(String openid, int scene, String title, String content, long timeout) throws Exception
    {
        JSONObject body = new JSONObject();
        body.put("version", 2);
        body.put("scene", scene);
        body.put("openid", openid);
        // 正文为空（只写了标题的动态）时把标题当正文查；有正文时标题单独放 title 一起查
        body.put("content", content.isEmpty() ? title : content);
        if (!content.isEmpty() && !title.isEmpty())
        {
            body.put("title", title);
        }
        String token = wx.sharedAccessToken();
        String resp = post(URL_BASE + "?access_token=" + token, body.toJSONString(), (int) timeout);
        JSONObject json = JSON.parseObject(resp);
        Integer err = errcode(json);
        if (err != null && (err.intValue() == 40001 || err.intValue() == 42001))
        {
            wx.invalidateAccessToken();
        }
        boolean ok = verdictPass(json);
        if (!ok)
        {
            JSONObject r = json == null ? null : json.getJSONObject("result");
            log.info("内容安全检查未判通过 errcode={} suggest={} label={}，本条转人工审核", err,
                    r == null ? null : r.getString("suggest"), r == null ? null : r.get("label"));
        }
        return ok;
    }

    /** 只有 errcode=0、result.suggest=pass、result.label=100 才算通过；字段缺了、类型不对都算不通过 */
    static boolean verdictPass(JSONObject json)
    {
        Integer err = errcode(json);
        if (err == null || err.intValue() != 0)
        {
            return false;
        }
        JSONObject r = json.getJSONObject("result");
        if (r == null || !"pass".equals(r.getString("suggest")))
        {
            return false;
        }
        Object label = r.get("label");
        return label instanceof Number && ((Number) label).intValue() == 100;
    }

    private static Integer errcode(JSONObject json)
    {
        Object code = json == null ? null : json.get("errcode");
        return code instanceof Number ? Integer.valueOf(((Number) code).intValue()) : null;
    }

    /** 不写日志的 POST；超时与总等待一致。留成 protected 给测试替换 */
    protected String post(String url, String json, int timeoutMs) throws Exception
    {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try
        {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300)
            {
                throw new java.io.IOException("HTTP " + status);
            }
            try (InputStream in = conn.getInputStream())
            {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 64 * 1024)
                {
                    out.write(buf, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        }
        finally
        {
            conn.disconnect();
        }
    }
}
