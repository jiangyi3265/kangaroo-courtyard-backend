package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.JobStatus;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgCommunityGuard;
import com.ruoyi.kangaroo.ext.user.KgSkills;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppUserContext;

/**
 * 线上任务闭环：门店发布（草稿 → 平台审核上架）→ 用户报名、门店审核通过即接单 → 用户提交成果（可多轮）→ 门店验收通过 / 退回修改 →
 * 验收通过生成结算单 → 门店线下付款并登记凭证 → 接单人确认收款（或申诉）。平台不经手佣金、不做任何自动转账。
 */
@Service
public class KgOnlineTaskService
{
    static final List<String> TYPES = Arrays.asList("内容写作", "图片设计", "视频剪辑", "社群运营", "数据整理", "其他");

    static final List<String> WORKING = Arrays.asList("review_passed", "interview_booked", "interview_done", "store_confirmed", "user_confirmed", "contract_signed", "onboarded");

    static final List<String> PAYOUT_METHODS = Arrays.asList("银行转账", "微信转账", "支付宝转账", "现金", "其他");

    static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 成果被退回后，接单人至少还有这么多小时重新提交（超过任务截止时间也算）。
     * 之前截止后门店点「退回修改」，接单人交不了、门店也验收不了，这单永远卡在「已退回修改」
     */
    static final int RESUBMIT_HOURS = 48;

    /** 成果提交满这么多天门店还没验收也没退回，接单人可以在交付页请平台介入 */
    static final int ESCALATE_DAYS = 3;

