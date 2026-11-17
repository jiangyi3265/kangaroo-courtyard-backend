package com.ruoyi.kangaroo.controller.app;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletResponse;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.staff.KgMerchantAgreements;
import com.ruoyi.kangaroo.ext.staff.KgMerchantRequestService;
import com.ruoyi.kangaroo.security.AppAuth;

@RestController
@RequestMapping("/app/staff/merchant/agreements")
public class AppMerchantAgreementController {
    private final KgMerchantAgreements agreements;
    private final KgMerchantRequestService requests;
    public AppMerchantAgreementController(KgMerchantAgreements agreements,KgMerchantRequestService requests){this.agreements=agreements;this.requests=requests;}
    @AppAuth(staff=true,merchant=true)
    @GetMapping
    public AjaxResult read(@RequestParam Long storeId,@RequestParam String type,HttpServletResponse response){
        response.setHeader("Cache-Control","no-store");return AjaxResult.success(agreements.read(requests.merchantCtx(storeId),type));
    }
    @AppAuth(staff=true,merchant=true)
    @PostMapping("/{type}/accept")
    public AjaxResult accept(@PathVariable String type,@RequestParam Long storeId,@RequestBody Map<String,Object> body){
        return AjaxResult.success(agreements.accept(requests.merchantCtx(storeId),type,body));
    }
    @AppAuth(staff=true,merchant=true)
    @GetMapping("/records/{id}")
    public AjaxResult record(@PathVariable Long id,@RequestParam Long storeId,HttpServletResponse response){
        response.setHeader("Cache-Control","no-store");return AjaxResult.success(agreements.record(requests.merchantCtx(storeId),id));
    }
}
