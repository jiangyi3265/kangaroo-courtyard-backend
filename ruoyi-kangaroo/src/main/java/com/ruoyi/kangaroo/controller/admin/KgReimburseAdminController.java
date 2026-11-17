package com.ruoyi.kangaroo.controller.admin;

import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.stay.KgReimburseService;

/**
 * 后台「采购报销」（V66，旅居目录，菜单默认只给角色 1）。只有两个权限：kangaroo:reimburse:list 看表格、详情、导出；kangaroo:reimburse:audit 通过、驳回、标记已打款。
 * 列表不用 TableDataInfo：要多带合计，AjaxResult 根上放 rows、total、summary，若依页面照旧读 response.rows、response.total。
 * 系统只记账不打款。
 */
@RestController
@RequestMapping("/kangaroo/reimburse")
public class KgReimburseAdminController
{
    @Autowired
    private KgReimburseService service;

    @PreAuthorize("@ss.hasPermi('kangaroo:reimburse:list')")
    @GetMapping("/list")
    public AjaxResult list(@RequestParam Map<String, String> query)
    {
        AjaxResult out = AjaxResult.success();
        out.putAll(service.adminList(query));
        return out;
    }

    /** 门店筛选的下拉 */
    @PreAuthorize("@ss.hasPermi('kangaroo:reimburse:list')")
    @GetMapping("/stores")
    public AjaxResult stores()
    {
        return AjaxResult.success(service.adminStores());
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:reimburse:list')")
    @GetMapping("/{id}")
    public AjaxResult detail(@PathVariable Long id)
    {
        return AjaxResult.success(service.adminDetail(id));
    }

    /** 和列表同一组筛选条件（表单参数），不分页 */
    @PreAuthorize("@ss.hasPermi('kangaroo:reimburse:list')")
    @Log(title = "采购报销导出", businessType = BusinessType.EXPORT, isSaveResponseData = false)
    @PostMapping("/export")
    public void export(@RequestParam Map<String, String> query, HttpServletResponse response) throws java.io.IOException
    {
        service.adminExport(query, response);
    }

    /** { revision: 页面看到的第几次提交 } */
    @PreAuthorize("@ss.hasPermi('kangaroo:reimburse:audit')")
    @Log(title = "采购报销通过", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/approve")
    public AjaxResult approve(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body)
    {
        return AjaxResult.success(service.approve(id, value(body, "revision"), SecurityUtils.getUserId(), SecurityUtils.getUsername()));
    }

    /** { reason: 驳回理由（必填）, revision } */
    @PreAuthorize("@ss.hasPermi('kangaroo:reimburse:audit')")
    @Log(title = "采购报销驳回", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/reject")
    public AjaxResult reject(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body)
    {
        return AjaxResult.success(service.reject(id, KgJdbc.asStr(value(body, "reason")), value(body, "revision"), SecurityUtils.getUserId(), SecurityUtils.getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:reimburse:audit')")
    @Log(title = "采购报销标记已打款", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/paid")
    public AjaxResult paid(@PathVariable Long id)
    {
        return AjaxResult.success(service.markPaid(id, SecurityUtils.getUserId(), SecurityUtils.getUsername()));
    }

    private static Object value(Map<String, Object> body, String key)
    {
        return body == null ? null : body.get(key);
    }
}
