package com.ruoyi.kangaroo.controller.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.kangaroo.domain.KgApplication;
import com.ruoyi.kangaroo.dto.OperatorDTO;
import com.ruoyi.kangaroo.enums.ApplicationStatus;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.KgOnlineTaskService;
import com.ruoyi.kangaroo.ext.staff.StaffPerms;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.ext.staff.KgTalentService;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.security.StaffGrantView;
import com.ruoyi.kangaroo.service.IKgApplicationService;
import com.ruoyi.kangaroo.util.MaskUtils;

/**
 * 小程序员工工作台接口。
 *
 * 类上标注 @AppAuth(staff = true)：没有生效员工授权的账号一律 403，
 * 即使它直接访问 /pages/staff/* 路由或手工构造请求也进不来。
 * 这是员工权限的服务端边界，前端的路由拦截只是体验优化。
 */
@RestController
@RequestMapping("/app/staff")
@AppAuth(staff = true)
public class AppStaffController
{
    @Autowired
    private IKgApplicationService kgApplicationService;

    @Autowired
    private StaffSupport staffSupport;

    @Autowired
    private KgJdbc db;

    @Autowired
    private KgTalentService talents;

    @Autowired
    private com.ruoyi.kangaroo.ext.user.KgPointsService points;

    /** 线上接单走到哪一步（报名详情的步骤与提示用）；单测里没注入时跳过 */
    @Autowired(required = false)
    private KgOnlineTaskService onlineTasks;

    /** 我的门店授权列表 */
    @AppAuth(staff = true, onlineStaff = true, merchant = true, merchantService = true, cityLead = true)
    @GetMapping("/stores")
    public AjaxResult stores()
    {
        AppLoginUser me = AppUserContext.requireStaff();
        return AjaxResult.success(me.getStaffGrants());
    }

    /** 报名列表排序白名单：wait = 等待最久在前（提交时间正序）；默认（空 / newest）沿用 SQL 里的报名先后倒序，即最新在前 */
    static final String SORT_WAIT_ORDER = "a.submit_time asc, a.app_id asc";

    /**
     * 之前两种排序都只看时间；现在用户花积分加急、还在等审核的报名一律排在最前面，其后再按所选排序。
     * 这一段是代码里的常量（不含任何前端输入），带引号会被 PageHelper 的 setOrderBy 注入检查拦下，所以走 setUnsafeOrderBy。
     */
    static final String URGENT_FIRST = "(a.urgent_flag = '1' and a.status = 'submitted') desc, ";

    static final String SORT_DEFAULT_ORDER = URGENT_FIRST + "a.app_id desc";

