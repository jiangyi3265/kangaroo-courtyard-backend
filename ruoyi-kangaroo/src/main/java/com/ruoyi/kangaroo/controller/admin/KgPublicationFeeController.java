package com.ruoyi.kangaroo.controller.admin;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.kangaroo.ext.staff.KgPublicationFeeService;

@RestController
@RequestMapping("/kangaroo/publication-fees")
public class KgPublicationFeeController {
    @Autowired private KgPublicationFeeService fees;
    @PreAuthorize("@ss.hasPermi('kangaroo:publicationfee:query')")
    @GetMapping
    public AjaxResult list(@RequestParam(required=false) Long storeId,@RequestParam(defaultValue="1") int pageNum) { return AjaxResult.success(fees.list(storeId,pageNum)); }
    @PreAuthorize("@ss.hasPermi('kangaroo:publicationfee:edit')")
    @PutMapping("/config")
    public AjaxResult configure(@RequestBody Map<String,Object> b) { fees.configure(b,SecurityUtils.getUsername());return AjaxResult.success(); }
    @PreAuthorize("@ss.hasPermi('kangaroo:publicationfee:edit')")
    @PostMapping("/{id}/{action}")
    public AjaxResult command(@PathVariable Long id,@PathVariable String action,@RequestBody Map<String,Object> b) { fees.command(id,action,b,SecurityUtils.getUsername());return AjaxResult.success(); }
}
