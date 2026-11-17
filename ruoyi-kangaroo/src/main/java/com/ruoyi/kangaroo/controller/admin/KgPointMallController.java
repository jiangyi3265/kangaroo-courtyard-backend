package com.ruoyi.kangaroo.controller.admin;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.kangaroo.ext.user.KgPointMallService;

@RestController
@RequestMapping("/kangaroo/point-mall")
public class KgPointMallController {
    @Autowired private KgPointMallService mall;
    @PreAuthorize("@ss.hasPermi('kangaroo:pointmall:query')")
    @GetMapping("/goods")
    public AjaxResult goods(@RequestParam(defaultValue="1") int pageNum) { return AjaxResult.success(mall.goods(true, pageNum)); }
    @PreAuthorize("@ss.hasPermi('kangaroo:pointmall:query')")
    @GetMapping("/orders")
    public AjaxResult orders(@RequestParam(defaultValue="1") int pageNum, @RequestParam(defaultValue="") String status) { return AjaxResult.success(mall.orders(null, status, pageNum)); }
    @PreAuthorize("@ss.hasPermi('kangaroo:pointmall:edit')")
    @PostMapping("/goods")
    public AjaxResult create(@RequestBody Map<String,Object> b) { return AjaxResult.success(mall.save(null, b, SecurityUtils.getUsername())); }
    @PreAuthorize("@ss.hasPermi('kangaroo:pointmall:edit')")
    @PutMapping("/goods/{id}")
    public AjaxResult update(@PathVariable Long id, @RequestBody Map<String,Object> b) { return AjaxResult.success(mall.save(id, b, SecurityUtils.getUsername())); }
    @PreAuthorize("@ss.hasPermi('kangaroo:pointmall:edit')")
    @PostMapping("/orders/{id}/{action}")
    public AjaxResult command(@PathVariable Long id, @PathVariable String action, @RequestBody Map<String,Object> b) {
        mall.command(id, null, action, b, "admin:" + SecurityUtils.getUsername());
        return AjaxResult.success();
    }
}