    /** 新建线上任务时截止时间最多填多少天以后 */
    static final int MAX_DEADLINE_DAYS = 180;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KgOnlineTaskService.class);

    @Autowired
    private KgJdbc db;

    @Autowired private KgOnlineRecipient recipients;

    @Autowired
    private StaffSupport support;

    @Autowired
    private KgCommunityGuard guard;

    @Autowired
    private com.ruoyi.kangaroo.ext.user.KgInviteService invites;

    /** 合作商家发布的线上任务先由换工主理人核实（V63）；单测没注入时照旧只落草稿 */
    @org.springframework.context.annotation.Lazy
    @Autowired(required = false)
    private KgMerchantRequestService merchantRequests;

    @Autowired(required = false)
    private KgPublicationFeeService publicationFees;

    @Autowired private KgMerchantAgreements merchantAgreements;

    /** 按城市授权的员工（专员提交任务的通知也发给本城按城市授权的商家服务人）；单测没注入时不算 */
    @Autowired(required = false)
    private CityGrantScope cityGrants;

    static String str(Object v)
    {
        String s = KgJdbc.asStr(v);
        return s == null ? "" : s;
    }

    static long num(Object v)
    {
        Long l = KgJdbc.asLong(v);
        return l == null ? 0L : l;
    }

    static String yuan(long fen)
    {
        return fen % 100 == 0 ? String.valueOf(fen / 100) : String.format("%.2f", fen / 100.0);
    }

    static String mask(String n)
    {
        if (n == null || n.isEmpty())
        {
            return "接单人";
        }
        return n.length() == 1 ? n + "*" : n.charAt(0) + "*" + (n.length() > 2 ? String.valueOf(n.charAt(n.length() - 1)) : "");
    }

    // ---------------------------------------------------------------- 员工：任务

    public List<Map<String, Object>> tasks(StaffSupport.Ctx c)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> j : db.list("select j.job_id, j.title, j.status, j.headcount, j.close_time, t.task_type, t.deadline_time, t.commission_fen, "
                + "(select mr.request_id from kg_merchant_request mr where mr.job_id = j.job_id and mr.del_flag = '0') request_id, "
                + "(select mr.status from kg_merchant_request mr where mr.job_id = j.job_id and mr.del_flag = '0') request_status, "
                + "(select count(*) from kg_application a where a.job_id = j.job_id and a.del_flag = '0' and a.status <> 'withdrawn') applied, "
                + "(select count(*) from kg_application a where a.job_id = j.job_id and a.del_flag = '0' and a.status = 'submitted') pending_applications, "
                + "(select count(*) from kg_online_submission x join kg_application a on a.app_id = x.app_id where a.job_id = j.job_id and x.status = 'submitted') waiting "
                + "from kg_job j left join kg_online_task t on t.job_id = j.job_id where j.store_id = :s and j.job_type = 'online' and j.del_flag = '0' order by j.job_id desc limit 50",
                p("s", c.storeId)))
        {
            Map<String, Object> m = new LinkedHashMap<String, Object>(j);
            m.put("canAuditApplications", StaffPerms.allows(c.role, "online", StaffPerms.AUDIT));
            // 商家发的任务还在核实 / 转派时，「待平台审核」不对：按需求的实际进度说
            String requestStatus = str(j.get("requestStatus"));
            m.put("statusLabel", JobStatus.DRAFT.equals(str(j.get("status"))) && !requestStatus.isEmpty()
                    ? KgMerchantRequestService.statusLabel(requestStatus) : statusLabel(str(j.get("status"))));
            // 2026-11-03 主人：截止时间是预计完成时间，到期关闭的可以「继续开启」（KgOnlineTaskReopen）：选任务时写「已到期」，不再和平台关掉的混在一起
            m.put("reopenable", KgOnlineTaskReopen.reopenable(j));
            if (KgOnlineTaskReopen.reopenable(j))
            {
                m.put("statusLabel", "已到期");
            }
            out.add(m);
        }
        return out;
    }

    static String statusLabel(String s)
    {
        // 后台可以把线上任务暂停（paused），之前没写中文，卡片上直接显示英文 paused
        return JobStatus.DRAFT.equals(s) ? "待平台审核" : ("published".equals(s) ? "已上线" : ("closed".equals(s) ? "已关闭" : (JobStatus.PAUSED.equals(s) ? "已暂停" : s)));
    }

    // ---------------------------------------------------------------- 截止时间：上架 / 报名 / 自动关闭

    /** 线上任务是否已过截止时间（没有扩展行或没设截止时间的老数据算没过）。小程序岗位详情据此告诉用户不能再报名 */
    public boolean deadlinePassed(Long jobId)
    {
        return db.count("select count(*) from kg_online_task where job_id = :j and deadline_time is not null and deadline_time < now()", p("j", jobId)) > 0;
    }

    /**
     * 平台上架线上任务前调用（岗位管理里「草稿 → 上架」「暂停 → 恢复」）：截止时间已过就不能上架；
     * 草稿第一次上架还要求离截止至少 2 小时，与门店发布时的要求一致。
     * 之前平台第二天才审、照样能把已经过期的草稿上架，一上线就没人报得了名
     */
    public void checkPublishable(Long jobId, String fromStatus)
    {
        Map<String, Object> t = db.one("select deadline_time from kg_online_task where job_id = :id", p("id", jobId));
        Date deadline = t == null ? null : StaffSupport.parse(t.get("deadlineTime"));
        if (deadline == null)
        {
            return;
        }
        String at = StaffSupport.fmt(deadline, "yyyy-MM-dd HH:mm");
        if (!deadline.after(new Date()))
        {
            throw new ServiceException("这个线上任务的截止时间（" + at + "）已经过了，不能上架；请让门店重新发布", 409);
        }
        if (JobStatus.DRAFT.equals(fromStatus) && deadline.getTime() < System.currentTimeMillis() + 2L * 3600000L)
        {
            throw new ServiceException("这个线上任务离截止时间（" + at + "）不到 2 小时，上架后几乎没人来得及接单；请让门店调整截止时间后重新发布", 409);
        }
    }

    /**
     * 过了截止时间的线上任务自动关闭（每 5 分钟扫一次）。之前没有任何东西下架过期任务：状态一直是「招聘中」，
     * 首页、搜索、门店主页照样列出，用户填完报名表最后一步才被拒。关闭只是不再接单，已接单的成果照常验收、结算。
     * 还在「待平台审核」就过期的草稿也一并关闭，并告诉提交它的门店，免得门店那边一直写着「待平台审核」。
     * 已上架 / 暂停的任务关闭时告诉门店和还在做的接单人（notifyClosed）；还有没审核的接单申请时：过了截止门店已不能通过
     * （KgApplicationServiceImpl.audit 会拦下并提示驳回），提醒门店去驳回，申请人才会收到结果，不会一直停在「等待门店审核」。
     * 小程序线上任务列表另外按截止时间过滤（KgJobMapper.selectAppJobList），两次扫描之间也不会露出来
     */
    @Scheduled(fixedDelay = 300000, initialDelay = 100000)
    public void closeExpiredTasks()
    {
        try
        {
            for (Map<String, Object> j : db.list("select j.job_id, j.store_id, j.title, j.status, j.source, t.deadline_time from kg_job j join kg_online_task t on t.job_id = j.job_id "
                    + "where j.job_type = 'online' and j.del_flag = '0' and j.status in ('draft', 'published', 'paused') "
                    + "and t.deadline_time is not null and t.deadline_time < now() order by j.job_id limit 200", p()))
            {
                Long jobId = KgJdbc.asLong(j.get("jobId"));
                Long storeId = KgJdbc.asLong(j.get("storeId"));
                int n = db.update("update kg_job set status = 'closed', close_time = now(), update_by = 'system', update_time = now() "
                        + "where job_id = :id and status = :st and del_flag = '0'", p("id", jobId, "st", j.get("status")));
                if (n == 0)
                {
                    continue;
                }
                if (JobStatus.DRAFT.equals(str(j.get("status"))))
                {
                    if (publicationFees != null) publicationFees.closed(jobId);
                    // 还在核实 / 转派的商家需求跟着关闭，免得换工主理人、商家服务人那边还挂着一条打不开的任务
                    db.update("update kg_merchant_request set status = 'closed', return_reason = '到截止时间还没上架，已自动关闭', version = version + 1, "
                            + "handle_time = now(), update_by = 'system', update_time = now() where job_id = :j and del_flag = '0' and status not in ('done', 'closed', 'published')",
                            p("j", jobId));
                    // 平台在后台替门店建的草稿（source = admin）门店没提交过，不发
                    if ("staff".equals(str(j.get("source"))))
                    {
                        support.notifyStore(storeId, "system", "线上任务未能上架",
                                "「" + j.get("title") + "」到截止时间还没通过平台审核，已自动关闭；如果还需要，请调整截止时间后重新发布", "job", jobId,
                                "/pages/staff/online-jobs?jobId=" + jobId + "&storeId=" + storeId);
                    }
                    continue;
                }
                try
                {
                    notifyClosed(jobId, storeId, str(j.get("title")), StaffSupport.parse(j.get("deadlineTime")));
                }
                catch (RuntimeException e)
                {
                    // 通知是附属动作：发不出去不影响关闭，也不挡后面的任务
                    log.warn("线上任务 {} 到期关闭的通知没发出去：{}", jobId, e.getMessage());
                }
            }
        }
        catch (RuntimeException e)
        {
            log.warn("关闭过期线上任务失败：{}", e.getMessage());
        }
    }

    /**
     * 上过架的任务到期自动关闭后：之前只在还有没审核的申请时提醒门店，门店和接单人都不知道任务关了，交付页还写「进行中」。
     * 门店：发给能管线上任务的人（StaffSupport.notifyOnline），有没审核的申请就落到报名列表去驳回，没有就落到任务页（可以「继续开启」）。
     * 接单人：只发给还在做的（报名进行中、还没有结算单）；已结算、已退出、被拒的不发。按各自情况说清楚还能不能交
     */
    void notifyClosed(Long jobId, Long storeId, String title, Date deadline)
    {
        long waiting = db.count("select count(*) from kg_application where job_id = :j and del_flag = '0' and status = 'submitted'", p("j", jobId));
        support.notifyOnline(storeId, false, null, "system", "线上任务已到截止时间",
                "「" + title + "」已到截止时间，已自动关闭、不再接单；已接单的成果照常验收、结算"
                        + (waiting > 0 ? "。还有 " + waiting + " 条接单申请没审核，过了截止已不能通过，请驳回让申请人收到结果"
                                : "。需要延长的话，打开任务选新的预计完成时间「继续开启」"),
                "job", jobId, waiting > 0 ? "/pages/staff/applications?status=submitted&jobType=online&storeId=" + storeId
                        : "/pages/staff/online-jobs?jobId=" + jobId + "&storeId=" + storeId);
        Date now = new Date();
        for (Map<String, Object> a : db.list("select a.app_id, a.app_user_id, "
                + "(select x.status from kg_online_submission x where x.app_id = a.app_id order by x.round desc limit 1) sub, "
                + "(select x.reviewed_time from kg_online_submission x where x.app_id = a.app_id order by x.round desc limit 1) reviewed "
                + "from kg_application a where a.job_id = :j and a.del_flag = '0' and a.status in (:w) "
                + "and not exists (select 1 from kg_online_settlement s where s.app_id = a.app_id)", p("j", jobId, "w", WORKING)))
        {
            Long appId = KgJdbc.asLong(a.get("appId"));
            String sub = str(a.get("sub"));
            Date until = resubmitUntil(sub, a.get("reviewed"), deadline);
            String text = "submitted".equals(sub) ? "「" + title + "」已到截止时间，任务已关闭、不再接单；你已提交的成果门店会照常验收、结算"
                    : until != null && until.after(now) ? "「" + title + "」已到截止时间，任务已关闭；你被退回的成果请在 " + StaffSupport.fmt(until, "MM-dd HH:mm") + " 前修改后重新提交"
                    : "「" + title + "」已到截止时间，任务已关闭，不能再提交成果；门店继续开启的话会再通知你";
            support.notifyUser(KgJdbc.asLong(a.get("appUserId")), "apply", "线上任务已截止关闭", text, "application", appId, "/pages/jobs/deliver?appId=" + appId);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(StaffSupport.Ctx c, Map<String, Object> b) {
        if (StaffRole.MERCHANT.equals(c.role) && publicationFees != null) {
            Map<String,Object> previous = publicationFees.retry(c,b);
            if (previous != null) return previous;
        }
        boolean perItem = StaffRole.MERCHANT.equals(c.role);
        if (perItem) merchantAgreements.requireAccepted(c,"online_task");
        Map<String,Object> result=createTask(c.storeId,c.name(),"staff:"+c.uid(),"staff",b,perItem);
        if (StaffRole.MERCHANT.equals(c.role) && publicationFees != null) publicationFees.capture(c,KgJdbc.asLong(result.get("jobId")),b);
        support.op(c,"online_create","发布线上任务","general",str(b.get("title")),true,null);
        // 线上任务专员发的任务，佣金最后由主理人线下付：第一时间告诉本店主理人（不加审批步骤，照旧由平台审核上架）
        if (StaffRole.ONLINE_STAFF.equals(c.role) || StaffRole.CITY_LEAD.equals(c.role))
        {
            notifyManagersOfSpecialistTask(c, KgJdbc.asLong(result.get("jobId")), b);
        }
        // 合作商家发的：本城换工主理人核实后转商家服务人上架（后台岗位管理仍能直接上架）；同一事务，失败不留草稿
        if (StaffRole.MERCHANT.equals(c.role) && merchantRequests != null)
        {
            String summary = str(b.get("description")).trim();
            result.putAll(merchantRequests.onlineTaskCreated(c, KgJdbc.asLong(result.get("jobId")), str(b.get("title")).trim(),
                    summary.isEmpty() ? str(b.get("deliverables")).trim() : summary));
        }
        return result;
    }

    /**
     * 商家修改被退回的线上任务（V63）：和发布同一套校验，覆盖草稿和扩展行。调用方已核对这是本店被退回的需求。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateDraftTask(StaffSupport.Ctx c, Long jobId, Map<String, Object> b)
    {
        Map<String, Object> j = db.must("select job_id, store_id, status from kg_job where job_id = :id and job_type = 'online' and del_flag = '0' for update",
                p("id", jobId), "线上任务不存在");
        if (c.storeId == null || !c.storeId.equals(KgJdbc.asLong(j.get("storeId"))))
        {
            throw new ServiceException("没有该门店的操作权限", 403);
        }
        if (!JobStatus.DRAFT.equals(str(j.get("status"))))
        {
            throw new ServiceException("这个任务已经不是草稿了，请刷新后查看", 409);
        }
        String operator = "staff:" + c.uid();
        Map<String,Object> terms=db.one("select reward_mode from kg_online_task where job_id=:id for update",p("id",jobId));
        boolean perItem=KgOnlineReward.perItem(terms);
        if(perItem && StaffRole.MERCHANT.equals(c.role)) merchantAgreements.requireAccepted(c,"online_task");
        List<Map<String, Object>> rows = taskRows(c.storeId, c.name(), operator, "staff", b,perItem);
        Map<String, Object> job = rows.get(0);
        for (String insertOnly : Arrays.asList("store_id", "job_type", "publisher_name", "status", "source", "create_by", "create_time", "start_date"))
        {
            job.remove(insertOnly);
        }
        job.put("update_by", operator);
        job.put("update_time", new Date());
        db.updateRow("kg_job", "job_id", jobId, job);
        Map<String, Object> ext = rows.get(1);
        ext.remove("create_by");
        ext.remove("create_time");
        ext.put("update_by", operator);
        ext.put("update_time", new Date());
        db.updateRow("kg_online_task", "job_id", jobId, ext);
    }

    /**
     * 专员提交线上任务后，给本店每位在职主理人发一条员工身份站内信，点开是这个任务的详情（带门店，多店主理人也能直接打开）。
     * 只在 createTask 校验通过、草稿已落库之后调用，标题 / 佣金 / 名额按它同样的规则取。
     */
    int notifyManagersOfSpecialistTask(StaffSupport.Ctx c, Long jobId, Map<String, Object> b)
    {
        if (jobId == null)
        {
            return 0;
        }
        // 切换后线上任务付款归合作商家、商家服务人（登记入口也只有他们），新换工主理人进不了线上任务页，不再发给他
        boolean switched = RolePhase.isNew();
        List<Map<String, Object>> managers = db.list("select distinct app_user_id from kg_staff_grant where store_id = :s and " + (switched ? "role_code in ('merchant','merchant_service') " : "role_code = 'manager' ")
                + "and status = '0' and del_flag = '0' and app_user_id <> :me", p("s", c.storeId, "me", c.uid()));
        // 本城按城市授权的同角色（合作商家的城市授权不展开，这里只会有商家服务人 / 老主理人）
        if (cityGrants != null)
        {
            managers = new ArrayList<Map<String, Object>>(managers);
            for (Long uid : cityGrants.holderIds(c.storeId, switched ? Arrays.asList(StaffRole.MERCHANT, StaffRole.MERCHANT_SERVICE) : Arrays.asList(StaffRole.MANAGER)))
            {
                if (uid != null && !uid.equals(c.uid()))
                {
                    managers.add(p("appUserId", uid));
                }
            }
        }
        String content = "专员 " + c.name() + " 提交了线上任务「" + str(b.get("title")).trim() + "」，佣金 ¥" + yuan(num(b.get("commissionFen"))) + " × "
                + num(b.get("quota")) + " 人，平台审核通过后上线；" + (switched ? "验收后请线下付款并登记" : "验收后由你线下付款");
        String route = "/pages/staff/online-jobs?jobId=" + jobId + "&storeId=" + c.storeId;
        int sent = 0;
        for (Map<String, Object> m : managers)
        {
            Long uid = KgJdbc.asLong(m.get("appUserId"));
            if (uid != null)
            {
                support.notifyStaff(uid, "system", "专员提交了线上任务", content, "job", jobId, route);
                sent++;
            }
        }
        return sent;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String,Object> createByAdmin(Map<String,Object> b) {
        Long storeId=KgJdbc.asLong(b.get("storeId"));
        if(storeId==null) throw new ServiceException("请选择所属门店",422);
        String operator=com.ruoyi.common.utils.SecurityUtils.getUsername();
        Map<String,Object> store=db.must("select store_name from kg_store where store_id=:id and status='0' and del_flag='0'",p("id",storeId),"请选择有效门店");
        Map<String,Object> result=createTask(storeId,str(store.get("storeName")),operator,"admin",b);
        result.put("message","已创建线上任务草稿，请在岗位管理审核上架");
        return result;
    }

    private Map<String, Object> createTask(Long storeId, String publisher, String operator, String source, Map<String, Object> b)
    {
        return createTask(storeId,publisher,operator,source,b,false);
    }

    private Map<String,Object> createTask(Long storeId,String publisher,String operator,String source,Map<String,Object> b,boolean perItem)
    {
        List<Map<String, Object>> rows = taskRows(storeId, publisher, operator, source, b,perItem);
        long jobId = db.insert("kg_job", rows.get(0));
        Map<String, Object> ext = new LinkedHashMap<String, Object>();
        ext.put("job_id", jobId);
        ext.putAll(rows.get(1));
        db.insert("kg_online_task", ext);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("jobId", jobId);
        m.put("message", "已提交平台审核，审核通过后对用户可见");
        return m;
    }

    /** 发布和修改共用：校验表单，返回 [kg_job 的列, kg_online_task 的列（不含 job_id）] */
    private List<Map<String, Object>> taskRows(Long storeId, String publisher, String operator, String source, Map<String, Object> b,boolean perItem)
    {
        String title = str(b.get("title")).trim();
        String type = str(b.get("taskType"));
        String deliver = str(b.get("deliverables")).trim();
        String criteria = str(b.get("criteria")).trim();
        String desc = str(b.get("description")).trim();
        int items=perItem ? KgOnlineReward.count(b.get("itemsPerPerson"),100,"每人交付条数") : 0;
        long unitFen = perItem ? KgOnlineReward.unitFen(b.get("unitRewardFen")) : 0;
        long fen = perItem ? items * unitFen : num(b.get("commissionFen"));
        long quota = num(b.get("quota"));
        if (title.isEmpty() || title.length() > 30)
        {
            throw new ServiceException("任务标题 1–30 字", 422);
        }
        if (!TYPES.contains(type))
        {
            throw new ServiceException("请选择任务类型", 422);
        }
        if (deliver.isEmpty() || deliver.length() > 100 || criteria.isEmpty() || criteria.length() > 200 || desc.length() > 300)
        {
            throw new ServiceException("请填写交付物（100 字内）与验收标准（200 字内），描述不超过 300 字", 422);
        }
        checkCommission(fen);
        if (quota < 1 || quota > 50)
        {
            throw new ServiceException("名额 1–50 人", 422);
        }
        LocalDateTime deadline;
        try
        {
            deadline = LocalDateTime.parse(str(b.get("deadline")), DT);
        }
        catch (Exception e)
        {
            throw new ServiceException("截止时间格式为 yyyy-MM-dd HH:mm", 422);
        }
        if (deadline.isBefore(LocalDateTime.now().plusHours(2)))
        {
            throw new ServiceException("截止时间至少晚于现在 2 小时", 422);
        }
        // 截止时间设上限：之前填成几年后也收，任务一直挂在列表里接单、名额和佣金长期占着
        if (deadline.isAfter(LocalDateTime.now().plusDays(MAX_DEADLINE_DAYS)))
        {
            throw new ServiceException("截止时间最多 " + MAX_DEADLINE_DAYS + " 天内", 422);
        }
        List<String> reqs = new ArrayList<String>();
        if (b.get("requirements") instanceof List)
        {
            for (Object o : (List<?>) b.get("requirements"))
            {
                if (!str(o).trim().isEmpty() && reqs.size() < 3)
                {
                    reqs.add(StaffSupport.cut(str(o).trim(), 40));
                }
            }
        }
        List<String> skills = new ArrayList<String>();
        if (b.get("skills") instanceof List)
        {
            for (Object o : (List<?>) b.get("skills"))
            {
                if (!str(o).trim().isEmpty() && skills.size() < 3)
                {
                    skills.add(StaffSupport.cut(str(o).trim(), 12));
                }
            }
        }
        guard.check(title, deliver, criteria, desc, String.join(" ", reqs));
        Map<String, Object> store = db.must("select cover from kg_store where store_id = :s and status = '0' and del_flag = '0'", p("s", storeId), "门店不存在");
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("store_id", storeId);
        row.put("job_type", "online");
        row.put("title", title);
        row.put("cover", store.get("cover"));
        row.put("city", "不限");
        row.put("address", "远程");
        // 计薪方式用字典里有的 piece（计件 / 按单），单位「元/单」：原先写的 fixed 不在 kg_salary_type 字典和后台白名单里，平台在岗位管理里改不了这类任务；
        // 金额按「分」换算成带两位小数的元：原先 fen / 100 是整数除法，¥12.50 存成 12，列表上显示「12 元/单」
        row.put("salary_type", "piece");
        row.put("salary_min", java.math.BigDecimal.valueOf(fen, 2));
        row.put("salary_max", java.math.BigDecimal.valueOf(fen, 2));
        row.put("salary_unit", "元/单");
        row.put("settle_desc", "验收通过后由门店线下结算");
        row.put("start_date", LocalDateTime.now().toLocalDate().toString());
        // deliver_desc 在小程序列表和岗位管理里都显示为「交付日期」，列宽 varchar(32)；交付物全文另存 kg_online_task.deliverables。
        // 原先把最长 100 字的交付物写进来，超过 32 字整条插入失败（500），列表上还把交付物当成日期显示
        row.put("deliver_desc", deliverDesc(deadline));
        row.put("est_hours", StaffSupport.cut(str(b.get("estHours")).trim(), 16));
        row.put("headcount", quota);
        row.put("tags", skills.isEmpty() ? null : String.join(",", skills));
        row.put("content", desc.isEmpty() ? deliver : desc);
        row.put("requirement", String.join("\n", reqs));
        row.put("remote_flag", "0");
        row.put("publisher_name", StaffSupport.cut(publisher, 32));
        row.put("status", JobStatus.DRAFT);
        row.put("source", source);
        row.put("create_by", operator);
        row.put("create_time", new Date());
        Map<String, Object> ext = new LinkedHashMap<String, Object>();
        ext.put("task_type", type);
        ext.put("deliverables", deliver);
        ext.put("deadline_time", deadline.format(DT) + ":00");
        ext.put("commission_fen", fen);
        ext.put("reward_mode",perItem?"per_item":"fixed");
        ext.put("items_per_person",perItem?items:null);
        ext.put("unit_reward_fen",perItem?unitFen:null);
        ext.put("platform_fee_unit_fen",perItem?KgOnlineReward.PLATFORM_UNIT_FEN:0);
        ext.put("criteria", criteria);
        ext.put("announcement", StaffSupport.cut(str(b.get("announcement")).trim().isEmpty() ? "欢迎参与本次任务！请在截止时间前提交成果，逾期将无法提交。" : str(b.get("announcement")).trim(), 300));
        ext.put("create_by", operator);
        ext.put("create_time", new Date());
        return Arrays.asList(row, ext);
    }

    /** 单人佣金 1–5000 元：发布（createTask）与后台「岗位管理」改价共用同一条规则 */
    static void checkCommission(long fen)
    {
        if (fen < 100 || fen > 500000)
        {
            throw new ServiceException("佣金 1–5000 元", 422);
        }
    }

    /**
     * 后台岗位表单里的佣金（元，kg_job.salary_min）→ 分。按 BigDecimal 精确换算、最多两位小数，
     * 不能像 asLong 那样把 12.50 截成 12：kg_online_task.commission_fen 就是结算单金额。
     */
    public static long commissionFen(BigDecimal yuan)
    {
        if (yuan == null)
        {
            throw new ServiceException("请填写单人佣金（1–5000 元）", 422);
        }
        long fen;
        try
        {
            fen = yuan.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        }
        catch (ArithmeticException e)
        {
            throw new ServiceException("佣金最多两位小数", 422);
        }
        checkCommission(fen);
        return fen;
    }

    /** kg_job.deliver_desc（列表和岗位管理里显示为「交付日期」，varchar(32)）一律由截止时间生成，发布和后台改价写法一致 */
    static String deliverDesc(LocalDateTime deadline)
    {
        return deadline.format(DateTimeFormatter.ofPattern("M月d日 HH:mm")) + " 前";
    }

    /**
     * 后台「岗位管理」保存线上任务时调用，须与 kg_job 的更新处在同一事务（KgJobServiceImpl.updateKgJob）。
     * 小程序展示的价格读 kg_job.salary_min/max，结算单金额读 kg_online_task.commission_fen：原先岗位管理只改前者，
     * 运营把 12.5 元/单改成 20 元/单后用户看到 20、验收结算仍是 12.5。这里把佣金一并写回扩展行。
     *
     * 返回 kg_job.deliver_desc 应写的值：截止时间只在扩展行里，交付日期由它生成，不接受表单里手填的值
     *（后台没有改截止时间的入口，手改只会让「交付日期」和真正卡提交的截止时间对不上）。
     * 没有扩展行的老数据（早期在岗位管理直接新建的线上岗位）返回 null，调用方保持原值；它的结算回落到 salary_min，见 commission()。
     */
    public String syncFromJobEdit(Long jobId, long fen, String operator)
    {
        checkCommission(fen);
        Map<String, Object> t = db.one("select deadline_time,reward_mode,items_per_person,unit_reward_fen,platform_fee_unit_fen from kg_online_task where job_id = :id for update", p("id", jobId));
        if (t == null)
        {
            return null;
        }
        if(KgOnlineReward.perItem(t) && fen != KgOnlineReward.rewardUnit(t) * num(t.get("itemsPerPerson"))) throw new ServiceException("该任务按约定单价和合格条数结算，不能通过岗位改价修改已约定报酬",422);
        db.update("update kg_online_task set commission_fen = :f, update_by = :by, update_time = now() where job_id = :id",
                p("f", fen, "by", StaffSupport.cut(operator, 64), "id", jobId));
        // KgJdbc 把 datetime 列转成 "yyyy-MM-dd HH:mm:ss"，与 detail()/view() 一样取前 16 位
        return t.get("deadlineTime") == null ? null : deliverDesc(LocalDateTime.parse(str(t.get("deadlineTime")).substring(0, 16), DT));
    }

    private Map<String, Object> ownJob(StaffSupport.Ctx c, Long jobId)
    {
        Map<String, Object> j = db.one("select j.*, t.task_type, t.deliverables, t.deadline_time, t.commission_fen, t.reward_mode,t.items_per_person,t.unit_reward_fen,t.platform_fee_unit_fen, t.criteria, t.announcement, t.material_url, t.material_name "
                + "from kg_job j left join kg_online_task t on t.job_id = j.job_id where j.job_id = :id and j.del_flag = '0' and j.job_type = 'online'", p("id", jobId));
        if (j == null || !c.storeId.equals(KgJdbc.asLong(j.get("storeId"))))
        {
            throw new ServiceException("任务不存在或不属于当前门店", 404);
        }
        return j;
    }

    public Map<String,Object> rewardTerms(Long jobId) {
        Map<String,Object> t=db.one("select reward_mode,items_per_person,unit_reward_fen,platform_fee_unit_fen from kg_online_task where job_id=:id",p("id",jobId));
        Map<String,Object> out=p();KgOnlineReward.describe(t,out);return out;
    }

    static long commission(Map<String, Object> j)
    {
        if (j.get("commissionFen") != null)
        {
            return num(j.get("commissionFen"));
        }
        // 没有扩展行的老数据回落到 kg_job.salary_min（元，decimal(12,2)）。原先 num(...) * 100 先经 asLong 把 12.50 截成 12 再乘，
        // 结算单比岗位上写的少 0.5 元；按 BigDecimal 换算成分
        Object v = j.get("salaryMin");
        if (v == null || str(v).trim().isEmpty())
        {
            return 0L;
        }
        return new BigDecimal(str(v).trim()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    public Map<String, Object> detail(StaffSupport.Ctx c, Long jobId)
    {
        Map<String, Object> j = ownJob(c, jobId);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("jobId", jobId);
        m.put("title", j.get("title"));
        m.put("chip", (j.get("taskType") == null ? "在线任务" : j.get("taskType")) + " · " + statusLabel(str(j.get("status"))));
        m.put("hasExt", j.get("taskType") != null);
        m.put("content", j.get("content"));
        m.put("estHours", str(j.get("estHours")).isEmpty() ? "—" : j.get("estHours"));
        m.put("commission", "¥" + yuan(commission(j)));
        KgOnlineReward.describe(j,m);
        m.put("deadline", j.get("deadlineTime") == null ? "—" : str(j.get("deadlineTime")).substring(0, 16));
        // 到期关闭的给「继续开启」：能不能开看任务（reopenable），这个人能不能开看角色（不是合作商家、有线上任务·编辑），默认新时间 7 天后 18:00
        m.put("status", j.get("status"));
        m.put("reopenable", KgOnlineTaskReopen.reopenable(j));
        m.put("canReopen", KgOnlineTaskReopen.reopenable(j) && KgOnlineTaskReopen.canReopen(c.role));
        m.put("reopenDeadline", KgOnlineTaskReopen.defaultDeadline());
        List<String> tags = str(j.get("tags")).isEmpty() ? new ArrayList<String>() : Arrays.asList(str(j.get("tags")).split(","));
        m.put("skills", tags);
        List<String> jt = KgSkills.tagsOf(str(j.get("title")) + " " + str(j.get("tags")) + " " + str(j.get("content")));
        double sum = 0;
        int n = 0;
        for (Map<String, Object> r : db.list("select r.skill_text, r.extra_text from kg_application a left join kg_resume r on r.app_user_id = a.app_user_id "
                + "where a.job_id = :j and a.del_flag = '0' and a.status <> 'withdrawn'", p("j", jobId)))
        {
            if (jt.isEmpty())
            {
                break;
            }
            List<String> ut = KgSkills.tagsOf(str(r.get("skillText")) + " " + str(r.get("extraText")));
            int hit = 0;
            for (String t : jt)
            {
                hit += ut.contains(t) ? 1 : 0;
            }
            sum += hit * 100.0 / jt.size();
            n++;
        }
        m.put("avgMatch", n == 0 ? "—" : Math.round(sum / n) + "%");
        m.put("requirements", str(j.get("requirement")).isEmpty() ? new ArrayList<String>() : Arrays.asList(str(j.get("requirement")).split("\n")));
        m.put("headcount", j.get("headcount"));
        m.put("applied", db.count("select count(*) from kg_application where job_id = :j and del_flag = '0' and status <> 'withdrawn'", p("j", jobId)));
        m.put("pendingApplications", db.count("select count(*) from kg_application where job_id = :j and del_flag = '0' and status = 'submitted'", p("j", jobId)));
        m.put("canAuditApplications", StaffPerms.allows(c.role, "online", StaffPerms.AUDIT));
        m.put("assignees", db.count("select count(*) from kg_application where job_id = :j and del_flag = '0' and status in ('" + String.join("','", WORKING) + "')", p("j", jobId)));
        return m;
    }

    public List<Map<String, Object>> assignments(StaffSupport.Ctx c, Long jobId)
    {
        ownJob(c, jobId);
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> a : db.list("select a.app_id, a.real_name, a.status, u.nickname, (select x.status from kg_online_submission x where x.app_id = a.app_id order by x.round desc limit 1) sub, "
                + "(select max(x.create_time) from kg_online_submission x where x.app_id = a.app_id) last_time, (select s.status from kg_online_settlement s where s.app_id = a.app_id) settle, "
                + "(select count(*) from kg_online_material mt where mt.app_id = a.app_id and mt.del_flag = '0') material_count "
                + "from kg_application a left join kg_app_user u on u.user_id = a.app_user_id where a.job_id = :j and a.del_flag = '0' and (a.status in ('" + String.join("','", WORKING) + "') "
                + "or exists (select 1 from kg_online_submission x where x.app_id = a.app_id) or exists (select 1 from kg_online_settlement s where s.app_id = a.app_id)) "
                + "order by (select count(*) from kg_online_submission x where x.app_id = a.app_id and x.status = 'submitted') desc, a.app_id", p("j", jobId)))
        {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("appId", a.get("appId"));
            m.put("name", mask(str(a.get("realName")).isEmpty() ? str(a.get("nickname")) : str(a.get("realName"))));
            m.put("statusLabel", !WORKING.contains(str(a.get("status"))) && str(a.get("settle")).isEmpty()
                    ? "报名已结束 · 成果留存" : label(str(a.get("sub")), str(a.get("settle"))));
            m.put("lastSubmitTime", a.get("lastTime") == null ? null : StaffSupport.fmt(StaffSupport.parse(a.get("lastTime")), "MM-dd HH:mm"));
            // 进行中、还没给这位接单人发过素材（V37）：选人列表里标出来，提醒门店发素材
            m.put("materialCount", num(a.get("materialCount")));
            m.put("noMaterial", WORKING.contains(str(a.get("status"))) && str(a.get("settle")).isEmpty() && num(a.get("materialCount")) == 0);
            out.add(m);
        }
        return out;
    }

    static String label(String sub, String settle)
    {
        if (!settle.isEmpty())
        {
            return "pending_payout".equals(settle) ? "已验收 · 待门店付款" : ("payout_registered".equals(settle) ? "已登记付款 · 待确认" : ("confirmed".equals(settle) ? "结算完成" : "收款申诉中"));
        }
        return "submitted".equals(sub) ? "待验收" : ("returned".equals(sub) ? "已退回修改" : "进行中");
    }

    /**
     * 结算单状态 → 给人看的说法（待门店付款 / 待确认 / 结算完成 / 收款申诉中），没有结算单返回 null。
     * 员工端报名列表和详情用它：线上任务验收后报名一直停在「审核通过」，看不出是进行中还是已经做完了
     */
    public static String settleLabel(String settle)
    {
        return settle == null || settle.isEmpty() ? null : label("", settle);
    }

    /**
     * 退回修改后的重新提交期限：从退回时起留 RESUBMIT_HOURS 小时，晚于任务截止时间时才用它（早于截止就按截止算，返回 null）。
     * 不加新字段，按最近一轮的退回时间推出来。
     */
    static Date resubmitUntil(String latestStatus, Object reviewedTime, Date deadline)
    {
        Date reviewed = "returned".equals(latestStatus) ? StaffSupport.parse(reviewedTime) : null;
        if (reviewed == null || deadline == null)
        {
            return null;
        }
        Date until = new Date(reviewed.getTime() + RESUBMIT_HOURS * 3600000L);
        return until.after(deadline) ? until : null;
    }

    /** 拼进通知的退回原因 / 平台处理结果去掉句末标点，后面再接「。请…」时不会出现「。。」 */
    static String clause(String s)
    {
        return s == null ? "" : s.trim().replaceAll("[。.！!；;，,\\s]+$", "");
    }

    /** 逗号拼接的图片路径 → 列表（去空格、去空项）；空串 / null 是空列表 */
    static List<String> splitImages(Object v)
    {
        List<String> out = new ArrayList<String>();
        for (String x : str(v).split(","))
        {
            if (!x.trim().isEmpty())
            {
                out.add(x.trim());
            }
        }
        return out;
    }

    /** 某个接单的完整记录（员工 / 本人共用） */
    private Map<String, Object> view(Long appId, boolean staffView)
    {
        Map<String, Object> a = db.must("select a.*, u.nickname, u.avatar, u.status worker_status, u.del_flag worker_del_flag, j.title, j.store_id job_store, j.content, j.headcount, t.task_type, t.deliverables, t.deadline_time, "
                + "t.commission_fen, t.reward_mode,t.items_per_person,t.unit_reward_fen,t.platform_fee_unit_fen, t.criteria, t.announcement, t.material_url, t.material_name, j.salary_min, s.store_name, s.cover store_cover from kg_application a "
                + "join kg_job j on j.job_id = a.job_id left join kg_online_task t on t.job_id = j.job_id left join kg_app_user u on u.user_id = a.app_user_id "
                + "left join kg_store s on s.store_id = j.store_id where a.app_id = :id and a.del_flag = '0' and j.job_type = 'online'", p("id", appId), "接单记录不存在");
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("appId", appId);
        m.put("jobId", a.get("jobId"));
        m.put("storeId", a.get("jobStore"));
        m.put("title", a.get("title"));
        m.put("taskType", a.get("taskType") == null ? "在线任务" : a.get("taskType"));
        m.put("deliverables", a.get("deliverables") == null ? str(a.get("content")) : a.get("deliverables"));
        m.put("criteria", a.get("criteria"));
        m.put("announcement", a.get("announcement") == null ? "请在截止时间前提交成果，逾期将无法提交。" : a.get("announcement"));
        m.put("deadline", a.get("deadlineTime") == null ? null : str(a.get("deadlineTime")).substring(0, 16));
        m.put("deadlineTs", a.get("deadlineTime") == null ? null : StaffSupport.parse(a.get("deadlineTime")).getTime());
        m.put("commissionFen", commission(a));
        m.put("commission", "¥" + yuan(commission(a)));
        KgOnlineReward.describe(a,m);
        m.put("material", a.get("materialUrl") == null ? null : a.get("materialUrl"));
        m.put("materialName", a.get("materialName"));
        m.put("storeName", a.get("storeName"));
        m.put("storeCover", a.get("storeCover"));
        m.put("worker", staffView ? mask(str(a.get("realName")).isEmpty() ? str(a.get("nickname")) : str(a.get("realName"))) : "我");
        m.put("workerAvatar", staffView ? null : a.get("avatar"));
        Map<String, Object> taken = db.one("select min(create_time) t from kg_application_log where app_id = :id and to_status = 'review_passed'", p("id", appId));
        m.put("takenTime", taken == null || taken.get("t") == null ? (a.get("submitTime") == null ? null : StaffSupport.fmt(StaffSupport.parse(a.get("submitTime")), "MM-dd HH:mm"))
                : StaffSupport.fmt(StaffSupport.parse(taken.get("t")), "MM-dd HH:mm"));
        m.put("appStatus", a.get("status"));
        m.put("working", WORKING.contains(str(a.get("status"))));
        m.put("workerActive", "0".equals(str(a.get("workerStatus"))) && "0".equals(str(a.get("workerDelFlag"))));
        List<Map<String, Object>> subs = new ArrayList<Map<String, Object>>();
        // 最近一轮的提交 / 退回时间：推重新提交期限和「请平台介入」要用
        Object latestCreated = null;
        Object latestReviewed = null;
        for (Map<String, Object> x : db.list("select * from kg_online_submission where app_id = :id order by round desc", p("id", appId)))
        {
            if (subs.isEmpty())
            {
                latestCreated = x.get("createTime");
                latestReviewed = x.get("reviewedTime");
            }
            Map<String, Object> s = new LinkedHashMap<String, Object>();
            s.put("round", x.get("round"));
            s.put("submittedCount",x.get("submittedCount"));
            s.put("link", x.get("link"));
            // 2026-10-17 起一轮最多 3 个链接或口令（换行隔开存在 link 里）：拆好给页面一个一个显示；老数据就是一个
            s.put("links", splitLinks(x.get("link")));
            s.put("images", str(x.get("images")).isEmpty() ? new ArrayList<String>() : Arrays.asList(str(x.get("images")).split(",")));
            s.put("note", x.get("note"));
            s.put("status", x.get("status"));
            s.put("statusLabel", "submitted".equals(str(x.get("status"))) ? "待验收" : ("accepted".equals(str(x.get("status"))) ? "已通过" : "已退回"));
            s.put("comment", x.get("reviewComment"));
            // 退回修改附的截图（V41），门店端和接单人都返回；没有截图（或老数据）是空列表
            s.put("reviewImages", splitImages(x.get("reviewImages")));
            s.put("time", StaffSupport.fmt(StaffSupport.parse(x.get("createTime")), "yyyy-MM-dd HH:mm"));
            subs.add(s);
        }
        m.put("submissions", subs);
        m.put("latest", subs.isEmpty() ? null : subs.get(0));
        Map<String, Object> st = db.one("select * from kg_online_settlement where app_id = :id", p("id", appId));
        if (st != null)
        {
            // 验收已生成结算单后，本次接单金额以快照为准；后续岗位改价不能改变付款提示或历史记录。
            long settledFen = num(st.get("amountFen"));
            m.put("commissionFen", settledFen);
            m.put("commission", "¥" + yuan(settledFen));
            Map<String, Object> s = new LinkedHashMap<String, Object>();
            s.put("settleNo", st.get("settleNo"));
            s.put("status", st.get("status"));
            s.put("statusLabel", label("", str(st.get("status"))));
            s.put("amount", "¥" + yuan(settledFen));
            s.put("qualifiedCount",st.get("qualifiedCount"));
            s.put("unitRewardFen",st.get("unitRewardFen"));
            if(staffView) { s.put("platformFeeFen",st.get("platformFeeFen")); s.put("platformFeeUnitFen",st.get("platformFeeUnitFen")); }
            if(st.get("qualifiedCount")!=null)m.put("rewardText",st.get("qualifiedCount")+" 条合格 × ¥"+yuan(num(st.get("unitRewardFen")))+" = ¥"+yuan(settledFen));
            s.put("method", st.get("payoutMethod"));
            s.put("ref", st.get("payoutRef"));
            s.put("voucher", st.get("payoutVoucher"));
            s.put("registeredTime", st.get("registeredTime") == null ? null : StaffSupport.fmt(StaffSupport.parse(st.get("registeredTime")), "yyyy-MM-dd HH:mm"));
            s.put("confirmedTime", st.get("confirmedTime") == null ? null : StaffSupport.fmt(StaffSupport.parse(st.get("confirmedTime")), "yyyy-MM-dd HH:mm"));
            s.put("dispute", st.get("disputeReason"));
            s.put("correctionCount", db.count("select count(*) from kg_online_payout_correction where app_id = :id", p("id", appId)));
            s.put("canDispute", "payout_registered".equals(str(st.get("status")))
                    || ("pending_payout".equals(str(st.get("status"))) && st.get("createTime") != null
                        && StaffSupport.parse(st.get("createTime")).getTime() < System.currentTimeMillis() - 7L * 86400000L));
            // 申诉中也能自己确认已到账（钱其实到了就直接结束申诉）：之前申诉后只剩一个灰按钮，平台处理完也确认不了
            s.put("canConfirm", "payout_registered".equals(str(st.get("status"))) || "disputed".equals(str(st.get("status"))));
            m.put("settlement", s);
        }
        String latestStatus = subs.isEmpty() ? "" : str(subs.get(0).get("status"));
        Date now = new Date();
        Date deadlineAt = a.get("deadlineTime") == null ? null : StaffSupport.parse(a.get("deadlineTime"));
        // 截止前后被退回的，从退回时起至少留 RESUBMIT_HOURS 小时重新提交（见 resubmitUntil）
        Date resubmitUntil = st == null ? resubmitUntil(latestStatus, latestReviewed, deadlineAt) : null;
        boolean beforeDeadline = deadlineAt == null || deadlineAt.after(now) || (resubmitUntil != null && resubmitUntil.after(now));
        m.put("resubmitUntil", resubmitUntil == null ? null : StaffSupport.fmt(resubmitUntil, "yyyy-MM-dd HH:mm"));
        m.put("resubmitUntilTs", resubmitUntil == null ? null : resubmitUntil.getTime());
        m.put("resubmitHours", RESUBMIT_HOURS);
        m.put("canSubmit", Boolean.TRUE.equals(m.get("workerActive")) && WORKING.contains(str(a.get("status"))) && st == null && !"submitted".equals(latestStatus) && !"accepted".equals(latestStatus) && beforeDeadline);
        // 到截止时间任务已自动关闭、也没有重新提交期限（交了在等验收的除外，门店照常验收）：之前交付页还写「进行中」、提示可以提交，按钮却是灰的
        boolean closedOut = WORKING.contains(str(a.get("status"))) && st == null && !beforeDeadline && !"submitted".equals(latestStatus);
        m.put("taskClosed", closedOut);
        m.put("stateLabel", closedOut ? "任务已截止关闭" : !WORKING.contains(str(a.get("status"))) && st == null
                ? ("submitted".equals(str(a.get("status"))) ? "等待门店审核报名" : "报名已结束 · 成果留存")
                : label(latestStatus, st == null ? "" : str(st.get("status"))));
        // 成果交了门店一直不验收也不退回：提交满 ESCALATE_DAYS 天，接单人可以请平台介入（走平台「申报处理」，处理结果发给双方）
        boolean waitingReview = WORKING.contains(str(a.get("status"))) && st == null && "submitted".equals(latestStatus);
        Date escalateFrom = waitingReview && StaffSupport.parse(latestCreated) != null
                ? new Date(StaffSupport.parse(latestCreated).getTime() + ESCALATE_DAYS * 86400000L) : null;
        boolean escalated = waitingReview && db.count("select count(*) from kg_appeal where biz_type = 'online_review' and biz_id = :id and status = 'pending'",
                p("id", appId)) > 0;
        // 平台处理过这一轮的介入申请（处理完成或不予受理）后，不马上再放开「请平台介入」：从平台处理时起再等 ESCALATE_DAYS 天，
        // 门店仍没验收才能再请一次。之前平台一处理完就又能点，同一轮成果可以反复催平台、反复给门店发「接单人请平台介入验收」。
        // 只看这一轮提交之后发起的申请：退回后重新提交的新一轮，照旧从提交时间算
        boolean escalateAgain = false;
        if (waitingReview && !escalated && escalateFrom != null)
        {
            Map<String, Object> h = db.one("select max(coalesce(handled_time, create_time)) t from kg_appeal where biz_type = 'online_review' and biz_id = :id "
                    + "and status in ('resolved','rejected') and create_time >= :since", p("id", appId, "since", str(latestCreated)));
            Date handledAt = h == null ? null : StaffSupport.parse(h.get("t"));
            if (handledAt != null)
            {
                escalateAgain = true;
                Date again = new Date(handledAt.getTime() + ESCALATE_DAYS * 86400000L);
                if (again.after(escalateFrom))
                {
                    escalateFrom = again;
                }
            }
        }
        m.put("escalateAgain", escalateAgain);
        m.put("escalated", escalated);
        m.put("escalateFrom", escalateFrom == null ? null : StaffSupport.fmt(escalateFrom, "MM-dd HH:mm"));
        m.put("canEscalate", waitingReview && !escalated && escalateFrom != null && !escalateFrom.after(now) && Boolean.TRUE.equals(m.get("workerActive")));
        // 门店发给这位接单人的任务素材（V37 kg_online_material，按发送先后）和交付步骤条。
        // 之前只有任务级的一个参考素材链接，接单人在交付页看不到门店单独发给自己的素材，也看不出走到了哪一步
        // 随素材发出的附件（V41 kg_online_material_file）一次查出来，按素材归好；没发过素材就不查
        List<Map<String, Object>> materialRows = db.list("select material_id, content, images, sent_by, sent_time from kg_online_material "
                + "where app_id = :id and del_flag = '0' order by sent_time, material_id", p("id", appId));
        List<Map<String, Object>> fileRows = materialRows == null || materialRows.isEmpty() ? null
                : db.list("select file_id, material_id, file_name, file_ext, file_size from kg_online_material_file "
                        + "where app_id = :id and material_id is not null and del_flag = '0' order by file_id", p("id", appId));
        List<Map<String, Object>> materials = KgOnlineMaterialService.rows(materialRows, staffView, str(a.get("storeName")), fileRows);
        m.put("materials", materials);
        Map<String, Object> flow = KgOnlineMaterialService.flow(str(a.get("status")), a.get("submitTime"), m.get("takenTime"), materials, subs, st);
        if (WORKING.contains(str(a.get("status"))) && st == null)
        {
            // 步骤条下的提示按重新提交期限、请平台介入的情况说清楚
            if ("returned".equals(latestStatus))
            {
                // 门店附了截图的（V41），提示里一并说「看看原因和截图」
                List<?> shots = (List<?>) subs.get(0).get("reviewImages");
                String look = shots == null || shots.isEmpty() ? "看看原因" : "看看原因和截图";
                flow.put("tip", !beforeDeadline ? "重新提交的期限已过，这一轮不能再交了；有疑问可以私信主理人"
                        : resubmitUntil != null ? "成果被退回修改，请在 " + StaffSupport.fmt(resubmitUntil, "MM-dd HH:mm") + " 前" + look + "后重新提交"
                        : "成果被退回修改，" + look + "后重新提交");
            }
            else if (closedOut)
            {
                flow.put("tip", "任务已到截止时间并关闭，不能再提交成果；门店继续开启的话会通知你");
            }
            else if (escalated)
            {
                flow.put("tip", "你已请平台介入，平台会联系门店尽快验收，处理结果会发到消息里");
            }
            else if (Boolean.TRUE.equals(m.get("canEscalate")))
            {
                flow.put("tip", escalateAgain ? "平台处理后门店仍没验收，可以再点「请平台介入」"
                        : "成果提交已超过 " + ESCALATE_DAYS + " 天门店还没验收，可以点「请平台介入」");
            }
            else if (escalateAgain)
            {
                flow.put("tip", "平台已处理你的介入申请，结果见消息；到 " + m.get("escalateFrom") + " 门店还没验收的话，可以再次请平台介入");
            }
        }
        m.put("flow", flow);
        m.put("appUserId", a.get("appUserId"));
        return m;
    }

    public Map<String, Object> assignment(StaffSupport.Ctx c, Long appId)
    {
        Map<String, Object> m = view(appId, true);
        if (!c.storeId.equals(KgJdbc.asLong(m.get("storeId"))))
        {
            throw new ServiceException("接单记录不属于当前门店", 404);
        }
        boolean activeAssignment = Boolean.TRUE.equals(m.get("working")) && Boolean.TRUE.equals(m.get("workerActive"));
        m.put("canReview", activeAssignment && StaffPerms.allows(c.role, "online", StaffPerms.EDIT) && !c.uid().equals(KgJdbc.asLong(m.get("appUserId"))));
        m.put("reviewBlockReason", activeAssignment ? null : ("submitted".equals(str(m.get("appStatus")))
                ? "报名尚未通过审核，不能验收成果" : "报名已结束或接单账号不可用，成果仅供查阅，不能继续验收；已有结算仍可按原流程处理"));
        String payRole = payoutRole(c, KgJdbc.asLong(m.get("jobId")));
        m.put("canCorrectPayout", canCorrectPayout(payRole) && !c.uid().equals(KgJdbc.asLong(m.get("appUserId"))));
        // 发素材（V37）：与验收同一档权限（线上任务·编辑），接单仍在进行、不是自己的接单、结算还没确认完成
        Object settled = m.get("settlement") instanceof Map ? ((Map<?, ?>) m.get("settlement")).get("status") : null;
        m.put("canSendMaterial", activeAssignment && StaffPerms.allows(c.role, "online", StaffPerms.EDIT)
                && !c.uid().equals(KgJdbc.asLong(m.get("appUserId"))) && !"confirmed".equals(settled));
        m.remove("appUserId");
        m.put("canPay", StaffPerms.canRegisterOnlinePayout(payRole));
        if (recipients != null && Boolean.TRUE.equals(m.get("canPay")) && m.get("settlement") != null) m.put("recipient", recipients.view(appId, false));
        if (StaffRole.ONLINE_STAFF.equals(c.role) && m.get("settlement") instanceof Map)
        {
            // 验收需要任务金额和状态，不需要付款凭证、流水号或争议详情：只留单号、金额和结算到了哪一步。
            // 之前整块拿掉，专员验收通过后再打开这单，页面按「没有结算单」显示成「等待提交」
            Map<?, ?> full = (Map<?, ?>) m.get("settlement");
            Map<String, Object> brief = new LinkedHashMap<String, Object>();
            for (String k : Arrays.asList("settleNo", "status", "statusLabel", "amount"))
            {
                brief.put(k, full.get(k));
            }
            m.put("settlement", brief);
        }
        m.put("payoutMethods", StaffRole.ONLINE_STAFF.equals(c.role) ? java.util.Collections.emptyList() : PAYOUT_METHODS);
        return m;
    }

    /**
     * 员工端报名详情用（AppStaffController，调用方已核对门店授权）：线上接单走到哪一步、给员工看的一句提示、接单人现在还能不能提交成果。
     * onlineStep 对应详情步骤条「提交 / 审核 / 确认」：3 = 进行中（当前在「确认」），4 = 结算完成（三步都完成）。
     * 报名还没通过或已经结束（驳回 / 终止 / 撤回）时返回空，页面照旧按报名状态显示。
     * 之前详情只按报名状态写「申请已通过，无需重复审核。接单人可在任务交付页提交成果」，结算都完成了也这么写，步骤条停在「确认」
     */
    public Map<String, Object> staffStage(Long appId)
    {
        Map<String, Object> v = view(appId, true);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Map<?, ?> st = v.get("settlement") instanceof Map ? (Map<?, ?>) v.get("settlement") : null;
        String settle = st == null ? "" : str(st.get("status"));
        Map<?, ?> latest = v.get("latest") instanceof Map ? (Map<?, ?>) v.get("latest") : null;
        String latestStatus = latest == null ? "" : str(latest.get("status"));
        if (!Boolean.TRUE.equals(v.get("working")))
        {
            // 还没通过 / 已驳回 / 已终止 / 已撤回：页面按报名状态显示（已终止的单子不能写成「进行中」）
            return out;
        }
        boolean canSubmit = Boolean.TRUE.equals(v.get("canSubmit"));
        String tip;
        if ("confirmed".equals(settle))
        {
            tip = "结算完成：接单人已确认收款，这单线上任务已经做完。";
        }
        else if ("payout_registered".equals(settle))
        {
            tip = "成果已验收，门店已登记付款，等接单人核对到账后确认收款。";
        }
        else if ("disputed".equals(settle))
        {
            tip = "成果已验收，接单人对收款提出了申诉，平台和主理人会核实处理。";
        }
        else if (st != null)
        {
            tip = "成果已验收，结算单已生成，等主理人线下付款后登记凭证。";
        }
        else if ("submitted".equals(latestStatus))
        {
            tip = "接单人已提交第 " + latest.get("round") + " 轮成果，等门店验收或退回修改。";
        }
        else if (canSubmit)
        {
            tip = "returned".equals(latestStatus) ? "成果已退回修改，等接单人重新提交。" : "申请已通过，无需重复审核。接单人可在任务交付页提交成果。";
        }
        else if (!Boolean.TRUE.equals(v.get("workerActive")))
        {
            tip = "接单账号已注销或不可用，不能再提交成果。";
        }
        else
        {
            tip = "已过提交期限，接单人不能再提交成果。";
        }
        out.put("onlineStep", "confirmed".equals(settle) ? 4 : 3);
        out.put("onlineTip", tip);
        out.put("workerCanSubmit", canSubmit);
        return out;
    }

    /** 设计（m2 3.1）：自己的接单不得由自己验收、结算——否则主理人可以自接、自验、自登记打款，给自己凭空生成用工结算（2026-09-28 复核发现） */
    private void assertNotOwnAssignment(StaffSupport.Ctx c, Long appId, String what)
    {
        Map<String, Object> a = db.one("select app_user_id from kg_application where app_id = :id", p("id", appId));
        if (a != null && c.uid().equals(KgJdbc.asLong(a.get("appUserId"))))
        {
            throw new ServiceException("不能" + what + "自己的接单，请由本店其他员工或平台处理", 403);
        }
    }

    /** 与账号注销使用同一把账号锁，再锁报名；拒绝失效会话及撤回后的旧页面继续提交/验收。 */
    private Map<String, Object> lockWorkingAssignment(Long appId)
    {
        Map<String, Object> pointer = db.one("select app_user_id from kg_application where app_id = :id", p("id", appId));
        if (pointer == null) throw new ServiceException("接单记录不存在", 404);
        Long uid = KgJdbc.asLong(pointer.get("appUserId"));
        Map<String, Object> user = db.one("select status, del_flag from kg_app_user where user_id = :u for update", p("u", uid));
        if (user == null || !"0".equals(str(user.get("status"))) || !"0".equals(str(user.get("delFlag"))))
        {
            throw new ServiceException("接单账号已注销或不可用，不能继续提交或验收", 409);
        }
        Map<String, Object> app = db.one("select app_user_id, job_id, store_id, status, del_flag from kg_application where app_id = :id for update", p("id", appId));
        if (app == null || !uid.equals(KgJdbc.asLong(app.get("appUserId"))) || !"0".equals(str(app.get("delFlag")))
                || !WORKING.contains(str(app.get("status"))))
        {
            throw new ServiceException("报名已撤回或结束，不能继续提交或验收", 409);
        }
        return app;
    }

    /** 成果链接或分享口令的字数上限（V47 起 kg_online_submission.link 是 varchar(500)）；2026-10-17 起是「每一个」的上限 */
    static final int LINK_MAX = 500;

    /**
     * 一轮成果最多几个链接或口令（2026-10-17 主人反馈：有人一次发了好几篇帖子，两个帖子分两栏交两个地址，三个就分三栏）。
     * 几个链接存在同一列 kg_online_submission.link 里、用换行隔开（每个链接里的换行和连续空白先并成一个空格，换行就只当分隔符用）；
     * V50 把这一列放宽到 varchar(1600)（3 × 500 + 分隔符）
     */
    static final int LINKS_MAX = 3;

    /** 把一个链接或口令收成一行：去首尾空白，中间的换行、连续空白并成一个空格（分享口令不需要换行） */
    static String oneLine(Object v)
    {
        return str(v).replaceAll("\\s+", " ").trim();
    }

    /** 库里存的成果链接（换行隔开，老数据是一段）拆成列表，空的去掉；没有链接是空列表 */
    static List<String> splitLinks(Object v)
    {
        List<String> out = new ArrayList<String>();
        for (String s : str(v).split("\n"))
        {
            if (!s.trim().isEmpty())
            {
                out.add(s.trim());
            }
        }
        return out;
    }

    /** 退回说明的字数上限（V41 review_comment 已改成 text）；验收通过的评语仍是 COMMENT_MAX */
    static final int RETURN_MAX = 5000;

    static final int COMMENT_MAX = 200;

    /** 退回修改最多附几张截图，以及拼起来的长度上限（review_images 列宽） */
    static final int REVIEW_IMAGES_MAX = 9;

    static final int REVIEW_IMAGES_LEN = 2000;

    /** 验收 / 退回（不带截图）：验收通过和老调用方走这里。同一个类里转调不经过事务代理，所以这里也要开事务 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> review(StaffSupport.Ctx c, Long appId, boolean pass, String text)
    {
        return review(c, appId, pass, text, null);
    }

    /** 退回截图：去空、去重，只认本平台上传的图片，最多 9 张、拼起来不超过 2000 字符 */
    static List<String> reviewImages(Object raw)
    {
        List<String> images = new ArrayList<String>();
        if (raw instanceof List)
        {
            for (Object o : (List<?>) raw)
            {
                String url = str(o).trim();
                if (url.isEmpty())
                {
                    continue;
                }
                if (!KgJdbc.isPlatformImage(url))
                {
                    throw new ServiceException("退回截图只能用本平台上传的图片", 422);
                }
                if (!images.contains(url))
                {
                    images.add(url);
                }
            }
        }
        else if (raw != null && !str(raw).trim().isEmpty())
        {
            throw new ServiceException("退回截图参数不正确，请重新添加", 422);
        }
        if (images.size() > REVIEW_IMAGES_MAX)
        {
            throw new ServiceException("最多附 " + REVIEW_IMAGES_MAX + " 张截图", 422);
        }
        if (String.join(",", images).length() > REVIEW_IMAGES_LEN)
        {
            throw new ServiceException("截图太多了，请删几张再退回", 422);
        }
        return images;
    }

    /**
     * 退回通知正文：只放简短预览 + 截图数 + 截止提醒，保证截止时间不会被站内信 500 字截掉；完整原因和截图在交付页看。
     * until 不为空 = 截止前后才退回，接单人要在这个时间前重新提交
     */
    static String returnNotice(String title, String text, int images, Date until)
    {
        String t = clause(text).replaceAll("\\s+", " ");
        String preview = t.isEmpty() ? "门店附了 " + images + " 张截图，标出了要改的地方"
                : StaffSupport.cut(t, 60) + (t.length() > 60 ? "…" : "") + (images > 0 ? "（附 " + images + " 张截图）" : "");
        return "「" + title + "」：" + preview + (until != null ? "。请在 " + StaffSupport.fmt(until, "MM-dd HH:mm") + " 前修改后重新提交"
                : "。点开查看完整原因" + (images > 0 ? "和截图" : "") + "，改好后重新提交");
    }

    /**
     * 验收通过 / 退回修改。退回时文字和截图至少要有一样：文字最多 5000 字、过违禁词；截图最多 9 张（rawImages，通过时忽略）。
     * 通过时的评语仍限 200 字。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> review(StaffSupport.Ctx c, Long appId, boolean pass, String text, Object rawImages)
    {
        return review(c,appId,pass,text,rawImages,null);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String,Object> review(StaffSupport.Ctx c,Long appId,boolean pass,String text,Object rawImages,Object qualifiedCount)
    {
        support.need(c, "online", StaffPerms.EDIT, pass ? "online_accept" : "online_return", pass ? "线上任务验收" : "线上任务退回");
        Map<String, Object> v = assignment(c, appId);
        assertNotOwnAssignment(c, appId, "验收");
        Map<String, Object> a = lockWorkingAssignment(appId);
        if (!c.storeId.equals(KgJdbc.asLong(a.get("storeId")))) throw new ServiceException("接单记录不属于当前门店", 404);
        Map<String, Object> last = db.one("select * from kg_online_submission where app_id = :id order by round desc limit 1 for update", p("id", appId));
        if (last == null || !"submitted".equals(str(last.get("status"))))
        {
            throw new ServiceException("没有待验收的成果", 409);
        }
        // Read authoritative reward terms after acquiring the assignment lock.
        Map<String,Object> terms=db.one("select reward_mode,items_per_person,unit_reward_fen,platform_fee_unit_fen,commission_fen from kg_online_task where job_id=:id for update",p("id",a.get("jobId")));
        Map<String,Object> reward=pass ? KgOnlineReward.settlement(terms,last,qualifiedCount,num(v.get("commissionFen"))) : p();
        String t = text == null ? "" : text.trim();
        List<String> shots = pass ? new ArrayList<String>() : reviewImages(rawImages);
        if (!pass && t.isEmpty() && shots.isEmpty())
        {
            throw new ServiceException("请写退回原因或附上截图", 422);
        }
        if (!pass && t.length() > RETURN_MAX)
        {
            throw new ServiceException("退回说明不能超过 " + RETURN_MAX + " 字", 422);
        }
        if (pass && t.length() > COMMENT_MAX)
        {
            throw new ServiceException("评语不超过 " + COMMENT_MAX + " 字", 422);
        }
        if (!pass && !t.isEmpty() && guard != null)
        {
            // 退回说明和成果说明、素材一样过违禁词（单测里没注入时跳过）
            guard.check(t);
        }
        db.update("update kg_online_submission set status = :s, review_comment = :c, review_images = :imgs, reviewed_by = :by, reviewed_time = now() where submission_id = :id",
                p("s", pass ? "accepted" : "returned", "c", t.isEmpty() ? null : t, "imgs", String.join(",", shots), "by", StaffSupport.cut(c.name(), 64),
                        "id", last.get("submissionId")));
        // 接单人请过平台介入验收（escalate）、平台还没处理的：门店已经验收 / 退回，这条申诉随之结束，免得平台再去催
        db.update("update kg_appeal set status = 'resolved', reply = :r, handled_by = :by, handled_time = now() "
                + "where biz_type = 'online_review' and biz_id = :id and status = 'pending'",
                p("r", pass ? "门店已验收通过，本次验收申诉已结束。" : "门店已退回修改并说明了原因，本次验收申诉已结束。", "by", "门店已处理", "id", appId));
        Long uid = KgJdbc.asLong(a.get("appUserId"));
        if (pass)
        {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("settle_no", "OS" + StaffSupport.fmt(new Date(), "yyyyMMddHHmmss") + String.format("%03d", ThreadLocalRandom.current().nextInt(1000)));
            row.put("app_id", appId);
            row.put("job_id", a.get("jobId"));
            row.put("store_id", c.storeId);
            row.put("app_user_id", uid);
            row.putAll(reward);
            row.put("status", "pending_payout");
            row.put("create_time", new Date());
            db.insert("kg_online_settlement", row);
            // 被邀请的新朋友第一次线上任务验收通过：给邀请人发邀请积分（每位朋友只发一次，跟验收同一个事务）。单测里没注入时跳过。
            // 之前不看是谁验收的；现在把本店和验收人带过去：验收人就是邀请人、或邀请人是本店员工时不发
            if (invites != null)
            {
                invites.rewardInviter(uid, "online", c.storeId, c.uid());
            }
            support.notifyUser(uid, "apply", "成果已验收通过", "「" + v.get("title") + "」验收通过，结算单已生成（" + "¥"+yuan(num(reward.get("amount_fen"))) + "），门店线下付款后请在任务页确认收款",
                    "application", appId, "/pages/jobs/deliver?appId=" + appId);
            support.notifyStore(c.storeId, "system", "线上任务待付款", "「" + v.get("title") + "」已验收，请线下付款后登记凭证", "online_settlement", appId,
                    "/pages/staff/online-jobs?appId=" + appId);
        }
        else
        {
            // 截止前后才退回的，接单人从现在起还有 RESUBMIT_HOURS 小时重新提交（view() 按这一轮的退回时间推出同一个期限）
            Date until = new Date(System.currentTimeMillis() + RESUBMIT_HOURS * 3600000L);
            boolean extended = v.get("deadlineTs") instanceof Number && ((Number) v.get("deadlineTs")).longValue() < until.getTime();
            // 标题「成果被退回修改」不变（e2e S7 靠它判断）；正文只放预览，完整原因和截图在交付页看
            support.notifyUser(uid, "apply", "成果被退回修改", returnNotice(String.valueOf(v.get("title")), t, shots.size(), extended ? until : null),
                    "application", appId, "/pages/jobs/deliver?appId=" + appId);
        }
        support.op(c, pass ? "online_accept" : "online_return", pass ? "线上任务验收通过" : "线上任务退回修改", "general",
                String.valueOf(v.get("title")) + (shots.isEmpty() ? "" : "（附 " + shots.size() + " 张图）"), true, null);
        return assignment(c, appId);
    }

    /**
     * 线上任务付款登记看的角色。兼任的小院主理人只在「商家的任务」上算商家服务人：这个任务真的来自这家店的商家需求，且门店不是直营（商家店正好是他当小院主理人的店，没有单独的商家服务人授权）。
     * 直营店的任务、门店自己发的任务还是小院主理人（finance·支付 = no），开了兼任也不能登记、更正付款。别的账号一律是 Ctx 上的角色。
     */
    String payoutRole(StaffSupport.Ctx c, Long jobId)
    {
        if (jobId == null || !KgMerchantRequestService.alsoService(c))
        {
            return c.role;
        }
        return db.count("select count(*) from kg_merchant_request r join kg_store s on s.store_id = r.store_id "
                + "where r.job_id = :j and r.store_id = :s and r.del_flag = '0' and coalesce(s.op_type, '') <> 'direct'", p("j", jobId, "s", c.storeId)) > 0
                        ? StaffRole.MERCHANT_SERVICE : c.role;
    }

    /** 同上，控制器手里只有接单记录 */
    public String payoutRoleOfApp(StaffSupport.Ctx c, Long appId)
    {
        if (!KgMerchantRequestService.alsoService(c))
        {
            return c.role;
        }
        Map<String, Object> a = db.one("select job_id from kg_application where app_id = :id and del_flag = '0'", p("id", appId));
        return payoutRole(c, a == null ? null : KgJdbc.asLong(a.get("jobId")));
    }

    /** 争议后谁能更正付款凭证：本店主理人，以及实际付钱的合作商家、替他跑的商家服务人（换工主理人进不了 /online，否则争议单没人能更正） */
    private static boolean canCorrectPayout(String role)
    {
        // 切换后换工主理人不碰钱
        return (!RolePhase.isNew() && StaffRole.MANAGER.equals(role)) || StaffRole.MERCHANT.equals(role) || StaffRole.MERCHANT_SERVICE.equals(role)
                || (RolePhase.isNew() && StaffRole.CITY_LEAD.equals(role));
    }

    /** 登记线下付款；争议后可更正凭证（见 canCorrectPayout），不修改结算金额，不执行转账。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> payout(StaffSupport.Ctx c, Long appId, Map<String, Object> b)
    {
        Map<String, Object> v = assignment(c, appId);
        assertNotOwnAssignment(c, appId, "登记付款");
        String payRole = payoutRoleOfApp(c, appId);
        if (!StaffPerms.canRegisterOnlinePayout(payRole))
        {
            throw new ServiceException("当前角色不能登记付款", 403);
        }
        for (String key : Arrays.asList("amount", "amountFen", "amount_fen", "commission", "commissionFen"))
        {
            if (b.containsKey(key))
            {
                throw new ServiceException("结算金额由验收单确定，登记或更正凭证不能修改金额", 422);
            }
        }
        for (String key : b.keySet())
        {
            if (!Arrays.asList("method", "ref", "voucher", "correctionNote").contains(key))
            {
                throw new ServiceException("付款凭证只支持方式、流水号、凭证和更正说明", 422);
            }
        }
        String method = str(b.get("method"));
        if (!PAYOUT_METHODS.contains(method))
        {
            throw new ServiceException("请选择付款方式", 422);
        }
        String voucher = str(b.get("voucher")).trim();
        if (voucher.isEmpty() || voucher.length() > 255 || !KgJdbc.isPlatformImage(voucher))
        {
            throw new ServiceException("请上传本平台的付款凭证图片", 422);
        }
        Map<String, Object> before = db.one("select * from kg_online_settlement where app_id = :id for update", p("id", appId));
        String beforeStatus = before == null ? "" : str(before.get("status"));
        boolean correction = "disputed".equals(beforeStatus);
        if (!"pending_payout".equals(beforeStatus) && !correction)
        {
            throw new ServiceException("结算单当前不能登记或更正付款凭证，请刷新后查看", 409);
        }
        String correctionNote = str(b.get("correctionNote")).trim();
        if (correction && !canCorrectPayout(payRole))
        {
            throw new ServiceException("当前角色不能核实并更正争议付款凭证", 403);
        }
        if (correction && (correctionNote.isEmpty() || correctionNote.length() > 200))
        {
            throw new ServiceException("请填写核实与更正说明（200 字内）", 422);
        }
        String payoutRef = str(b.get("ref")).trim();
        if (payoutRef.length() > 64)
        {
            throw new ServiceException("付款流水号不能超过 64 字", 422);
        }
        int n = db.update("update kg_online_settlement set status = 'payout_registered', payout_method = :m, payout_ref = :r, payout_voucher = :v, registered_by = :by, "
                + "registered_time = now(), update_by = :by, update_time = now() where app_id = :id and status = :oldStatus",
                p("m", method, "r", payoutRef, "v", voucher, "by", StaffSupport.cut(c.name(), 64), "id", appId, "oldStatus", beforeStatus));
        if (n == 0)
        {
            throw new ServiceException("结算状态已变化，请刷新后查看", 409);
        }
        Long correctionId = null;
        if (correction)
        {
            // 独立审计表保存完整旧/新凭证，避免员工通用日志 255 字限制截断证据。
            correctionId = db.insert("kg_online_payout_correction", p(
                    "settle_id", before.get("settleId"), "app_id", appId, "store_id", c.storeId,
                    "amount_fen", before.get("amountFen"), "operator_id", c.uid(), "operator_name", StaffSupport.cut(c.name(), 64),
                    "old_method", before.get("payoutMethod"), "old_ref", before.get("payoutRef"), "old_voucher", before.get("payoutVoucher"),
                    "new_method", method, "new_ref", payoutRef, "new_voucher", voucher,
                    "dispute_reason", before.get("disputeReason"), "correction_note", correctionNote, "create_time", new Date()));
        }
        Map<String, Object> a = db.one("select app_user_id from kg_application where app_id = :id", p("id", appId));
        String amount = "¥" + yuan(num(before.get("amountFen")));
        support.notifyUser(KgJdbc.asLong(a.get("appUserId")), "apply", correction ? "门店已更正付款凭证" : "门店已登记线下付款",
                "「" + v.get("title") + "」" + amount + (correction ? " 的付款凭证已核实更正，请查看新的" + method + "记录，核对实际到账后重新确认收款。本次操作仅更正凭证，不会发起转账。"
                        : " 已通过" + method + "付款，请核对到账后在任务页确认收款"), "application", appId, "/pages/jobs/deliver?appId=" + appId);
        support.op(c, correction ? "online_payout_correct" : "online_payout", correction ? "更正争议付款凭证" : "登记线下付款", "fund",
                v.get("title") + " " + amount + (correction ? "；凭证更正记录 " + correctionId : ""), true, null);
        return assignment(c, appId);
    }

    // ---------------------------------------------------------------- 用户：本人接单

    private Map<String, Object> mineOrThrow(Long appId)
    {
        AppLoginUser me = AppUserContext.require();
        Map<String, Object> m = view(appId, false);
        if (!me.getUserId().equals(KgJdbc.asLong(m.get("appUserId"))))
        {
            throw new ServiceException("接单记录不存在", 404);
        }
        return m;
    }

    public Map<String, Object> mine(Long appId)
    {
        Map<String, Object> m = mineOrThrow(appId);
        if (recipients != null && m.get("settlement") instanceof Map) {
            Map<?,?> settlement=(Map<?,?>)m.get("settlement");
            m.put("recipient",recipients.view(appId,"pending_payout".equals(settlement.get("status"))));
        }
        m.remove("appUserId");
        return m;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submit(Long appId, Map<String, Object> b)
    {
        Map<String, Object> v = mineOrThrow(appId);
        lockWorkingAssignment(appId);
        // 等待账号/报名锁期间，另一请求可能已提交或验收。锁定读取最新状态，不能沿用旧页面快照。
        Map<String, Object> current = db.one("select status from kg_online_submission where app_id = :id order by round desc limit 1 for update", p("id", appId));
        Map<String, Object> settled = db.one("select settle_id from kg_online_settlement where app_id = :id for update", p("id", appId));
        if (settled != null || (current != null && Arrays.asList("submitted", "accepted").contains(str(current.get("status")))))
        {
            throw new ServiceException("当前不能提交（等待验收中或已验收）", 409);
        }
        if (!Boolean.TRUE.equals(v.get("canSubmit")))
        {
            throw new ServiceException(Boolean.TRUE.equals(v.get("working")) ? "当前不能提交（等待验收中、已验收或已过截止时间）" : "门店审核通过后才能提交成果", 409);
        }
        // 2026-10-17 主人反馈：新版小程序分三栏交，传 links（列表，最多 3 个，每个 500 字内）；
        // 老版本小程序只传 link 一段文字，照旧按一个链接收（规则和提示都不变）
        boolean listed = b.get("links") instanceof List;
        List<String> links = new ArrayList<String>();
        if (listed)
        {
            for (Object o : (List<?>) b.get("links"))
            {
                if (!oneLine(o).isEmpty())
                {
                    links.add(oneLine(o));
                }
            }
        }
        else if (!oneLine(b.get("link")).isEmpty())
        {
            links.add(oneLine(b.get("link")));
        }
        String link = String.join("\n", links);
        String note = str(b.get("note")).trim();
        List<String> images = new ArrayList<String>();
        if (b.get("images") instanceof List)
        {
            for (Object o : (List<?>) b.get("images"))
            {
                if (!str(o).isEmpty())
                {
                    if (!KgJdbc.isPlatformImage(str(o).trim()))
                    {
                        throw new ServiceException("交付图片只能使用本平台上传的图片", 422);
                    }
                    images.add(str(o).trim());
                }
            }
        }
        // 成果链接原样保存、不再查格式（2026-10-16 主人定）：小红书、抖音等 App 复制出来的是「链接 + 口令」一整段文字，
        // 以前要求以 http(s):// 开头，粘贴分享口令就被拒。门店那边会把其中的链接标出来、点了复制
        if (links.isEmpty() && images.isEmpty())
        {
            throw new ServiceException("请提交成果链接或图片", 422);
        }
        if (links.size() > LINKS_MAX)
        {
            throw new ServiceException("最多提交 " + LINKS_MAX + " 个链接或口令", 422);
        }
        for (int i = 0; listed && i < links.size(); i++)
        {
            if (links.get(i).length() > LINK_MAX)
            {
                throw new ServiceException("第 " + (i + 1) + " 个链接或口令超过 " + LINK_MAX + " 字", 422);
            }
        }
        if (images.size() > 9 || (!listed && link.length() > LINK_MAX) || note.length() > 300)
        {
            throw new ServiceException("图片最多 9 张，链接或口令 " + LINK_MAX + " 字内，说明 300 字内", 422);
        }
        // 现在链接一栏可以是任意文字，和补充说明一起过内容检查（几个链接每个都查）
        if (listed)
        {
            List<String> texts = new ArrayList<String>(links);
            texts.add(note);
            guard.check(texts.toArray(new String[0]));
        }
        else
        {
            guard.check(link, note);
        }
        long round = db.count("select coalesce(max(round), 0) from kg_online_submission where app_id = :id", p("id", appId)) + 1;
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("app_id", appId);
        row.put("round", round);
        row.put("link", link);
        row.put("images", String.join(",", images));
        row.put("note", note);
        if(KgOnlineReward.perItem(v)) row.put("submitted_count",KgOnlineReward.count(b.get("submittedCount"),(int)num(v.get("itemsPerPerson")),"本次交付条数"));
        row.put("status", "submitted");
        row.put("create_time", new Date());
        try
        {
            db.insert("kg_online_submission", row);
        }
        catch (org.springframework.dao.DuplicateKeyException e)
        {
            throw new ServiceException("正在提交，请勿重复点击", 409);
        }
        support.notifyStore(KgJdbc.asLong(v.get("storeId")), "system", "线上任务有新成果", "「" + v.get("title") + "」收到第 " + round + " 轮成果，请验收", "online_submission", appId,
                "/pages/staff/online-jobs?appId=" + appId);
        return mine(appId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> confirm(Long appId, boolean received, String reason)
    {
        Map<String, Object> v = mineOrThrow(appId);
        if (received)
        {
            // 申诉中（disputed）也可以确认：钱其实到了，接单人自己确认就结束申诉；之前只认「已登记付款」，申诉后再也确认不了
            int n = db.update("update kg_online_settlement set status = 'confirmed', confirmed_time = now(), update_time = now() where app_id = :id "
                    + "and status in ('payout_registered', 'disputed')", p("id", appId));
            if (n == 0)
            {
                throw new ServiceException("门店还没有登记付款", 409);
            }
            // 本人确认实际到账即解决该接单的未收款争议；不改变已处理申诉及其他业务的申诉。
            db.update("update kg_appeal set status = 'resolved', reply = :r, handled_by = :by, handled_time = now() "
                    + "where biz_type = 'online_settlement' and biz_id = :id and app_user_id = :u and status = 'pending'",
                    p("r", "接单人已确认实际收到结算款，本次未收款争议已解决；原申诉与付款凭证更正记录保留。",
                            "by", "接单人确认收款", "id", appId, "u", AppUserContext.require().getUserId()));
            support.notifyStore(KgJdbc.asLong(v.get("storeId")), "system", "接单人已确认收款", "「" + v.get("title") + "」结算完成", "online_settlement", appId,
                    "/pages/staff/online-jobs?appId=" + appId);
        }
        else
        {
            String r = reason == null ? "" : reason.trim();
            if (r.isEmpty() || r.length() > 200)
            {
                throw new ServiceException("请说明没有收到款的情况（200 字内）", 422);
            }
            Map<String, Object> before = db.one("select status from kg_online_settlement where app_id = :id", p("id", appId));
            boolean unpaid = before != null && "pending_payout".equals(str(before.get("status")));
            // 已登记付款但没收到，或门店验收后超过 7 天还没付款（设计 m2：pending_payout 超过 7 天可申诉；原先只接受前者，门店不付款接单人只能干等）
            int n = db.update("update kg_online_settlement set status = 'disputed', dispute_reason = :r, update_time = now() where app_id = :id "
                    + "and (status = 'payout_registered' or (status = 'pending_payout' and create_time < date_sub(now(), interval 7 day)))",
                    p("r", r, "id", appId));
            if (n == 0)
            {
                throw new ServiceException("门店还没有登记付款；验收后超过 7 天仍未付款时可以申诉", 409);
            }
            AppLoginUser me = AppUserContext.require();
            Map<String, Object> ap = new LinkedHashMap<String, Object>();
            ap.put("app_user_id", me.getUserId());
            ap.put("appeal_type", "其他");
            ap.put("biz_type", "online_settlement");
            ap.put("biz_id", appId);
            ap.put("biz_label", StaffSupport.cut("线上任务结算 " + v.get("title"), 100));
            ap.put("content", StaffSupport.cut((unpaid ? "门店验收后超过 7 天仍没有付款：" : "门店登记已付款但我没有收到：") + r, 500));
            ap.put("proofs", "");
            ap.put("help_wanted", "需要平台协助核实结算");
            ap.put("status", "pending");
            ap.put("create_time", new Date());
            db.insert("kg_appeal", ap);
            support.notifyStore(KgJdbc.asLong(v.get("storeId")), "system", "接单人申诉未收到款", "「" + v.get("title") + "」：" + r + "（平台会介入核实）", "online_settlement", appId,
                    "/pages/staff/online-jobs?appId=" + appId);
        }
        return mine(appId);
    }

    /**
     * 请平台介入验收：成果提交满 ESCALATE_DAYS 天门店还没验收也没退回时，接单人在交付页发起。
     * 复用平台「申报处理」（kg_appeal，biz_type = online_review），同时告诉门店；平台处理后两边都会收到结果（appealHandled）。
     * 之前成果交了门店一直不处理，接单人既没有超时、也没有任何申诉入口。不改成果和报名状态，验收仍由门店做
     */
    @SuppressWarnings("unchecked")
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> escalate(Long appId, String reason)
    {
        Map<String, Object> v = mineOrThrow(appId);
        String r = reason == null ? "" : reason.trim();
        if (r.isEmpty() || r.length() > 200)
        {
            throw new ServiceException("请说一下情况（200 字内）", 422);
        }
        if (!Boolean.TRUE.equals(v.get("canEscalate")))
        {
            throw new ServiceException(Boolean.TRUE.equals(v.get("escalated")) ? "已经请平台介入了，处理结果会发到消息里"
                    : Boolean.TRUE.equals(v.get("escalateAgain")) && v.get("escalateFrom") != null
                            ? "平台刚处理过你的介入申请，到 " + v.get("escalateFrom") + " 门店还没验收的话才能再次请平台介入"
                            : "成果提交满 " + ESCALATE_DAYS + " 天门店还没验收时，才能请平台介入", 409);
        }
        guard.check(r);
        Map<String, Object> latest = v.get("latest") instanceof Map ? (Map<String, Object>) v.get("latest") : new LinkedHashMap<String, Object>();
        Map<String, Object> ap = new LinkedHashMap<String, Object>();
        ap.put("app_user_id", AppUserContext.require().getUserId());
        ap.put("appeal_type", "任务验收争议");
        ap.put("biz_type", "online_review");
        ap.put("biz_id", appId);
        ap.put("biz_label", StaffSupport.cut("线上任务验收 " + v.get("title"), 100));
        ap.put("content", StaffSupport.cut("第 " + latest.get("round") + " 轮成果于 " + latest.get("time") + " 提交，门店超过 " + ESCALATE_DAYS + " 天没有验收也没有退回：" + r, 500));
        ap.put("proofs", "");
        ap.put("help_wanted", "需要平台联系门店验收");
        ap.put("status", "pending");
        ap.put("create_time", new Date());
        db.insert("kg_appeal", ap);
        // 消息类型用 online_submission（和「线上任务有新成果」一样）：线上任务专员的消息中心只放行 application / online_submission 这类
        // 能核对到本店线上接单的消息（KgMessageMapper.currentChatReception），写成别的类型，负责验收的专员看不到这条催办
        support.notifyStore(KgJdbc.asLong(v.get("storeId")), "system", "接单人请平台介入验收",
                "「" + v.get("title") + "」第 " + latest.get("round") + " 轮成果提交已超过 " + ESCALATE_DAYS + " 天还没验收：" + r + "（请尽快验收或退回修改）",
                "online_submission", appId, "/pages/staff/online-jobs?appId=" + appId);
        return mine(appId);
    }

    /**
     * 平台在「申报处理」里处理完线上任务的申诉后调用（KgHonorService.handleAppeal）；返回 false 表示不是这类申诉，由调用方按普通申报通知。
     * - 收款申诉（online_settlement）：结算单从「收款申诉中」回到申诉前那一步 —— 门店登记过付款的回到「已登记付款 · 待确认」，
     *   没登记过的回到「待门店付款」；接单人核对到账后确认收款，仍没收到可以再申诉。之前平台处理完结算单一直停在「申诉中」，
     *   接单人确认不了收款，门店也收不到处理结果
     * - 验收申诉（online_review）：只把处理结果告诉双方，成果状态不变，验收仍由门店做
     * 结果发给接单人（链到这单的交付页，不再链到经营者的申报表单）和门店（链到这单的交付记录）。平台只做协调记录，不经手资金。
     */
    public boolean appealHandled(String bizType, Long appId, Long appealUserId, boolean resolve, String reply)
    {
        boolean settle = "online_settlement".equals(bizType);
        if ((!settle && !"online_review".equals(bizType)) || appId == null || appealUserId == null)
        {
            return false;
        }
        Map<String, Object> a = db.one("select a.app_user_id, j.store_id, j.title from kg_application a join kg_job j on j.job_id = a.job_id "
                + "where a.app_id = :id and j.job_type = 'online'", p("id", appId));
        // 只认接单人本人发起的申诉（交付页里发起的都是），对不上的按普通申报处理，不碰别人的结算单
        if (a == null || !appealUserId.equals(KgJdbc.asLong(a.get("appUserId"))))
        {
            return false;
        }
        String title = "「" + a.get("title") + "」";
        String result = "平台处理结果：" + StaffSupport.cut(clause(reply), 200);
        String userTitle;
        String userText;
        String storeTitle;
        String storeText;
        if (settle)
        {
            Map<String, Object> st = db.one("select status, payout_method from kg_online_settlement where app_id = :id for update", p("id", appId));
            String back = "";
            if (st != null && "disputed".equals(str(st.get("status"))))
            {
                back = str(st.get("payoutMethod")).isEmpty() ? "pending_payout" : "payout_registered";
                db.update("update kg_online_settlement set status = :to, update_time = now() where app_id = :id and status = 'disputed'", p("to", back, "id", appId));
            }
            userTitle = resolve ? "收款申诉已处理" : "收款申诉未受理";
            userText = title + result + ("payout_registered".equals(back) ? "。请核对实际到账后在任务页确认收款，仍没收到可以再次申诉"
                    : "pending_payout".equals(back) ? "。门店登记付款后，请核对到账并确认收款" : "");
            storeTitle = "平台已处理收款申诉";
            storeText = title + result + ("payout_registered".equals(back) ? "。结算单已回到「已登记付款」，等接单人确认收款"
                    : "pending_payout".equals(back) ? "。结算单已回到「待付款」，请线下付款后登记凭证" : "");
        }
        else
        {
            userTitle = resolve ? "验收申诉已处理" : "验收申诉未受理";
            userText = title + result;
            storeTitle = "平台已处理验收申诉";
            storeText = title + "接单人请平台介入验收，" + result + (resolve ? "。请尽快验收或退回修改" : "");
        }
        support.notifyUser(appealUserId, "apply", userTitle, userText, "application", appId, "/pages/jobs/deliver?appId=" + appId);
        // 验收申诉的结果要让负责验收的线上任务专员也看到，消息类型同 escalate 用 online_submission；收款申诉涉及付款，仍按 online_settlement 只给能看结算的人
        support.notifyStore(KgJdbc.asLong(a.get("storeId")), "system", storeTitle, storeText, settle ? bizType : "online_submission", appId,
                "/pages/staff/online-jobs?appId=" + appId);
        return true;
    }
}
