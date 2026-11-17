package com.ruoyi.kangaroo.controller.admin;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.kangaroo.domain.KgStaffGrant;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;

/**
 * 员工授权管理（管理后台）
 *
 * 本接口直接决定小程序端的员工身份，每个方法都必须挂权限，不提供任何免鉴权入口。
 */
@RestController
@RequestMapping("/kangaroo/grant")
public class KgStaffGrantController extends BaseController
{
    @Autowired
    private IKgStaffGrantService kgStaffGrantService;

    @Autowired
    private com.ruoyi.kangaroo.ext.KgJdbc db;

    @PreAuthorize("@ss.hasAnyPermi('kangaroo:grant:list,kangaroo:grant:add,kangaroo:grant:edit')")
    @GetMapping("/cities")
    public AjaxResult cities()
    {
        return success(db.list("select city_id, name from kg_city where status='0' and trim(name)<>'' and name not in ('全国','不限','全部城市') order by sort, city_id", com.ruoyi.kangaroo.ext.KgJdbc.p()));
    }

    /** 与员工端接口校验同源，只展示已有角色能力，不修改权限。 */
    @PreAuthorize("@ss.hasAnyPermi('kangaroo:grant:list,kangaroo:grant:add,kangaroo:grant:edit')")
    @GetMapping("/roles")
    public AjaxResult roles()
    {
        java.util.List<java.util.Map<String, Object>> roles = new java.util.ArrayList<>();
        for (String code : com.ruoyi.kangaroo.ext.staff.StaffPerms.roles()) {
            roles.add(com.ruoyi.kangaroo.ext.KgJdbc.p("value", code, "label", com.ruoyi.kangaroo.enums.StaffRole.label(code),
                    "matrix", com.ruoyi.kangaroo.ext.staff.StaffPerms.matrix(code)));
        }
        // labels 是显示用（老记录、操作日志还可能写着员工）；selectable 是单选能选的，切换前后不同。页面只认 selectable，不用再发版
        java.util.Map<String, String> labels = new java.util.LinkedHashMap<>();
        for (String code : java.util.Arrays.asList("city_lead", "keeper", "manager", "online_staff", "merchant_service", "merchant", "staff")) {
            labels.put(code, com.ruoyi.kangaroo.enums.StaffRole.label(code));
        }
        boolean switched = com.ruoyi.kangaroo.ext.staff.RolePhase.isNew();
        return success(com.ruoyi.kangaroo.ext.KgJdbc.p("roles", roles, "actions", com.ruoyi.kangaroo.ext.staff.StaffPerms.actionLabels(),
                "selectable", com.ruoyi.kangaroo.ext.staff.StaffPerms.roles(), "labels", labels, "phase", com.ruoyi.kangaroo.ext.staff.RolePhase.code(),
                "hint", switched ? "" : "这里开的换工主理人切换后变城市负责人；要开新换工主理人请选员工"));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:grant:list')")
    @GetMapping("/list")
    public TableDataInfo list(KgStaffGrant kgStaffGrant)
    {
        startPage();
        List<KgStaffGrant> list = kgStaffGrantService.selectKgStaffGrantList(kgStaffGrant);
        return getDataTable(list);
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:grant:query')")
    @GetMapping(value = "/{grantId}")
    public AjaxResult getInfo(@PathVariable("grantId") Long grantId)
    {
        return success(kgStaffGrantService.requireGrant(grantId));
    }

    /**
     * 授予员工身份。
     * 同一账号在同一门店只允许一条授权，历史记录存在时由 Service 走重新激活。
     */
    @PreAuthorize("@ss.hasPermi('kangaroo:grant:add')")
    @Log(title = "员工授权", businessType = BusinessType.GRANT)
    @PostMapping
    public AjaxResult add(@RequestBody KgStaffGrant kgStaffGrant)
    {
        kgStaffGrantService.grant(kgStaffGrant);
        return success(kgStaffGrant.getGrantId());
    }

    /**
     * 仅修改员工角色，账号与门店不可改。
     */
    @PreAuthorize("@ss.hasPermi('kangaroo:grant:edit')")
    @Log(title = "员工授权", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody KgStaffGrant kgStaffGrant)
    {
        return toAjax(kgStaffGrantService.updateRole(kgStaffGrant));
    }

    /**
     * 撤销授权。用 PUT 而非 DELETE：授权记录本身要留档备查，只是把状态置为已撤销。
     * 撤销后该账号在小程序侧的员工身份会立即失效。
     */
    @PreAuthorize("@ss.hasPermi('kangaroo:grant:revoke')")
    @Log(title = "撤销员工授权", businessType = BusinessType.GRANT)
    @PutMapping("/revoke/{grantIds}")
    public AjaxResult revoke(@PathVariable Long[] grantIds)
    {
        return toAjax(kgStaffGrantService.revoke(grantIds));
    }
}
