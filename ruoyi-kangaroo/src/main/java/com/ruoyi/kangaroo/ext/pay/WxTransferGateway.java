package com.ruoyi.kangaroo.ext.pay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import java.util.List;
import java.util.Map;
import com.ruoyi.common.exception.ServiceException;
import com.wechat.pay.java.core.util.GsonUtil;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Uses the same signed APIv3 client and merchant credentials as room payments. */
@Component
public class WxTransferGateway {
    private final WxPayGateway pay;
    private final boolean acceptNew;
    private final String sceneId;
    private final List<?> reports;

    public WxTransferGateway(WxPayGateway pay, Environment env) {
        this.pay = pay;
        acceptNew = env.getProperty("kangaroo.pay.wechat.transfer.enabled", Boolean.class, false);
        sceneId = env.getProperty("kangaroo.pay.wechat.transfer.scene-id", "");
        String json = env.getProperty("kangaroo.pay.wechat.transfer.scene-report-infos", "[]");
        try {
            reports = GsonUtil.getGson().fromJson(json, List.class);
            if (acceptNew) {
                if (!pay.enabled() || !sceneId.matches("[0-9]{1,36}") || reports == null || reports.isEmpty()) throw new IllegalArgumentException();
                for (Object entry : reports) {
                    Map<?, ?> info = (Map<?, ?>) entry;
                    checkText(info.get("info_type"), 15);
                    checkText(info.get("info_content"), 32);
                }
            }
        } catch (Exception e) { throw new IllegalStateException("WeChat transfer scene configuration incomplete"); }
    }

    private static void checkText(Object value, int max) {
        if (!(value instanceof String) || ((String) value).trim().isEmpty() || ((String) value).length() > max) throw new IllegalArgumentException();
    }

    public boolean enabled(Long store) { return acceptNew && pay.enabled() && pay.storeIds().contains(store); }
    public boolean canQuery() { return pay.enabled(); }
    public String merchantId() { return pay.merchantId(); }
    public String appId() { return pay.appId(); }
    public void requireEnabled(Long store) {
        if (!enabled(store)) throw new ServiceException("本店微信零钱提现尚未开通，请联系平台完成商家转账配置", 409);
    }

    public Map<String, Object> request(String number, String openid, long amount, String name) {
        Map<String, Object> body = p("appid", appId(), "out_bill_no", number, "transfer_scene_id", sceneId,
                "openid", openid, "transfer_amount", amount, "transfer_remark", "袋鼠旅居房费结算",
                "notify_url", pay.transferNotifyUrl(), "transfer_scene_report_infos", reports);
        if (name != null && !name.isEmpty()) body.put("user_name", pay.encryptTransferName(name));
        return body;
    }

    public Map<String, Object> submit(Map<String, Object> request) { return pay.transfer(request); }
    public Map<String, Object> query(String number) { return pay.queryTransfer(number); }
    public Map<String, Object> notification(String body, String serial, String timestamp, String nonce, String signature) {
        return pay.transferNotification(body, serial, timestamp, nonce, signature);
    }
}
