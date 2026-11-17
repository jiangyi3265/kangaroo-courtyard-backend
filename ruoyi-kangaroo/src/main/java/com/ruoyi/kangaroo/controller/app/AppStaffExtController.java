package com.ruoyi.kangaroo.controller.app;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.KgOnlineTaskService;
import com.ruoyi.kangaroo.ext.staff.KgStaffInterviewService;
import com.ruoyi.kangaroo.ext.staff.KgStaffReviewService;
import com.ruoyi.kangaroo.ext.staff.KgTalentService;
import com.ruoyi.kangaroo.ext.staff.StaffPerms;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppRateLimiter;
import com.ruoyi.kangaroo.security.AppUserContext;

/** 员工端扩展：评价运营、人才库、报名详情补充（简历、面试）、线上任务（门店侧） */
@RestController
@RequestMapping("/app/staff")
public class AppStaffExtController
{
    @Autowired
    private KgStaffReviewService reviews;

    @Autowired
    private KgTalentService talents;

    @Autowired
    private KgOnlineTaskService online;

    @Autowired
    private KgStaffInterviewService interviews;

    @Autowired
    private StaffSupport support;

    @Autowired
    private KgJdbc db;

    @Autowired
    private AppRateLimiter limiter;

    private static String s(Map<String, Object> b, String k)
    {
        return b == null ? null : KgJdbc.asStr(b.get(k));
    }

    private StaffSupport.Ctx reviewCtx(Long id)
    {
        Map<String, Object> r = db.one("select store_id from kg_review where review_id = :id and del_flag = '0'", KgJdbc.p("id", id));
        if (r == null || r.get("storeId") == null)
        {
            throw new ServiceException("评价不存在", 404);
        }
        return support.ctx(KgJdbc.asLong(r.get("storeId")));
    }

    private StaffSupport.Ctx appCtx(Long appId)
    {
        Map<String, Object> r = db.one("select j.store_id from kg_application a join kg_job j on j.job_id = a.job_id where a.app_id = :id and a.del_flag='0' and j.job_type='online'", KgJdbc.p("id", appId));
        if (r == null)
        {
            throw new ServiceException("接单记录不存在", 404);
        }
        return support.ctx(KgJdbc.asLong(r.get("storeId")));
    }

    // ---------------------------------------------------------------- 评价运营

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/reviews")
    public AjaxResult reviews(@RequestParam(required = false) Long storeId, @RequestParam(required = false) String tab, @RequestParam(required = false) String source,
            @RequestParam(required = false) String stars, @RequestParam(required = false) String days, @RequestParam(required = false) String filter)
    {
        StaffSupport.Ctx c = support.ctx(storeId);
        support.need(c, "reviews", StaffPerms.VIEW, "review_view", "查看评价");
        return AjaxResult.success(reviews.list(c, tab, source, stars, days, filter));
    }

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/reviews/{id}")
    public AjaxResult review(@PathVariable Long id)
    {
        StaffSupport.Ctx c = reviewCtx(id);
        support.need(c, "reviews", StaffPerms.VIEW, "review_view", "查看评价");
        return AjaxResult.success(reviews.detail(c, id));
    }

    @AppAuth(staff = true, merchant = true)
    @PutMapping("/reviews/{id}/note")
    public AjaxResult note(@PathVariable Long id, @RequestBody Map<String, Object> body)
    {
        StaffSupport.Ctx c = reviewCtx(id);
        support.need(c, "reviews", StaffPerms.EDIT, "review_note", "评价内部备注");
        return AjaxResult.success(reviews.note(c, id, s(body, "note")));
    }

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/reviews/{id}/draft")
    public AjaxResult draft(@PathVariable Long id, @RequestParam(required = false) String tone, @RequestParam(defaultValue = "0") int seed)
    {
        StaffSupport.Ctx c = reviewCtx(id);
        support.need(c, "reviews", StaffPerms.EDIT, "review_reply", "回复评价");
        return AjaxResult.success(reviews.draft(c, id, tone, seed));
    }

