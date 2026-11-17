package com.ruoyi.kangaroo.controller.app;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.KgDispatchService;
import com.ruoyi.kangaroo.ext.staff.KgInsuranceService;
import com.ruoyi.kangaroo.ext.staff.KgStaffInterviewService;
import com.ruoyi.kangaroo.ext.staff.KgStaffJobService;
import com.ruoyi.kangaroo.ext.staff.KgStaffTeamService;
import com.ruoyi.kangaroo.ext.staff.StaffPerms;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.security.AppAuth;

/**
 * 员工工作台扩展接口：面试日程、投保、岗位发布与分享追踪、调度、门店与角色。
 *
 * storeId 参数只是"想看哪家门店"，StaffSupport.ctx 会校验它在授权范围内；
 * 针对某条岗位/报名/面试的操作，一律以那条记录所属门店的授权与角色为准。
 */
@RestController
@RequestMapping("/app/staff")
@AppAuth(staff = true)
public class AppStaffOpsController
{
    @Autowired
    private StaffSupport support;

    @Autowired
    private KgJdbc db;

    @Autowired
    private KgStaffInterviewService interviews;

    @Autowired
    private KgInsuranceService insurance;

    @Autowired
    private KgStaffJobService jobs;

    @Autowired
    private com.ruoyi.kangaroo.security.AppRateLimiter limiter;

    @Autowired
    private KgDispatchService dispatch;

    @Autowired
    private KgStaffTeamService team;

    private StaffSupport.Ctx ctxOf(String table, String pk, Long id)
    {
        Map<String, Object> r = db.one("select store_id from " + table + " where " + pk + " = :id", p("id", id));
        if (r == null)
        {
            throw new ServiceException("记录不存在");
        }
        return support.ctx(KgJdbc.asLong(r.get("storeId")));
    }

    private static String s(Map<String, Object> body, String k)
    {
        return body == null ? null : KgJdbc.asStr(body.get(k));
    }

    private static Long l(Map<String, Object> body, String k)
    {
        return body == null ? null : KgJdbc.asLong(body.get(k));
    }

    // ---------- 面试日程 ----------

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/interviews")
    public AjaxResult interviewList(@RequestParam(required = false) Long storeId, @RequestParam(required = false) String range,
            @RequestParam(required = false) String date)
    {
        return AjaxResult.success(interviews.schedule(support.ctx(storeId), range, date));
    }

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/interviews/{id}")
    public AjaxResult interviewDetail(@PathVariable Long id)
    {
        return AjaxResult.success(interviews.detail(ctxOfInterview(id), id));
    }

    private StaffSupport.Ctx ctxOfInterview(Long id)
    {
        Map<String, Object> r = db.one("select a.store_id from kg_interview i join kg_application a on a.app_id = i.app_id where i.interview_id = :id",
                p("id", id));
        if (r == null)
        {
            throw new ServiceException("面试不存在");
        }
        return support.ctx(KgJdbc.asLong(r.get("storeId")));
    }

    @AppAuth(staff = true, merchant = true)
    @PostMapping("/interviews/{id}/reschedule")
    public AjaxResult reschedule(@PathVariable Long id, @RequestBody Map<String, Object> body)
    {
        Map<String, Object> r = interviews.reschedule(ctxOfInterview(id), id, l(body, "slotId"));
        return AjaxResult.success("已改期至 " + r.get("when"), r);
    }

    @AppAuth(staff = true, merchant = true)
    @PutMapping("/interviews/{id}/meeting")
    public AjaxResult meeting(@PathVariable Long id, @RequestBody Map<String,Object> body)
    {
        return AjaxResult.success("会议信息已保存，候选人可在面试详情查看",interviews.meeting(ctxOfInterview(id),id,s(body,"meetingInfo")));
    }

    @AppAuth(staff = true, merchant = true)
    @PostMapping("/interviews/{id}/result")
    public AjaxResult result(@PathVariable Long id, @RequestBody Map<String, Object> body)
    {
        interviews.result(ctxOfInterview(id), id, s(body, "result"));
        return AjaxResult.success("面试结果已登记");
    }

