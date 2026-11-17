package com.ruoyi.kangaroo.controller.app;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.KgMerchantRequestService;
import com.ruoyi.kangaroo.ext.staff.KgVolunteerDailyService;
import com.ruoyi.kangaroo.ext.staff.StaffPerms;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppRateLimiter;

/**
 * 商家需求（V63）的小程序接口。按角色分四组，都不放在 /app/staff/online 下（那里按「线上任务·查看」过滤，换工主理人进不来）：
 *   /app/staff/intake        换工主理人：本城线上任务 / 代运营 / 抖音卖房的核实、转发、退回、代提交；
 *   /app/staff/merchant-jobs 换工主理人：派给他的换工 / 兼职 / 全职草稿，完善、上架、不通过；
 *   /app/staff/service       商家服务人：转过来的需求，接手、上架线上任务、退回、标完成；本城商家活动的审核；
 *   /app/staff/city          城市负责人：只读；
 *   /app/staff/merchant/requests 合作商家：我发布的、申请代运营 / 抖音卖房、被退回后修改。
 * 权限在控制器（事务外）查，拒绝时写的失败日志不随业务回滚；城市隔离在服务里查。
 */
@RestController
@RequestMapping("/app/staff")
public class AppStaffMerchantRequestController
{
    @Autowired
    private KgMerchantRequestService requests;

    @Autowired
    private KgVolunteerDailyService daily;

    @Autowired
    private AppRateLimiter limiter;

    private static String s(Map<String, Object> b, String k)
    {
        return b == null ? null : KgJdbc.asStr(b.get(k));
    }

    /** 请求体里的是非：true / false（小程序发的）或字符串、1 / 0；没传或认不出是 null */
    private static Boolean bool(Map<String, Object> b, String k)
    {
        String v = s(b, k);
        if ("true".equalsIgnoreCase(v) || "1".equals(v))
        {
            return Boolean.TRUE;
        }
        return "false".equalsIgnoreCase(v) || "0".equals(v) ? Boolean.FALSE : null;
    }

    // ---------------------------------------------------------------- 换工主理人：线上任务审核

    @AppAuth(staff = true)
    @GetMapping("/intake")
    public AjaxResult intakeList(@RequestParam(required = false) Long storeId, @RequestParam(required = false) String status,
            @RequestParam(required = false) String type, @RequestParam(required = false) Integer pageNum, @RequestParam(required = false) Integer pageSize)
    {
        return AjaxResult.success(requests.intakeList(requests.managerCtx(storeId, StaffPerms.VIEW), status, type, pageNum, pageSize));
    }

    @AppAuth(staff = true)
    @GetMapping("/intake/options")
    public AjaxResult intakeOptions(@RequestParam(required = false) Long storeId)
    {
        return AjaxResult.success(requests.intakeOptions(requests.managerCtx(storeId, StaffPerms.VIEW)));
    }

    @AppAuth(staff = true)
    @PostMapping("/intake/submit")
    public AjaxResult intakeSubmit(@RequestParam(required = false) Long storeId, @RequestBody(required = false) Map<String, Object> body)
    {
        StaffSupport.Ctx c = requests.managerCtx(storeId, StaffPerms.EDIT);
        limiter.check("staff:intake-submit", String.valueOf(c.uid()), 30, 3600, "提交太频繁了，稍后再试");
        Map<String, Object> out = requests.intakeSubmit(c, body);
        return AjaxResult.success(String.valueOf(out.get("message")), out);
    }

    @AppAuth(staff = true)
    @GetMapping("/intake/{requestId:[0-9]+}")
    public AjaxResult intakeDetail(@PathVariable Long requestId, @RequestParam(required = false) Long storeId)
    {
        return AjaxResult.success(requests.intakeDetail(requests.managerCtx(storeId, StaffPerms.VIEW), requestId));
    }

    @AppAuth(staff = true)
    @PutMapping("/intake/{requestId:[0-9]+}/note")
    public AjaxResult intakeNote(@PathVariable Long requestId, @RequestParam(required = false) Long storeId, @RequestBody(required = false) Map<String, Object> body)
    {
        return AjaxResult.success("备注已保存", requests.intakeNote(requests.managerCtx(storeId, StaffPerms.EDIT), requestId, s(body, "verifyNote")));
    }

    @AppAuth(staff = true)
    @PostMapping("/intake/{requestId:[0-9]+}/forward")
    public AjaxResult intakeForward(@PathVariable Long requestId, @RequestParam(required = false) Long storeId, @RequestBody(required = false) Map<String, Object> body)
    {
        StaffSupport.Ctx c = requests.managerCtx(storeId, StaffPerms.AUDIT);
        Map<String, Object> out = requests.intakeForward(c, requestId, body == null ? null : KgJdbc.asLong(body.get("serviceUserId")), s(body, "verifyNote"));
        return AjaxResult.success(String.valueOf(out.get("message")), out);
    }

