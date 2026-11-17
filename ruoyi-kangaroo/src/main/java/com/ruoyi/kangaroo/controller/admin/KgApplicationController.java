package com.ruoyi.kangaroo.controller.admin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.kangaroo.domain.KgApplication;
import com.ruoyi.kangaroo.dto.OperatorDTO;
import com.ruoyi.kangaroo.enums.ApplicationStatus;
import com.ruoyi.kangaroo.service.IKgApplicationService;

/**
 * 报名管理（管理后台）。
 *
 * 只提供查询与状态操作，不提供新增和删除：
 * 报名只能由小程序用户本人发起，后台代建会产生没有真实用户意愿的记录；
 * 删除会破坏履约链路的审计完整性，需要作废时用"终止申请"。
 */
@RestController
@RequestMapping("/kangaroo/application")
public class KgApplicationController extends BaseController
{
    @Autowired
    private IKgApplicationService kgApplicationService;

    @PreAuthorize("@ss.hasPermi('kangaroo:application:list')")
    @GetMapping("/list")
    public TableDataInfo list(KgApplication kgApplication)
    {
        startPage();
        List<KgApplication> list = kgApplicationService.selectKgApplicationList(kgApplication);
        return getDataTable(list);
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:application:export')")
    @Log(title = "报名管理", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, KgApplication kgApplication)
    {
        List<KgApplication> list = kgApplicationService.selectKgApplicationList(kgApplication);
        ExcelUtil<KgApplication> util = new ExcelUtil<KgApplication>(KgApplication.class);
        util.exportExcel(response, list, "报名数据");
    }

    /** 报名详情：含完整状态流转日志与面试信息 */
    @PreAuthorize("@ss.hasPermi('kangaroo:application:query')")
    @GetMapping(value = "/{appId}")
    public AjaxResult getInfo(@PathVariable("appId") Long appId)
    {
        KgApplication app = kgApplicationService.requireApplication(appId);
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("application", app);
        data.put("statusLabel", ApplicationStatus.label(app.getStatus()));
        data.put("logs", kgApplicationService.selectLogs(appId));
        data.put("interview", kgApplicationService.selectInterview(appId));
        return success(data);
    }

    /** 审核：pass=true 通过，false 驳回（必须带原因） */
    @PreAuthorize("@ss.hasPermi('kangaroo:application:audit')")
    @Log(title = "报名审核", businessType = BusinessType.UPDATE)
    @PutMapping("/audit")
    public AjaxResult audit(@RequestBody Map<String, Object> body)
    {
        Long appId = toLong(body.get("appId"));
        boolean pass = Boolean.parseBoolean(String.valueOf(body.get("pass")));
        String reason = body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        return toAjax(kgApplicationService.audit(appId, pass, reason, admin()));
    }

    /** 商家确认录用 */
    @PreAuthorize("@ss.hasPermi('kangaroo:application:confirm')")
    @Log(title = "确认录用", businessType = BusinessType.UPDATE)
    @PutMapping("/confirm/{appId}")
    public AjaxResult confirm(@PathVariable Long appId)
    {
        return toAjax(kgApplicationService.storeConfirm(appId, admin()));
    }

    /** 标记入职 */
    @PreAuthorize("@ss.hasPermi('kangaroo:application:confirm')")
    @Log(title = "标记入职", businessType = BusinessType.UPDATE)
    @PutMapping("/onboard/{appId}")
    public AjaxResult onboard(@PathVariable Long appId)
    {
        return toAjax(kgApplicationService.onboard(appId, admin()));
    }

    /** 终止申请 */
    @PreAuthorize("@ss.hasPermi('kangaroo:application:terminate')")
    @Log(title = "终止报名", businessType = BusinessType.UPDATE)
    @PutMapping("/terminate")
    public AjaxResult terminate(@RequestBody Map<String, Object> body)
    {
        Long appId = toLong(body.get("appId"));
        String reason = body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        return toAjax(kgApplicationService.terminate(appId, reason, admin()));
    }

    private OperatorDTO admin()
    {
        return OperatorDTO.admin(SecurityUtils.getUsername());
    }

    private static Long toLong(Object v)
    {
        if (v == null)
        {
            return null;
        }
        try
        {
            return Long.valueOf(String.valueOf(v));
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }
}
