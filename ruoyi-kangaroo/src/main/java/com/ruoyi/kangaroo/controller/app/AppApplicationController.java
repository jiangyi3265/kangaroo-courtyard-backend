package com.ruoyi.kangaroo.controller.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.kangaroo.domain.KgApplication;
import com.ruoyi.kangaroo.domain.KgApplicationLog;
import com.ruoyi.kangaroo.domain.KgInterview;
import com.ruoyi.kangaroo.domain.KgInterviewSlot;
import com.ruoyi.kangaroo.dto.ApplicationSubmitDTO;
import com.ruoyi.kangaroo.dto.OperatorDTO;
import com.ruoyi.kangaroo.enums.ApplicationStatus;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppRateLimiter;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.service.IKgApplicationService;
import com.ruoyi.kangaroo.service.IKgInterviewSlotService;

/**
 * 小程序端报名与履约接口。
 *
 * 所有接口的报名人身份都取自登录态（AppUserContext），
 * 请求体里即使带了 appUserId 也会被忽略。
 */
@RestController
@RequestMapping("/app/application")
@AppAuth
public class AppApplicationController
{
    @org.springframework.beans.factory.annotation.Autowired private com.ruoyi.kangaroo.ext.user.KgDepartureNotice departureNotice;

    @Autowired
    private IKgApplicationService kgApplicationService;

    @Autowired
    private IKgInterviewSlotService kgInterviewSlotService;

    @Autowired
    private AppRateLimiter rateLimiter;

    @Autowired
    private KgJdbc db;

    @Autowired
    private com.ruoyi.kangaroo.ext.community.KgChatReception reception;

    /** 提交报名 */
    @PostMapping
    public AjaxResult submit(@RequestBody ApplicationSubmitDTO dto)
    {
        AppLoginUser me = AppUserContext.require();
        // 「报名 → 撤回 → 再报名」这条循环没有任何上限：
        // applied_count 是累计值只增不减，会被永久污染；
        // 而每次报名都要按 2×(1+门店员工数) 写站内信 —— 单个账号就能淹没门店员工的消息中心。
        // 这里给两层上限：账号整体的，和账号对同一个岗位的。
        rateLimiter.check("apply:user", String.valueOf(me.getUserId()), 60, 3600,
                "提交报名过于频繁，请稍后再试");
        if (dto != null && dto.getJobId() != null)
        {
            // 同一人同一岗位 12 次/天。
            //
            // 这个数被特意抬高过一次，原因值得记下来：回归用例 R4 会用同一个账号
            // 对同一个岗位发 8 个并发请求，断言"只有 1 个成功"——它证明的是
            // **唯一索引**在并发下挡住了重复报名。如果限流把其中几个提前拒掉，
            // 那条断言照样是绿的，但证明的东西已经变了（变成"限流挡住的"）。
            // 检查必须继续证明它原本要证明的事，所以阈值要让开这个用例。
            // 12 次/天对真人来说仍然是够不到的上限，防刷的目的不受影响。
            rateLimiter.check("apply:user-job",
                    me.getUserId() + "-" + dto.getJobId(), 12, 86400,
                    "同一个岗位今天提交次数过多，请明天再试或直接联系门店");
        }
        KgApplication app = kgApplicationService.submit(dto, me.getUserId(), me.getNickname());
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("appId", app.getAppId());
        data.put("appNo", app.getAppNo());
        data.put("status", app.getStatus());
        data.put("statusLabel", ApplicationStatus.label(app.getStatus()));
        return AjaxResult.success("报名已提交", data);
    }

    /** 我的报名列表 */
    @GetMapping("/mine")
    public AjaxResult mine(@RequestParam(required = false) String status)
    {
        Long userId = AppUserContext.getUserId();
        List<KgApplication> list = kgApplicationService.selectMyApplications(userId, status);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (list != null)
        {
            for (KgApplication a : list)
            {
                rows.add(brief(a));
            }
        }
        return AjaxResult.success(rows);
    }