    /**
     * 门店报名列表。
     *
     * storeId 必须是当前账号有授权的门店；不传时默认取第一条授权门店。
     */
    @AppAuth(staff = true, onlineStaff = true, merchant = true, merchantService = true)
    @GetMapping("/applications")
    public AjaxResult applications(@RequestParam(required = false) Long storeId,
            @RequestParam(required = false) Long jobId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String submitDate,
            @RequestParam(required = false) String jobType,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize)
    {
        AppLoginUser me = AppUserContext.requireStaff();
        Long target = resolveStoreId(me, storeId);

        if (pageNum < 1)
        {
            pageNum = 1;
        }
        if (pageSize < 1 || pageSize > 50)
        {
            pageSize = 10;
        }

        KgApplication query = new KgApplication();
        query.setStoreId(target);
        if (jobId != null && jobId <= 0) throw new ServiceException("任务编号无效", 422);
        query.setJobId(jobId); // 与已授权门店同时过滤，任务编号不能扩大数据范围。
        if (StringUtils.isNotEmpty(status) && status.contains(","))
        {
            // 页签一次传几个状态（逗号分隔，如「待面试」= 审核通过待约 + 已约 + 面试已完成）：之前只能精确匹配一个状态，
            // 审核通过待约面试、面试已完成、待确认录用、已签约待入职四种只在「全部」里。每一个都要是真实的报名状态
            for (String one : status.split(","))
            {
                if (!ApplicationStatus.exists(one.trim())) throw new ServiceException("报名状态无效", 422);
            }
            query.getParams().put("statusIn", status.replace(" ", ""));
        }
        else
        {
            query.setStatus(StringUtils.isEmpty(status) ? null : status);
        }
        if (StringUtils.isNotEmpty(jobType) && !java.util.Arrays.asList("online", "volunteer", "parttime", "fulltime").contains(jobType)) throw new ServiceException("岗位类型无效", 422);
        StaffSupport.Ctx context = staffSupport.ctxForStore(target);
        query.setJobType(com.ruoyi.kangaroo.enums.StaffRole.onlineOnly(context.role) ? "online" : jobType);
        staffSupport.need(context, "online".equals(query.getJobType()) ? "online" : "jobs", StaffPerms.VIEW, "applications_view", "查看门店报名");
        if (!StaffPerms.allows(context.role, "online", StaffPerms.VIEW)) query.getParams().put("excludeOnline", Boolean.TRUE);
        // 与工作台新增报名使用同一服务端自然日和提交时间口径，包括已处理报名。
        java.time.LocalDate day = null;
        if (StringUtils.isNotEmpty(submitDate))
        {
            try
            {
                day = "today".equals(submitDate) ? java.time.LocalDate.now() : java.time.LocalDate.parse(submitDate);
            }
            catch (java.time.format.DateTimeParseException e)
            {
                throw new ServiceException("报名日期格式应为 YYYY-MM-DD", 422);
            }
            query.getParams().put("submitFrom", day.toString());
            query.getParams().put("submitBefore", day.plusDays(1).toString());
        }

        // 员工端「默认排序」原先没有任何选项，页面写着"按等待时长"却只能按最新在前。
        // 排序只认白名单里的值，不把前端字符串拼进 SQL；PageHelper 的 orderBy 会替换报名列表 SQL 原有的 order by，
        // 不必改动后台管理也在用的共用列表 SQL。
        if ("wait".equals(sort))
        {
            PageHelper.startPage(pageNum, pageSize).setUnsafeOrderBy(URGENT_FIRST + SORT_WAIT_ORDER);
        }
        else if (StringUtils.isEmpty(sort) || "newest".equals(sort))
        {
            PageHelper.startPage(pageNum, pageSize).setUnsafeOrderBy(SORT_DEFAULT_ORDER);
        }
        else
        {
            throw new ServiceException("排序方式无效", 422);
        }
        List<KgApplication> list = kgApplicationService.selectKgApplicationList(query);
        PageInfo<KgApplication> page = new PageInfo<KgApplication>(list);

        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (list != null)
        {
            for (KgApplication a : list)
            {
                rows.add(staffBrief(a));
            }
        }
        if (!rows.isEmpty())
        {
            // 加急标记（还在等审核的才标），一页一条查询
            points.markUrgent(rows);
            markOnlineSettlement(rows);
        }
        AjaxResult ajax = AjaxResult.success();
        ajax.put("rows", rows);
        ajax.put("total", Long.valueOf(page.getTotal()));
        ajax.put("hasMore", Boolean.valueOf(page.isHasNextPage()));
        ajax.put("submitDate", day == null ? null : day.toString());
        return ajax;
    }

