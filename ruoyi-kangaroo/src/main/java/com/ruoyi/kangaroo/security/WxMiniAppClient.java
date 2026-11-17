package com.ruoyi.kangaroo.security;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.kangaroo.config.KangarooProperties;

/**
 * 微信小程序开放接口客户端。
 *
 * 登录、手机号、订阅消息及活动小程序码；服务凭据仅留在服务端。
 *
 * 真实调用需要环境变量 KANGAROO_WX_APPID / KANGAROO_WX_SECRET。
 * 未配置且未开启本地联调模式时，直接抛出明确错误，不做任何降级，
 * 避免"接口返回成功但身份是假的"这种比报错更糟的情况。
 */
@Component
public class WxMiniAppClient
{
    private static final Logger log = LoggerFactory.getLogger(WxMiniAppClient.class);

    private static final String CODE2SESSION_URL = "https://api.weixin.qq.com/sns/jscode2session";

    @Autowired
    private KangarooProperties properties;

    /**
     * code 换 openid。
     *
     * @param code 小程序 uni.login 返回的一次性 code
     * @return 会话信息，至少包含 openid
     */
    public WxSession code2session(String code)
    {
        if (StringUtils.isEmpty(code))
        {
            throw new ServiceException("微信登录凭证 code 不能为空");
        }

        if (properties.isWxMockEnabled())
        {
            // 本地联调模式：把 code 当作 openid。启动时已打过醒目警告。
            WxSession session = new WxSession();
            session.setOpenid("mock_" + code);
            session.setUnionid(null);
            session.setSessionKey("mock-session-key");
            session.setMock(true);
            return session;
        }

        if (!properties.isWxConfigured())
        {
            throw new ServiceException(
                    "微信登录尚未配置：请在服务端环境变量中设置 KANGAROO_WX_APPID 与 KANGAROO_WX_SECRET");
        }

        String params = "appid=" + properties.getWxAppId()
                + "&secret=" + properties.getWxAppSecret()
                + "&js_code=" + code
                + "&grant_type=authorization_code";

        String body;
        try
        {
            body = httpGetWithoutLogging(CODE2SESSION_URL + "?" + params);
        }
        catch (Exception e)
        {
            // 异常消息本身可能带着完整 URL（HttpURLConnection 在非 2xx 时抛的
            // "Server returned HTTP response code: 400 for URL: https://...secret=..."
            // 就是这种），所以只打脱敏后的类名与消息，不打堆栈。
            log.error("调用微信 code2session 失败: {} {}", e.getClass().getName(), redact(e.getMessage()));
            throw new ServiceException("微信登录服务暂时不可用，请稍后重试");
        }

        if (StringUtils.isEmpty(body))
        {
            throw new ServiceException("微信登录服务无响应，请稍后重试");
        }

        JSONObject json;
        try
        {
            json = JSON.parseObject(body);
        }
        catch (Exception e)
        {
            // 不打响应体：它在正常情况下含 session_key 与 openid。
            // 出错时长度和前缀足够判断是不是被网关/代理换成了 HTML 错误页。
            log.error("微信 code2session 返回无法解析（长度 {}，首字符 {}）",
                    body.length(), body.isEmpty() ? "" : body.substring(0, 1));
            throw new ServiceException("微信登录返回异常");
        }

        Integer errcode = json.getInteger("errcode");
        if (errcode != null && errcode.intValue() != 0)
        {
            // 不把微信原始 errmsg 直接透给客户端，避免泄露配置细节
            log.warn("微信 code2session 失败 errcode={} errmsg={}", errcode, json.getString("errmsg"));
            if (errcode.intValue() == 40029)
            {
                throw new ServiceException("微信登录凭证已失效，请重新登录");
            }
            throw new ServiceException("微信登录失败，请重新登录");
        }

        String openid = json.getString("openid");
        if (StringUtils.isEmpty(openid))
        {
            throw new ServiceException("微信登录未返回 openid，请重新登录");
        }

        WxSession session = new WxSession();
        session.setOpenid(openid);
        session.setUnionid(json.getString("unionid"));
        session.setSessionKey(json.getString("session_key"));
        session.setMock(false);
        return session;
    }