    /** 报名详情：含状态、流转日志与面试信息，供进度页使用 */
    @GetMapping("/{appId}")
    public AjaxResult detail(@PathVariable Long appId)
    {
        Long userId = AppUserContext.getUserId();
        KgApplication app = kgApplicationService.requireOwnedByUser(appId, userId);

        Map<String, Object> data = brief(app);
        data.put("realName", app.getRealName());
        data.put("age", app.getAge());
        data.put("phone", app.getPhone());
        data.put("skills", app.getSkills());
        data.put("note", app.getNote());
        data.put("rejectReason", app.getRejectReason());
        data.put("terminateReason", app.getTerminateReason());
        data.put("userIntent", app.getUserIntent());
        data.put("confirmDeadline", app.getConfirmDeadline());
        data.put("storeConfirmTime", app.getStoreConfirmTime());
        data.put("step", stepOf(app.getStatus()));
        // 进度页要靠岗位大类分流：线上任务（online）没有视频面试，审核通过后应去交付页提交成果。
        // 之前详情里没有这个字段，进度页只能一律给"请选择视频面试时间"，预约又被后端以"线上任务不需要预约面试"拒绝，接单人卡死。
        // 与 KgApplicationServiceImpl.isOnlineJob 同一口径：直接读 kg_job，不按 del_flag 过滤（岗位下架后进行中的任务仍要能交付）
        Map<String, Object> jobRow = db.one("select job_type from kg_job where job_id = :j", KgJdbc.p("j", app.getJobId()));
        data.put("jobType", jobRow == null ? null : jobRow.get("jobType"));
        // 线上任务是本店在职的线上任务专员发布的（kg_job.create_by = 'staff:<uid>'、此人仍是本店 online_staff）：
        // 进度页「联系主理人」改叫「联系专员」（2026-10-16 主人拍板），会话里发布的专员一定在（KgChatReception.publishers）
        data.put("contactSpecialist", jobRow != null && "online".equals(jobRow.get("jobType"))
                && reception.specialistOf(app.getStoreId(), appId) != null);

        List<KgApplicationLog> logs = kgApplicationService.selectLogs(appId);
        List<Map<String, Object>> logRows = new ArrayList<Map<String, Object>>();
        if (logs != null)
        {
            for (KgApplicationLog l : logs)
            {
                Map<String, Object> m = new HashMap<String, Object>();
                m.put("toStatus", l.getToStatus());
                m.put("toStatusLabel", ApplicationStatus.label(l.getToStatus()));
                m.put("reason", l.getReason());
                m.put("operatorName", l.getOperatorName());
                m.put("createTime", l.getCreateTime());
                logRows.add(m);
            }
        }
        data.put("logs", logRows);

        KgInterview interview = kgApplicationService.selectInterview(appId);
        if (interview != null)
        {
            Map<String, Object> iv = new HashMap<String, Object>();
            iv.put("interviewId", interview.getInterviewId());
            iv.put("startTime", interview.getStartTime());
            iv.put("endTime", interview.getEndTime());
            iv.put("status", interview.getStatus());
            // 撤回 / 终止后时段会放回可约池，但 kg_interview.slot_id 不清空；时段被下一位约走后，
            // 只认"这个时段现在仍由本报名占用"，否则前一位用户会看到下一位的会议号
            KgInterviewSlot meetingSlot = interview.getSlotId() == null ? null : kgInterviewSlotService.selectKgInterviewSlotBySlotId(interview.getSlotId());
            boolean ownsSlot = meetingSlot != null && appId.equals(meetingSlot.getBookedAppId());
            // 门店给这场面试单独填的会议信息存在 kg_interview.remark，优先显示；没填才用时段预设的
            String ownMeeting = interview.getRemark() == null ? "" : interview.getRemark().trim();
            iv.put("meetingInfo", ownsSlot ? (ownMeeting.isEmpty() ? meetingSlot.getMeetingInfo() : ownMeeting) : null);
            iv.put("rescheduleCount", interview.getRescheduleCount() == null ? Integer.valueOf(0) : interview.getRescheduleCount());
            iv.put("rescheduleLimit", Integer.valueOf(2));
            // 面试后问卷交过没有：门店先点「标记结束」时，没交过的候选人在面试已完成后还能补交一次
            iv.put("feedbackSubmitted", Boolean.valueOf(com.ruoyi.kangaroo.service.impl.KgApplicationServiceImpl.questionnaireSubmitted(interview)));
            data.put("interview", iv);
        }
        return AjaxResult.success(data);
    }

