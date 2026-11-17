package com.ruoyi.kangaroo.controller.app;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.KgCityFinanceService;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.security.AppAuth;

/**
 * 城市负责人（角色切换后才开放）：本城数字、活动退款审批、取消收费活动。只限他有授权的门店，权限在服务里查（KgCityFinanceService）。
 */
@RestController
@RequestMapping("/app/staff/city-finance")
public class AppStaffCityFinanceController
{
    @Autowired
    private KgCityFinanceService finance;

    private static String s(Map<String, Object> b, String k)
    {
        return b == null ? null : KgJdbc.asStr(b.get(k));
    }

    @AppAuth(staff = true, cityLeadAfterSwitch = true)
    @GetMapping("/summary")
    public AjaxResult summary(@RequestParam(required = false) Long storeId)
    {
        return AjaxResult.success(finance.summary(storeId));
    }

    @AppAuth(staff = true, cityLeadAfterSwitch = true)
    @GetMapping("/refunds")
    public AjaxResult refunds(@RequestParam(required = false) Long storeId, @RequestParam(required = false) Integer pageNum, @RequestParam(required = false) Integer pageSize)
    {
        return AjaxResult.success(finance.refunds(storeId, pageNum, pageSize));
    }

    @AppAuth(staff = true, cityLeadAfterSwitch = true)
    @PostMapping("/refunds/{signupId:[0-9]+}/approve")
    public AjaxResult approve(@PathVariable Long signupId)
    {
        StaffSupport.Ctx c = finance.ctxOfSignup(signupId, "activity_refund", "活动退款审批");
        return AjaxResult.success(finance.approve(c, signupId));
    }

    /** reason 必填，会发给报名人 */
    @AppAuth(staff = true, cityLeadAfterSwitch = true)
    @PostMapping("/refunds/{signupId:[0-9]+}/reject")
    public AjaxResult reject(@PathVariable Long signupId, @RequestBody(required = false) Map<String, Object> body)
    {
        StaffSupport.Ctx c = finance.ctxOfSignup(signupId, "activity_refund", "活动退款审批");
        return AjaxResult.success(finance.reject(c, signupId, s(body, "reason")));
    }

    @AppAuth(staff = true, cityLeadAfterSwitch = true)
    @GetMapping("/activities")
    public AjaxResult activities(@RequestParam(required = false) Long storeId, @RequestParam(required = false) Integer pageNum, @RequestParam(required = false) Integer pageSize)
    {
        return AjaxResult.success(finance.cancellable(storeId, pageNum, pageSize));
    }

    /** 取消收费或已有报名的活动：会批量退款并通知全部报名人。reason 必填 */
    @AppAuth(staff = true, cityLeadAfterSwitch = true)
    @PostMapping("/activities/{activityId:[0-9]+}/cancel")
    public AjaxResult cancel(@PathVariable Long activityId, @RequestBody(required = false) Map<String, Object> body)
    {
        StaffSupport.Ctx c = finance.ctxOfActivity(activityId, "activity_cancel", "取消已有报名的活动");
        return AjaxResult.success(finance.cancel(c, activityId, s(body, "reason")));
    }
}