    @AppAuth(staff = true)
    @PostMapping("/intake/{requestId:[0-9]+}/return")
    public AjaxResult intakeReturn(@PathVariable Long requestId, @RequestParam(required = false) Long storeId, @RequestBody(required = false) Map<String, Object> body)
    {
        StaffSupport.Ctx c = requests.managerCtx(storeId, StaffPerms.AUDIT);
        Map<String, Object> out = requests.intakeReturn(c, requestId, s(body, "reason"));
        return AjaxResult.success(String.valueOf(out.get("message")), out);
    }

    // ---------------------------------------------------------------- 换工主理人：派给他的商家岗位

    @AppAuth(staff = true)
    @GetMapping("/merchant-jobs")
    public AjaxResult dispatchedList(@RequestParam(required = false) Long storeId, @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer pageNum, @RequestParam(required = false) Integer pageSize)
    {
        return AjaxResult.success(requests.dispatchedList(requests.managerCtx(storeId, StaffPerms.VIEW), status, pageNum, pageSize));
    }

    @AppAuth(staff = true)
    @GetMapping("/merchant-jobs/{jobId:[0-9]+}")
    public AjaxResult dispatchedJob(@PathVariable Long jobId)
    {
        return AjaxResult.success(requests.dispatchedJob(requests.jobManagerCtx(jobId, StaffPerms.VIEW, "merchant_job", "查看商家岗位"), jobId));
    }

    @AppAuth(staff = true)
    @PutMapping("/merchant-jobs/{jobId:[0-9]+}")
    public AjaxResult dispatchedEdit(@PathVariable Long jobId, @RequestBody(required = false) Map<String, Object> body)
    {
        return AjaxResult.success("已保存", requests.dispatchedEdit(requests.jobManagerCtx(jobId, StaffPerms.EDIT, "merchant_job_edit", "完善商家岗位"), jobId, body));
    }

    @AppAuth(staff = true)
    @PostMapping("/merchant-jobs/{jobId:[0-9]+}/publish")
    public AjaxResult dispatchedPublish(@PathVariable Long jobId)
    {
        Map<String, Object> out = requests.dispatchedPublish(requests.jobManagerCtx(jobId, StaffPerms.AUDIT, "merchant_job_publish", "上架商家岗位"), jobId);
        return AjaxResult.success(String.valueOf(out.get("message")), out);
    }

    @AppAuth(staff = true)
    @PostMapping("/merchant-jobs/{jobId:[0-9]+}/close")
    public AjaxResult dispatchedClose(@PathVariable Long jobId, @RequestBody(required = false) Map<String, Object> body)
    {
        Map<String, Object> out = requests.dispatchedClose(requests.jobManagerCtx(jobId, StaffPerms.AUDIT, "merchant_job_close", "不通过商家岗位"), jobId, s(body, "reason"));
        return AjaxResult.success(String.valueOf(out.get("message")), out);
    }

    // ---------------------------------------------------------------- 商家服务人

    @AppAuth(staff = true, merchantService = true)
    @GetMapping("/service/requests")
    public AjaxResult serviceList(@RequestParam(required = false) Long storeId, @RequestParam(required = false) String status,
            @RequestParam(required = false) String type, @RequestParam(required = false) Integer pageNum, @RequestParam(required = false) Integer pageSize)
    {
        return AjaxResult.success(requests.serviceList(requests.serviceCtx(storeId, StaffPerms.VIEW), status, type, pageNum, pageSize));
    }

    @AppAuth(staff = true, merchantService = true)
    @GetMapping("/service/requests/{requestId:[0-9]+}")
    public AjaxResult serviceDetail(@PathVariable Long requestId, @RequestParam(required = false) Long storeId)
    {
        return AjaxResult.success(requests.serviceDetail(requests.serviceCtx(storeId, StaffPerms.VIEW), requestId));
    }

    @AppAuth(staff = true, merchantService = true)
    @PostMapping("/service/requests/{requestId:[0-9]+}/accept")
    public AjaxResult serviceAccept(@PathVariable Long requestId, @RequestParam(required = false) Long storeId)
    {
        Map<String, Object> out = requests.serviceAccept(requests.serviceCtx(storeId, StaffPerms.EDIT), requestId);
        return AjaxResult.success(String.valueOf(out.get("message")), out);
    }

    @AppAuth(staff = true, merchantService = true)
    @PostMapping("/service/requests/{requestId:[0-9]+}/publish")
    public AjaxResult servicePublish(@PathVariable Long requestId, @RequestParam(required = false) Long storeId)
    {
        Map<String, Object> out = requests.servicePublish(requests.serviceCtx(storeId, StaffPerms.EDIT), requestId);
        return AjaxResult.success(String.valueOf(out.get("message")), out);
    }

    @AppAuth(staff = true, merchantService = true)
    @PostMapping("/service/requests/{requestId:[0-9]+}/done")
    public AjaxResult serviceDone(@PathVariable Long requestId, @RequestParam(required = false) Long storeId)
    {
        Map<String, Object> out = requests.serviceDone(requests.serviceCtx(storeId, StaffPerms.EDIT), requestId);
        return AjaxResult.success(String.valueOf(out.get("message")), out);
    }

