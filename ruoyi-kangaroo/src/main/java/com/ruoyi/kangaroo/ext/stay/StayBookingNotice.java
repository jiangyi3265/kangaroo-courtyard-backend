package com.ruoyi.kangaroo.ext.stay;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;

/** 房源的预订须知：后台编辑、用户确认和订单存档使用同一份内容。 */
public final class StayBookingNotice {
    private StayBookingNotice() {}
    public static final int TITLE_LIMIT = 80;
    public static final int CONTENT_LIMIT = 6000;

    public static Map<String,Object> defaultConfig() {
        // 正文按角色阶段取（切换后第 4 条是「平台审核」），见 KgStayBrowseService.agreementSummary
        return config(KgStayBrowseService.AGREEMENT_TITLE, KgStayBrowseService.agreementSummary());
    }
    private static Map<String,Object> config(String title, String content) {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("version",1); out.put("title",title); out.put("content",content); return out;
    }
    public static Map<String,Object> parse(Object raw) {
        if (raw == null || String.valueOf(raw).trim().isEmpty()) return null;
        try {
            Map<?,?> source = raw instanceof Map ? (Map<?,?>)raw : JSON.parseObject(String.valueOf(raw));
            if (source == null || !source.keySet().equals(new HashSet<>(Arrays.asList("version","title","content")))
                    || !Integer.valueOf(1).equals(source.get("version"))) throw new IllegalArgumentException();
            return config(text(source.get("title"),TITLE_LIMIT,true),text(source.get("content"),CONTENT_LIMIT,false));
        } catch (Exception e) {
            throw new ServiceException("请填写预订须知：标题为1–80字，正文为1–6000字，标题不能换行");
        }
    }
    private static String text(Object raw, int max, boolean singleLine) {
        if (!(raw instanceof String)) throw new IllegalArgumentException();
        String s = ((String)raw).replace("\r\n","\n").replace('\r','\n').trim();
        if (s.isEmpty() || s.length()>max || (singleLine && s.contains("\n")) || s.matches("(?s).*[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F].*")) throw new IllegalArgumentException();
        return s;
    }
    public static void validateColumns(Map<String,Object> cols) {
        if (!cols.containsKey("booking_notice_config")) return;
        Map<String,Object> config = parse(cols.get("booking_notice_config"));
        cols.put("booking_notice_config",config == null ? null : JSON.toJSONString(config));
    }
    public static Map<String,Object> snapshot(Object raw) {
        Map<String,Object> config = parse(raw);
        if (config == null) config = defaultConfig();
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("code","stay_booking_notice");
        out.put("version","notice-" + sha256(JSON.toJSONString(config)));
        out.put("title",config.get("title")); out.put("summary",config.get("content"));
        return out;
    }
    private static String sha256(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : bytes) out.append(String.format("%02x", b & 0xff));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static void validateConsent(Map<String,Object> current, Object accepted) {
        String version = accepted instanceof String ? ((String)accepted).trim() : "";
        Object expected = current == null ? null : current.get("version");
        if (!version.isEmpty() && version.equals(expected)) return;
        throw new ServiceException("预订须知已更新，请重新阅读并同意",409);
    }
}