    /**
     * 报名结束页（2026-10-31 主人反馈）：给亲友分享的行程（trip）和安全负责人（safety）。只有报名人本人能看，别人的 403（同详情）。
     * 线上任务只回 {online: true}；报名已结束（被拒、撤回、终止）ended = true，safety 为 null。
     * 有接待人时提供该报名所属门店登记的联系电话；行程 trip 不带电话，也不公开接待人的账号绑定手机号。
     * 打开安全负责人的聊天走门店会话（chat?appId=，「联系专员」同一条路），KgChatReception.offlineLead 选的人一定在这段会话里；没人时 kind = service，页面退到人工客服。
     */
    @GetMapping("/{appId}/after-apply")
    public AjaxResult afterApply(@PathVariable Long appId)
    {
        Long userId = AppUserContext.getUserId();
        KgApplication app = kgApplicationService.requireOwnedByUser(appId, userId);
        // 岗位下架后这份报名还要能看（同详情，不按 del_flag 过滤）
        Map<String, Object> job = db.one("select j.job_type, j.title, j.city, j.cover, j.images, j.recruit_store_id, s.store_name, s.cover store_cover, "
                + "rs.store_name recruit_store_name, rs.cover recruit_store_cover from kg_job j left join kg_store s on s.store_id = j.store_id "
                + "left join kg_store rs on rs.store_id = j.recruit_store_id where j.job_id = :j", KgJdbc.p("j", app.getJobId()));
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        if (job != null && "online".equals(job.get("jobType")))
        {
            data.put("online", Boolean.TRUE);
            return AjaxResult.success(data);
        }
        String status = app.getStatus();
        boolean ended = job == null || ApplicationStatus.REVIEW_REJECTED.equals(status) || ApplicationStatus.WITHDRAWN.equals(status) || ApplicationStatus.TERMINATED.equals(status);
        data.put("online", Boolean.FALSE);
        data.put("ended", Boolean.valueOf(ended));
        data.put("trip", job == null ? null : tripOf(app, job));
        Map<String, Object> safety = null;
        if (!ended)
        {
            safety = reception.offlineLead(app.getStoreId(), userId, app.getJobId());
            if (!"service".equals(safety.get("kind")))
            {
                safety.put("appId", appId);
                Map<String, Object> store = db.one("select contact_phone from kg_store where store_id = :s and status = '0' and del_flag = '0'",
                        KgJdbc.p("s", app.getStoreId()));
                String phone = store == null ? "" : KgJdbc.asStr(store.get("contactPhone"));
                phone = phone == null ? "" : phone.replaceAll("[\\s()（）-]", "");
                safety.put("contactPhone", phone.matches("\\+?[0-9]{5,20}") ? phone : "");
            }
        }
        data.put("safety", safety);
        return AjaxResult.success(data);
    }

