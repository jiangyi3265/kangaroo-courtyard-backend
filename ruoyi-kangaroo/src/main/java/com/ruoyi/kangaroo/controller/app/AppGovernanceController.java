package com.ruoyi.kangaroo.controller.app;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.ext.governance.*;

@RestController @RequestMapping("/app/governance") @AppAuth
public class AppGovernanceController {
    @Autowired private KgBadgeService badges;
    @Autowired private KgMediationService mediation;
    @Autowired private KgMediationOrders orders;
    @Autowired private KgMediationFiles files;
    @Autowired private KgMerchantSanctions merchantSanctions;
    @Autowired private KgGovernanceDelivery delivery;
    @GetMapping("/sms-consent") public AjaxResult smsConsent(){return AjaxResult.success(delivery.consent(uid()));}
    @PostMapping("/sms-consent") public AjaxResult smsConsent(@RequestBody Map<String,Object>b){delivery.consent(uid(),Boolean.TRUE.equals(b.get("enabled")));return AjaxResult.success(delivery.consent(uid()));}
    @GetMapping("/merchant-cases") public AjaxResult merchantCases(@RequestParam(defaultValue="1")int pageNum){return AjaxResult.success(merchantSanctions.list(uid(),pageNum,false));}
    @GetMapping("/merchant-cases/{id}") public AjaxResult merchantCase(@PathVariable Long id){return AjaxResult.success(merchantSanctions.detail(uid(),id,false));}
    @PostMapping("/merchant-cases/{id}/appeal") public AjaxResult merchantAppeal(@PathVariable Long id,@RequestBody Map<String,Object>b){merchantSanctions.appeal(uid(),id,b);return AjaxResult.success();}
    private Long uid(){return AppUserContext.getUserId();}
    @GetMapping("/badges") public AjaxResult badges(){return AjaxResult.success(badges.wall(uid()));}
    @PostMapping("/badges/{id}/display") public AjaxResult display(@PathVariable Long id,@RequestBody Map<String,Object>b){badges.display(uid(),id,b);return AjaxResult.success();}
    @GetMapping("/feedback") public AjaxResult feedback(){return AjaxResult.success(badges.feedbacks(uid()));}
    @PostMapping("/feedback") public AjaxResult feedback(@RequestBody Map<String,Object>b){badges.feedback(uid(),b);return AjaxResult.success();}
    @GetMapping("/context") public AjaxResult context(){return AjaxResult.success(mediation.context(uid()));}
    @GetMapping("/order") public AjaxResult order(@RequestParam String type,@RequestParam Long id){return AjaxResult.success(orders.mineOne(uid(),type,id));}
    @GetMapping("/orders") public AjaxResult orders(@RequestParam(defaultValue="user")String role,@RequestParam(defaultValue="1")int pageNum){return AjaxResult.success(orders.mine(uid(),role,pageNum));}
    @GetMapping("/cases") public AjaxResult cases(@RequestParam(defaultValue="user")String mode,@RequestParam(defaultValue="")String status,@RequestParam(defaultValue="1")int pageNum){return AjaxResult.success(mediation.list(uid(),mode,status,pageNum,false));}
    @PostMapping("/cases") public AjaxResult create(@RequestBody Map<String,Object>b){return AjaxResult.success(mediation.create(uid(),b));}
    @GetMapping("/cases/{id}") public AjaxResult detail(@PathVariable Long id){return AjaxResult.success(mediation.detail(uid(),id,false));}
    @PostMapping("/cases/{id}/{action}") public AjaxResult command(@PathVariable Long id,@PathVariable String action,@RequestBody Map<String,Object>b){mediation.command(uid(),id,action,b,false,"");return AjaxResult.success();}
    @PostMapping("/files") public AjaxResult upload(@RequestParam MultipartFile file,@RequestParam(defaultValue="false")boolean redacted)throws java.io.IOException{return AjaxResult.success(files.upload(uid(),file,redacted));}
    @GetMapping("/files/{id}") public AjaxResult file(@PathVariable Long id){return AjaxResult.success(files.link(uid(),id,false));}
    @PostMapping("/juror") public AjaxResult optIn(@RequestBody Map<String,Object>b){mediation.optIn(uid(),b);return AjaxResult.success(mediation.juror(uid()));}
    @GetMapping("/jury") public AjaxResult jury(){return AjaxResult.success(mediation.juryList(uid()));}
    @GetMapping("/jury/{id}") public AjaxResult juryDetail(@PathVariable Long id){return AjaxResult.success(mediation.juryDetail(uid(),id));}
    @PostMapping("/jury/{id}/vote") public AjaxResult vote(@PathVariable Long id,@RequestBody Map<String,Object>b){mediation.vote(uid(),id,b);return AjaxResult.success();}
}