    /**
     * 报名详情（员工视角）。
     *
     * 与用户视角的区别：员工能看到完整手机号（需要联系应聘者），
     * 但服务端仍会先校验该员工对这条报名所属门店有授权。
     */
    @AppAuth(staff = true, onlineStaff = true, merchant = true, merchantService = true)
    @GetMapping("/applications/{appId}")
    public AjaxResult applicationDetail(@PathVariable Long appId)
    {
        AppLoginUser me = AppUserContext.requireStaff();
        KgApplication app;
        boolean managementAccess = true;
        StaffSupport.Ctx context;
        try
        {
            app = kgApplicationService.requireOwnedByStore(appId, operator(me, null));
            context = staffSupport.ctxForStore(app.getStoreId());
        }
        catch (ServiceException denied)
        {
            if (!Integer.valueOf(403).equals(denied.getCode())) throw denied;
            app = kgApplicationService.requireApplication(appId);
            // 代招的实际接待店可查看履约所需信息；招聘状态变更仍归原管理店。
            if (!java.util.Arrays.asList("volunteer", "parttime", "fulltime").contains(app.getJobType())) throw denied;
            Long actualStore = fulfillmentStore(app);
            if (actualStore == null || actualStore.equals(app.getStoreId())) throw denied;
            context = staffSupport.ctxForStore(actualStore);
            managementAccess = false;
        }
        boolean online = "online".equals(app.getJobType());
        staffSupport.need(context, online ? "online" : "jobs", StaffPerms.VIEW, "application_view", "查看报名详情");
        Map<String, Object> data = staffBrief(app);
        data.put("fulfillmentStoreId", fulfillmentStore(app));
        data.put("canViewRecruitmentDetails", managementAccess);
        markOnlineSettlement(java.util.Collections.singletonList(data));
        data.put("realName", app.getRealName());
        // 完整联系方式只给能推进录用的角色（主理人 / 管家）；普通员工只看脱敏号码（默认口径：staff 没有完整联系方式）
        boolean fullContact = StaffPerms.allows(context.role, "jobs", StaffPerms.EDIT);
        boolean canManage = managementAccess && fullContact && !me.getUserId().equals(app.getAppUserId());
        String state = app.getStatus();
        data.put("canManage", canManage);
        data.put("canReviewVolunteerContract", canManage && "volunteer".equals(app.getJobType()));
        if ("volunteer".equals(app.getJobType()))
        {
            Map<String, Object> agreement = db.one("select status from kg_volunteer_contract where app_id=:a order by revision desc limit 1", KgJdbc.p("a", appId));
            data.put("volunteerContractStatus", agreement == null ? null : agreement.get("status"));
        }
        data.put("canForward", managementAccess && fullContact);
        data.put("canAudit", managementAccess && !me.getUserId().equals(app.getAppUserId()) && StaffPerms.allows(context.role, online ? "online" : "jobs", StaffPerms.AUDIT) && ApplicationStatus.SUBMITTED.equals(state));
        data.put("auditBlockReason", !ApplicationStatus.SUBMITTED.equals(state) ? ""
                : !managementAccess ? "报名审核由代招管理门店处理；实际接待门店可登记离店、办理保险。"
                : me.getUserId().equals(app.getAppUserId()) ? "不能审核自己提交的报名，请由本店其他有权限的人员或平台处理。"
                : !StaffPerms.allows(context.role, online ? "online" : "jobs", StaffPerms.AUDIT)
                    ? (online ? "你在此门店没有线上任务报名审核权限，请联系本店商家、商家服务人或城市负责人。" : "你在此门店没有报名审核权限，请联系本店主理人或平台。") : "");
        data.put("canScheduleInterview", canManage && !online && ApplicationStatus.REVIEW_PASSED.equals(state));
        data.put("canConfirm", canManage && !online && ApplicationStatus.USER_CONFIRMED.equals(state));
        data.put("canOnboard", canManage && !online && ApplicationStatus.CONTRACT_SIGNED.equals(state));
        // 与真正确认时使用同一预计日期和中国日历日规则。
        data.put("onboardWait", !online && ApplicationStatus.CONTRACT_SIGNED.equals(state)
                ? com.ruoyi.kangaroo.ext.staff.ArrivalConfirmationPolicy.waitReason(db, appId, false) : null);
        // 线上任务已经结算完成（接单人确认收款）的不再给「终止」：之前照样能点，接单人做完收完钱还会收到「报名已终止」
        data.put("canTerminate", canManage && ApplicationStatus.canTransfer(state, ApplicationStatus.TERMINATED) && !"confirmed".equals(data.get("settleStatus")));
        data.put("phone", fullContact ? app.getPhone() : MaskUtils.phone(app.getPhone()));
        data.put("contactMasked", !fullContact);
        data.put("age", app.getAge());
        if (!online)
        {
            // 性别：先取简历的（女 / 男 / 其他），没填退回账号资料；岗位分了男女名额（genderPlanned）时页面没填也写出「性别未填」
            Map<String, Object> g = db.one("select u.gender, r.gender resume_gender from kg_app_user u left join kg_resume r on r.app_user_id = u.user_id where u.user_id = :u",
                    KgJdbc.p("u", app.getAppUserId()));
            data.put("gender", com.ruoyi.kangaroo.ext.staff.KgStaffInterviewService.genderOf(g == null ? new java.util.HashMap<String, Object>() : g));
            data.put("genderPlanned", db.count("select count(*) from kg_job where job_id = :j and gender_male_count is not null and gender_female_count is not null",
                    KgJdbc.p("j", app.getJobId())) > 0);
        }
        // 「技能与经历」是报名人自己写的：只看脱敏号码的角色，里面的电话、微信号也隐去（之前号码脱敏了，这里写的 138-1234-5678 照样看得到）
        data.put("skills", fullContact ? app.getSkills() : MaskUtils.contacts(app.getSkills()));
        // 门店给这个人加的技能标签（V35，人才库同一份）；能审核报名的角色可以加 / 删
        data.put("storeTags", online || !managementAccess ? new ArrayList<String>() : talents.storeTags(app.getStoreId(), app.getAppUserId()));
        data.put("canTag", managementAccess && !online && !me.getUserId().equals(app.getAppUserId()) && StaffPerms.allows(context.role, "jobs", StaffPerms.AUDIT));
        // 备注 / 接单说明也是报名人自己写的，只看脱敏号码的角色同样隐去里面的联系方式（10-14 回归：普通员工能看到原文里的电话、微信）
        data.put("note", fullContact ? app.getNote() : MaskUtils.contacts(app.getNote()));
        data.put("rejectReason", app.getRejectReason());
        data.put("confirmDeadline", app.getConfirmDeadline());
        // 详情顶部「当前：…」状态条要显示这一步是哪天（2026-11-03 主人：六步全打勾看不出现在什么状态、哪天入职的）。
        // 只是报名表里已有的几个时间，不新增列；老小程序不读这几个字段
        data.put("auditTime", app.getAuditTime());
        data.put("userConfirmTime", app.getUserConfirmTime());
        data.put("storeConfirmTime", app.getStoreConfirmTime());
        data.put("onboardTime", app.getOnboardTime());
        data.put("withdrawTime", app.getWithdrawTime());
        data.put("userIntent", app.getUserIntent());
        // 到店信息是用户录用后单独保存的记录，不能用报名时的可到岗日期代替。
        // 先确认本店当前有效授权；只返回接待所需字段，不带签署或证件信息。
        // 「接站需求」已下线：不再查 need_pickup，老版本员工端拿不到这个字段，就不会再显示「需要接站」（库里的列和旧数据保留）
        data.put("arrival", online ? null : db.one(
                "select arrive_date, arrive_time, transport, transport_no, tickets, note, update_time "
                + "from kg_app_arrival where app_id = :a and app_user_id = :u",
                KgJdbc.p("a", appId, "u", app.getAppUserId())));
        // 是否加急、换工完成情况、能不能点「确认换工完成」（积分流 V37）
        data.putAll(points.staffExtras(appId, app.getJobType(), canManage));
        if (online && onlineTasks != null)
        {
            // 线上接单：onlineStep（3 进行中 / 4 结算完成）、onlineTip（给员工看的一句话）、workerCanSubmit（接单人现在还能不能交成果）。
            // 之前详情只按报名状态写「接单人可在任务交付页提交成果」，结算完成了也这么写，步骤条停在「确认」
            try
            {
                data.putAll(onlineTasks.staffStage(appId));
            }
            catch (ServiceException e)
            {
                // 接单记录对不上（如岗位改成了非线上）：不影响详情其余部分，页面按报名状态显示
            }
        }
        return AjaxResult.success(data);
    }

