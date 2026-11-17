package com.ruoyi.kangaroo.controller.app;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.staff.KgInsuranceFinance;
import com.ruoyi.kangaroo.security.AppAuth;
/** Access to owned funds survives staff-role revocation. New applications/policy actions validate selected grants in service. */
@RestController @RequestMapping("/app/insurance") @AppAuth
public class AppInsuranceFinanceController {
 @Autowired private KgInsuranceFinance finance;
 @Autowired private AppMessageController scopes;
 @ModelAttribute public void scope(@RequestHeader(value="X-Message-Scope",required=false)String selected){try{scopes.selectMessageScope(selected);}catch(com.ruoyi.common.exception.ServiceException e){
  // A revoked staff grant must not strand the user's own funds. New business actions still require actor().
  com.ruoyi.kangaroo.security.AppUserContext.messageScope(java.util.Collections.emptyList(),"user");
 }}
 @GetMapping("/context") public AjaxResult context(@RequestParam(required=false) Long storeId){return AjaxResult.success(finance.context(storeId));}
 @GetMapping("/account") public AjaxResult account(){return AjaxResult.success(finance.account());}
 @GetMapping("/applies") public AjaxResult list(@RequestParam(required=false) Long storeId){return AjaxResult.success(finance.list(storeId));}
 @GetMapping("/applies/{id}") public AjaxResult detail(@PathVariable long id){return AjaxResult.success(finance.detail(id,false));}
 @PostMapping("/applies") public AjaxResult create(@RequestBody Map<String,Object>b){return AjaxResult.success(finance.create(b));}
 @PostMapping("/applies/{id}/pay") public AjaxResult pay(@PathVariable long id){return AjaxResult.success(finance.resume(id));}
 @PostMapping("/applies/{id}/cancel") public AjaxResult cancel(@PathVariable long id){return AjaxResult.success(finance.cancel(id));}
 @PostMapping("/applies/{id}/refund") public AjaxResult refund(@PathVariable long id,@RequestBody Map<String,Object>b){return AjaxResult.success(finance.requestRefund(id,b));}
 @PostMapping("/applies/{id}/policy") public AjaxResult policy(@PathVariable long id,@RequestBody Map<String,Object>b){return AjaxResult.success(finance.register(id,b,false,null));}
}
