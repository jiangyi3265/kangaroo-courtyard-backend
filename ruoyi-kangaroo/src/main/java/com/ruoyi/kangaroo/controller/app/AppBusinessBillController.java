package com.ruoyi.kangaroo.controller.app;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.ext.stay.KgBusinessBillService;
import com.ruoyi.kangaroo.ext.stay.KgBusinessWithdrawalService;
import com.ruoyi.kangaroo.security.AppAuth;

@RestController
@RequestMapping("/app/staff/business-bills")
@AppAuth(staff = true, merchant = true)
public class AppBusinessBillController {
    @Autowired private KgBusinessBillService bills;
    @Autowired private StaffSupport support;
    @Autowired private KgBusinessWithdrawalService withdrawals;

    private StaffSupport.Ctx merchant(Long storeId) {
        StaffSupport.Ctx c = support.ctx(storeId);
        if (!"merchant".equals(c.role)) throw new ServiceException("请使用该门店的商家身份查看结算", 403);
        return c;
    }

    @GetMapping
    public AjaxResult list(@RequestParam(required = false) Long storeId, @RequestParam(defaultValue = "") String kind,
            @RequestParam(defaultValue = "1") int pageNum) {
        return AjaxResult.success(bills.list(merchant(storeId).storeId, kind, pageNum));
    }

    @GetMapping("/candidates")
    public AjaxResult candidates(@RequestParam(required = false) Long storeId) {
        return AjaxResult.success(bills.candidates(merchant(storeId).storeId));
    }

    @GetMapping("/{id}")
    public AjaxResult detail(@PathVariable Long id, @RequestParam(required = false) Long storeId) {
        StaffSupport.Ctx c = merchant(storeId);
        return AjaxResult.success(withdrawals.detail(id, c.storeId, c.uid()));
    }

    @PostMapping("/{id}/withdraw")
    public AjaxResult withdraw(@PathVariable Long id, @RequestParam(required = false) Long storeId, @RequestBody Map<String, Object> body) {
        StaffSupport.Ctx c = merchant(storeId);
        return AjaxResult.success(withdrawals.create(id, c.storeId, c.uid(), body));
    }

    @PostMapping("/withdrawals/{number}/refresh")
    public AjaxResult refreshWithdrawal(@PathVariable String number, @RequestParam(required = false) Long storeId) {
        StaffSupport.Ctx c = merchant(storeId);
        return AjaxResult.success(withdrawals.refresh(number, c.storeId, c.uid()));
    }

    @PostMapping("/work")
    public AjaxResult work(@RequestParam(required = false) Long storeId, @RequestBody Map<String, Object> body) {
        StaffSupport.Ctx c = merchant(storeId);
        return AjaxResult.success(bills.recordWork(body, c.storeId, "merchant:" + c.uid()));
    }
}
