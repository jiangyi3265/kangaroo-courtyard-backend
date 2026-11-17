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
import com.ruoyi.kangaroo.ext.staff.KgInsuranceService;

/**
 * 员工运营的后台动作：投保申请登记保单号 / 驳回。
 */
@RestController
@RequestMapping("/kangaroo/staffops")
public class KgStaffOpsAdminController extends BaseController
{
    @Autowired
    private KgInsuranceService insurance;

    @PreAuthorize("@ss.hasPermi('kangaroo:insapply:handle')")
    @Log(title = "投保登记保单", businessType = BusinessType.UPDATE)
    @PutMapping("/insapply/{applyId}/effect")
    public AjaxResult effect(@PathVariable Long applyId, @RequestBody(required = false) Map<String, Object> body)
    {
        String no = body == null ? null : KgJdbc.asStr(body.get("reason"));
        return AjaxResult.success(insurance.effect(applyId, no, SecurityUtils.getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:insapply:handle')")
    @Log(title = "投保驳回", businessType = BusinessType.UPDATE)
    @PutMapping("/insapply/{applyId}/reject")
    public AjaxResult reject(@PathVariable Long applyId, @RequestBody(required = false) Map<String, Object> body)
    {
        String reason = body == null ? null : KgJdbc.asStr(body.get("reason"));
        return AjaxResult.success(insurance.reject(applyId, reason, SecurityUtils.getUsername()));
    }
}