    /**
     * 发一个不写日志的 GET。
     *
     * 这里没有复用 com.ruoyi.common.utils.http.HttpUtils：它在 INFO 级别打印
     * 完整请求 URL（HttpUtils.java 的 "sendGet - {}"）与完整响应体（"recv - {}"）。
     * 本方法的 URL 里带着 AppSecret、响应体里带着 session_key 和 openid ——
     * 用它等于把小程序密钥和每一个用户的会话密钥写进应用日志，
     * 而日志通常比配置文件流转得更广（采集、归档、发给别人排查）。
     *
     * 只在这一处绕开 HttpUtils，不改动框架里的共用工具：那会影响其它模块，
     * 属于本轮约定里"不擅自进行无关重构"的范围。
     */
    private String httpGetWithoutLogging(String url) throws Exception
    {
        HttpURLConnection conn = null;
        BufferedReader reader = null;
        try
        {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("accept", "*/*");
            int status = conn.getResponseCode();
            InputStream in = (status >= 200 && status < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            if (in == null)
            {
                return "";
            }
            reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null)
            {
                sb.append(line);
            }
            return sb.toString();
        }
        finally
        {
            if (reader != null)
            {
                try
                {
                    reader.close();
                }
                catch (Exception ignored)
                {
                    // 关闭失败不影响已经读到的内容
                }
            }
            if (conn != null)
            {
                conn.disconnect();
            }
        }
    }

    /** 把 AppID / AppSecret 从要写进日志的文本里抹掉 */
    private String redact(String text)
    {
        if (text == null)
        {
            return "";
        }
        String out = text;
        String secret = properties.getWxAppSecret();
        if (StringUtils.isNotEmpty(secret))
        {
            out = out.replace(secret, "***");
        }
        String appId = properties.getWxAppId();
        if (StringUtils.isNotEmpty(appId))
        {
            out = out.replace(appId, "***");
        }
        return out;
    }

    private static final String TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/token";
    private static final String PHONE_URL = "https://api.weixin.qq.com/wxa/business/getuserphonenumber";

    private static final String SUBSCRIBE_URL = "https://api.weixin.qq.com/cgi-bin/message/subscribe/send";
    private String accessToken;
    private long accessTokenExpireAt;

    /**
     * 手机号快速验证：小程序端 open-type="getPhoneNumber" 回调里的 code 换用户微信绑定的手机号。
     * 本地联调模式下只接受 "mock-phone-1xxxxxxxxxx" 形式的 code（前端 H5 会让开发者自己输入号码），生产无此通道。
     */
    public String getPhoneNumber(String code)
    {
        if (StringUtils.isEmpty(code))
        {
            throw new ServiceException("手机号授权凭证不能为空");
        }
        if (properties.isWxMockEnabled())
        {
            if (!code.matches("mock-phone-1[3-9]\\d{9}"))
            {
                throw new ServiceException("联调模式下请用 mock-phone-<11 位手机号> 模拟微信手机号授权");
            }
            return code.substring("mock-phone-".length());
        }
        if (!properties.isWxConfigured())
        {
            throw new ServiceException("微信手机号验证尚未配置（缺少 KANGAROO_WX_APPID / KANGAROO_WX_SECRET）");
        }
        String body;
        try
        {
            body = httpPostJsonWithoutLogging(PHONE_URL + "?access_token=" + accessToken(), "{\"code\":\"" + code.replace("\"", "") + "\"}");
        }
        catch (ServiceException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.error("调用微信手机号接口失败: {} {}", e.getClass().getName(), redact(e.getMessage()));
            throw new ServiceException("微信手机号服务暂时不可用，请稍后重试");
        }
        JSONObject json;
        try
        {
            json = JSON.parseObject(body);
        }
        catch (Exception e)
        {
            throw new ServiceException("微信手机号接口返回异常");
        }
        Integer errcode = json.getInteger("errcode");
        if (errcode != null && errcode.intValue() != 0)
        {
            log.warn("微信手机号接口失败 errcode={}", errcode);
            if (errcode.intValue() == 40001 || errcode.intValue() == 42001)
            {
                accessToken = null;
            }
            throw new ServiceException("手机号验证失败，请重新授权");
        }
        JSONObject info = json.getJSONObject("phone_info");
        String phone = info == null ? null : info.getString("purePhoneNumber");
        if (StringUtils.isEmpty(phone))
        {
            throw new ServiceException("微信未返回手机号，请重新授权");
        }
        return phone;
    }

