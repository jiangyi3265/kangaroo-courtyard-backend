package com.ruoyi.kangaroo.controller.admin;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.kangaroo.ext.wish.WishService;
@RestController @RequestMapping("/kangaroo/wishes")
public class KgWishController {
    @Autowired private WishService service;
    @GetMapping @PreAuthorize("@ss.hasPermi('kangaroo:wish:list')")
    public AjaxResult list(@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="1") int pageNum){return AjaxResult.success(service.adminList(status,pageNum));}
    @GetMapping("/{id}") @PreAuthorize("@ss.hasPermi('kangaroo:wish:list')")
    public AjaxResult detail(@PathVariable long id){return AjaxResult.success(service.adminDetail(id));}
    @PostMapping("/{id}/{action}") @PreAuthorize("@ss.hasPermi('kangaroo:wish:edit')")
    public AjaxResult action(@PathVariable long id,@PathVariable String action,@RequestBody Map<String,Object>b){service.adminAction(id,action,b,SecurityUtils.getUsername());return AjaxResult.success();}
    @GetMapping("/credentials") @PreAuthorize("@ss.hasPermi('kangaroo:wish:list')")
    public AjaxResult credentials(@RequestParam(required=false) Long userId){return AjaxResult.success(service.credentials(userId,true));}
    @PostMapping("/credentials/{id}/review") @PreAuthorize("@ss.hasPermi('kangaroo:wish:edit')")
    public AjaxResult review(@PathVariable long id,@RequestBody Map<String,Object>b){service.reviewCredential(id,Boolean.TRUE.equals(b.get("approved")),b,SecurityUtils.getUsername());return AjaxResult.success();}
}