    private Long fulfillmentStore(KgApplication app)
    {
        Map<String, Object> job = db.one("select recruit_store_id from kg_job where job_id=:j", KgJdbc.p("j", app.getJobId()));
        Long actualStore = job == null ? null : KgJdbc.asLong(job.get("recruitStoreId"));
        return actualStore == null ? app.getStoreId() : actualStore;
    }

    /** 报名详情「＋技能标签」：门店给报名人加 / 删标签（body: tag, action=add|remove），存人才库标记，人才库按标签也能筛到 */
    @AppAuth(staff = true, merchant = true)
    @PostMapping("/applications/{appId}/tags")
    public AjaxResult editTags(@PathVariable Long appId, @RequestBody Map<String, Object> body)
    {
        AppLoginUser me = AppUserContext.requireStaff();
        KgApplication app = kgApplicationService.requireOwnedByStore(appId, operator(me, null));
        if ("online".equals(app.getJobType()) || me.getUserId().equals(app.getAppUserId()))
        {
            throw new ServiceException("这条报名不能加门店标签", 422);
        }
        StaffSupport.Ctx c = staffSupport.ctxForStore(app.getStoreId());
        staffSupport.need(c, "jobs", StaffPerms.AUDIT, "application_tag", "编辑报名人标签");
        boolean add = !"remove".equals(body == null ? null : String.valueOf(body.get("action")));
        String tag = body == null || body.get("tag") == null ? "" : String.valueOf(body.get("tag"));
        return AjaxResult.success(talents.editStoreTag(c, app.getAppUserId(), tag, add));
    }

