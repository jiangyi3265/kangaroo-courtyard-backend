package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.staff.StaffSupport.str;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.dto.OperatorDTO;
import com.ruoyi.kangaroo.enums.ApplicationStatus;
import com.ruoyi.kangaroo.enums.MessageCategory;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.service.IKgApplicationService;
import com.ruoyi.kangaroo.util.MaskUtils;

/**
 * 员工端面试日程：日程列表、面试详情、改期、面试后评分、标记面试结束。
 *
 * 状态流转仍然只走 IKgApplicationService（面试结束 = interview_booked → interview_done）；
 * 这里新增的改期只换时段、不改报名状态，用 CAS 抢新时段再归还旧时段，和用户自己预约走同一套时段规则。
 */
@Service
public class KgStaffInterviewService
{
    private static final String BASE = "select i.interview_id, i.app_id, i.slot_id, i.start_time, i.end_time, i.status istatus, "
            + "i.finish_time, i.staff_rating, i.staff_note, i.staff_result, i.staff_result_time, i.reschedule_count, i.rescheduled_time, "
            + "i.understand_job, i.accept_board, i.confirm_arrival, i.feedback_note, i.remark iv_meeting, "
            + "a.status, a.real_name, a.age, a.phone, a.arrival_date, a.stay_days, a.skills, a.submit_time, a.user_intent, "
            + "a.user_confirm_time, a.store_confirm_time, a.confirm_deadline, a.share_code, a.job_id, a.store_id, a.app_user_id, "
            + "j.title job_title, j.job_type, j.start_date job_start, j.min_days, j.tags, "
            + "s.store_name, u.gender, rs.gender resume_gender, u.city user_city, u.avatar "
            + "from kg_interview i join kg_application a on a.app_id = i.app_id and a.del_flag = '0' "
            + "left join kg_job j on j.job_id = a.job_id "
            + "left join kg_store s on s.store_id = a.store_id "
            + "left join kg_app_user u on u.user_id = a.app_user_id "
            + "left join kg_resume rs on rs.app_user_id = a.app_user_id ";

    /** 日程里不再出现的报名终态（婉拒、撤回、终止）；入职的仍保留，方便回看 */
    private static final List<String> HIDDEN = Arrays.asList(ApplicationStatus.WITHDRAWN, ApplicationStatus.TERMINATED,
            ApplicationStatus.REVIEW_REJECTED);

    @Autowired
    private KgJdbc db;

    @Autowired
    private IKgApplicationService apps;

    @Autowired
    private StaffSupport support;

    /** range=day（默认，date 当天）或 week（date 所在自然周，周一起） */
    public Map<String, Object> schedule(StaffSupport.Ctx c, String range, String date)
    {
        Date base = StaffSupport.parse(date);
        Date day = StaffSupport.startOfDay(base == null ? new Date() : base);
        boolean week = "week".equals(range);
        Date from = day;
        Date to = StaffSupport.addDays(day, 1);
        if (week)
        {
            Calendar cal = Calendar.getInstance();
            cal.setTime(day);
            int dow = cal.get(Calendar.DAY_OF_WEEK); // 周日=1
            from = StaffSupport.addDays(day, dow == Calendar.SUNDAY ? -6 : Calendar.MONDAY - dow);
            to = StaffSupport.addDays(from, 7);
        }
        List<Map<String, Object>> list = db.list(BASE + "where a.store_id = :store and i.start_time >= :from and i.start_time < :to "
                + "and i.status in ('booked', 'done') order by i.start_time",
                p("store", c.storeId, "from", from, "to", to));

        Date now = new Date();
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        int pending = 0;
        int upcoming = 0;
        int rescheduled = 0;
        for (Map<String, Object> r : list)
        {
            if (HIDDEN.contains(str(r.get("status"))))
            {
                continue;
            }
            Map<String, Object> m = brief(r, now, week);
            String label = str(m.get("status"));
            if ("待确认".equals(label))
            {
                pending++;
            }
            else if ("即将开始".equals(label))
            {
                upcoming++;
            }
            else if ("已改期".equals(label))
            {
                rescheduled++;
            }
            rows.add(m);
        }
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("total", rows.size());
        summary.put("pending", pending);
        summary.put("upcoming", upcoming);
        summary.put("rescheduled", rescheduled);

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("range", week ? "week" : "day");
        out.put("date", StaffSupport.fmt(day, "yyyy-MM-dd"));
        out.put("title", week ? StaffSupport.fmt(from, "M月d日") + " – " + StaffSupport.fmt(StaffSupport.addDays(to, -1), "M月d日")
                : StaffSupport.dayLabel(day));
        out.put("isToday", !week && StaffSupport.startOfDay(now).equals(day));
        out.put("summary", summary);
        out.put("rows", rows);
        out.put("storeId", c.storeId);
        out.put("storeName", c.storeName);
        return out;
    }

