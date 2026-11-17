package com.ruoyi.kangaroo.controller.app;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgActivityService;
import com.ruoyi.kangaroo.ext.staff.StaffPerms;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.security.AppAuth;

/**
 * 员工端活动管理：本店活动列表、创建 / 修改、报名数据、详情与现场操作（暂停 / 结束 / 取消 / 通知 / 签到）、退款审批。
 */
@RestController
@RequestMapping("/app/staff/activities")
public class AppStaffActivityController
{
    @Autowired
    private KgActivityService activities;

    @Autowired
    private StaffSupport support;

    @Autowired
    private KgJdbc db;

    private static String s(Map<String, Object> b, String k)
    {
        return b == null ? null : KgJdbc.asStr(b.get(k));
    }

    private StaffSupport.Ctx ctxOf(Long activityId)
    {
        Map<String, Object> a = db.one("select store_id from kg_activity where activity_id = :a and del_flag = '0'", KgJdbc.p("a", activityId));
        if (a == null || a.get("storeId") == null)
        {
            throw new ServiceException("活动不存在或不是门店活动", 404);
        }
        return support.ctx(KgJdbc.asLong(a.get("storeId")));
    }

    @AppAuth(staff = true, merchant = true)
    @GetMapping
    public AjaxResult list(@RequestParam(required = false) Long storeId, @RequestParam(required = false) String tab,
            @RequestParam(required = false) String source, @RequestParam(required = false) String keyword, @RequestParam(required = false) String sort)
    {
        return AjaxResult.success(activities.staffList(support.ctx(storeId), tab, source, keyword, sort));
    }

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/{id}")
    public AjaxResult detail(@PathVariable Long id)
    {
        StaffSupport.Ctx c = ctxOf(id);
        Map<String, Object> a = activities.ownStoreActivity(c, id);
        Map<String, Object> m = activities.manageView(a);
        activities.attachCheckinCode(m, a);
        m.put("canAudit", com.ruoyi.kangaroo.ext.staff.StaffPerms.allows(c.role, "activities", StaffPerms.AUDIT));
        m.put("canPay", !support.autoManager(c) && StaffSupport.canFund(c));
        return AjaxResult.success(m);
    }

    @AppAuth(staff = true, merchant = true)
    @PostMapping
    public AjaxResult create(@RequestParam(required = false) Long storeId, @RequestBody Map<String, Object> body)
    {
        StaffSupport.Ctx c = support.ctx(storeId);
        support.need(c, "activities", StaffPerms.EDIT, "activity_create", "创建活动");
        return AjaxResult.success(activities.staffSave(c, null, body));
    }

    @AppAuth(staff = true, merchant = true)
    @PutMapping("/{id}")
    public AjaxResult edit(@PathVariable Long id, @RequestBody Map<String, Object> body)
    {
        StaffSupport.Ctx c = ctxOf(id);
        support.need(c, "activities", StaffPerms.EDIT, "activity_edit", "修改活动");
        return AjaxResult.success(activities.staffSave(c, id, body));
    }

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/{id}/data")
    public AjaxResult data(@PathVariable Long id, @RequestParam(required = false) String from, @RequestParam(required = false) String to)
    {
        StaffSupport.Ctx c = ctxOf(id);
        return AjaxResult.success(activities.data(activities.ownStoreActivity(c, id), from, to));
    }

