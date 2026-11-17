package com.ruoyi.kangaroo.controller.app;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.stay.KgShopService;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppRateLimiter;
import com.ruoyi.kangaroo.security.AppUserContext;

/**
 * 增值服务（入住期间可预订的到店服务）。服务列表与详情游客可看；下单、订单、退款、改期需要登录，且必须有本人进行中的入住单。
 */
@RestController
@RequestMapping("/app/shop")
public class AppShopController
{
    @Autowired
    private KgShopService shop;

    @Autowired
    private AppRateLimiter limiter;

    @AppAuth(required = false)
    @GetMapping("/services")
    public AjaxResult services(@RequestParam(required = false) String stayOrderNo, @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) String category)
    {
        AppLoginUser me = AppUserContext.get();
        return AjaxResult.success(shop.services(me == null ? null : me.getUserId(), stayOrderNo, storeId, category));
    }

    @AppAuth(required = false)
    @GetMapping("/services/{serviceId}")
    public AjaxResult service(@PathVariable Long serviceId)
    {
        AppLoginUser me = AppUserContext.get();
        return AjaxResult.success(shop.service(serviceId, me == null ? null : me.getUserId()));
    }

    /** 收藏 / 取消收藏服务 */
    @AppAuth
    @PostMapping("/services/{serviceId}/favorite")
    public AjaxResult favoriteService(@PathVariable Long serviceId, @RequestParam(defaultValue = "true") boolean favorite)
    {
        return AjaxResult.success(shop.favorite(AppUserContext.getUserId(), serviceId, favorite));
    }

    /** 我收藏的服务 */
    @AppAuth
    @GetMapping("/favorites")
    public AjaxResult favorites()
    {
        return AjaxResult.success(shop.favorites(AppUserContext.getUserId()));
    }

    @AppAuth
    @GetMapping("/services/{serviceId}/slots")
    public AjaxResult slots(@PathVariable Long serviceId, @RequestParam(required = false) String stayOrderNo)
    {
        return AjaxResult.success(shop.slots(AppUserContext.getUserId(), serviceId, stayOrderNo));
    }

    @AppAuth
    @PostMapping("/orders/quote")
    public AjaxResult quote(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(shop.quote(AppUserContext.getUserId(), body));
    }

    @AppAuth
    @PostMapping("/orders")
    public AjaxResult create(@RequestBody Map<String, Object> body)
    {
        Long me = AppUserContext.getUserId();
        limiter.check("shop-order", String.valueOf(me), 20, 3600, "下单太频繁，请稍后再试");
        return AjaxResult.success(shop.create(me, body));
    }

    @AppAuth
    @GetMapping("/orders/mine")
    public AjaxResult mine(@RequestParam(required = false) String tab, @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize)
    {
        return AjaxResult.success(shop.mine(AppUserContext.getUserId(), tab, pageNum, pageSize));
    }

    @AppAuth
    @GetMapping("/orders/{orderNo}")
    public AjaxResult order(@PathVariable String orderNo)
    {
        return AjaxResult.success(shop.detail(AppUserContext.getUserId(), orderNo));
    }

    @AppAuth
    @PostMapping("/orders/{orderNo}/cancel")
    public AjaxResult cancel(@PathVariable String orderNo)
    {
        shop.cancel(AppUserContext.getUserId(), orderNo);
        return AjaxResult.success("订单已取消");
    }

    @AppAuth
    @PostMapping("/orders/{orderNo}/refund")
    public AjaxResult refund(@PathVariable String orderNo, @RequestBody(required = false) Map<String, Object> body)
    {
        Long me = AppUserContext.getUserId();
        limiter.check("shop-refund", String.valueOf(me), 10, 86400, "今天的退款申请次数已用完");
        // 角色切换后由平台在后台审，之前门店审
        return AjaxResult.success("退款申请已提交，等待" + com.ruoyi.kangaroo.ext.stay.KgStayOrderService.refundReviewer() + "审核",
                shop.applyRefund(me, orderNo, body == null ? null : KgJdbc.asStr(body.get("reason"))));
    }

    @AppAuth
    @PostMapping("/orders/{orderNo}/reschedule")
    public AjaxResult reschedule(@PathVariable String orderNo, @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success("已改期", shop.reschedule(AppUserContext.getUserId(), orderNo, KgJdbc.asStr(body.get("useDate")),
                KgJdbc.asStr(body.get("timeSlot"))));
    }
}
