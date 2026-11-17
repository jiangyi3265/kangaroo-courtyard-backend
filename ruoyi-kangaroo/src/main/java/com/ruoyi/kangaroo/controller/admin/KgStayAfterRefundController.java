package com.ruoyi.kangaroo.controller.admin;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.kangaroo.ext.stay.KgStayAfterRefundService;
@RestController @RequestMapping("/kangaroo/stayops/after-refunds")
public class KgStayAfterRefundController {
    private final KgStayAfterRefundService refunds;
    public KgStayAfterRefundController(KgStayAfterRefundService refunds){this.refunds=refunds;}
    @PreAuthorize("@ss.hasPermi('kangaroo:stayorder:refund')")
    @GetMapping public AjaxResult list(@RequestParam(defaultValue="pending") String status,@RequestParam(defaultValue="") String orderNo,
        @RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize){return AjaxResult.success(refunds.list(status,orderNo,pageNum,pageSize));}
    @PreAuthorize("@ss.hasPermi('kangaroo:stayorder:refund')")
    @Log(title="退房后退款审批",businessType=BusinessType.UPDATE)
    @PutMapping("/{id}/approve") public AjaxResult approve(@PathVariable Long id){return AjaxResult.success("已同意申请，退款结果以支付渠道为准",refunds.review(id,true,"",SecurityUtils.getUsername()));}
    @PreAuthorize("@ss.hasPermi('kangaroo:stayorder:refund')")
    @Log(title="退房后退款驳回",businessType=BusinessType.UPDATE)
    @PutMapping("/{id}/reject") public AjaxResult reject(@PathVariable Long id,@RequestBody Map<String,Object> b){return AjaxResult.success("已驳回申请",refunds.review(id,false,b.get("reason")==null?"":b.get("reason").toString(),SecurityUtils.getUsername()));}
}