    /**
     * 发送订阅消息（一次性订阅）。
     *
     * 调用方必须自己确认用户还有授权额度 —— 微信侧每次授权只允许发一条，超发会返回 43101 并被用户端标记为骚扰。
     * 返回 null 表示发送成功，否则返回失败原因（调用方写进发送留痕，不抛异常打断主业务）。
     *
     * @param openid     接收人
     * @param templateId 公众平台申请到的模板ID
     * @param page       点击后打开的小程序页面（可为空）
     * @param data       模板字段，形如 {"thing1": {"value": "..."}}
     */
    public static class SubscribeDelivery
    {
        public enum State { SENT, REJECTED, NOT_ATTEMPTED, UNKNOWN }
        public final State state;
        public final String detail;
        public SubscribeDelivery(State state, String detail) { this.state=state; this.detail=detail; }
    }

    public SubscribeDelivery deliverSubscribeMessage(String openid, String templateId, String page, java.util.Map<String, Object> data)
    {
        if (StringUtils.isEmpty(openid) || StringUtils.isEmpty(templateId))
        {
            return new SubscribeDelivery(SubscribeDelivery.State.NOT_ATTEMPTED, "缺少 openid 或模板ID，尚未发送");
        }
        if (!properties.isWxConfigured())
        {
            return new SubscribeDelivery(SubscribeDelivery.State.NOT_ATTEMPTED, "微信小程序未配置，尚未发送");
        }
        JSONObject body = new JSONObject();
        body.put("touser", openid);
        body.put("template_id", templateId);
        body.put("miniprogram_state",properties.getSubscribeMiniProgramState());
        if (StringUtils.isNotEmpty(page))
        {
            body.put("page", page);
        }
        body.put("data", data == null ? new JSONObject() : data);
        String resp;
        String token;
        try { token = accessToken(); }
        catch (Exception e) { return new SubscribeDelivery(SubscribeDelivery.State.NOT_ATTEMPTED, "微信凭证获取失败，尚未发送"); }
        try
        {
            resp = httpPostJsonWithoutLogging(SUBSCRIBE_URL + "?access_token=" + token, body.toJSONString());
        }
        catch (Exception e)
        {
            return new SubscribeDelivery(SubscribeDelivery.State.UNKNOWN, "消息请求中断，结果待核对");
        }
        JSONObject json;
        try
        {
            json = JSON.parseObject(resp);
        }
        catch (Exception e)
        {
            return new SubscribeDelivery(SubscribeDelivery.State.UNKNOWN, "微信接口返回异常");
        }
        if (subscribeSucceeded(json))
        {
            return new SubscribeDelivery(SubscribeDelivery.State.SENT, "");
        }
        Integer errcode = subscribeErrorCode(json);
        if (errcode == null)
        {
            return new SubscribeDelivery(SubscribeDelivery.State.UNKNOWN, "微信接口未返回明确发送结果");
        }
        if (errcode.intValue() == 40001 || errcode.intValue() == 42001)
        {
            accessToken = null;
        }
        log.warn("订阅消息发送失败 errcode={} template={}", errcode, templateId);
        return new SubscribeDelivery(SubscribeDelivery.State.REJECTED, "微信返回 errcode=" + errcode);
    }