    private Map<String, Object> brief(Map<String, Object> r, Date now, boolean week)
    {
        Date start = StaffSupport.parse(r.get("startTime"));
        String status = str(r.get("status"));
        // 改过期没有：门店改期不再计入候选人的改期次数（reschedule_count），只记改期时间，两种都算「已改期」
        boolean rescheduled = StaffSupport.toInt(r.get("rescheduleCount"), 0) > 0 || r.get("rescheduledTime") != null;
        String label;
        String tone;
        String note;
        if (ApplicationStatus.INTERVIEW_BOOKED.equals(status))
        {
            if (start != null && start.after(now))
            {
                if (rescheduled)
                {
                    label = "已改期";
                    tone = "green";
                    note = "新时间：" + StaffSupport.fmt(start, "M月d日 HH:mm");
                }
                else
                {
                    label = "即将开始";
                    tone = "blue";
                    note = "发起方：候选人本人预约";
                }
            }
            else
            {
                label = "待确认";
                tone = "red";
                note = "面试时间已到，请确认面试结果";
            }
        }
        else if (ApplicationStatus.INTERVIEW_DONE.equals(status))
        {
            label = "待确认";
            tone = "amber";
            note = "面试已结束，等待候选人表态";
        }
        else if (ApplicationStatus.USER_CONFIRMED.equals(status))
        {
            label = "待确认";
            tone = "red";
            note = "候选人已确认，等待门店确认录用";
        }
        else
        {
            label = ApplicationStatus.label(status);
            tone = "green";
            note = ApplicationStatus.STORE_CONFIRMED.equals(status) ? "等待候选人签署责任书"
                    : (ApplicationStatus.CONTRACT_SIGNED.equals(status) ? "责任书已签署，待标记入职" : "");
        }
        if (week && start != null)
        {
            note = StaffSupport.dayLabel(start) + " · " + note;
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("interviewId", r.get("interviewId"));
        m.put("appId", r.get("appId"));
        m.put("appStatus", status);
        m.put("time", StaffSupport.fmt(start, "HH:mm"));
        m.put("startTs", start == null ? 0 : start.getTime());
        m.put("status", label);
        m.put("tone", tone);
        m.put("name", MaskUtils.name(str(r.get("realName"))));
        m.put("age", r.get("age"));
        m.put("gender", genderOf(r));
        m.put("job", r.get("jobTitle"));
        m.put("place", r.get("storeName"));
        m.put("note", note);
        return m;
    }

    public Map<String, Object> detail(StaffSupport.Ctx c, Long interviewId)
    {
        Map<String, Object> r = load(c, interviewId);
        Date now = new Date();
        Date start = StaffSupport.parse(r.get("startTime"));
        Date end = StaffSupport.parse(r.get("endTime"));
        String status = str(r.get("status"));

        Map<String, Object> d = new LinkedHashMap<String, Object>();
        d.put("interviewId", r.get("interviewId"));
        d.put("appId", r.get("appId"));
        d.put("jobId", r.get("jobId"));
        // 报名所属门店：消息深链打开的面试可能不在当前切换的门店，页面要按这家门店上的角色决定确认录用 / 投保按钮
        d.put("storeId", r.get("storeId"));
        d.put("appStatus", status);
        d.put("statusLabel", ApplicationStatus.label(status));
        d.put("interviewStatus", r.get("istatus"));
        d.put("name", r.get("realName"));
        d.put("maskedName", MaskUtils.name(str(r.get("realName"))));
        d.put("age", r.get("age"));
        d.put("gender", genderOf(r));
        d.put("city", str(r.get("userCity")));
        d.put("avatar", r.get("avatar"));
        // 与报名详情同一口径：完整联系方式只给主理人 / 管家（岗位管理·编辑），普通员工只看脱敏号码
        boolean fullContact = StaffPerms.allows(c.role, "jobs", StaffPerms.EDIT);
        d.put("phone", fullContact ? r.get("phone") : MaskUtils.phone(str(r.get("phone"))));
        d.put("contactMasked", !fullContact);
        d.put("jobTitle", r.get("jobTitle"));
        d.put("jobType", r.get("jobType"));
        d.put("storeName", r.get("storeName"));
        d.put("source", r.get("shareCode") == null ? "袋鼠全球青年" : "员工分享链接");
        d.put("match", match(r));
        d.put("submitTime", StaffSupport.fmt(StaffSupport.parse(r.get("submitTime")), "MM-dd HH:mm"));
        d.put("arrivalDate", r.get("arrivalDate"));
        d.put("startTs", start == null ? 0 : start.getTime());
        d.put("dateText", dateText(start, end));
        d.put("slots", slots(r, now));
        d.put("questions", questions(r));
        d.put("rating", r.get("staffRating"));
        d.put("staffResult", r.get("staffResult"));
        d.put("staffNote", r.get("staffNote"));
        d.put("rescheduleCount", r.get("rescheduleCount"));
        boolean booked = ApplicationStatus.INTERVIEW_BOOKED.equals(status);
        boolean happened = !booked || (start != null && !start.after(now));
        boolean canManage = fullContact && !c.uid().equals(KgJdbc.asLong(r.get("appUserId")));
        boolean bookedInterview = booked && "booked".equals(str(r.get("istatus")));
        d.put("canReschedule", canManage && bookedInterview);
        d.put("canFinish", canManage && bookedInterview && start != null && !start.after(now));
        d.put("canRate", canManage && happened);
        d.put("canRecordResult", canManage && "done".equals(str(r.get("istatus"))));
        // 视频通话服务（第三方实时音视频）尚未签约接入：页面据此改走电话联系，不能假装进了视频间
        d.put("videoEnabled", Boolean.FALSE);
        // 与用户端一致：时段被释放、再被别人约走后不再显示，免得员工把下一位的会议号发给这位
        // 员工给这场面试单独填的会议信息存在 kg_interview.remark（跟着面试走，改期时清空）；没填时才用时段上预设的
        Map<String,Object> meetingSlot=db.one("select meeting_info from kg_interview_slot where slot_id=:id and booked_app_id=:app and del_flag='0'",p("id",r.get("slotId"),"app",r.get("appId")));
        String ownMeeting=str(r.get("ivMeeting")).trim();
        d.put("meetingInfo", meetingSlot==null?null:(ownMeeting.isEmpty()?meetingSlot.get("meetingInfo"):ownMeeting));
        d.put("canEditMeeting", booked && "booked".equals(str(r.get("istatus"))) && meetingSlot!=null && fullContact
                && !"online".equals(r.get("jobType")) && !c.uid().equals(KgJdbc.asLong(r.get("appUserId"))));
        d.put("userIntent", r.get("userIntent"));
        putFeedback(d, r);
        d.put("userConfirmTime", r.get("userConfirmTime"));
        d.put("storeConfirmTime", r.get("storeConfirmTime"));
        Date deadline = StaffSupport.parse(r.get("confirmDeadline"));
        d.put("confirmDeadline", r.get("confirmDeadline"));
        d.put("deadlineTs", deadline == null ? 0 : deadline.getTime());
        d.put("self", c.uid().equals(KgJdbc.asLong(r.get("appUserId"))));
        // 责任书由候选人本人在报名进度里签署（用户端签约页只对报名人本人开放），门店只能看签署留痕。
        // 双向确认屏的「责任书」按钮原先跳到保险办理，这里把真实签署记录带上，页面据此如实显示进度
        Map<String, Object> sign = db.one("select signer_name, version, signed_time from kg_contract_sign where app_id = :a",
                p("a", r.get("appId")));
        d.put("contractSigned", sign != null);
        d.put("contractSigner", sign == null ? null : MaskUtils.name(str(sign.get("signerName"))));
        d.put("contractVersion", sign == null ? null : sign.get("version"));
        d.put("contractSignTime", sign == null ? null : StaffSupport.fmt(StaffSupport.parse(sign.get("signedTime")), "yyyy-MM-dd HH:mm"));
        if ("volunteer".equals(r.get("jobType"))) {
            Map<String,Object> vc=db.one("select status from kg_volunteer_contract where app_id=:a order by revision desc limit 1",p("a",r.get("appId")));
            d.put("volunteerContractStatus",vc==null?null:vc.get("status"));
        }
        return d;
    }

    /**
     * 报名详情里的这场面试（员工端）：编号、时间和候选人面试后填的问卷。
     * 「面试已预约」通知点进来是报名详情，之前那里看不到面试时间，也没有入口去这场面试填会议号、结束面试；
     * 候选人的问卷和「想让门店知道的事」门店在小程序里哪儿都看不到。没约过面试返回 null。
     * 门店授权由调用方按报名所属门店先校验
     */
    public Map<String, Object> ofApplication(Long appId)
    {
        Map<String, Object> r = db.one("select interview_id, start_time, end_time, status, understand_job, accept_board, confirm_arrival, feedback_note "
                + "from kg_interview where app_id = :a and del_flag = '0' order by interview_id desc limit 1", p("a", appId));
        if (r == null)
        {
            return null;
        }
        Map<String, Object> d = new LinkedHashMap<String, Object>();
        d.put("interviewId", r.get("interviewId"));
        d.put("interviewStatus", r.get("status"));
        d.put("dateText", dateText(StaffSupport.parse(r.get("startTime")), StaffSupport.parse(r.get("endTime"))));
        putFeedback(d, r);
        return d;
    }

    /**
     * 报名人性别：先取简历（kg_resume.gender：女 / 男 / 其他），没填再退回账号资料（kg_app_user.gender：1 男 / 2 女，0 没填）。
     * 之前只读账号的，账号是 0、简历写着「女」的人在处理端显示「性别未填」
     */
    public static String genderOf(Map<String, Object> r)
    {
        String g = str(r.get("resumeGender")).trim();
        if ("女".equals(g) || "男".equals(g) || "其他".equals(g))
        {
            return g;
        }
        return StaffSupport.genderLabel(r.get("gender"));
    }

    /** 面试时间的显示口径：「10月14日（周二）14:00–14:30」 */
    private static String dateText(Date start, Date end)
    {
        return start == null ? "" : StaffSupport.fmt(start, "MM月dd日") + "（" + StaffSupport.week(start) + "）"
                + StaffSupport.fmt(start, "HH:mm") + (end == null ? "" : "–" + StaffSupport.fmt(end, "HH:mm"));
    }

    /** 候选人面试后问卷（三道单选 + 备注）：候选人点「面试已结束，填写反馈」后表态时一并落库，没答的题是 null */
    private static void putFeedback(Map<String, Object> d, Map<String, Object> r)
    {
        d.put("understandJob", r.get("understandJob"));
        d.put("acceptBoard", r.get("acceptBoard"));
        d.put("confirmArrival", r.get("confirmArrival"));
        d.put("feedbackNote", r.get("feedbackNote"));
    }

    /** 档期、时长、技能三项规则分，满分 99；只做排序参考，不参与任何自动决策 */
    static int match(Map<String, Object> r)
    {
        int score = 0;
        Date arrival = StaffSupport.parse(r.get("arrivalDate"));
        Date jobStart = StaffSupport.parse(r.get("jobStart"));
        if (jobStart == null || arrival == null)
        {
            score += 30;
        }
        else
        {
            long days = Math.abs(arrival.getTime() - jobStart.getTime()) / 86400000L;
            score += days <= 7 ? 40 : (days <= 30 ? 25 : 10);
        }
        int stay = StaffSupport.toInt(r.get("stayDays"), 0);
        int min = StaffSupport.toInt(r.get("minDays"), 0);
        score += min <= 0 ? 30 : (stay >= min ? 30 : (stay * 2 >= min ? 15 : 5));
        String skills = str(r.get("skills"));
        String tags = str(r.get("tags"));
        boolean overlap = false;
        for (String t : tags.split("[,，、\\s]+"))
        {
            if (t.length() > 0 && skills.contains(t))
            {
                overlap = true;
                break;
            }
        }
        score += overlap ? 29 : (skills.trim().isEmpty() ? 5 : 15);
        return Math.min(99, score);
    }

    private List<Map<String, Object>> slots(Map<String, Object> r, Date now)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        Long current = KgJdbc.asLong(r.get("slotId"));
        List<Map<String, Object>> rows = db.list("select slot_id, start_time, end_time, status from kg_interview_slot "
                + "where del_flag = '0' and (slot_id = :cur or (store_id = :store and (job_id is null or job_id = :job) "
                + "and status = 'open' and start_time > :now)) order by start_time limit 7",
                p("cur", current, "store", r.get("storeId"), "job", r.get("jobId"), "now", now));
        for (Map<String, Object> s : rows)
        {
            Date st = StaffSupport.parse(s.get("startTime"));
            Date et = StaffSupport.parse(s.get("endTime"));
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("slotId", s.get("slotId"));
            m.put("time", StaffSupport.fmt(st, "HH:mm") + (et == null ? "" : "–" + StaffSupport.fmt(et, "HH:mm")));
            m.put("date", StaffSupport.dayLabel(st));
            m.put("current", current != null && current.equals(KgJdbc.asLong(s.get("slotId"))));
            out.add(m);
        }
        return out;
    }