    /** 审核报名 */
    @AppAuth(staff = true, onlineStaff = true, merchant = true, merchantService = true)
    @PostMapping("/applications/{appId}/audit")
    public AjaxResult audit(@PathVariable Long appId, @RequestBody Map<String, Object> body)
    {
        AppLoginUser me = AppUserContext.requireStaff();
        requireHiringRole(me, appId, StaffPerms.AUDIT, "application_audit", "审核报名");
        boolean pass = Boolean.parseBoolean(String.valueOf(body == null ? "false" : body.get("pass")));
        String reason = body == null ? null : (body.get("reason") == null ? null : String.valueOf(body.get("reason")));
        kgApplicationService.audit(appId, pass, reason, operator(me, null));
        return AjaxResult.success(pass ? "已通过审核" : "已驳回");
    }

    /** 商家确认录用 */
    @AppAuth(staff = true, merchant = true)
    @PostMapping("/applications/{appId}/confirm")
    public AjaxResult confirm(@PathVariable Long appId)
    {
        AppLoginUser me = AppUserContext.requireStaff();
        requireHiringRole(me, appId, StaffPerms.EDIT, "application_confirm", "确认录用");
        kgApplicationService.storeConfirm(appId, operator(me, null));
        return AjaxResult.success("已确认录用");
    }

    /** 标记入职 */
    @AppAuth(staff = true, merchant = true)
    @PostMapping("/applications/{appId}/onboard")
    public AjaxResult onboard(@PathVariable Long appId)
    {
        AppLoginUser me = AppUserContext.requireStaff();
        requireHiringRole(me, appId, StaffPerms.EDIT, "application_onboard", "标记入职");
        kgApplicationService.onboard(appId, operator(me, null));
        return AjaxResult.success("已标记入职");
    }

    /** 终止申请 */
    @AppAuth(staff = true, merchant = true)
    @PostMapping("/applications/{appId}/terminate")
    public AjaxResult terminate(@PathVariable Long appId, @RequestBody Map<String, Object> body)
    {
        AppLoginUser me = AppUserContext.requireStaff();
        requireHiringRole(me, appId, StaffPerms.EDIT, "application_terminate", "终止报名");
        if (db.count("select count(*) from kg_online_settlement where app_id = :a and status = 'confirmed'", KgJdbc.p("a", appId)) > 0)
        {
            throw new ServiceException("这单线上任务已经结算完成，不能再终止", 409);
        }
        String reason = body == null ? null : (body.get("reason") == null ? null : String.valueOf(body.get("reason")));
        kgApplicationService.terminate(appId, reason, operator(me, null));
        return AjaxResult.success("已终止");
    }

    // ---------- 内部 ----------

