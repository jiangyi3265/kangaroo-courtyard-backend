package com.ruoyi.kangaroo.controller.admin;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.KgMerchantRequestService;

/** 后台「商家需求」（V63）：列表、改内容走通用 /kangaroo/ext/merchantrequest，这里是铃铛的待处理数和重新派单、关闭、标记完成。 */
@RestController
@RequestMapping("/kangaroo/merchant-request")
public class KgMerchantRequestAdminController
{
    private final KgMerchantRequestService requests;

    public KgMerchantRequestAdminController(KgMerchantRequestService requests)
    {
        this.requests = requests;
    }

    /** 等平台处理（本城没有人接）的条数 + 最近 24 小时新派到本地的条数：Navbar 铃铛轮询；没有列表权限的账号拿不到 */
    @PreAuthorize("@ss.hasPermi('kangaroo:merchantrequest:list')")
    @GetMapping("/pending-count")
    public AjaxResult pendingCount()
    {
        return AjaxResult.success(KgJdbc.p("count", requests.pendingCount(), "recent", requests.recentDispatchedCount()));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:merchantrequest:edit')")
    @Log(title = "商家需求", businessType = BusinessType.UPDATE)
    @PutMapping("/{requestId}/redispatch")
    public AjaxResult redispatch(@PathVariable Long requestId)
    {
        java.util.Map<String, Object> out = requests.redispatch(requestId, SecurityUtils.getUsername());
        return AjaxResult.success(String.valueOf(out.get("message")), out);
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:merchantrequest:edit')")
    @Log(title = "商家需求", businessType = BusinessType.UPDATE)
    @PutMapping("/{requestId}/close")
    public AjaxResult close(@PathVariable Long requestId, @RequestBody(required = false) java.util.Map<String, Object> body)
    {
        java.util.Map<String, Object> out = requests.adminClose(requestId, body == null ? null : KgJdbc.asStr(body.get("reason")), SecurityUtils.getUsername());
        return AjaxResult.success(String.valueOf(out.get("message")), out);
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:merchantrequest:edit')")
    @Log(title = "商家需求", businessType = BusinessType.UPDATE)
    @PutMapping("/{requestId}/done")
    public AjaxResult done(@PathVariable Long requestId)
    {
        java.util.Map<String, Object> out = requests.adminDone(requestId, SecurityUtils.getUsername());
        return AjaxResult.success(String.valueOf(out.get("message")), out);
    }
}
