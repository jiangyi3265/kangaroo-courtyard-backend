package com.ruoyi.kangaroo.controller.admin;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgActivityService;
import com.ruoyi.kangaroo.ext.community.KgPostService;
import com.ruoyi.kangaroo.ext.community.KgTripService;

/**
 * 社区后台动作：动态 / 评论 / 活动 / 公开行程审核，活动取消，活动报名退款审批。列表与字段编辑走通用 ExtCrud。
 */
@RestController
@RequestMapping("/kangaroo/community")
public class KgCommunityAdminController extends BaseController
{
    @Autowired
    private KgPostService posts;

    @Autowired
    private KgActivityService activities;

    @Autowired
    private KgTripService trips;

    @Autowired
    private KgJdbc db;

    private static String reason(Map<String, Object> body)
    {
        String r = body == null ? null : KgJdbc.asStr(body.get("reason"));
        return r == null ? "" : r.trim();
    }

    private static String who()
    {
        return "admin:" + SecurityUtils.getUsername();
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:post:audit')")
    @Log(title = "社区动态审核", businessType = BusinessType.UPDATE)
    @PutMapping("/post/{id}/approve")
    public AjaxResult approvePost(@PathVariable Long id)
    {
        return AjaxResult.success(posts.audit(id, true, null, who()));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:post:audit')")
    @Log(title = "社区动态驳回/隐藏", businessType = BusinessType.UPDATE)
    @PutMapping("/post/{id}/reject")
    public AjaxResult rejectPost(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body)
    {
        return AjaxResult.success(posts.audit(id, false, reason(body), who()));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:postcomment:audit')")
    @Log(title = "社区评论展示", businessType = BusinessType.UPDATE)
    @PutMapping("/comment/{id}/show")
    public AjaxResult showComment(@PathVariable Long id)
    {
        return AjaxResult.success(posts.auditComment(id, true, "审核通过", who()));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:postcomment:audit')")
    @Log(title = "社区评论隐藏", businessType = BusinessType.UPDATE)
    @PutMapping("/comment/{id}/hide")
    public AjaxResult hideComment(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body)
    {
        String r = reason(body);
        if (r.isEmpty())
        {
            throw new ServiceException("请填写隐藏原因", 422);
        }
        return AjaxResult.success(posts.auditComment(id, false, r, who()));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:activity:audit')")
    @Log(title = "社区活动审核", businessType = BusinessType.UPDATE)
    @PutMapping("/activity/{id}/approve")
    public AjaxResult approveActivity(@PathVariable Long id)
    {
        return AjaxResult.success(activities.audit(id, true, null, who()));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:activity:audit')")
    @Log(title = "社区活动驳回", businessType = BusinessType.UPDATE)
    @PutMapping("/activity/{id}/reject")
    public AjaxResult rejectActivity(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body)
    {
        return AjaxResult.success(activities.audit(id, false, reason(body), who()));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:activity:audit')")
    @Log(title = "社区活动取消", businessType = BusinessType.UPDATE)
    @PutMapping("/activity/{id}/cancel")
    public AjaxResult cancelActivity(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body)
    {
        // 不存在按 404 报（db.must 不带码，接口会是 500）
        Map<String, Object> a = db.one("select * from kg_activity where activity_id = :a and del_flag = '0'", KgJdbc.p("a", id));
        if (a == null) throw new ServiceException("活动不存在", 404);
        return AjaxResult.success(activities.transition(a, "cancel", reason(body), who()));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:actsignup:audit')")
    @Log(title = "活动报名退款同意", businessType = BusinessType.UPDATE)
    @PutMapping("/signup/{id}/refund-approve")
    public AjaxResult refundApprove(@PathVariable Long id)
    {
        return AjaxResult.success(activities.approveRefund(id, who()));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:actsignup:audit')")
    @Log(title = "活动报名退款驳回", businessType = BusinessType.UPDATE)
    @PutMapping("/signup/{id}/refund-reject")
    public AjaxResult refundReject(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body)
    {
        return AjaxResult.success(activities.rejectRefund(id, reason(body), who()));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:tripplan:audit')")
    @Log(title = "公开行程审核", businessType = BusinessType.UPDATE)
    @PutMapping("/plan/{id}/approve")
    public AjaxResult approvePlan(@PathVariable Long id)
    {
        return AjaxResult.success(trips.auditPlan(id, true, null, who()));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:tripplan:audit')")
    @Log(title = "公开行程驳回", businessType = BusinessType.UPDATE)
    @PutMapping("/plan/{id}/reject")
    public AjaxResult rejectPlan(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body)
    {
        return AjaxResult.success(trips.auditPlan(id, false, reason(body), who()));
    }
}