    @AppAuth(staff = true, merchant = true)
    @PostMapping("/interviews/{id}/rate")
    public AjaxResult rate(@PathVariable Long id, @RequestBody Map<String, Object> body)
    {
        interviews.rate(ctxOfInterview(id), id, StaffSupport.toInt(body == null ? null : body.get("rating"), 0), s(body, "note"));
        return AjaxResult.success("评价已保存");
    }

    @AppAuth(staff = true, merchant = true)
    @PostMapping("/interviews/{id}/finish")
    public AjaxResult finish(@PathVariable Long id)
    {
        interviews.finish(ctxOfInterview(id), id);
        return AjaxResult.success("已标记面试结束，等待候选人确认");
    }

    private StaffSupport.Ctx insuranceContext(Long appId) {
        Map<String,Object> row = db.must("select coalesce(j.recruit_store_id,a.store_id) store_id from kg_application a "
                + "join kg_job j on j.job_id=a.job_id where a.app_id=:id and a.del_flag='0'", p("id",appId),"报名不存在");
        return support.ctx(KgJdbc.asLong(row.get("storeId")));
    }

    // ---------- 保险 ----------

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/insurance")
    public AjaxResult insuranceOverview(@RequestParam(required = false) Long storeId, @RequestParam(required = false) String tab)
    {
        return AjaxResult.success(insurance.overview(support.ctx(storeId), tab));
    }

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/insurance/plans")
    public AjaxResult insurancePlans()
    {
        support.ctx(null);
        return AjaxResult.success(insurance.plans());
    }

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/insurance/app/{appId}")
    public AjaxResult insuranceForApp(@PathVariable Long appId)
    {
        return AjaxResult.success(insurance.forApplication(insuranceContext(appId), appId));
    }

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/insurance/to-buy")
    public AjaxResult insuranceToBuy(@RequestParam(required = false) Long storeId)
    {
        return AjaxResult.success(insurance.toBuy(support.ctx(storeId)));
    }

    /** storeId 为空 = 本账号有授权的全部门店 */
    @AppAuth(staff = true, merchant = true)
    @GetMapping("/insurance/policies")
    public AjaxResult insurancePolicies(@RequestParam(required = false) Long storeId, @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword, @RequestParam(defaultValue = "0") int days,
            @RequestParam(required = false) String sort)
    {
        List<Long> ids = new ArrayList<Long>();
        if (storeId != null)
        {
            ids.add(support.ctx(storeId).storeId);
        }
        else
        {
            for (com.ruoyi.kangaroo.security.StaffGrantView g : support.ctx(null).me.getStaffGrants())
            {
                ids.add(g.getStoreId());
            }
        }
        return AjaxResult.success(insurance.policies(ids, status, keyword, days, sort));
    }

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/insurance/{applyId}/notices")
    public AjaxResult insuranceNotices(@PathVariable Long applyId)
    {
        return AjaxResult.success(insurance.notices(ctxOf("kg_insurance_apply", "apply_id", applyId), applyId));
    }

    /**
     * 主理人登记保单号（之前只能平台在后台登记）。只认这条申请所在门店的主理人，其他角色只能查看。
     * storeId 可不传：按申请所在门店取身份；传了就按那家门店取，和申请门店不一致时服务层返回 403。
     * needManager 放在事务外调用，拒绝时记的失败日志不会跟着回滚
     */
    @PutMapping("/insurance/applies/{applyId}/policy")
    public AjaxResult insuranceRegisterPolicy(@PathVariable Long applyId, @RequestParam(required = false) Long storeId,
            @RequestBody(required = false) Map<String, Object> body)
    {
        StaffSupport.Ctx c = storeId == null ? ctxOf("kg_insurance_apply", "apply_id", applyId) : support.ctx(storeId);
        support.need(c, "insurance", StaffPerms.AUDIT, "insurance_effect", "登记保单号");
        return AjaxResult.success(insurance.effectByManager(c, applyId, s(body, "policyNo")));
    }