    /** 行程：称呼是报名时的简历称呼（kg_resume.name），地点是招聘门店（同岗位详情的 storeName），没有就写城市 */
    private static Map<String, Object> tripOf(KgApplication app, Map<String, Object> job)
    {
        boolean recruit = job.get("recruitStoreId") != null;
        String place = KgJdbc.asStr(job.get(recruit ? "recruitStoreName" : "storeName"));
        String city = KgJdbc.asStr(job.get("city"));
        Map<String, Object> trip = new LinkedHashMap<String, Object>();
        trip.put("jobId", app.getJobId());
        trip.put("jobType", job.get("jobType"));
        trip.put("jobTitle", job.get("title"));
        trip.put("place", StringUtils.isEmpty(place) ? (city == null ? "" : city) : place);
        trip.put("city", city == null ? "" : city);
        trip.put("cover", com.ruoyi.kangaroo.domain.JobImages.cover(com.ruoyi.kangaroo.domain.JobImages.read(KgJdbc.asStr(job.get("images"))), KgJdbc.asStr(job.get("cover")),
                KgJdbc.asStr(job.get(recruit ? "recruitStoreCover" : "storeCover"))));
        trip.put("arrival", com.ruoyi.kangaroo.ext.staff.StaffSupport.fmt(app.getArrivalDate(), "yyyy-MM-dd"));
        trip.put("days", app.getStayDays());
        trip.put("name", app.getRealName() == null ? "" : app.getRealName());
        return trip;
    }

    /** 撤回报名 */
    @PostMapping("/{appId}/withdraw")
    public AjaxResult withdraw(@PathVariable Long appId)
    {
        AppLoginUser me = AppUserContext.require();
        kgApplicationService.withdraw(appId, OperatorDTO.user(me.getUserId(), me.getNickname()));
        return AjaxResult.success("已撤回报名");
    }

    /** 可选面试时段（审核通过后展示） */
    @GetMapping("/{appId}/slots")
    public AjaxResult slots(@PathVariable Long appId)
    {
        Long userId = AppUserContext.getUserId();
        KgApplication app = kgApplicationService.requireOwnedByUser(appId, userId);
        List<KgInterviewSlot> slots = kgInterviewSlotService.selectOpenSlots(app.getStoreId(), app.getJobId());
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (slots != null)
        {
            for (KgInterviewSlot s : slots)
            {
                Map<String, Object> m = new HashMap<String, Object>();
                m.put("slotId", s.getSlotId());
                m.put("startTime", s.getStartTime());
                m.put("endTime", s.getEndTime());
                rows.add(m);
            }
        }
        return AjaxResult.success(rows);
    }

    /** 选择面试时段 */
    @PostMapping("/{appId}/book-interview")
    public AjaxResult bookInterview(@PathVariable Long appId, @RequestBody Map<String, Object> body)
    {
        AppLoginUser me = AppUserContext.require();
        Long slotId = toLong(body == null ? null : body.get("slotId"));
        KgInterview interview = kgApplicationService.bookInterview(appId, slotId,
                OperatorDTO.user(me.getUserId(), me.getNickname()));
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("interviewId", interview.getInterviewId());
        data.put("startTime", interview.getStartTime());
        data.put("endTime", interview.getEndTime());
        return AjaxResult.success("面试时间已确认", data);
    }

    /** 申请改期：换一个还开放的面试时段 */
    @PostMapping("/{appId}/reschedule-interview")
    public AjaxResult rescheduleInterview(@PathVariable Long appId, @RequestBody Map<String, Object> body)
    {
        AppLoginUser me = AppUserContext.require();
        Long slotId = toLong(body == null ? null : body.get("slotId"));
        String reason = str(body, "reason");
        KgInterview interview = kgApplicationService.rescheduleInterview(appId, slotId, reason,
                OperatorDTO.user(me.getUserId(), me.getNickname()));
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("interviewId", interview.getInterviewId());
        data.put("startTime", interview.getStartTime());
        data.put("endTime", interview.getEndTime());
        data.put("rescheduleCount", interview.getRescheduleCount());
        return AjaxResult.success("面试已改期", data);
    }

    /** 结束面试并提交面试后问卷 */
    @PostMapping("/{appId}/finish-interview")
    public AjaxResult finishInterview(@PathVariable Long appId, @RequestBody Map<String, Object> body)
    {
        AppLoginUser me = AppUserContext.require();
        Integer duration = toInt(body == null ? null : body.get("durationSeconds"));
        String understandJob = str(body, "understandJob");
        String acceptBoard = str(body, "acceptBoard");
        String confirmArrival = str(body, "confirmArrival");
        String note = str(body, "feedbackNote");
        kgApplicationService.finishInterview(appId, duration, understandJob, acceptBoard, confirmArrival, note,
                OperatorDTO.user(me.getUserId(), me.getNickname()));
        return AjaxResult.success("面试已结束");
    }

