package com.ruoyi.kangaroo.controller.app;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffPerms;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.ext.user.KgInviteService;
import com.ruoyi.kangaroo.ext.user.KgPointsService;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppRateLimiter;
import com.ruoyi.kangaroo.security.AppUserContext;

/**
 * 积分与邀请：我的邀请、绑定邀请人、加急投递（花积分）、员工确认换工完成（发积分）。
 * 积分总览仍是 GET /app/me/points（AppMeController → KgAccountService.points → KgPointsService.summary）。
 */
@RestController
@RequestMapping("/app")
public class AppPointsController
{
    @Autowired
    private KgPointsService points;

    @Autowired
    private KgInviteService invites;

    @Autowired
    private AppRateLimiter limiter;

    @Autowired
    private StaffSupport staffSupport;

    @Autowired
    private KgJdbc db;

    /** 「我的邀请」：我的邀请码（没有就生成）、分享路径、规则、我邀请的人 */
    @AppAuth
    @GetMapping("/me/invite")
    public AjaxResult myInvite()
    {
        return AjaxResult.success(invites.mine(AppUserContext.getUserId()));
    }

    /** 绑定邀请人 {code}：不符合条件时返回 bound=false 和原因，不报错（登录后自动绑定会直接忽略） */
    @AppAuth
    @PostMapping("/me/inviter")
    public AjaxResult bindInviter(@RequestBody(required = false) Map<String, Object> body)
    {
        Long uid = AppUserContext.getUserId();
        limiter.check("invite:bind", String.valueOf(uid), 20, 3600, "操作太频繁，请稍后再试");
        return AjaxResult.success(invites.bind(uid, body == null ? null : KgJdbc.asStr(body.get("code"))));
    }

    /** 报名进度页：这条报名能不能加急、要花多少积分、我还有多少 */
    @AppAuth
    @GetMapping("/applications/{appId}/urgent")
    public AjaxResult urgentInfo(@PathVariable Long appId)
    {
        return AjaxResult.success(points.urgentInfo(AppUserContext.getUserId(), appId));
    }

    /** 加急投递：花积分把自己一条等门店审核的报名标成「加急」（每条一次）；积分不够 422，已加急 / 已不在等审核 409 */
    @AppAuth
    @PostMapping("/applications/{appId}/urgent")
    public AjaxResult urgent(@PathVariable Long appId)
    {
        Long uid = AppUserContext.getUserId();
        limiter.check("points:urgent", String.valueOf(uid), 30, 3600, "操作太频繁，请稍后再试");
        Map<String, Object> r = points.urgent(uid, appId);
        return AjaxResult.success(String.valueOf(r.get("message")), r);
    }

    /** 员工端：确认换工完成 {days 实际天数，可不填}。只限报名所属门店有岗位编辑权限的换工主理人、小院主理人，不能确认自己的 */
    @AppAuth(staff = true)
    @PostMapping("/staff/applications/{appId}/volunteer-done")
    public AjaxResult volunteerDone(@PathVariable Long appId, @RequestBody(required = false) Map<String, Object> body)
    {
        Map<String, Object> a = db.one("select store_id from kg_application where app_id = :a and del_flag = '0'", KgJdbc.p("a", appId));
        if (a == null)
        {
            throw new ServiceException("报名记录不存在", 404);
        }
        // 先按报名所属门店取员工身份（没有这家门店的授权直接 403），再按角色判权；被拒时记一条失败的员工操作日志（不随业务事务回滚）
        StaffSupport.Ctx c = staffSupport.ctxForStore(KgJdbc.asLong(a.get("storeId")));
        staffSupport.need(c, "jobs", StaffPerms.EDIT, "volunteer_done", "确认换工完成");
        Map<String, Object> r = points.finishVolunteer(c, appId, days(body == null ? null : body.get("days")));
        return AjaxResult.success(String.valueOf(r.get("message")), r);
    }

    /** 天数：不填 = null（按能填的最大天数算）；不是整数给 422，不变成 500 */
    static Integer days(Object v)
    {
        String t = v == null ? "" : String.valueOf(v).trim();
        if (t.isEmpty())
        {
            return null;
        }
        if (!t.matches("\\d{1,4}"))
        {
            throw new ServiceException("实际天数请填整数", 422);
        }
        return Integer.valueOf(t);
    }
}
