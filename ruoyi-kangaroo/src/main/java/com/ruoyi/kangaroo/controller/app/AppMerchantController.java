package com.ruoyi.kangaroo.controller.app;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.user.KgMerchantApplyService;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppRateLimiter;
import com.ruoyi.kangaroo.security.AppUserContext;

/**
 * 「成为商家」（V56，2026-10-18）：登录用户提交商家入驻申请、看自己最近一份申请的进度。
 * 只加新接口，不碰老接口；没有 V56 的库上程序起不来（MerchantApplySchema），不会走到这里才报没有表。
 */
@RestController
@RequestMapping("/app/merchant")
public class AppMerchantController
{
    @Autowired
    private KgMerchantApplyService merchants;

    @Autowired
    private AppRateLimiter limiter;

    /** 我最近一份申请（latest 为 null = 还没交过）+ 店铺类型、想招的人两组选项 */
    @AppAuth
    @GetMapping("/apply")
    public AjaxResult latest()
    {
        return AjaxResult.success(merchants.latest(AppUserContext.getUserId()));
    }

    /**
     * 提交即开通（V63）：同一事务建门店 + 合作商家授权，返回 approved / storeId / staffGrants；
     * 账号的合作商家授权被撤销过、门店被停用、或申请被改成「暂不合作」的不自动开通，照旧落「待联系」等后台处理（approved = false）。
     * 每人每天最多 5 次真正落库 / 开通的提交：被校验挡回的（没验证手机号、城市对不上、漏填）不算，
     * 在服务里全部校验通过、落库之前才计（KgMerchantApplyService.apply 的 beforeSave），不然填错几次就被锁一天。
     * 另有一个宽一点的桶挡脚本：每 10 分钟最多 20 次请求（含被挡回的）。
     */
    @AppAuth
    @PostMapping("/apply")
    public AjaxResult apply(@RequestBody(required = false) Map<String, Object> body)
    {
        Long me = AppUserContext.getUserId();
        limiter.check("merchant:apply-try", String.valueOf(me), 20, 600, "提交太频繁了，请过几分钟再试");
        return AjaxResult.success(merchants.apply(me, body,
                () -> limiter.check("merchant:apply", String.valueOf(me), 5, 86400, "今天提交次数太多了，明天再试，或直接联系袋鼠全球青年客服")));
    }

    /** 自助退出合作商家（我的 → 退出合作商家）：否则提交过「成为商家」的账号没法注销，也没人能替他撤授权。每人每小时 10 次，够看几遍还差什么 */
    @AppAuth
    @PostMapping("/exit")
    public AjaxResult exit()
    {
        Long me = AppUserContext.getUserId();
        limiter.check("merchant:exit", String.valueOf(me), 10, 3600, "操作太频繁了，稍后再试");
        Map<String, Object> out = merchants.exit(me);
        return AjaxResult.success(String.valueOf(out.get("message")), out);
    }
}
