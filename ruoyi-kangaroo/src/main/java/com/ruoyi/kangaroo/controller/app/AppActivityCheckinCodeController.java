package com.ruoyi.kangaroo.controller.app;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgActivityCheckinCodes;
import com.ruoyi.kangaroo.ext.community.KgActivityService;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppRateLimiter;
import com.ruoyi.kangaroo.security.AppUserContext;

/**
 * 2026-11-03 主人：「出示二维码失效，生成失败」。活动签到码的三个接口单独放这里（不动另外两个活动控制器）：
 * <ul>
 *   <li>门店活动组织者（员工 / 商家）：GET /app/staff/activities/{id}/checkin-code?env=trial</li>
 *   <li>用户发起活动的发起人：GET /app/activities/{id}/manage/checkin-code?env=trial</li>
 *   <li>参与者输入 6 位活动签到码：POST /app/activities/{id}/checkin-pin {code}</li>
 * </ul>
 * env 是小程序自己的版本（uni.getAccountInfoSync().miniProgram.envVersion），服务端只认 release / trial / develop。
 * 授权与原详情接口相同：先确认活动属于当前门店 / 当前发起人，再碰微信接口。
 */
@RestController
public class AppActivityCheckinCodeController
{
    @Autowired
    private KgActivityService activities;

    @Autowired
    private KgActivityCheckinCodes codes;

    @Autowired
    private StaffSupport support;

    @Autowired
    private KgJdbc db;

    @Autowired
    private AppRateLimiter limiter;

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/app/staff/activities/{id}/checkin-code")
    public AjaxResult staffCode(@PathVariable Long id, @RequestParam(required = false) String env)
    {
        Map<String, Object> s = db.one("select store_id from kg_activity where activity_id = :a and del_flag = '0'", KgJdbc.p("a", id));
        if (s == null || s.get("storeId") == null) throw new ServiceException("活动不存在或不是门店活动", 404);
        StaffSupport.Ctx c = support.ctx(KgJdbc.asLong(s.get("storeId")));
        return AjaxResult.success(codes.organizerCode(activities.ownStoreActivity(c, id), env));
    }

    @AppAuth
    @GetMapping("/app/activities/{id}/manage/checkin-code")
    public AjaxResult ownerCode(@PathVariable Long id, @RequestParam(required = false) String env)
    {
        return AjaxResult.success(codes.organizerCode(activities.ownUserActivity(AppUserContext.getUserId(), id), env));
    }

    @AppAuth
    @PostMapping("/app/activities/{id}/checkin-pin")
    public AjaxResult checkinPin(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body)
    {
        Long me = AppUserContext.getUserId();
        // 6 位数字只有一百万种：每人每场 10 分钟最多试 10 次，猜不出来
        limiter.check("activity:checkin-pin", me + ":" + id, 10, 600, "试的次数太多了，请 10 分钟后再输，或请领队帮你签到");
        String msg = codes.selfCheckinByPin(me, id, body == null ? null : KgJdbc.asStr(body.get("code")));
        return AjaxResult.success(msg);
    }
}