    @AppAuth(staff = true, merchant = true)
    @PostMapping("/{id}/action/{action}")
    public AjaxResult action(@PathVariable Long id, @PathVariable String action, @RequestBody(required = false) Map<String, Object> body)
    {
        StaffSupport.Ctx c = ctxOf(id);
        // 普通员工对活动只有「部分编辑」：暂停 / 恢复 / 结束报名这类日常操作可以；
        // 取消整场活动会批量退款并通知全部报名人，只给有「活动·审核」权限的主理人 / 管家（2026-09-26 回归发现 staff 也能取消）
        support.need(c, "activities", "cancel".equals(action) ? StaffPerms.AUDIT : StaffPerms.EDIT, "activity_" + action, "活动操作：" + action);
        if ("cancel".equals(action) && db.count("select count(*) from kg_activity where activity_id = :a and (fee_fen > 0 or signed > 0)", KgJdbc.p("a", id)) > 0)
        {
            // 取消有人报名 / 收费的活动会批量退款并通知全部报名人：只有主理人（管家可以取消没人报名的空活动）；商家需求自动补的换工主理人不算（needManager 里挡）
            if (com.ruoyi.kangaroo.enums.StaffRole.MANAGER.equals(c.role))
                support.needManager(c, "activity_cancel", "取消已有报名的活动");
            else
                support.need(c, "activities", StaffPerms.PAY, "activity_cancel", "取消已有报名的活动");
        }
        String msg = activities.transition(activities.ownStoreActivity(c, id), action, s(body, "reason"), "staff:" + c.uid(), StaffSupport.canFund(c));
        support.op(c, "activity_" + action, "活动操作", "general", id + "：" + msg, true, null);
        return AjaxResult.success(msg);
    }

    @AppAuth(staff = true, merchant = true)
    @PostMapping("/{id}/broadcast")
    public AjaxResult broadcast(@PathVariable Long id, @RequestBody Map<String, Object> body)
    {
        StaffSupport.Ctx c = ctxOf(id);
        support.need(c, "activities", StaffPerms.EDIT, "activity_notice", "活动现场通知");
        return AjaxResult.success(activities.broadcast(activities.ownStoreActivity(c, id), s(body, "text"), c.storeName));
    }

    @AppAuth(staff = true, merchant = true)
    @PostMapping("/{id}/checkin")
    public AjaxResult checkin(@PathVariable Long id, @RequestBody Map<String, Object> body)
    {
        StaffSupport.Ctx c = ctxOf(id);
        return AjaxResult.success(activities.checkinByCode(activities.ownStoreActivity(c, id), s(body, "code")));
    }

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/{id}/members/{signupId}/phone")
    public AjaxResult phone(@PathVariable Long id, @PathVariable Long signupId)
    {
        StaffSupport.Ctx c = ctxOf(id);
        support.need(c, "activities", StaffPerms.EDIT, "activity_phone", "查看参与者电话");
        return AjaxResult.success(activities.revealPhone(c.uid(), activities.ownStoreActivity(c, id), signupId));
    }

    @AppAuth(staff = true)
    @PostMapping("/{id}/refunds/{signupId}/{decision}")
    public AjaxResult refund(@PathVariable Long id, @PathVariable Long signupId, @PathVariable String decision,
            @RequestBody(required = false) Map<String, Object> body)
    {
        StaffSupport.Ctx c = ctxOf(id);
        activities.ownStoreActivity(c, id);
        if (db.count("select count(*) from kg_activity_signup where signup_id = :s and activity_id = :a", KgJdbc.p("s", signupId, "a", id)) == 0)
        {
            throw new ServiceException("报名记录不存在", 404);
        }
        if (com.ruoyi.kangaroo.enums.StaffRole.CITY_LEAD.equals(c.role)
                && db.count("select count(*) from kg_activity_signup where signup_id = :s and app_user_id = :u", KgJdbc.p("s", signupId, "u", c.uid())) > 0)
        {
            support.op(c, "activity_refund", "活动退款审批", "fund", String.valueOf(signupId), false, "不能审批自己的报名");
            throw new ServiceException("这是你自己的报名，请让平台处理", 403);
        }
        support.need(c, "activities", StaffPerms.AUDIT, "activity_refund", "活动退款审批");
        if (com.ruoyi.kangaroo.enums.StaffRole.MANAGER.equals(c.role))
            support.needManager(c, "activity_refund", "活动退款审批");
        else
            support.need(c, "activities", StaffPerms.PAY, "activity_refund", "活动退款审批");
        String msg = "approve".equals(decision) ? activities.approveRefund(signupId, "staff:" + c.uid())
                : activities.rejectRefund(signupId, s(body, "reason"), "staff:" + c.uid());
        support.op(c, "activity_refund", "活动退款审批", "fund", signupId + "：" + msg, true, null);
        return AjaxResult.success(msg);
    }
}
