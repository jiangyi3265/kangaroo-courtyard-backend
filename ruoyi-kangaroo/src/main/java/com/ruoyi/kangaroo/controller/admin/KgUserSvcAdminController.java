package com.ruoyi.kangaroo.controller.admin;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.KgSupportService;
import com.ruoyi.kangaroo.ext.user.KgUserHubService;

/** 用户服务后台动作：客服工单回复、体验官申请审核 */
@RestController
@RequestMapping("/kangaroo/usersvc")
public class KgUserSvcAdminController extends BaseController
{
    @Autowired
    private KgSupportService support;

    @Autowired
    private KgUserHubService hub;

    private static String reason(Map<String, Object> body)
    {
        String r = body == null ? null : KgJdbc.asStr(body.get("reason"));
        return r == null ? "" : r.trim();
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:ticket:audit')")
    @Log(title = "客服工单回复", businessType = BusinessType.UPDATE)
    @PutMapping("/ticket/{id}/reply")
    public AjaxResult reply(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body)
    {
        return AjaxResult.success(support.reply(id, reason(body), "admin:" + SecurityUtils.getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:guideapply:audit')")
    @Log(title = "体验官申请通过", businessType = BusinessType.UPDATE)
    @PutMapping("/guide/{id}/approve")
    public AjaxResult approve(@PathVariable Long id)
    {
        return AjaxResult.success(hub.auditGuide(id, true, null, "admin:" + SecurityUtils.getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:guideapply:audit')")
    @Log(title = "体验官申请驳回", businessType = BusinessType.UPDATE)
    @PutMapping("/guide/{id}/reject")
    public AjaxResult reject(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body)
    {
        return AjaxResult.success(hub.auditGuide(id, false, reason(body), "admin:" + SecurityUtils.getUsername()));
    }
}
