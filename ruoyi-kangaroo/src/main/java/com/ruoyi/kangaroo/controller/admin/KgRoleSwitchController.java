package com.ruoyi.kangaroo.controller.admin;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.KgRoleSwitchService;

/**
 * 角色切换（管理后台，菜单只给超级管理员）：看名单、切换、退回、打开派单。名单带手机号打码，只在这一页出现。
 */
@RestController
@RequestMapping("/kangaroo/roleswitch")
public class KgRoleSwitchController extends BaseController
{
    @Autowired
    private KgRoleSwitchService service;

    private static String s(Map<String, Object> b, String k)
    {
        return b == null ? null : KgJdbc.asStr(b.get(k));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:roleswitch:list')")
    @GetMapping("/preview")
    public AjaxResult preview()
    {
        return success(service.preview());
    }

    /** { fingerprint: 页面看到的名单指纹, confirm: "切换" } */
    @PreAuthorize("@ss.hasPermi('kangaroo:roleswitch:switch')")
    @Log(title = "角色切换", businessType = BusinessType.GRANT, isSaveResponseData = false)
    @PostMapping("/switch")
    public AjaxResult switchOver(@RequestBody(required = false) Map<String, Object> body)
    {
        return success(service.switchOver(s(body, "fingerprint"), s(body, "confirm"), SecurityUtils.getUsername()));
    }

    /** { confirm: "退回" } */
    @PreAuthorize("@ss.hasPermi('kangaroo:roleswitch:rollback')")
    @Log(title = "角色切换退回", businessType = BusinessType.GRANT, isSaveResponseData = false)
    @PostMapping("/rollback")
    public AjaxResult rollback(@RequestBody(required = false) Map<String, Object> body)
    {
        return success(service.rollback(s(body, "confirm"), SecurityUtils.getUsername()));
    }

    /** { confirm: "打开派单" } */
    @PreAuthorize("@ss.hasPermi('kangaroo:roleswitch:routing')")
    @Log(title = "打开派单", businessType = BusinessType.UPDATE)
    @PostMapping("/open-routing")
    public AjaxResult openRouting(@RequestBody(required = false) Map<String, Object> body)
    {
        return success(service.openRouting(s(body, "confirm"), SecurityUtils.getUsername()));
    }
}