    @AppAuth(staff = true, merchant = true)
    @PostMapping("/reviews/{id}/reply")
    public AjaxResult reply(@PathVariable Long id, @RequestBody Map<String, Object> body)
    {
        StaffSupport.Ctx c = reviewCtx(id);
        support.need(c, "reviews", StaffPerms.EDIT, "review_reply", "回复评价");
        limiter.check("staff:review-reply", String.valueOf(c.uid()), 60, 3600, "回复太频繁了，稍后再试");
        return AjaxResult.success(reviews.reply(c, id, s(body, "content")));
    }

    @AppAuth(staff = true)
    @SuppressWarnings("unchecked")
    @PostMapping("/reviews/{id}/feature")
    public AjaxResult feature(@PathVariable Long id, @RequestBody Map<String, Object> body)
    {
        StaffSupport.Ctx c = reviewCtx(id);
        support.need(c, "reviews", StaffPerms.AUDIT, "review_feature", "精选评价");
        return AjaxResult.success(reviews.feature(c, id, Boolean.TRUE.equals(body.get("featured")), (List<String>) body.get("reasons"), s(body, "note")));
    }

    @AppAuth(staff = true, merchant = true)
    @PostMapping("/reviews/{id}/report")
    public AjaxResult report(@PathVariable Long id, @RequestBody Map<String, Object> body)
    {
        StaffSupport.Ctx c = reviewCtx(id);
        support.need(c, "reviews", StaffPerms.VIEW, "review_report", "提请平台处理评价");
        return AjaxResult.success(reviews.report(c, id, s(body, "reason")));
    }

    // ---------------------------------------------------------------- 人才库

    @AppAuth(staff = true)
    @GetMapping("/talents/tags")
    public AjaxResult talentTags(@RequestParam(required = false) Long storeId)
    {
        StaffSupport.Ctx c = support.ctx(storeId);
        support.need(c, "jobs", StaffPerms.VIEW, "talent_view", "查看人才库");
        return AjaxResult.success(talents.tags(c));
    }

    @AppAuth(staff = true)
    @PostMapping("/talents/search")
    public AjaxResult talentSearch(@RequestParam(required = false) Long storeId, @RequestParam(defaultValue = "false") boolean countOnly, @RequestBody Map<String, Object> body)
    {
        StaffSupport.Ctx c = support.ctx(storeId);
        support.need(c, "jobs", StaffPerms.VIEW, "talent_view", "查看人才库");
        return AjaxResult.success(talents.search(c, body, countOnly));
    }

    @AppAuth(staff = true)
    @GetMapping("/talents/jobs")
    public AjaxResult talentJobs(@RequestParam(required = false) Long storeId)
    {
        return AjaxResult.success(talents.openJobs(support.ctx(storeId)));
    }

    @AppAuth(staff = true)
    @GetMapping("/talents/{userId}")
    public AjaxResult talent(@PathVariable Long userId, @RequestParam(required = false) Long storeId)
    {
        StaffSupport.Ctx c = support.ctx(storeId);
        support.need(c, "jobs", StaffPerms.EDIT, "talent_profile", "查看人才档案");
        limiter.check("staff:talent-profile", String.valueOf(c.uid()), 120, 3600, "查看太频繁了，稍后再试");
        return AjaxResult.success(talents.profile(c, userId));
    }

    @AppAuth(staff = true)
    @PostMapping("/talents/{userId}/mark")
    public AjaxResult mark(@PathVariable Long userId, @RequestParam(required = false) Long storeId, @RequestBody Map<String, Object> body)
    {
        StaffSupport.Ctx c = support.ctx(storeId);
        support.need(c, "jobs", StaffPerms.EDIT, "talent_mark", "标记人才");
        return AjaxResult.success(talents.mark(c, userId, s(body, "what"), Boolean.TRUE.equals(body.get("on"))));
    }