    /** 一键购买：逐条提交投保申请（每条独立成败），起保日期取到店日期与今天中较晚的一天 */
    @AppAuth(staff = true, merchant = true)
    @PostMapping("/insurance/apply-batch")
    public AjaxResult insuranceApplyBatch(@RequestParam(required = false) Long storeId, @RequestBody Map<String, Object> body)
    {
        StaffSupport.Ctx c = support.ctx(storeId);
        support.need(c, "insurance", StaffPerms.EDIT, "insurance_apply", "投保申请");
        Long planId = l(body, "planId");
        List<Map<String, Object>> failed = new ArrayList<Map<String, Object>>();
        int ok = 0;
        Object raw = body == null ? null : body.get("appIds");
        if (raw instanceof List)
        {
            for (Object o : (List<?>) raw)
            {
                Long appId = KgJdbc.asLong(o);
                if (appId == null)
                {
                    continue;
                }
                try
                {
                    StaffSupport.Ctx ac = insuranceContext(appId);
                    // 上面的 need 只校验了入参门店的角色；一个人在 A 店是管家、在 B 店只是普通员工时，
                    // 逐条按该报名所在门店的角色再判一次权
                    support.need(ac, "insurance", StaffPerms.EDIT, "insurance_apply", "投保申请");
                    Map<String, Object> info = insurance.forApplication(ac, appId);
                    insurance.apply(ac, appId, planId, String.valueOf(info.get("defaultStart")));
                    ok++;
                }
                catch (ServiceException e)
                {
                    Map<String, Object> f = new java.util.LinkedHashMap<String, Object>();
                    f.put("appId", appId);
                    f.put("reason", e.getMessage());
                    failed.add(f);
                }
            }
        }
        Map<String, Object> r = new java.util.LinkedHashMap<String, Object>();
        r.put("ok", ok);
        r.put("failed", failed);
        return AjaxResult.success(failed.isEmpty() ? "已提交 " + ok + " 份投保申请" : "已提交 " + ok + " 份，" + failed.size() + " 份未提交", r);
    }

    @AppAuth(staff = true, merchant = true)
    @PostMapping("/insurance/apply")
    public AjaxResult insuranceApply(@RequestBody Map<String, Object> body)
    {
        Long appId = l(body, "appId");
        StaffSupport.Ctx c = insuranceContext(appId);
        support.need(c, "insurance", StaffPerms.EDIT, "insurance_apply", "投保申请");
        Map<String, Object> r = insurance.apply(c, appId, l(body, "planId"), s(body, "startDate"));
        return AjaxResult.success("投保申请已提交；出单后请按保单起止日期核对保障", r);
    }