    /** 保留旧调用者的返回约定，调度器使用结构化投递阶段。 */
    public String sendSubscribeMessage(String openid, String templateId, String page, java.util.Map<String,Object> data)
    {
        SubscribeDelivery result=deliverSubscribeMessage(openid,templateId,page,data);
        return result.state==SubscribeDelivery.State.SENT ? null : result.detail;
    }

    /** 没有明确的数值成功码不等于投递成功。 */
    static boolean subscribeSucceeded(JSONObject response)
    {
        Integer code = subscribeErrorCode(response);
        return code != null && code == 0;
    }

    private static Integer subscribeErrorCode(JSONObject response)
    {
        Object code = response == null ? null : response.get("errcode");
        if (!(code instanceof Number)) return null;
        try { return new java.math.BigDecimal(code.toString()).intValueExact(); }
        catch (RuntimeException e) { return null; }
    }

    private synchronized String accessToken() throws Exception
    {
        if (accessToken != null && System.currentTimeMillis() < accessTokenExpireAt)
        {
            return accessToken;
        }
        String body = fetchAccessTokenJson();
        JSONObject json = JSON.parseObject(body);
        String token = json == null ? null : json.getString("access_token");
        if (StringUtils.isEmpty(token))
        {
            log.warn("获取微信 access_token 失败 errcode={}", json == null ? null : json.getInteger("errcode"));
            throw new ServiceException("微信接口凭证获取失败，请稍后重试");
        }
        accessToken = token;
        long ttl = json.getLongValue("expires_in");
        accessTokenExpireAt = System.currentTimeMillis() + Math.max(60, ttl - 300) * 1000L;
        return accessToken;
    }

    /** 内容安全检查（WxMsgSecCheck）复用这里同一份 access_token，不另外取，免得两份 token 互相顶掉 */
    public String sharedAccessToken() throws Exception
    {
        return accessToken();
    }

    /** 微信说 token 失效了（40001 / 42001）：作废缓存，下次重新取 */
    public synchronized void invalidateAccessToken()
    {
        accessToken = null;
        accessTokenExpireAt = 0;
    }

