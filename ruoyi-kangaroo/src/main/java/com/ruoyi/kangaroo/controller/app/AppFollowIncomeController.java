package com.ruoyi.kangaroo.controller.app;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.user.KgIncomeService;
import com.ruoyi.kangaroo.ext.user.KgStoreFollowService;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppUserContext;

/**
 * 「我的」里的两页：我的关注（关注的门店、某家门店的在招岗位）与我的收入（线上任务结算单的合计与明细，只读）。
 * 关注 / 取消关注仍是 POST /app/stores/{storeId}/follow（AppUserExtController）。
 */
@RestController
@RequestMapping("/app")
public class AppFollowIncomeController
{
    @Autowired
    private KgStoreFollowService follows;

    @Autowired
    private KgIncomeService income;

    /** 我关注的门店 */
    @AppAuth
    @GetMapping("/me/follows")
    public AjaxResult follows()
    {
        return AjaxResult.success(follows.follows(AppUserContext.getUserId()));
    }

    /** 某家门店的在招岗位（游客也能看；登录时带上是否已关注） */
    @AppAuth(required = false)
    @GetMapping("/stores/{storeId}/jobs")
    public AjaxResult storeJobs(@PathVariable Long storeId)
    {
        return AjaxResult.success(follows.storeJobs(AppUserContext.currentUserId(), storeId));
    }

    /**
     * 我的收入：status = all 全部 / pending 待到账 / confirmed 已到账 / disputed 申诉中。
     * 只读：报酬由门店线下支付，这里不发起任何付款或状态变更。
     */
    @AppAuth
    @GetMapping("/me/income")
    public AjaxResult income(@RequestParam(required = false, defaultValue = "all") String status,
            @RequestParam(required = false, defaultValue = "1") Integer pageNum,
            @RequestParam(required = false, defaultValue = "20") Integer pageSize)
    {
        return AjaxResult.success(income.income(AppUserContext.getUserId(), status, pageNum, pageSize));
    }
}