    /** 退回给商家修改（岗位还没上架时）；reason 必填，会发给商家 */
    @AppAuth(staff = true, merchantService = true)
    @PostMapping("/service/requests/{requestId:[0-9]+}/return")
    public AjaxResult serviceReturn(@PathVariable Long requestId, @RequestParam(required = false) Long storeId, @RequestBody(required = false) Map<String, Object> body)
    {
        Map<String, Object> out = requests.serviceReturn(requests.serviceCtx(storeId, StaffPerms.EDIT), requestId, s(body, "reason"));
        return AjaxResult.success(String.valueOf(out.get("message")), out);
    }

    /** 本城待审核的商家活动 */
    @AppAuth(staff = true, merchantService = true)
    @GetMapping("/service/activities")
    public AjaxResult serviceActivities(@RequestParam(required = false) Long storeId, @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer pageNum, @RequestParam(required = false) Integer pageSize)
    {
        return AjaxResult.success(requests.serviceActivities(requests.serviceCtx(storeId, StaffPerms.VIEW), status, pageNum, pageSize));
    }

    /** 通过（pass = true）/ 驳回（pass = false，reason 必填，会发给商家） */
    @AppAuth(staff = true, merchantService = true)
    @PostMapping("/service/activities/{activityId:[0-9]+}/audit")
    public AjaxResult serviceActivityAudit(@PathVariable Long activityId, @RequestParam(required = false) Long storeId, @RequestBody(required = false) Map<String, Object> body)
    {
        Map<String, Object> out = requests.serviceActivityAudit(requests.serviceCtx(storeId, StaffPerms.EDIT), activityId, bool(body, "pass"), s(body, "reason"));
        return AjaxResult.success(String.valueOf(out.get("message")), out);
    }

    // ---------------------------------------------------------------- 城市负责人（只读）

    @AppAuth(staff = true, cityLead = true)
    @GetMapping("/city/requests")
    public AjaxResult cityList(@RequestParam(required = false) Long storeId, @RequestParam(required = false) String status,
            @RequestParam(required = false) String type, @RequestParam(required = false) Integer pageNum, @RequestParam(required = false) Integer pageSize)
    {
        return AjaxResult.success(requests.cityList(requests.cityCtx(storeId), status, type, pageNum, pageSize));
    }

    @AppAuth(staff = true, cityLead = true)
    @GetMapping("/city/requests/{requestId:[0-9]+}")
    public AjaxResult cityDetail(@PathVariable Long requestId, @RequestParam(required = false) Long storeId)
    {
        return AjaxResult.success(requests.cityDetail(requests.cityCtx(storeId), requestId));
    }

    /** 各店概况：和「当地换工招募概况」同一份数据，城市负责人名下没有门店操作权，名单上一律 canManage = false */
    @AppAuth(staff = true, cityLead = true)
    @GetMapping("/city/overview")
    public AjaxResult cityOverview(@RequestParam(required = false) Long storeId)
    {
        return AjaxResult.success(daily.overview(requests.cityCtx(storeId).storeId));
    }

    // ---------------------------------------------------------------- 合作商家

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/merchant/requests")
    public AjaxResult merchantList(@RequestParam(required = false) Long storeId, @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer pageNum, @RequestParam(required = false) Integer pageSize)
    {
        return AjaxResult.success(requests.merchantList(requests.merchantCtx(storeId), status, pageNum, pageSize));
    }

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/merchant/requests/{requestId:[0-9]+}")
    public AjaxResult merchantDetail(@PathVariable Long requestId, @RequestParam(required = false) Long storeId)
    {
        return AjaxResult.success(requests.merchantDetail(requests.merchantCtx(storeId), requestId));
    }

    /** 申请代运营 / 抖音卖房 */
    @AppAuth(staff = true, merchant = true)
    @PostMapping("/merchant/requests")
    public AjaxResult merchantSubmit(@RequestParam(required = false) Long storeId, @RequestBody(required = false) Map<String, Object> body)
    {
        StaffSupport.Ctx c = requests.merchantCtx(storeId);
        limiter.check("staff:merchant-request", String.valueOf(c.uid()), 20, 3600, "提交太频繁了，稍后再试");
        Map<String, Object> out = requests.merchantSubmit(c, body);
        return AjaxResult.success(String.valueOf(out.get("message")), out);
    }

    /** 被退回后修改再提交 */
    @AppAuth(staff = true, merchant = true)
    @PutMapping("/merchant/requests/{requestId:[0-9]+}")
    public AjaxResult merchantResubmit(@PathVariable Long requestId, @RequestParam(required = false) Long storeId, @RequestBody(required = false) Map<String, Object> body)
    {
        StaffSupport.Ctx c = requests.merchantCtx(storeId);
        limiter.check("staff:merchant-request", String.valueOf(c.uid()), 20, 3600, "提交太频繁了，稍后再试");
        Map<String, Object> out = requests.merchantResubmit(c, requestId, body);
        return AjaxResult.success(String.valueOf(out.get("message")), out);
    }
}
