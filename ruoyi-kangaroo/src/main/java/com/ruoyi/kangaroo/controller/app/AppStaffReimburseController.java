package com.ruoyi.kangaroo.controller.app;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.stay.KgReimburseService;
import com.ruoyi.kangaroo.security.AppAuth;

/**
 * 采购报销（V66）：直营小院的小院主理人在小程序提交。没标 merchant / onlineStaff / merchantService / cityLead，这些授权在拦截器里就去掉了；
 * 账号上剩下小院主理人、换工主理人、老员工授权的进得来，每个接口再到服务里按「这家店的 keeper」挡一次（KgReimburseService.keeper），access 除外：它只回答要不要放入口。
 *  GET    /app/staff/reimbursements/access?storeId=       工作台要不要放入口：{storeId, storeName, canSubmit, visible}
 *  GET    /app/staff/reimbursements?storeId=&status=&page= 这家店的报销表
 *  GET    /app/staff/reimbursements/{id}                  详情
 *  POST   /app/staff/reimbursements?storeId=              提交 {itemName, quantity, amount, purchaseDate, receipts, remark}
 *  PUT    /app/staff/reimbursements/{id}                  驳回后修改再交（同上）
 *  DELETE /app/staff/reimbursements/{id}                  撤回待审的
 */
@RestController
@RequestMapping("/app/staff/reimbursements")
@AppAuth(staff = true)
public class AppStaffReimburseController
{
    @Autowired
    private KgReimburseService service;

    @GetMapping("/access")
    public AjaxResult access(@RequestParam(required = false) Long storeId)
    {
        return AjaxResult.success(service.access(storeId));
    }

    @GetMapping
    public AjaxResult list(@RequestParam(required = false) Long storeId, @RequestParam(required = false) String status, @RequestParam(defaultValue = "1") int page)
    {
        return AjaxResult.success(service.list(storeId, status, page));
    }

    @GetMapping("/{id}")
    public AjaxResult detail(@PathVariable Long id)
    {
        return AjaxResult.success(service.detail(id));
    }

    @PostMapping
    public AjaxResult submit(@RequestParam(required = false) Long storeId, @RequestBody(required = false) Map<String, Object> body)
    {
        return AjaxResult.success("已提交，等平台审核", service.submit(storeId, body));
    }

    @PutMapping("/{id}")
    public AjaxResult resubmit(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body)
    {
        return AjaxResult.success("已重新提交，等平台审核", service.resubmit(id, body));
    }

    @DeleteMapping("/{id}")
    public AjaxResult withdraw(@PathVariable Long id)
    {
        service.withdraw(id);
        return AjaxResult.success("已撤回");
    }
}