    /**
     * 审核 / 确认录用 / 标记入职 / 终止：只允许报名所属门店的主理人、店长；普通员工只查看。
     * 先按门店授权取到报名（跨门店照旧 403），再按那家门店上的角色判权，
     * 被拒时记一条失败的员工操作日志。
     */
    private void requireHiringRole(AppLoginUser me, Long appId, String permission, String opAction, String opLabel)
    {
        KgApplication app = kgApplicationService.requireOwnedByStore(appId, operator(me, null));
        StaffSupport.Ctx c = staffSupport.ctxForStore(app.getStoreId());
        staffSupport.need(c, "online".equals(app.getJobType()) ? "online" : "jobs", permission, opAction, opLabel);
    }

    private OperatorDTO operator(AppLoginUser me, Long storeId)
    {
        return OperatorDTO.staff(me.getUserId(), me.getNickname(), storeId);
    }

    private Long resolveStoreId(AppLoginUser me, Long storeId)
    {
        List<StaffGrantView> grants = me.getStaffGrants();
        if (grants == null || grants.isEmpty())
        {
            throw new ServiceException("当前账号没有员工授权");
        }
        if (storeId == null)
        {
            for (StaffGrantView grant : grants) if (grant.getStoreId() != null) return grant.getStoreId();
            throw new ServiceException("当前城市尚无可管理门店", com.ruoyi.common.constant.HttpStatus.FORBIDDEN);
        }
        if (me.getStaffGrants().stream().noneMatch(g -> storeId.equals(g.getStoreId())))
        {
            throw new ServiceException("没有该门店的操作权限",
                    Integer.valueOf(com.ruoyi.common.constant.HttpStatus.FORBIDDEN));
        }
        return storeId;
    }

    /**
     * 线上任务的报名验收后一直停在「审核通过」（不走面试 / 录用）：有结算单的按结算单显示状态（已验收 · 待门店付款 / 已登记付款 · 待确认 /
     * 结算完成 / 收款申诉中），并带上 settleStatus。之前做完、收完钱的接单在列表和详情里仍写「审核通过」，看不出是进行中还是已完成。
     * 一页一条查询
     */
    private void markOnlineSettlement(List<Map<String, Object>> rows)
    {
        List<Long> ids = new ArrayList<Long>();
        for (Map<String, Object> r : rows)
        {
            if ("online".equals(r.get("jobType")) && r.get("appId") != null)
            {
                ids.add(KgJdbc.asLong(r.get("appId")));
            }
        }
        if (ids.isEmpty() || db == null)
        {
            return;
        }
        Map<Long, String> settle = new HashMap<Long, String>();
        for (Map<String, Object> s : db.list("select app_id, status from kg_online_settlement where app_id in (:ids)", KgJdbc.p("ids", ids)))
        {
            settle.put(KgJdbc.asLong(s.get("appId")), KgJdbc.asStr(s.get("status")));
        }
        for (Map<String, Object> r : rows)
        {
            String st = settle.get(KgJdbc.asLong(r.get("appId")));
            if (st != null)
            {
                r.put("settleStatus", st);
                r.put("statusLabel", KgOnlineTaskService.settleLabel(st));
            }
        }
    }

    /** 列表里姓名脱敏，详情里才给完整信息，减少不必要的个人信息暴露面 */
    private Map<String, Object> staffBrief(KgApplication a)
    {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("appId", a.getAppId());
        m.put("appNo", a.getAppNo());
        m.put("jobId", a.getJobId());
        m.put("jobTitle", a.getJobTitle());
        m.put("jobType", a.getJobType());
        m.put("storeId", a.getStoreId());
        m.put("applicantName", MaskUtils.name(a.getRealName()));
        m.put("applicantPhone", MaskUtils.phone(a.getPhone()));
        m.put("age", a.getAge());
        m.put("status", a.getStatus());
        m.put("statusLabel", ApplicationStatus.label(a.getStatus()));
        m.put("arrivalDate", a.getArrivalDate());
        m.put("stayDays", a.getStayDays());
        m.put("submitTime", a.getSubmitTime());
        return m;
    }
}
