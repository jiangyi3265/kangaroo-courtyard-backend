package com.ruoyi.kangaroo.controller.admin;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.kangaroo.ext.staff.KgInsuranceFinance;
@RestController @RequestMapping("/kangaroo/insurance-finance")
public class KgInsuranceFinanceAdminController {
 @Autowired private KgInsuranceFinance finance;
 @PreAuthorize("@ss.hasPermi('kangaroo:insapply:list')") @GetMapping("/applies")
 public AjaxResult list(@RequestParam(defaultValue="1")int pageNum,@RequestParam(defaultValue="20")int pageSize,@RequestParam(required=false)String status,@RequestParam(required=false)String keyword,@RequestParam(required=false)Long storeId){return AjaxResult.success(finance.adminList(pageNum,pageSize,status,keyword,storeId));}
 @PreAuthorize("@ss.hasPermi('kangaroo:insapply:query')") @GetMapping("/applies/{id}") public AjaxResult detail(@PathVariable long id){return AjaxResult.success(finance.detail(id,true));}
 @PreAuthorize("@ss.hasPermi('kangaroo:insapply:handle')") @Log(title="保险登记保单",businessType=BusinessType.UPDATE) @PostMapping("/applies/{id}/policy")
 public AjaxResult policy(@PathVariable long id,@RequestBody Map<String,Object>b){return AjaxResult.success(finance.register(id,b,true,SecurityUtils.getUsername()));}
 @PreAuthorize("@ss.hasPermi('kangaroo:insapply:handle')") @Log(title="保险申请处理",businessType=BusinessType.UPDATE) @PostMapping("/applies/{id}/reject")
 public AjaxResult reject(@PathVariable long id,@RequestBody Map<String,Object>b){return AjaxResult.success(finance.reject(id,b.get("reason")==null?null:String.valueOf(b.get("reason")),SecurityUtils.getUsername()));}
 @PreAuthorize("@ss.hasPermi('kangaroo:insapply:list')") @GetMapping("/refunds")
 public AjaxResult refunds(@RequestParam(defaultValue="1")int pageNum,@RequestParam(defaultValue="20")int pageSize,@RequestParam(required=false)String status,@RequestParam(required=false)String keyword){return AjaxResult.success(finance.adminRefunds(pageNum,pageSize,status,keyword));}
 @PreAuthorize("@ss.hasPermi('kangaroo:insapply:handle')") @Log(title="保险退款审核",businessType=BusinessType.UPDATE) @PostMapping("/refunds/{id}/audit")
 public AjaxResult audit(@PathVariable long id,@RequestBody Map<String,Object>b){return AjaxResult.success(finance.audit(id,b,SecurityUtils.getUsername()));}
 @PreAuthorize("@ss.hasPermi('kangaroo:insapply:handle')") @Log(title="保险退款渠道核对",businessType=BusinessType.UPDATE) @PostMapping("/refunds/{id}/recheck")
 public AjaxResult recheck(@PathVariable long id){return AjaxResult.success(finance.recheck(id,SecurityUtils.getUsername()));}
}