    // ---------- 岗位 ----------

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/jobs")
    public AjaxResult jobList(@RequestParam(required = false) Long storeId, @RequestParam(required = false) String type,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) String status)
    {
        return AjaxResult.success(jobs.list(support.ctx(storeId), type, keyword, status));
    }

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/jobs/keywords")
    public AjaxResult jobKeywords()
    {
        support.ctx(null);
        return AjaxResult.success(KgStaffJobService.keywordOptions());
    }

    @AppAuth(staff = true, merchant = true)
    @PostMapping("/jobs/describe")
    public AjaxResult describe(@RequestParam(required = false) Long storeId, @RequestBody Map<String, Object> body)
    {
        StaffSupport.Ctx c = support.ctx(storeId);
        return AjaxResult.success(jobs.describe(c, body, StaffSupport.toInt(body.get("variant"), 0)));
    }

    @AppAuth(staff = true, merchant = true)
    @PostMapping("/jobs")
    public AjaxResult createJob(@RequestParam(required = false) Long storeId, @RequestBody Map<String, Object> body)
    {
        StaffSupport.Ctx c = support.ctx(storeId);
        support.need(c, "jobs", StaffPerms.EDIT, "job_create", "发布岗位");
        // 和发布线上任务同一个口径：合作商家现在是自助开通的，每发一条都会给本城员工各发一条消息
        limiter.check("staff:job-create", String.valueOf(c.uid()), 20, 3600, "发布太频繁了，稍后再试");
        // 合作商家发布的走派单（KgMerchantRequestService），提示语跟着实际去向；其他角色照旧
        Map<String, Object> created = jobs.create(c, body);
        Object said = created.get("message");
        return AjaxResult.success(said == null ? "已提交平台审核，审核通过后上架" : String.valueOf(said), created);
    }

    @AppAuth(staff = true, merchant = true)
    @PostMapping("/jobs/batch")
    public AjaxResult batchJobs(@RequestParam(required = false) Long storeId, @RequestBody Map<String, Object> body)
    {
        StaffSupport.Ctx c = support.ctx(storeId);
        support.need(c, "jobs", StaffPerms.EDIT, "job_batch", "批量管理岗位");
        List<Long> ids = new ArrayList<Long>();
        Object raw = body == null ? null : body.get("jobIds");
        if (raw instanceof List)
        {
            for (Object o : (List<?>) raw)
            {
                Long v = KgJdbc.asLong(o);
                if (v != null)
                {
                    ids.add(v);
                }
            }
        }
        int n = jobs.batch(c, ids, s(body, "action"));
        return AjaxResult.success(n == 0 ? "没有可操作的岗位" : "已处理 " + n + " 个岗位", n);
    }

    /**
     * 改已发岗位的工作地点（V42）：地图选点或手填，立即生效，不用重新审核。
     * 身份按岗位所属门店取（不认请求里的 storeId，也不能只看「这家店有授权」）：
     * 一个人在 A 店是主理人、在 B 店只是普通员工时，不能借 A 店的身份改 B 店岗位的地点。线上任务专员不在 staff 授权里，进不来
     */
    @AppAuth(staff = true, merchant = true)
    @PostMapping("/jobs/{jobId}/location")
    public AjaxResult jobLocation(@PathVariable Long jobId, @RequestBody(required = false) Map<String, Object> body)
    {
        StaffSupport.Ctx c = ctxOf("kg_job", "job_id", jobId);
        support.need(c, "jobs", StaffPerms.EDIT, "job_location", "修改工作地点");
        return AjaxResult.success("工作地点已更新", jobs.location(c, jobId, body));
    }

    /**
     * 给已发岗位补填筛选条件（10-16 跟进）：性别偏好、休息排班、住宿条件、最短在店。老岗位这几项都是空的，用户按条件筛时看不到。
     * 和改工作地点同一把关：身份按岗位所属门店取，要「岗位管理·编辑」（主理人、管家）；普通员工、线上任务专员进不来。
     * 筛选条件不是平台审核的内容，改了立即生效，不用重新审核
     */
    @AppAuth(staff = true, merchant = true)
    @PutMapping("/jobs/{jobId}/conditions")
    public AjaxResult jobConditions(@PathVariable Long jobId, @RequestBody(required = false) Map<String, Object> body)
    {
        StaffSupport.Ctx c = ctxOf("kg_job", "job_id", jobId);
        support.need(c, "jobs", StaffPerms.EDIT, "job_conditions", "补填筛选条件");
        return AjaxResult.success("已保存", jobs.conditions(c, jobId, body));
    }

    /**
     * 换已发岗位的照片（V49，内测反馈 2.0）：body = {images: [平台上传的图片路径，最多 9 张]}，整组覆盖，第一张做封面。
     * 和改工作地点、补填筛选条件同一把关：身份按岗位所属门店取，要「岗位管理·编辑」（主理人、管家）；普通员工、线上任务专员进不来。
     * 立即生效，不用重新审核
     */
    @AppAuth(staff = true, merchant = true)
    @PutMapping("/jobs/{jobId}/images")
    public AjaxResult jobImages(@PathVariable Long jobId, @RequestBody(required = false) Map<String, Object> body)
    {
        StaffSupport.Ctx c = ctxOf("kg_job", "job_id", jobId);
        support.need(c, "jobs", StaffPerms.EDIT, "job_images", "更换岗位照片");
        return AjaxResult.success("照片已更新", jobs.images(c, jobId, body));
    }

    /** 已发岗位的餐补、宠物、住宿照片和视频（V66）：打开「住宿与宠物」弹层时回填。身份按岗位所属门店取，要「岗位管理·查看」 */
    @AppAuth(staff = true, merchant = true)
    @GetMapping("/jobs/{jobId}/extras")
    public AjaxResult jobExtras(@PathVariable Long jobId)
    {
        StaffSupport.Ctx c = ctxOf("kg_job", "job_id", jobId);
        support.need(c, "jobs", StaffPerms.VIEW, "job_extras", "查看住宿和宠物资料");
        return AjaxResult.success(jobs.extras(c, jobId));
    }

    /**
     * 给已发岗位补餐补、宠物、住宿照片和视频（V66）：和换照片同一把关（身份按岗位所属门店取，要「岗位管理·编辑」，合作商家上架后不能改）。
     * 只改带了的键，立即生效，不用重新审核
     */
    @AppAuth(staff = true, merchant = true)
    @PutMapping("/jobs/{jobId}/extras")
    public AjaxResult saveJobExtras(@PathVariable Long jobId, @RequestBody(required = false) Map<String, Object> body)
    {
        StaffSupport.Ctx c = ctxOf("kg_job", "job_id", jobId);
        support.need(c, "jobs", StaffPerms.EDIT, "job_extras", "补填住宿和宠物资料");
        return AjaxResult.success("已保存", jobs.saveExtras(c, jobId, body));
    }

    @AppAuth(staff = true, merchant = true)
    @PostMapping("/jobs/{jobId}/share")
    public AjaxResult shareJob(@PathVariable Long jobId, @RequestBody(required = false) Map<String, Object> body)
    {
        boolean count = body == null || body.get("count") == null || Boolean.parseBoolean(String.valueOf(body.get("count")));
        return AjaxResult.success(jobs.share(ctxOf("kg_job", "job_id", jobId), jobId, s(body, "channel"), count));
    }

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/jobs/share-stats")
    public AjaxResult shareStats(@RequestParam(required = false) Long storeId, @RequestParam(required = false) Long jobId,
            @RequestParam(required = false) String dim)
    {
        StaffSupport.Ctx c = jobId == null ? support.ctx(storeId) : ctxOf("kg_job", "job_id", jobId);
        return AjaxResult.success(jobs.shareStats(c, jobId, dim));
    }

    // ---------- 调度 ----------

    @GetMapping("/dispatch/board")
    public AjaxResult board(@RequestParam(required = false) String dim, @RequestParam(required = false) String city,
            @RequestParam(required = false) String date, @RequestParam(required = false) String type)
    {
        return AjaxResult.success(dispatch.board(support.ctx(null), dim, city, date, type));
    }

    @GetMapping("/dispatch/adjust")
    public AjaxResult adjustInfo(@RequestParam(required = false) Long storeId, @RequestParam(required = false) Long jobId)
    {
        return AjaxResult.success(dispatch.adjustInfo(support.ctx(storeId), jobId));
    }

    @PostMapping("/dispatch/adjust")
    public AjaxResult adjust(@RequestBody Map<String, Object> body)
    {
        Long jobId = l(body, "jobId");
        StaffSupport.Ctx c = ctxOf("kg_job", "job_id", jobId);
        support.need(c, "dispatch", StaffPerms.EDIT, "dispatch_adjust", "调整名额");
        Map<String, Object> r = dispatch.adjust(c, jobId, StaffSupport.toInt(body.get("delta"), 0), s(body, "validFrom"),
                s(body, "validTo"), s(body, "reason"));
        return AjaxResult.success(Boolean.TRUE.equals(r.get("applied")) ? "名额已调整为 " + r.get("after") + " 人"
                : "已保存，将在有效期开始当天生效", r);
    }

    @GetMapping("/dispatch/urgent")
    public AjaxResult urgentInfo(@RequestParam Long jobId)
    {
        return AjaxResult.success(dispatch.urgentInfo(ctxOf("kg_job", "job_id", jobId), jobId));
    }

    @PostMapping("/dispatch/urgent")
    public AjaxResult urgent(@RequestBody Map<String, Object> body)
    {
        Long jobId = l(body, "jobId");
        StaffSupport.Ctx c = ctxOf("kg_job", "job_id", jobId);
        support.need(c, "dispatch", StaffPerms.EDIT, "dispatch_urgent", "紧急标记");
        List<String> targets = new ArrayList<String>();
        Object raw = body.get("targets");
        if (raw instanceof List)
        {
            for (Object o : (List<?>) raw)
            {
                targets.add(String.valueOf(o));
            }
        }
        Map<String, Object> r = dispatch.urgent(c, jobId, StaffSupport.toInt(body.get("level"), 0), s(body, "deadline"), targets,
                Boolean.parseBoolean(String.valueOf(body.get("push"))));
        return AjaxResult.success("已标记，站内通知 " + r.get("staffNotified") + " 位员工、" + r.get("usersNotified") + " 位用户", r);
    }

    @GetMapping("/dispatch/logs")
    public AjaxResult dispatchLogs(@RequestParam(required = false) Long storeId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String type)
    {
        return AjaxResult.success(dispatch.logs(support.ctx(null), storeId, from, to, type));
    }

    @PostMapping("/dispatch/logs/{logId}/undo")
    public AjaxResult undo(@PathVariable Long logId)
    {
        dispatch.undo(ctxOf("kg_dispatch_log", "log_id", logId), logId);
        return AjaxResult.success("已撤销这次调整");
    }

    @GetMapping("/dispatch/logs/export")
    public AjaxResult exportLogs(@RequestParam(required = false) Long storeId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String type)
    {
        StaffSupport.Ctx c = support.ctx(storeId);
        // 选了某家门店：按这家门店上的角色判导出权。选「全部门店」：之前这里用 ctx(null) 只导出第一家门店、也只按第一家的角色判；
        // 现在由 exportCsv 逐家按各门店上的角色判，只导出有导出权的门店。一家导出权都没有时，照样记一条被拒日志并 403
        if (storeId != null || !dispatch.canExport(c, null))
        {
            support.need(c, "dispatch", StaffPerms.EXPORT, "dispatch_export", "数据导出");
        }
        return AjaxResult.success(dispatch.exportCsv(c, storeId, from, to, type));
    }

    // ---------- 门店与角色 ----------

    // 门店身份、门店列表、切换门店对线上任务专员也开放（只读和记一笔切换日志，不涉及业务数据）：
    // 之前这三个接口把专员授权过滤掉，同时是普通员工和专员的账号在「门店切换」里看不到专员那家店，
    // 切过去也被拒，那家店的线上任务工作台一直进不去。现在列出全部有效授权并带上角色，
    // 切到专员门店后由小程序进线上任务工作台。
    @AppAuth(staff = true, onlineStaff = true, merchant = true, merchantService = true, cityLead = true)
    @GetMapping("/team/hub")
    public AjaxResult hub(@RequestParam(required = false) Long storeId)
    {
        return AjaxResult.success(team.hub(support.ctx(storeId)));
    }

    @AppAuth(staff = true, onlineStaff = true, merchant = true, merchantService = true, cityLead = true)
    @GetMapping("/team/stores")
    public AjaxResult stores(@RequestParam(required = false) Long storeId, @RequestParam(required = false) Long view)
    {
        return AjaxResult.success(team.stores(support.ctx(storeId), view));
    }

    @AppAuth(staff = true, onlineStaff = true, merchant = true, merchantService = true, cityLead = true)
    @PostMapping("/team/switch")
    public AjaxResult switchStore(@RequestBody Map<String, Object> body)
    {
        StaffSupport.Ctx c = support.ctx(l(body, "storeId"));
        team.switched(c);
        return AjaxResult.success("已切换到 " + c.storeName);
    }

    @AppAuth(staff = true, onlineStaff = true, merchant = true, merchantService = true, cityLead = true)
    @GetMapping("/team/roles")
    public AjaxResult roles(@RequestParam(required = false) Long storeId, @RequestParam(required = false) String role)
    {
        return AjaxResult.success(team.roles(support.ctx(storeId), role));
    }

    @PostMapping("/team/grant")
    public AjaxResult grant(@RequestParam(required = false) Long storeId, @RequestBody Map<String, Object> body)
    {
        StaffSupport.Ctx c = support.ctx(storeId);
        team.requireManager(c);
        return AjaxResult.success(team.grant(c, s(body, "target"), s(body, "role")));
    }

    @PostMapping("/team/revoke")
    public AjaxResult revoke(@RequestParam(required = false) Long storeId, @RequestBody Map<String, Object> body)
    {
        StaffSupport.Ctx c = support.ctx(storeId);
        team.requireManager(c);
        return AjaxResult.success(team.revoke(c, l(body, "grantId")));
    }

    @GetMapping("/team/logs")
    public AjaxResult opLogs(@RequestParam(required = false) Long storeId, @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "7") int days, @RequestParam(required = false) String result)
    {
        return AjaxResult.success(team.opLogs(support.ctx(storeId), category, days, result));
    }

    @PostMapping("/team/logs/{logId}/report")
    public AjaxResult reportOp(@PathVariable Long logId, @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(team.report(ctxOf("kg_staff_op_log", "log_id", logId), logId, s(body, "reason")));
    }
}
