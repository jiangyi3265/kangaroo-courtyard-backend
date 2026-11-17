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
@RequestMapping("/kangaroo/features")
public class KgSocialFeatureAdminController {
    private final KgCommunityFeature features;
    public KgSocialFeatureAdminController(KgCommunityFeature features) { this.features=features; }
    @GetMapping("/{feature:wish|group-chat|private-chat|stay-booking|recruitment}")
    @PreAuthorize("@ss.hasPermi('system:config:edit')")
    public AjaxResult state(@PathVariable String feature,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");return AjaxResult.success(features.state(feature));
    }
    @PutMapping("/{feature:wish|group-chat|private-chat|stay-booking|recruitment}")
    @PreAuthorize("@ss.hasPermi('system:config:edit')")
    @Log(title="功能开关",businessType=BusinessType.UPDATE)
    public AjaxResult save(@PathVariable String feature,@RequestBody KgFeatureAdminController.Change change) {
        return AjaxResult.success(features.saveFeature(feature,change.enabled,change.expectedEnabled,change.categoryConfirmed,SecurityUtils.getUsername()));
    }
}
