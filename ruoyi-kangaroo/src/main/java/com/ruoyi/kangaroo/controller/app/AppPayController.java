package com.ruoyi.kangaroo.controller.app;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.pay.KgPayService;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppUserContext;

/**
 * 小程序支付：取渠道配置 → 拉起支付 →（联调渠道）确认到账 → 查状态。
 * 支付单由各业务下单接口创建，金额来自服务端的业务单，这里不接收任何金额参数。
 */
@RestController
@RequestMapping("/app/pay")
public class AppPayController
{
    private static final Logger log = LoggerFactory.getLogger(AppPayController.class);

    @Autowired
    private KgPayService payService;

    @Autowired private com.ruoyi.kangaroo.ext.stay.KgBusinessWithdrawalService withdrawals;

    @AppAuth(required = false)
    @PostMapping("/wechat/transfer-notify")
    public org.springframework.http.ResponseEntity<Void> notifyTransfer(@RequestBody String body, javax.servlet.http.HttpServletRequest request) {
        try {
            withdrawals.notifyWechat(body, request.getHeader("Wechatpay-Serial"), request.getHeader("Wechatpay-Timestamp"),
                    request.getHeader("Wechatpay-Nonce"), request.getHeader("Wechatpay-Signature"));
            return org.springframework.http.ResponseEntity.noContent().build();
        } catch (Exception e) {
            return org.springframework.http.ResponseEntity.status(500).build();
        }
    }

    /** 渠道配置：页面据此决定走真实 uni.requestPayment 还是联调确认 */
    @AppAuth(required = false)
    @GetMapping("/config")
    public AjaxResult config()
    {
        return AjaxResult.success(payService.config());
    }

    @AppAuth
    @PostMapping("/{payNo}/prepay")
    public AjaxResult prepay(@PathVariable String payNo)
    {
        try { return AjaxResult.success(payService.prepay(payNo, AppUserContext.getUserId())); }
        catch (com.ruoyi.kangaroo.ext.pay.WxPayGateway.GatewayException e) {
            String message = "微信支付暂时无法处理，请稍后刷新订单重试";
            if ("APPID_MCHID_NOT_MATCH".equals(e.code)) message = "商户尚未关联此小程序，请联系商家完成支付设置";
            else if ("NO_AUTH".equals(e.code)) message = "商户尚未开通此支付功能，请联系商家处理";
            else if ("ORDERPAID".equals(e.code)) message = "订单已支付，请刷新查看支付结果";
            else if ("ORDER_CLOSED".equals(e.code)) message = "订单已关闭，请重新下单";
            // 之前没映射到的错误只给一句笼统提示，查不到原因；现在带上微信返回的错误码，截图就能定位
            else message = "微信支付暂时无法处理（" + e.code + "），请稍后刷新订单重试";
            log.warn("微信下单失败 payNo={} code={}", payNo, e.code);
            return AjaxResult.error(503, message);
        }
    }

    @AppAuth
    @PostMapping("/{payNo}/mock-confirm")
    public AjaxResult mockConfirm(@PathVariable String payNo)
    {
        payService.mockConfirm(payNo, AppUserContext.getUserId());
        return AjaxResult.success("支付成功（联调通道）");
    }

    @AppAuth
    @GetMapping("/{payNo}")
    public AjaxResult query(@PathVariable String payNo)
    {
        return AjaxResult.success(payService.queryOwned(payNo, AppUserContext.getUserId()));
    }

    @AppAuth(required = false)
    @PostMapping({"/wechat/notify", "/wechat/refund-notify"})
    public org.springframework.http.ResponseEntity<Void> notifyWechat(@RequestBody String body, javax.servlet.http.HttpServletRequest request) {
        try {
            payService.notifyWechat(body, request.getHeader("Wechatpay-Serial"), request.getHeader("Wechatpay-Timestamp"),
                    request.getHeader("Wechatpay-Nonce"), request.getHeader("Wechatpay-Signature"), request.getRequestURI().endsWith("/refund-notify"));
            return org.springframework.http.ResponseEntity.noContent().build();
        } catch (Exception e) {
            // Never echo SDK request/body/signature details. Non-2xx asks WeChat to retry.
            return org.springframework.http.ResponseEntity.status(500).build();
        }
    }
}
