package com.ruoyi.kangaroo.controller.admin;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.stay.KgBusinessBillService;

@RestController
@RequestMapping("/kangaroo/business-bills")
public class KgBusinessBillController {
    @Autowired private KgBusinessBillService bills;
    @Autowired private KgJdbc db;

    @PreAuthorize("@ss.hasPermi('kangaroo:businessbill:query')")
    @GetMapping
    public AjaxResult list(@RequestParam(required = false) Long storeId, @RequestParam(defaultValue = "") String kind,
            @RequestParam(defaultValue = "1") int pageNum) {
        return AjaxResult.success(bills.list(storeId, kind, pageNum));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:businessbill:query')")
    @GetMapping("/stores")
    public AjaxResult stores() {
        return AjaxResult.success(db.list("select store_id,store_name from kg_store where del_flag='0' order by store_id", KgJdbc.p()));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:businessbill:query')")
    @GetMapping("/candidates")
    public AjaxResult candidates(@RequestParam(required = false) Long storeId) { return AjaxResult.success(bills.candidates(storeId)); }

    @PreAuthorize("@ss.hasPermi('kangaroo:businessbill:query')")
    @GetMapping("/{id}")
    public AjaxResult detail(@PathVariable Long id) { return AjaxResult.success(bills.detail(id, null)); }

    @PreAuthorize("@ss.hasPermi('kangaroo:businessbill:edit')")
    @PostMapping("/work")
    public AjaxResult work(@RequestBody Map<String, Object> body) {
        return AjaxResult.success(bills.recordWork(body, null, "admin:" + SecurityUtils.getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:businessbill:edit')")
    @PostMapping("/{id}/{action}")
    public AjaxResult command(@PathVariable Long id, @PathVariable String action, @RequestBody Map<String, Object> body) {
        bills.command(id, action, body, "admin:" + SecurityUtils.getUsername());
        return AjaxResult.success();
    }
}