    @AppAuth(staff = true)
    @PostMapping("/talents/{userId}/invite")
    public AjaxResult invite(@PathVariable Long userId, @RequestParam(required = false) Long storeId, @RequestBody Map<String, Object> body)
    {
        StaffSupport.Ctx c = support.ctx(storeId);
        support.need(c, "jobs", StaffPerms.EDIT, "talent_invite", "邀请人才报名");
        return AjaxResult.success(talents.invite(c, userId, KgJdbc.asLong(body.get("jobId"))));
    }

    // ---------------------------------------------------------------- 报名详情补充

    /**
     * 员工端报名详情的补充信息（和 /applications/{appId} 一起取）：报名人简历里的技能描述与经历、
     * 这条报名约的面试（编号、时间）和候选人面试后填的问卷。之前门店审报名时「经历记录」永远是空的，
     * 「面试已预约」点进来找不到这场面试，候选人选了「无法按时到岗」门店也看不到。
     * 报名详情本身在 AppStaffController，这里只补它没有的几块；门店授权按报名所属门店校验，与报名详情同一口径
     */
    @AppAuth(staff = true, merchant = true)
    @GetMapping("/applications/{appId}/extras")
    public AjaxResult applicationExtras(@PathVariable Long appId)
    {
        Map<String, Object> a = db.one("select a.store_id, a.app_user_id, j.job_type from kg_application a left join kg_job j on j.job_id = a.job_id "
                + "where a.app_id = :id and a.del_flag = '0'", KgJdbc.p("id", appId));
        if (a == null)
        {
            throw new ServiceException("报名不存在", 404);
        }
        StaffSupport.Ctx c = support.ctxForStore(KgJdbc.asLong(a.get("storeId")));
        support.need(c, "jobs", StaffPerms.VIEW, "application_view", "查看报名详情");
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        // 线上任务不看简历经历、也没有面试
        boolean online = "online".equals(a.get("jobType"));
        out.put("resume", online ? null : talents.resumeBrief(KgJdbc.asLong(a.get("appUserId"))));
        out.put("interview", online ? null : interviews.ofApplication(appId));
        return AjaxResult.success(out);
    }

    // ---------------------------------------------------------------- 线上任务（门店侧）

    @AppAuth(staff = true, onlineStaff = true, merchant = true, merchantService = true)
    @GetMapping("/online/tasks")
    public AjaxResult onlineTasks(@RequestParam(required = false) Long storeId)
    {
        StaffSupport.Ctx c = support.ctx(storeId);
        support.need(c, "online", StaffPerms.VIEW, "online_view", "查看线上任务");
        return AjaxResult.success(online.tasks(c));
    }

    /**
     * 发布线上任务：主理人、管家和线上任务专员（2026-10 主人拍板：专员只能发本店的线上任务）。
     * 查「线上任务·编辑」：主理人/管家/员工这一行和「岗位管理」完全一样（StaffPerms），他们的结果不变；
     * 门店仍按这个人自己在所传门店上的授权取（StaffSupport.ctx），别家门店照样 403。
     * 发布后照旧是草稿、由平台审核上架；专员发的另外通知本店主理人（KgOnlineTaskService.create）。
     * 线下岗位（POST /app/staff/jobs）仍不对专员开放。
     */
    @AppAuth(staff = true, onlineStaff = true, merchant = true, merchantService = true)
    @PostMapping("/online/tasks")
    public AjaxResult onlineCreate(@RequestParam(required = false) Long storeId, @RequestBody Map<String, Object> body)
    {
        StaffSupport.Ctx c = support.ctx(storeId);
        support.need(c, "online", StaffPerms.EDIT, "online_create", "发布线上任务");
        limiter.check("staff:online-create", String.valueOf(c.uid()), 20, 3600, "发布太频繁了，稍后再试");
        return AjaxResult.success(online.create(c, body));
    }

