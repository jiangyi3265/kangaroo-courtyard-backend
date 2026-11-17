package com.ruoyi.kangaroo.ext.pay;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.config.KangarooProperties;
import com.wechat.pay.java.core.RSAPublicKeyConfig;
import com.wechat.pay.java.core.http.*;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.core.util.GsonUtil;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.HttpClients;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.*;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;

/** Official APIv3 SDK handles merchant signatures, platform signatures and AES-GCM. */
@Component
public class WxPayGateway {
    private final String appId, merchantId, notifyBase;
    private final Set<Long> stores = new HashSet<>();
    private RSAPublicKeyConfig config;
    private HttpClient client;
    private NotificationParser parser;

    public WxPayGateway(KangarooProperties properties, Environment env) {
        appId = properties.getWxAppId();
        merchantId = env.getProperty("kangaroo.pay.wechat.merchant-id", "");
        notifyBase = env.getProperty("kangaroo.pay.wechat.notify-base", "");
        if (!"wechat".equals(properties.getPayMode())) return;
        if (properties.isWxMockEnabled()) throw new IllegalStateException("Real payment cannot use mock WeChat login");
        String key = env.getProperty("KANGAROO_PAY_API_V3_KEY", "");
        String publicKeyId = env.getProperty("kangaroo.pay.wechat.public-key-id", "");
        String serial = env.getProperty("kangaroo.pay.wechat.serial-number", "");
        if (appId == null || !appId.matches("wx[0-9a-f]{16}") || !merchantId.matches("[0-9]{6,20}")
                || key.getBytes(StandardCharsets.UTF_8).length != 32 || !publicKeyId.startsWith("PUB_KEY_ID_")
                || !serial.matches("[0-9A-Fa-f]{16,64}") || !notifyBase.matches("https://[A-Za-z0-9.-]+"))
            throw new IllegalStateException("WeChat Pay merchant configuration incomplete");
        try {
            for (String id : env.getRequiredProperty("kangaroo.pay.wechat.store-ids").split(",")) stores.add(Long.valueOf(id.trim()));
            if (stores.isEmpty() || stores.stream().anyMatch(id -> id <= 0)) throw new IllegalArgumentException();
            config = new RSAPublicKeyConfig.Builder().merchantId(merchantId).merchantSerialNumber(serial)
                    .privateKeyFromPath(env.getRequiredProperty("kangaroo.pay.wechat.private-key-path"))
                    .publicKeyFromPath(env.getRequiredProperty("kangaroo.pay.wechat.public-key-path"))
                    .publicKeyId(publicKeyId).apiV3Key(key).build();
            X509Certificate cert;
            try (java.io.InputStream in = Files.newInputStream(Paths.get(env.getRequiredProperty("kangaroo.pay.wechat.certificate-path")))) {
                cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
            }
            cert.checkValidity();
            boolean merchantMatches = new javax.naming.ldap.LdapName(cert.getSubjectX500Principal().getName()).getRdns().stream()
                    .anyMatch(rdn -> "CN".equals(rdn.getType()) && merchantId.equals(String.valueOf(rdn.getValue())));
            String challenge = "kangaroo-pay-startup-key-check";
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(cert.getPublicKey()); verifier.update(challenge.getBytes(StandardCharsets.UTF_8));
            if (!merchantMatches || !serial.equalsIgnoreCase(cert.getSerialNumber().toString(16))
                    || !verifier.verify(Base64.getDecoder().decode(config.createSigner().sign(challenge).getSign())))
                throw new IllegalArgumentException();
            client = new ApacheHttpClientBuilder().config(config).apacheHttpClient(HttpClients.custom()
                    .setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(5000).setSocketTimeout(10000)
                            .setConnectionRequestTimeout(5000).build())
                    .disableRedirectHandling().disableAutomaticRetries().build()).build();
            parser = new NotificationParser(config);
        } catch (Exception e) {
            // SDK exception text can contain request bodies or key configuration. Never expose it.
            throw new IllegalStateException("WeChat Pay certificate/key/store configuration invalid");
        }
    }

    public boolean enabled() { return client != null; }
    public String appId() { return appId; }
    public String merchantId() { return merchantId; }
    public Set<Long> storeIds() { return Collections.unmodifiableSet(stores); }
    public void requireStore(Long storeId) {
        if (!enabled() || !stores.contains(storeId)) throw new ServiceException("该门店尚未开通在线收款", 409);
    }
    public void requireMerchant(Map<String,Object> pay) {
        if (!enabled() || !merchantId.equals(pay.get("wxMchid")) || !appId.equals(pay.get("wxAppid")))
            throw new ServiceException("支付商户配置与订单不匹配，请联系管理员", 409);
    }

    public Map<String,Object> prepay(Map<String,Object> pay, String expires) {
        requireMerchant(pay);
        Map<String,Object> result = request("/v3/pay/transactions/jsapi", p("appid", appId, "mchid", merchantId,
                "description", pay.get("description"), "out_trade_no", pay.get("payNo"), "time_expire", expires,
                "notify_url", notifyBase + "/app/pay/wechat/notify", "amount", p("total", pay.get("amount"), "currency", "CNY"),
                "payer", p("openid", pay.get("wxOpenid"))));
        String id = String.valueOf(result.get("prepay_id"));
        if (!id.matches("[A-Za-z0-9_-]{8,128}")) throw new GatewayException("INVALID_PREPAY");
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String pkg = "prepay_id=" + id;
        String sign = config.createSigner().sign(appId + "\n" + timestamp + "\n" + nonce + "\n" + pkg + "\n").getSign();
        return p("channel", "wechat", "payNo", pay.get("payNo"), "amount", pay.get("amount"),
                "params", p("timeStamp", timestamp, "nonceStr", nonce, "package", pkg, "signType", "RSA", "paySign", sign));
    }
    public Map<String,Object> query(Map<String,Object> pay) {
        requireMerchant(pay);
        try { return request("/v3/pay/transactions/out-trade-no/" + identifier(pay.get("payNo")) + "?mchid=" + merchantId, null); }
        catch (GatewayException e) { if ("ORDER_NOT_EXIST".equals(e.code)) return null; throw e; }
    }
    public void close(Map<String,Object> pay) {
        requireMerchant(pay);
        request("/v3/pay/transactions/out-trade-no/" + identifier(pay.get("payNo")) + "/close", p("mchid", merchantId));
    }
    public String encryptTransferName(String name) {
        if (!enabled()) throw new GatewayException("DISABLED");
        return config.createEncryptor().encrypt(name);
    }
    public String transferNotifyUrl() { return notifyBase + "/app/pay/wechat/transfer-notify"; }

    public Map<String,Object> transfer(Map<String,Object> body) {
        return request("/v3/fund-app/mch-transfer/transfer-bills", body);
    }

    public Map<String,Object> queryTransfer(String number) {
        try { return request("/v3/fund-app/mch-transfer/transfer-bills/out-bill-no/" + identifier(number), null); }
        catch (GatewayException e) { if ("NOT_FOUND".equals(e.code)) return null; throw e; }
    }
    public Map<String,Object> queryRefund(String refundNo) {
        try { return request("/v3/refund/domestic/refunds/" + identifier(refundNo), null); }
        catch (GatewayException e) { if ("RESOURCE_NOT_EXISTS".equals(e.code)) return null; throw e; }
    }
    public Map<String,Object> refund(Map<String,Object> pay, Map<String,Object> refund) {
        return refund(pay, refund, false);
    }
    /**
     * fromAvailable：带上 funds_account=AVAILABLE，从商户号可用余额出资。老资金流商户默认从「未结算资金」退，
     * 交易款已经结算走时微信回 NOT_ENOUGH 并提示用可用余额退（10-15：两笔门店退款因此卡了两天）。退款单号不变，微信按单号去重
     */
    public Map<String,Object> refund(Map<String,Object> pay, Map<String,Object> refund, boolean fromAvailable) {
        requireMerchant(pay);
        Map<String,Object> body = p("out_trade_no", pay.get("payNo"), "out_refund_no", refund.get("refundNo"),
                "reason", refund.get("reason"), "notify_url", notifyBase + "/app/pay/wechat/refund-notify",
                "amount", p("refund", refund.get("amount"), "total", pay.get("amount"), "currency", "CNY"));
        if (fromAvailable) body.put("funds_account", "AVAILABLE");
        return request("/v3/refund/domestic/refunds", body);
    }
    /** 微信的 NOT_ENOUGH 说明里提到「未结算」/「可用余额」：说明是默认从未结算资金退、但这笔钱已经结算了，可以改从可用余额退 */
    static boolean unsettledFundsHint(String detail) {
        return detail != null && (detail.contains("未结算") || detail.contains("可用余额"));
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> request(String path, Map<String,Object> body) {
        if (!enabled()) throw new GatewayException("DISABLED");
        try {
            HttpRequest.Builder request = new HttpRequest.Builder().url("https://api.mch.weixin.qq.com" + path)
                    .httpMethod(body == null ? HttpMethod.GET : HttpMethod.POST).addHeader("Accept", "application/json");
            if (path.startsWith("/v3/fund-app/")) request.addHeader("Wechatpay-Serial", config.createEncryptor().getWechatpaySerial());
            if (body != null) request.body(new JsonRequestBody.Builder().body(GsonUtil.getGson().toJson(body)).build());
            return client.execute(request.build(), Map.class).getServiceResponse();
        } catch (com.wechat.pay.java.core.exception.ServiceException e) {
            throw new GatewayException(e.getErrorCode(), e.getErrorMessage());
        } catch (Exception e) { throw new GatewayException("CHANNEL_UNAVAILABLE"); }
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> notification(String body, String serial, String timestamp, String nonce, String signature, boolean refund) {
        Map<String,Object> data = parseNotification(body, serial, timestamp, nonce, signature,
                refund ? Arrays.asList("REFUND.SUCCESS", "REFUND.ABNORMAL", "REFUND.CLOSED") : Collections.singletonList("TRANSACTION.SUCCESS"));
        if (refund) {
            Map<?,?> envelope = GsonUtil.getGson().fromJson(body, Map.class);
            if (!("REFUND." + data.get("refund_status")).equals(envelope.get("event_type"))) throw new ServiceException("无效支付通知", 400);
        }
        return data;
    }

    public Map<String,Object> transferNotification(String body, String serial, String timestamp, String nonce, String signature) {
        return parseNotification(body, serial, timestamp, nonce, signature, Collections.singletonList("MCHTRANSFER.BILL.FINISHED"));
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> parseNotification(String body, String serial, String timestamp, String nonce, String signature, List<String> events) {
        try {
            if (!enabled() || body == null || body.length() > 65536 || serial == null || timestamp == null
                    || nonce == null || nonce.length() > 128 || signature == null || signature.length() > 1024
                    || Math.abs(Instant.now().getEpochSecond() - Long.parseLong(timestamp)) > 300) throw new IllegalArgumentException();
            Map<String,Object> data = parser.parse(new RequestParam.Builder().serialNumber(serial).timestamp(timestamp)
                    .nonce(nonce).signature(signature).body(body).build(), Map.class);
            Map<String,Object> envelope = GsonUtil.getGson().fromJson(body, Map.class);
            String event = String.valueOf(envelope.get("event_type"));
            if (!events.contains(event)) throw new IllegalArgumentException();
            return data;
        } catch (Exception e) { throw new ServiceException("无效支付通知", 400); }
    }
    private static String identifier(Object value) {
        String s = String.valueOf(value);
        if (!s.matches("[A-Za-z0-9_-]{6,64}")) throw new GatewayException("INVALID_ID");
        return s;
    }
    public static class GatewayException extends RuntimeException {
        public final String code;
        /** 微信返回的说明原文（去掉换行，最多 120 字），只用于日志排查：之前只留了错误码，「余额不足」分不清是哪一种 */
        public final String detail;
        GatewayException(String code) {
            this(code, null);
        }
        GatewayException(String code, String detail) {
            super("微信支付暂时无法处理，请稍后刷新订单重试");
            this.code = code != null && code.matches("[A-Z0-9_]{1,64}") ? code : "CHANNEL_ERROR";
            String d = detail == null ? null : detail.replaceAll("[\\r\\n\\t]+", " ").trim();
            this.detail = d == null || d.isEmpty() ? null : (d.length() > 120 ? d.substring(0, 120) : d);
        }
    }
}