    private static List<String> questions(Map<String, Object> r)
    {
        String title = str(r.get("jobTitle"));
        String type = str(r.get("jobType"));
        List<String> q = new ArrayList<String>();
        q.add("请简单介绍一下自己的过往经历。");
        q.add("为什么选择「" + title + "」这个岗位？你有什么相关经验？");
        if ("volunteer".equals(type))
        {
            q.add("换宿期间你希望收获什么？每天能投入多少时间？");
        }
        else if ("parttime".equals(type))
        {
            q.add("你每周可以固定安排哪些时段上岗？");
        }
        else if ("fulltime".equals(type))
        {
            q.add("你对这个岗位的长期规划是什么？期望的工作节奏是怎样的？");
        }
        else
        {
            q.add("你平时用什么工具完成类似任务？能否提供过往作品？");
        }
        q.add("对我们的店有什么了解？你能为我们带来什么？");
        q.add("到岗后的住宿与作息安排，有没有需要提前沟通的地方？");
        return q;
    }

    private Map<String, Object> load(StaffSupport.Ctx c, Long interviewId)
    {
        Map<String, Object> r = db.must(BASE + "where i.interview_id = :id", p("id", interviewId), "面试不存在");
        Long storeId = KgJdbc.asLong(r.get("storeId"));
        if (!c.me.hasStoreAccess(storeId))
        {
            throw new ServiceException("没有该门店的操作权限", HttpStatus.FORBIDDEN);
        }
        return r;
    }

