package com.ruoyi.kangaroo.controller.admin;
import javax.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.kangaroo.ext.community.KgCommunityFeature;

@RestController
@RequestMapping("/kangaroo/features/community")
public class KgFeatureAdminController {
    private final KgCommunityFeature community;
    public KgFeatureAdminController(KgCommunityFeature community) { this.community=community; }
    @PreAuthorize("@ss.hasPermi('system:config:edit')")
    @GetMapping
    public AjaxResult state(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return AjaxResult.success(community.state());
    }
    public static class Change {
        public Boolean enabled;
        public Boolean expectedEnabled;
        public Boolean categoryConfirmed;
    }
    @PreAuthorize("@ss.hasPermi('system:config:edit')")
    @Log(title="社区内容功能开关",businessType=BusinessType.UPDATE)
    @PutMapping
    public AjaxResult save(@RequestBody Change change) {
        return AjaxResult.success(community.save(change.enabled, change.expectedEnabled, change.categoryConfirmed, SecurityUtils.getUsername()));
    }
}
