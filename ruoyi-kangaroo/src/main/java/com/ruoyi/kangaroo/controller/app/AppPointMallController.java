package com.ruoyi.kangaroo.controller.app;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.user.KgPointMallService;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppUserContext;

@RestController
@RequestMapping("/app/point-mall")
@AppAuth
public class AppPointMallController {
    @Autowired private KgPointMallService mall;
    @GetMapping("/goods")
    public AjaxResult goods(@RequestParam(defaultValue="1") int pageNum) { return AjaxResult.success(mall.goods(false, pageNum)); }
    @GetMapping("/balance")
    public AjaxResult balance() { return AjaxResult.success(mall.balance(AppUserContext.getUserId())); }
    @GetMapping("/orders")
    public AjaxResult orders(@RequestParam(defaultValue="1") int pageNum) { return AjaxResult.success(mall.orders(AppUserContext.getUserId(), "", pageNum)); }
    @PostMapping("/goods/{id}/redeem")
    public AjaxResult redeem(@PathVariable Long id, @RequestBody Map<String,Object> b) { return AjaxResult.success(mall.redeem(AppUserContext.getUserId(), id, b)); }
    @PostMapping("/orders/{id}/{action}")
    public AjaxResult command(@PathVariable Long id, @PathVariable String action, @RequestBody Map<String,Object> b) {
        Long uid = AppUserContext.getUserId();
        mall.command(id, uid, action, b, "user:" + uid);
        return AjaxResult.success();
    }
}
