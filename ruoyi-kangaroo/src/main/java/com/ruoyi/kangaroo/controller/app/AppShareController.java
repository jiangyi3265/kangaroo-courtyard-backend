package com.ruoyi.kangaroo.controller.app;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.*;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.ip.IpUtils;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.KgStaffJobService;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppRateLimiter;
import com.ruoyi.kangaroo.security.AppUserContext;

/**
 * 岗位分享的用户侧回传：打开分享链接记一次访问，报名成功后把分享码记到报名上（用于员工分享追踪）。
 */
@RestController
@RequestMapping("/app/share")
public class AppShareController
{
    @Autowired
    private KgStaffJobService jobs;

    @Autowired
    private AppRateLimiter limiter;

    /** 游客也能打开分享链接：登录了按账号去重，没登录按 IP 摘要去重（不落原始 IP） */
    @AppAuth(required = false)
    @PostMapping("/visit")
    public AjaxResult visit(@RequestBody Map<String, Object> body)
    {
        limiter.checkByIp("share-visit", 60, 60, "操作太频繁，请稍后再试");
        AppLoginUser me = AppUserContext.get();
        String key = me != null ? "u:" + me.getUserId()
                : "ip:" + DigestUtils.md5DigestAsHex(("kg-share:" + IpUtils.getIpAddr()).getBytes()).substring(0, 16);
        return AjaxResult.success(jobs.visit(body == null ? null : KgJdbc.asStr(body.get("code")), key));
    }

    @AppAuth
    @PostMapping("/bind")
    public AjaxResult bind(@RequestBody Map<String, Object> body)
    {
        Long appId = body == null ? null : KgJdbc.asLong(body.get("appId"));
        String code = body == null ? null : KgJdbc.asStr(body.get("code"));
        return AjaxResult.success(appId != null && jobs.bind(appId, AppUserContext.getUserId(), code));
    }
}
