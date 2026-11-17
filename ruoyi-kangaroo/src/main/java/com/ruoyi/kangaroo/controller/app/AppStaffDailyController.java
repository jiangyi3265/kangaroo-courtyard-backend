package com.ruoyi.kangaroo.controller.app;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.staff.KgMerchantRequestService;
import com.ruoyi.kangaroo.ext.staff.KgOnlineDailyService;
import com.ruoyi.kangaroo.ext.staff.KgStaffDailyService;
import com.ruoyi.kangaroo.ext.staff.StaffPerms;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppUserContext;

/**
 * 员工端「每日概况」（2026-10-17）：按城市 → 小院看某一天的入住情况、各门店岗位的报名情况。
 * 范围 = 登录态里有授权的门店（不认请求里传的门店），口径见 {@link KgStaffDailyService}。
 */
@RestController
@RequestMapping("/app/staff/daily")
@AppAuth(staff = true)
public class AppStaffDailyController
{
    @Autowired
    private KgStaffDailyService daily;

    @Autowired
    private com.ruoyi.kangaroo.ext.staff.KgVolunteerDailyService volunteers;

    @Autowired
    private KgOnlineDailyService online;

    @Autowired
    private KgMerchantRequestService requests;

    @GetMapping("/volunteers")
    public AjaxResult volunteers(@RequestParam(required = false) Long storeId)
    {
        return AjaxResult.success(volunteers.overview(storeId));
    }

    /** 本城线上任务招募概况（2026-10-30）：只给商家服务人和兼任的小院主理人，普通小院主理人、换工主理人 403（serviceCtx 把关） */
    @AppAuth(staff = true, merchantService = true)
    @GetMapping("/online")
    public AjaxResult online(@RequestParam(required = false) Long storeId)
    {
        return AjaxResult.success(online.overview(requests.serviceCtx(storeId, StaffPerms.VIEW)));
    }

    /** date = yyyy-MM-dd（不传 / today 为今天）；city 只看这一个城市 */
    @GetMapping
    public AjaxResult daily(@RequestParam(required = false) String date, @RequestParam(required = false) String city)
    {
        return AjaxResult.success(daily.daily(AppUserContext.requireStaff(), date, city));
    }
}
