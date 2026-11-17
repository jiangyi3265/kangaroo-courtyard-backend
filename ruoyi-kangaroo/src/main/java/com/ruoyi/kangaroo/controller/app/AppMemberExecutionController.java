package com.ruoyi.kangaroo.controller.app;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.ext.user.*;

@RestController @RequestMapping("/app/member") @AppAuth
public class AppMemberExecutionController {
    @Autowired private KgMemberWallet wallet;
    @Autowired private KgMembershipRewards rewards;
    @Autowired private KgMembershipOperations operations;
    private Long uid(){return AppUserContext.getUserId();}
    @GetMapping("/wallet") public AjaxResult wallet(){rewards.sync(uid());return AjaxResult.success(wallet.claim(uid()));}
    @PostMapping("/wallet/claim") public AjaxResult claim(){rewards.sync(uid());return AjaxResult.success(wallet.claim(uid()));}
    @PostMapping("/wallet/redeem") public AjaxResult redeem(@RequestBody Map<String,Object>b){return AjaxResult.success(wallet.redeem(uid(),b));}
    @PostMapping("/wallet/{id}/undo") public AjaxResult undo(@PathVariable Long id){return AjaxResult.success(wallet.undoRedeem(uid(),id));}
    @PostMapping("/wallet/gift") public AjaxResult gift(@RequestBody Map<String,Object>b){return AjaxResult.success(wallet.gift(uid(),b));}
    @PostMapping("/requests") public AjaxResult request(@RequestBody Map<String,Object>b){return AjaxResult.success(wallet.request(uid(),b));}
    @PostMapping("/requests/{id}/cancel") public AjaxResult cancel(@PathVariable Long id){wallet.cancelRequest(uid(),id);return AjaxResult.success();}
    @GetMapping("/pins") public AjaxResult pins(){return AjaxResult.success(operations.pins(uid()));}
    @PostMapping("/pins") public AjaxResult pin(@RequestBody Map<String,Object>b){return AjaxResult.success(operations.pin(uid(),b));}
    @GetMapping("/cases") public AjaxResult cases(){return AjaxResult.success(operations.cases(uid()));}
    @PostMapping("/cases/{id}/appeal") public AjaxResult appeal(@PathVariable Long id,@RequestBody Map<String,Object>b){operations.appeal(uid(),id,b);return AjaxResult.success();}
}
