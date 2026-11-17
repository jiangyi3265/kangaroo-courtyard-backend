package com.ruoyi.kangaroo.controller.admin;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.user.*;

@RestController @RequestMapping("/kangaroo/membership")
public class KgMembershipController extends BaseController {
    @Autowired private KgMembershipOperations operations;
    @Autowired private KgMembershipRewards rewards;
    @Autowired private KgMemberReviewQueue reviewQueue;
    @GetMapping("/review-queue") @PreAuthorize("@ss.hasPermi('kangaroo:report:query')")
    public AjaxResult reviewQueue(@RequestParam(defaultValue="1")int pageNum){return success(reviewQueue.overview(Math.max(1,Math.min(10000,pageNum))));}
    @GetMapping @PreAuthorize("@ss.hasPermi('kangaroo:memberlevel:query')")
    public AjaxResult list(@RequestParam(defaultValue="requests")String tab,@RequestParam(defaultValue="1")int pageNum){return success(operations.overview(tab,Math.max(1,Math.min(10000,pageNum))));}
    private String operator(){return "admin:"+getUserId();}
    @PostMapping("/requests/{id}") @PreAuthorize("@ss.hasPermi('kangaroo:memberlevel:edit')")
    public AjaxResult fulfill(@PathVariable Long id,@RequestBody Map<String,Object>b){operations.fulfill(id,b,operator());return success();}
    @PostMapping("/reviews/{id}") @PreAuthorize("@ss.hasPermi('kangaroo:memberlevel:edit')")
    public AjaxResult review(@PathVariable Long id,@RequestBody Map<String,Object>b){operations.review(id,b,operator());return success();}
    @PostMapping("/config/{type}/{id}") @PreAuthorize("@ss.hasPermi('kangaroo:memberlevel:edit')")
    public AjaxResult config(@PathVariable String type,@PathVariable Long id,@RequestBody Map<String,Object>b){operations.configure(type,id,b,operator());return success();}
    @PostMapping("/calendar") @PreAuthorize("@ss.hasPermi('kangaroo:memberlevel:edit')")
    public AjaxResult calendar(@RequestBody Map<String,Object>b){operations.calendar(b,operator());return success();}
    @PostMapping("/posts/{id}/repost") @PreAuthorize("@ss.hasPermi('kangaroo:memberlevel:edit')")
    public AjaxResult repost(@PathVariable Long id,@RequestBody Map<String,Object>b){operations.repost(id,b,operator());return success();}
    @PostMapping("/cases") @PreAuthorize("@ss.hasPermi('kangaroo:memberlevel:edit')")
    public AjaxResult createCase(@RequestBody Map<String,Object>b){return success(operations.notifyCase(b,operator()));}
    @PostMapping("/cases/{id}") @PreAuthorize("@ss.hasPermi('kangaroo:memberlevel:edit')")
    public AjaxResult decide(@PathVariable Long id,@RequestBody Map<String,Object>b){operations.decideCase(id,b,operator());return success();}
    @PostMapping("/reconcile/{id}") @PreAuthorize("@ss.hasPermi('kangaroo:memberlevel:edit')")
    public AjaxResult reconcile(@PathVariable Long id){rewards.sync(id);return success();}
}