    /** 面试后表态：accept 愿意前往 / pending 还想考虑 / reject 不合适 */
    @PostMapping("/{appId}/confirm")
    public AjaxResult confirm(@PathVariable Long appId, @RequestBody Map<String, Object> body)
    {
        AppLoginUser me = AppUserContext.require();
        String intent = str(body, "intent");
        if (StringUtils.isEmpty(intent))
        {
            return AjaxResult.error("请选择你的意向");
        }
        kgApplicationService.userConfirm(appId, intent, OperatorDTO.user(me.getUserId(), me.getNickname()));
        return AjaxResult.success();
    }

    /** 签署责任书 */
    @PostMapping("/{appId}/sign")
    public AjaxResult sign(@PathVariable Long appId)
    {
        AppLoginUser me = AppUserContext.require();
        departureNotice.requireAccepted(me.getUserId(),appId);
        kgApplicationService.signContract(appId, OperatorDTO.user(me.getUserId(), me.getNickname()));
        return AjaxResult.success("责任书已签署");
    }

    // ---------- 内部 ----------

    private Map<String, Object> brief(KgApplication a)
    {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("appId", a.getAppId());
        m.put("appNo", a.getAppNo());
        m.put("jobId", a.getJobId());
        m.put("jobTitle", a.getJobTitle());
        m.put("storeId", a.getStoreId());
        m.put("storeName", a.getStoreName());
        m.put("status", a.getStatus());
        m.put("statusLabel", ApplicationStatus.label(a.getStatus()));
        m.put("arrivalDate", a.getArrivalDate());
        m.put("stayDays", a.getStayDays());
        m.put("submitTime", a.getSubmitTime());
        m.put("auditTime", a.getAuditTime());
        m.put("step", stepOf(a.getStatus()));
        return m;
    }

    /**
     * 把状态映射成 /pages/jobs/progress.vue 的六步进度条下标（1 起）：
     * 提交报名 / 商家审核 / 视频面试 / 能力评估 / 录用沟通 / 入职成功
     */
    private static int stepOf(String status)
    {
        if (ApplicationStatus.SUBMITTED.equals(status))
        {
            return 1;
        }
        if (ApplicationStatus.REVIEW_PASSED.equals(status) || ApplicationStatus.REVIEW_REJECTED.equals(status))
        {
            return 2;
        }
        if (ApplicationStatus.INTERVIEW_BOOKED.equals(status))
        {
            return 3;
        }
        if (ApplicationStatus.INTERVIEW_DONE.equals(status))
        {
            return 4;
        }
        if (ApplicationStatus.USER_CONFIRMED.equals(status) || ApplicationStatus.STORE_CONFIRMED.equals(status)
                || ApplicationStatus.CONTRACT_SIGNED.equals(status))
        {
            return 5;
        }
        if (ApplicationStatus.ONBOARDED.equals(status))
        {
            return 6;
        }
        if (ApplicationStatus.WITHDRAWN.equals(status) || ApplicationStatus.TERMINATED.equals(status))
        {
            // 流程已中止，不落在任何一步。
            // 这两个状态原先掉进下面的兜底 return 1，
            // 于是一条已终止的报名在进度条上被画回第一步"提交报名"，
            // 而同屏的状态文案写着"已终止"，自相矛盾。
            return 0;
        }
        // 兜底也改成 0：新增状态时宁可整条进度条不高亮，
        // 也不要悄悄把它说成"正在第一步"。
        return 0;
    }

    private static String str(Map<String, Object> body, String key)
    {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static Long toLong(Object v)
    {
        if (v == null)
        {
            return null;
        }
        try
        {
            return Long.valueOf(String.valueOf(v));
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private static Integer toInt(Object v)
    {
        if (v == null)
        {
            return null;
        }
        try
        {
            return Integer.valueOf(String.valueOf(v));
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }
}
