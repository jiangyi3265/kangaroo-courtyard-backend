package com.ruoyi.kangaroo.controller.app;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.stay.KgStayAfterRefundService;
import com.ruoyi.kangaroo.security.*;
@RestController @RequestMapping("/app/stay/orders/{orderNo}/after-refund") @AppAuth
public class AppStayAfterRefundController {
    private final KgStayAfterRefundService refunds;
    private final AppRateLimiter limiter;
    public AppStayAfterRefundController(KgStayAfterRefundService refunds,AppRateLimiter limiter){this.refunds=refunds;this.limiter=limiter;}
    @GetMapping public AjaxResult overview(@PathVariable String orderNo){return AjaxResult.success(refunds.overview(AppUserContext.getUserId(),orderNo));}
    @PostMapping public AjaxResult apply(@PathVariable String orderNo,@RequestBody Map<String,Object> b){
        Long uid=AppUserContext.getUserId();limiter.check("stay-after-refund",String.valueOf(uid),20,3600,"申请过于频繁，请稍后再试");
        String raw=String.valueOf(b.get("amountFen"));
        if(!raw.matches("[1-9][0-9]{0,9}"))throw new ServiceException("退款金额格式不正确",422);
        return AjaxResult.success("申请已提交，等待平台审核",refunds.apply(uid,orderNo,Long.parseLong(raw),
            b.get("reason")==null?"":b.get("reason").toString(),b.get("requestKey")==null?"":b.get("requestKey").toString()));
    }
}