    /** 不写日志的 POST（URL 带 access_token，响应带手机号，都不能进日志） */
    private String httpPostJsonWithoutLogging(String url, String json) throws Exception
    {
        HttpURLConnection conn = null;
        try
        {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) throw new java.io.IOException("WeChat HTTP request failed");
            InputStream in = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (in == null)
            {
                return "";
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
            {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null)
                {
                    sb.append(line);
                }
                return sb.toString();
            }
        }
        finally
        {
            if (conn != null)
            {
                conn.disconnect();
            }
        }
    }

    private static final String ACTIVITY_PAGE = "pages/community/activity";
    private static final String CODE_URL = "https://api.weixin.qq.com/wxa/getwxacodeunlimit";
    private static final int MAX_CODE_BYTES = 2 * 1024 * 1024;
    private final Map<String, Map<String, Object>> codeCache = new LinkedHashMap<String, Map<String, Object>>();

    // Test seams keep credentials private; no caller can choose the remote host or page.
    protected String fetchAccessTokenJson() throws Exception
    {
        return httpGetWithoutLogging(TOKEN_URL + "?grant_type=client_credential&appid=" + properties.getWxAppId()
                + "&secret=" + properties.getWxAppSecret());
    }

    static String activityScene(Long id, String checkinToken)
    {
        if (id == null || id <= 0) throw new IllegalArgumentException("Invalid activity identity");
        if (checkinToken == null) return "a_" + Long.toString(id, 36);
        if (!checkinToken.matches("(?:[0-9a-f]{12}|[0-9a-f]{16})"))
            throw new IllegalArgumentException("Invalid activity check-in token");
        return "c_" + Long.toString(id, 36) + "_" + checkinToken;
    }

    /** Official binary API, never a relative-path QR or a static success placeholder. */
    public Map<String, Object> activityCode(Long id, String checkinToken)
    {
        return activityCode(id, checkinToken, null);
    }

    /**
     * 2026-11-03 主人：签到二维码「生成失败」。原来固定 check_path=true + env_version=服务端配置（默认 release），
     * 小程序还没有正式版（或这一页还没随正式版发布）时微信直接返回 41030，体验版 / 开发版里永远生成不出来。
     * 现在 check_path=false，env_version 跟着点按钮的那个小程序版本走（前端传 uni.getAccountInfoSync 的 envVersion，
     * 只认 release / trial / develop，其余按服务端配置），扫出来打开的就是同一个版本。
     */
    public synchronized Map<String, Object> activityCode(Long id, String checkinToken, String clientEnv)
    {
        String scene;
        try { scene = activityScene(id, checkinToken); }
        catch (IllegalArgumentException e)
        {
            Map<String, Object> invalid = new LinkedHashMap<String, Object>();
            invalid.put("image", ""); invalid.put("state", "error");
            invalid.put("message", "活动码参数无效，请联系管理员");
            invalid.put("envVersion", codeEnv(clientEnv));
            return invalid;
        }
        return pageCode(ACTIVITY_PAGE, scene, clientEnv);
    }

    public synchronized Map<String, Object> werewolfCode(String code, String clientEnv)
    {
        if (code == null || !code.matches("[1-9][0-9]{5}")) throw new IllegalArgumentException("Invalid room code");
        return pageCode("pages/werewolf/invite", "w_" + code, clientEnv);
    }

    public synchronized Map<String, Object> undercoverCode(String code, String clientEnv)
    {
        if (code == null || !code.matches("[1-9][0-9]{5}")) throw new IllegalArgumentException("Invalid room code");
        return pageCode("pages/undercover/room", "u_" + code, clientEnv);
    }

    private Map<String, Object> pageCode(String page, String scene, String clientEnv)
    {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("image", ""); result.put("state", "error");
        result.put("message", "微信小程序码暂时没生成出来");
        String env = codeEnv(clientEnv);
        result.put("envVersion", env);
        result.put("page", page); result.put("scene", scene);
        if (properties.isWxMockEnabled())
        {
            result.put("state", "mock");
            result.put("message", "本地联调模式：未调用微信，不提供可扫码小程序码");
            return result;
        }
        if (!properties.isWxConfigured())
        {
            result.put("message", "微信小程序码尚未配置，请联系管理员"); return result;
        }
        if (!"release".equals(env) && !"trial".equals(env) && !"develop".equals(env)) return result;
        String key = env + ":" + page + ":" + scene;
        Map<String, Object> cached = codeCache.get(key);
        if (cached != null && ((Long) cached.get("expiresAt")) > System.currentTimeMillis())
        {
            Map<String, Object> copy = new LinkedHashMap<String, Object>(cached);
            copy.remove("expiresAt"); return copy;
        }
        JSONObject request = new JSONObject();
        request.put("scene", scene); request.put("page", page);
        request.put("check_path", false); request.put("env_version", env);
        request.put("width", 430); request.put("is_hyaline", false);
        try
        {
            for (int attempt = 0; attempt < 2; attempt++)
            {
                byte[] bytes = requestCodeImage(accessToken(), request.toJSONString());
                if (bytes == null || bytes.length == 0 || bytes.length > MAX_CODE_BYTES)
                    throw new java.io.IOException("Invalid code response size");
                int first = 0;
                while (first < bytes.length && (bytes[first] & 255) <= 32) first++;
                if (first < bytes.length && bytes[first] == '{')
                {
                    Integer error = subscribeErrorCode(JSON.parseObject(new String(bytes, StandardCharsets.UTF_8)));
                    if (attempt == 0 && error != null && (error == 40001 || error == 40014 || error == 42001))
                    {
                        accessToken = null; accessTokenExpireAt = 0; continue;
                    }
                    log.warn("微信小程序码生成失败 errcode={} env={}", error, env);
                    result.put("errcode", error);
                    result.put("message", codeErrorMessage(error));
                    return result;
                }
                result.put("image", pngDataUri(bytes));
                result.put("state", "ready");
                result.put("message", "release".equals(env) ? "微信小程序码（正式版）" : ("trial".equals(env) ? "微信小程序码（体验版）" : "微信小程序码（开发版）"));
                if (codeCache.size() >= 256) codeCache.remove(codeCache.keySet().iterator().next());
                Map<String, Object> entry = new LinkedHashMap<String, Object>(result);
                entry.put("expiresAt", System.currentTimeMillis() + 3600_000L); codeCache.put(key, entry);
                return result;
            }
        }
        catch (Exception e)
        {
            // Do not log URL, response, scene/token or exception text containing access_token.
            log.warn("微信小程序码请求失败 type={}", e.getClass().getSimpleName());
        }
        return result;
    }

    /** 前端传来的版本只认三种；没传或乱传按服务端配置（kangaroo.wx.code-env） */
    String codeEnv(String clientEnv)
    {
        if ("release".equals(clientEnv) || "trial".equals(clientEnv) || "develop".equals(clientEnv)) return clientEnv;
        return properties.getWxCodeEnv();
    }

    /** 微信错误码 → 给人看的一句话（不带「请稍后重试」，前端不再追加，避免两句重复） */
    static String codeErrorMessage(Integer errcode)
    {
        if (errcode == null) return "微信小程序码暂时没生成出来";
        switch (errcode.intValue())
        {
            case 41030: return "这个小程序版本里还没有活动页（多半是正式版还没发布）";
            case 45009: return "今天生成小程序码的次数用完了";
            case 40001: case 40014: case 42001: return "微信登录凭证失效了";
            case 40097: case 40129: return "小程序码参数不对";
            case 85096: return "当前小程序版本不能生成小程序码";
            default: return "微信小程序码暂时没生成出来（错误码 " + errcode + "）";
        }
    }

    static String pngDataUri(byte[] bytes) throws Exception
    {
        try (MemoryCacheImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes)))
        {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw new java.io.IOException("Not an image");
            ImageReader reader = readers.next();
            try
            {
                String format = reader.getFormatName();
                if (!"png".equalsIgnoreCase(format) && !"jpeg".equalsIgnoreCase(format)) throw new java.io.IOException("Unexpected image format");
                reader.setInput(in, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 280 || width > 1280 || height != width) throw new java.io.IOException("Invalid code dimensions");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                if (!ImageIO.write(reader.read(0), "png", out) || out.size() > MAX_CODE_BYTES) throw new java.io.IOException("Invalid PNG");
                return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
            }
            finally { reader.dispose(); }
        }
    }

    protected byte[] requestCodeImage(String token, String json) throws Exception
    {
        HttpURLConnection conn = openCodeConnection(new URL(CODE_URL + "?access_token="
                + java.net.URLEncoder.encode(token, "UTF-8")));
        try
        {
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("POST"); conn.setConnectTimeout(3000); conn.setReadTimeout(5000);
            conn.setDoOutput(true); conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (java.io.OutputStream out = conn.getOutputStream()) { out.write(json.getBytes(StandardCharsets.UTF_8)); }
            if (conn.getResponseCode() != 200) throw new java.io.IOException("Code request failed");
            if (conn.getContentLengthLong() > MAX_CODE_BYTES) throw new java.io.IOException("Code response too large");
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream())
            {
                byte[] chunk = new byte[8192]; int n;
                while ((n = in.read(chunk)) != -1)
                {
                    if (out.size() + n > MAX_CODE_BYTES) throw new java.io.IOException("Code response too large");
                    out.write(chunk, 0, n);
                }
                return out.toByteArray();
            }
        }
        finally { conn.disconnect(); }
    }

    /** Transport seam for bounded-response tests; production URL is fixed above. */
    protected HttpURLConnection openCodeConnection(URL url) throws Exception
    {
        return (HttpURLConnection) url.openConnection();
    }

    /** 当前是否处于本地联调模式（登录响应里会带上，便于前端与测试识别） */
    public boolean isMock()
    {
        return properties.isWxMockEnabled();
    }
}