    @AppAuth(staff = true, onlineStaff = true, merchant = true, merchantService = true)
    @GetMapping("/online/tasks/{jobId}")
    public AjaxResult onlineTask(@PathVariable Long jobId, @RequestParam(required = false) Long storeId)
    {
        StaffSupport.Ctx c = support.ctx(storeId);
        support.need(c, "online", StaffPerms.VIEW, "online_view", "查看线上任务");
        return AjaxResult.success(online.detail(c, jobId));
    }

    @AppAuth(staff = true, onlineStaff = true, merchant = true, merchantService = true)
    @GetMapping("/online/tasks/{jobId}/assignments")
    public AjaxResult onlineAssignments(@PathVariable Long jobId, @RequestParam(required = false) Long storeId)
    {
        StaffSupport.Ctx c = support.ctx(storeId);
        support.need(c, "online", StaffPerms.VIEW, "online_view", "查看线上任务");
        return AjaxResult.success(online.assignments(c, jobId));
    }

    @AppAuth(staff = true, onlineStaff = true, merchant = true, merchantService = true)
    @GetMapping("/online/assignments/{appId}")
    public AjaxResult onlineAssignment(@PathVariable Long appId)
    {
        StaffSupport.Ctx c = appCtx(appId);
        support.need(c, "online", StaffPerms.VIEW, "online_view", "查看线上任务");
        return AjaxResult.success(online.assignment(c, appId));
    }

    @AppAuth(staff = true, onlineStaff = true, merchant = true, merchantService = true)
    @PostMapping("/online/assignments/{appId}/accept")
    public AjaxResult onlineAccept(@PathVariable Long appId, @RequestBody(required = false) Map<String, Object> body)
    {
        StaffSupport.Ctx c = appCtx(appId);
        support.need(c, "online", StaffPerms.EDIT, "online_accept", "线上任务验收");
        return AjaxResult.success(online.review(c, appId, true, s(body, "comment"),null,body==null?null:body.get("qualifiedCount")));
    }

    @AppAuth(staff = true, onlineStaff = true, merchant = true, merchantService = true)
    @PostMapping("/online/assignments/{appId}/return")
    public AjaxResult onlineReturn(@PathVariable Long appId, @RequestBody Map<String, Object> body)
    {
        StaffSupport.Ctx c = appCtx(appId);
        support.need(c, "online", StaffPerms.EDIT, "online_return", "线上任务退回");
        // body = {reason: 退回说明（5000 字内）, images: [平台图片地址，最多 9 张截图]}，两样至少有一样
        return AjaxResult.success(online.review(c, appId, false, s(body, "reason"), body == null ? null : body.get("images")));
    }

    /**
     * 登记线下付款。之前只认「财务·支付」（只有换工主理人有），而换工主理人进不了 /online，谁都登记不了：
     * 现在开放给这家店的合作商家（付钱方）和商家服务人，其他角色不变；兼任的小院主理人只在商家的任务上算商家服务人。
     */
    @AppAuth(staff = true, merchant = true, merchantService = true)
    @PostMapping("/online/assignments/{appId}/payout")
    public AjaxResult onlinePayout(@PathVariable Long appId, @RequestBody Map<String, Object> body)
    {
        StaffSupport.Ctx c = appCtx(appId);
        // 商家店正好是他兼任商家服务人的小院时，商家的任务按商家服务人算（KgOnlineTaskService.payoutRole）
        if (!StaffPerms.canRegisterOnlinePayout(online.payoutRoleOfApp(c, appId)))
        {
            support.need(c, "finance", StaffPerms.PAY, "online_payout", "登记线下付款");
        }
        limiter.check("staff:online-payout", String.valueOf(AppUserContext.getUserId()), 30, 3600, "操作太频繁了，稍后再试");
        return AjaxResult.success(online.payout(c, appId, body));
    }
}