    private void notSelf(StaffSupport.Ctx c, Map<String, Object> r)
    {
        if (c.uid().equals(KgJdbc.asLong(r.get("appUserId"))))
        {
            throw new ServiceException("不能处理自己提交的报名", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * 会议信息写在这场面试上（kg_interview.remark），不再写到时段上：
     * 之前写时段，候选人改期后旧时段放回可约池，下一位约到这个时段就会看到上一位的会议号。
     * 仍要求时段由本报名占着，免得改到一条已改期 / 已释放的面试。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String,Object> meeting(StaffSupport.Ctx c, Long interviewId, String text)
    {
        Map<String,Object> r=load(c,interviewId);
        support.need(c,"jobs",StaffPerms.EDIT,"interview:meeting","设置面试会议信息");
        notSelf(c,r);
        if (!ApplicationStatus.INTERVIEW_BOOKED.equals(str(r.get("status"))) || !"booked".equals(str(r.get("istatus"))) || "online".equals(r.get("jobType")))
            throw new ServiceException("当前面试状态不能修改会议信息",409);
        String info=text==null?"":text.trim();
        if(info.isEmpty() || info.length()>500) throw new ServiceException("请填写 1–500 字的会议号或链接",422);
        int changed=db.update("update kg_interview i join kg_interview_slot s on s.slot_id=i.slot_id join kg_application a on a.app_id=i.app_id "
            + "set i.remark=:m,i.update_by=:u,i.update_time=now() where i.interview_id=:id and s.slot_id=:slot and a.app_id=:app and a.store_id=:store "
            + "and s.store_id=:store and s.booked_app_id=a.app_id and s.status='booked' and s.del_flag='0' and i.status='booked' "
            + "and a.status='interview_booked' and a.del_flag='0'",p("m",info,"u","staff:"+c.uid(),"id",interviewId,"slot",r.get("slotId"),"app",r.get("appId"),"store",c.storeId));
        if(changed!=1) throw new ServiceException("面试已改期或时段已释放，请刷新后重试",409);
        support.op(c,"interview:meeting","设置面试会议信息","general","面试编号 "+interviewId,true,null);
        return detail(c,interviewId);
    }

    /** 改期：只换时段，不动报名状态 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reschedule(StaffSupport.Ctx c, Long interviewId, Long slotId)
    {
        Map<String, Object> r = load(c, interviewId);
        support.need(c, "jobs", StaffPerms.EDIT, "interview_reschedule", "面试改期");
        notSelf(c, r);
        if (!ApplicationStatus.INTERVIEW_BOOKED.equals(str(r.get("status"))) || !"booked".equals(str(r.get("istatus"))))
        {
            throw new ServiceException("当前状态【" + ApplicationStatus.label(str(r.get("status"))) + "】不能改期");
        }
        Long oldSlot = KgJdbc.asLong(r.get("slotId"));
        if (slotId == null || slotId.equals(oldSlot))
        {
            throw new ServiceException("请选择一个新的面试时段");
        }
        Long appId = KgJdbc.asLong(r.get("appId"));
        Map<String, Object> slot = db.must("select * from kg_interview_slot where slot_id = :id and del_flag = '0'",
                p("id", slotId), "面试时段不存在");
        if (!KgJdbc.asLong(slot.get("storeId")).equals(KgJdbc.asLong(r.get("storeId"))))
        {
            throw new ServiceException("面试时段与报名门店不匹配");
        }
        Long slotJob = KgJdbc.asLong(slot.get("jobId"));
        if (slotJob != null && !slotJob.equals(KgJdbc.asLong(r.get("jobId"))))
        {
            throw new ServiceException("该时段不适用于本岗位");
        }
        Date st = StaffSupport.parse(slot.get("startTime"));
        if (st == null || !st.after(new Date()))
        {
            throw new ServiceException("该面试时段已过期");
        }
        // 先释放旧时段再占新时段：uk_slot_booked 要求一条报名同时只能占一个时段，顺序反了必然撞唯一键返回 500
        // （2026-09-28 复核发现员工端改期每次都失败）；占新时段失败时整个事务回滚，旧时段原样保留
        db.update("update kg_interview_slot set status = 'open', booked_app_id = null, update_time = now() "
                + "where slot_id = :id and booked_app_id = :app and del_flag = '0'", p("id", oldSlot, "app", appId));
        if (db.update("update kg_interview_slot set status = 'booked', booked_app_id = :app, update_time = now() "
                + "where slot_id = :id and status = 'open' and del_flag = '0'", p("id", slotId, "app", appId)) == 0)
        {
            throw new ServiceException("该时段刚刚被占用，请选择其他时段");
        }
        // 门店改期不计入 reschedule_count：那是候选人自己的改期额度（最多 2 次），之前门店改一次就吃掉候选人一次。
        // 门店改期只记 rescheduled_time，次数和原因在 kg_application_log 里有（「面试改期至 …」）
        // 单独填的会议信息是按原时间约的，改期后清空，避免候选人按旧会议号入会；新时段有预设的就显示预设
        int n = db.update("update kg_interview set slot_id = :slot, start_time = :st, end_time = :et, remark = null, "
                + "rescheduled_time = now(), update_by = :u, update_time = now() "
                + "where interview_id = :id and status = 'booked'",
                p("slot", slotId, "st", slot.get("startTime"), "et", slot.get("endTime"), "u", "staff:" + c.uid(), "id", interviewId));
        if (n == 0)
        {
            throw new ServiceException("面试状态已变化，请刷新后重试");
        }
        String when = StaffSupport.fmt(st, "M月d日 HH:mm");
        Map<String, Object> log = new LinkedHashMap<String, Object>();
        log.put("app_id", appId);
        log.put("from_status", ApplicationStatus.INTERVIEW_BOOKED);
        log.put("to_status", ApplicationStatus.INTERVIEW_BOOKED);
        log.put("operator_type", "staff");
        log.put("operator_id", String.valueOf(c.uid()));
        log.put("operator_name", c.name());
        log.put("reason", "面试改期至 " + when);
        log.put("create_time", new Date());
        db.insert("kg_application_log", log);
        support.notifyUser(KgJdbc.asLong(r.get("appUserId")), MessageCategory.INTERVIEW, "面试时间已调整",
                "门店把你的视频面试调整到 " + when + "，如时间不合适请在消息里联系门店。", "application", appId,
                "/pages/jobs/interview?appId=" + appId);
        support.op(c, "interview_reschedule", "面试改期", "general", MaskUtils.name(str(r.get("realName"))) + " → " + when, true, null);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("startTime", slot.get("startTime"));
        out.put("when", when);
        return out;
    }

    @Transactional(rollbackFor = Exception.class)
    public void result(StaffSupport.Ctx c, Long interviewId, String result)
    {
        Map<String, Object> r = load(c, interviewId);
        if (!java.util.Objects.equals(c.storeId, KgJdbc.asLong(r.get("storeId")))) throw new ServiceException("请先切换到面试所属门店再登记结果", 403);
        support.need(c, "jobs", StaffPerms.EDIT, "interview_result", "登记面试结果");
        notSelf(c, r);
        if (!Arrays.asList("pass", "reject", "pending").contains(result)) throw new ServiceException("请选择通过、未通过或待定", 422);
        if (!"done".equals(str(r.get("istatus")))) throw new ServiceException("结束面试后再登记结果", 422);
        // Editing a note or retrying the same result must not move the original reporting date.
        db.update("update kg_interview set staff_result_time=case when staff_result<=>:r then staff_result_time else now() end, staff_result=:r, update_by=:u, update_time=now() where interview_id=:id", p("r",result,"u","staff:"+c.uid(),"id",interviewId));
        support.op(c, "interview_result", "登记面试结果", "general", MaskUtils.name(str(r.get("realName")))+" "+result, true, null);
    }

    public void rate(StaffSupport.Ctx c, Long interviewId, int rating, String note)
    {
        Map<String, Object> r = load(c, interviewId);
        support.need(c, "jobs", StaffPerms.EDIT, "interview_rate", "面试评价");
        notSelf(c, r);
        if (rating < 1 || rating > 5)
        {
            throw new ServiceException("评分为 1–5 星");
        }
        Date start = StaffSupport.parse(r.get("startTime"));
        boolean booked = ApplicationStatus.INTERVIEW_BOOKED.equals(str(r.get("status")));
        if (booked && (start == null || start.after(new Date())))
        {
            throw new ServiceException("面试开始后才能评价");
        }
        db.update("update kg_interview set staff_rating = :r, staff_note = :n, update_by = :u, update_time = now() where interview_id = :id",
                p("r", rating, "n", StaffSupport.cut(note == null ? null : note.trim(), 500), "u", "staff:" + c.uid(), "id", interviewId));
        support.op(c, "interview_rate", "面试评价", "general", MaskUtils.name(str(r.get("realName"))) + " " + rating + " 星", true, null);
    }

    /** 标记面试已结束：走报名状态机（interview_booked → interview_done），候选人随后在小程序里表态 */
    public void finish(StaffSupport.Ctx c, Long interviewId)
    {
        Map<String, Object> r = load(c, interviewId);
        support.need(c, "jobs", StaffPerms.EDIT, "interview_finish", "结束面试");
        notSelf(c, r);
        Long appId = KgJdbc.asLong(r.get("appId"));
        Date start = StaffSupport.parse(r.get("startTime"));
        if (start != null && start.after(new Date()))
        {
            throw new ServiceException("面试还没开始，不能标记结束");
        }
        apps.finishInterview(appId, null, null, null, null, null,
                OperatorDTO.staff(c.uid(), c.name(), KgJdbc.asLong(r.get("storeId"))));
        support.notifyUser(KgJdbc.asLong(r.get("appUserId")), MessageCategory.INTERVIEW, "面试已结束，请确认意向",
                "门店已结束与你的面试，请在 24 小时内告诉门店你是否愿意前往。", "application", appId,
                "/pages/jobs/interview?appId=" + appId);
        support.op(c, "interview_finish", "结束面试", "general", MaskUtils.name(str(r.get("realName"))), true, null);
    }
}
