package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * 过期线上任务「继续开启」（2026-11-03 主人：「线上任务，几天，是预期，过期了线上专员有个，增加一个继续开启的功能」）。
 *
 * 截止时间只是预计完成时间：到点后 KgOnlineTaskService.closeExpiredTasks 照旧自动关闭（不再接单，已接单的照常验收、结算），
 * 但关闭不再是死路 —— 线上任务专员（以及有「线上任务·编辑」的小院主理人、商家服务人、城市负责人）和后台都能选一个新的预计完成时间重新开启。
 *
 * 只有「因为到期被关闭」的才能继续开启：close_time ≥ deadline_time。到期前被平台关掉的（审核不通过、违规下架）close_time 早于截止，
 * 不能借这个按钮绕过平台；合作商家不能用（他的任务走「换工主理人核实 → 商家服务人上架」，过期的需求要重新提交）。
 * 上过架的回到「招聘中」（publish_time 刷新，列表里重新曝光）；从没上过架的草稿回到「待平台审核」，照旧由平台审核上架；
 * 商家需求转来的草稿不能继续开启（需求已经跟着关了），要商家重新提交。
 * 新的预计完成时间：至少晚于现在 2 小时、最多 180 天内（和发布时一样）。每次都记操作日志（员工端 kg_staff_op_log，后台 sys_oper_log），并通知门店和已接单的人。
 */
@Service
public class KgOnlineTaskReopen
{
    @Autowired
    private KgJdbc db;

    @Autowired
    private StaffSupport support;

    /** 能继续开启的角色：有「线上任务·编辑」、但不是合作商家 */
    public static boolean canReopen(String role)
    {
        return !StaffRole.MERCHANT.equals(role) && StaffPerms.allows(role, "online", StaffPerms.EDIT);
    }

    /** 控制器在事务外调用：拒绝时的失败日志不随业务回滚 */
    public StaffSupport.Ctx ctx(Long storeId)
    {
        StaffSupport.Ctx c = support.ctx(storeId);
        if (!canReopen(c.role))
        {
            support.op(c, "online_reopen", "继续开启线上任务", "general", null, false, "角色不符");
            throw new ServiceException("当前角色（" + StaffRole.label(c.role) + "）不能继续开启线上任务", HttpStatus.FORBIDDEN);
        }
        return c;
    }

    /** 小程序 / 后台据此决定给不给「继续开启」按钮（job 行要带 status、close_time、deadline_time、publish_time） */
    public static boolean reopenable(Map<String, Object> j)
    {
        if (!"closed".equals(KgJdbc.asStr(j.get("status"))))
        {
            return false;
        }
        Date deadline = StaffSupport.parse(j.get("deadlineTime"));
        Date closed = StaffSupport.parse(j.get("closeTime"));
        return deadline != null && closed != null && !closed.before(deadline) && !deadline.after(new Date());
    }

    /** 默认的新预计完成时间：今天起 7 天后的 18:00（页面先填上，人可以改） */
    public static String defaultDeadline()
    {
        return LocalDateTime.now().plusDays(7).withHour(18).withMinute(0).format(KgOnlineTaskService.DT);
    }

    static LocalDateTime parseDeadline(Object raw)
    {
        String s = raw == null ? "" : String.valueOf(raw).trim();
        LocalDateTime d;
        try
        {
            d = LocalDateTime.parse(s, KgOnlineTaskService.DT);
        }
        catch (RuntimeException e)
        {
            throw new ServiceException("请选择新的预计完成时间（年-月-日 时:分）", 422);
        }
        if (d.isBefore(LocalDateTime.now().plusHours(2)))
        {
            throw new ServiceException("新的预计完成时间至少晚于现在 2 小时", 422);
        }
        if (d.isAfter(LocalDateTime.now().plusDays(KgOnlineTaskService.MAX_DEADLINE_DAYS)))
        {
            throw new ServiceException("新的预计完成时间最多 " + KgOnlineTaskService.MAX_DEADLINE_DAYS + " 天内", 422);
        }
        return d;
    }

    /** 员工端：只能开本店的 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reopenByStaff(StaffSupport.Ctx c, Long jobId, Object deadline)
    {
        Map<String, Object> out = reopen(jobId, c.storeId, deadline, "staff:" + c.uid(), c.name());
        support.op(c, "online_reopen", "继续开启线上任务", "general", out.get("title") + " → 预计 " + out.get("deadline") + " 完成", true, null);
        return out;
    }

    /** 后台（岗位管理）：任何门店的 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reopenByAdmin(Long jobId, Object deadline, String operator)
    {
        return reopen(jobId, null, deadline, operator, "平台");
    }

    private Map<String, Object> reopen(Long jobId, Long storeId, Object rawDeadline, String operator, String who)
    {
        LocalDateTime deadline = parseDeadline(rawDeadline);
        Map<String, Object> j = db.one("select j.job_id, j.store_id, j.title, j.status, j.publish_time, j.close_time, t.deadline_time from kg_job j "
                + "join kg_online_task t on t.job_id = j.job_id where j.job_id = :id and j.job_type = 'online' and j.del_flag = '0' for update", p("id", jobId));
        if (j == null || (storeId != null && !storeId.equals(KgJdbc.asLong(j.get("storeId")))))
        {
            throw new ServiceException("任务不存在或不属于当前门店", 404);
        }
        if (!"closed".equals(KgJdbc.asStr(j.get("status"))))
        {
            throw new ServiceException("这个任务没有关闭，不用继续开启；要改预计完成时间请联系平台", HttpStatus.CONFLICT);
        }
        if (!reopenable(j))
        {
            throw new ServiceException("这个任务不是到期关闭的（平台审核不通过或手动关闭），不能继续开启；如需要请重新发布", HttpStatus.CONFLICT);
        }
        boolean published = j.get("publishTime") != null;
        if (!published && db.count("select count(*) from kg_merchant_request where job_id = :j and del_flag = '0'", p("j", jobId)) > 0)
        {
            throw new ServiceException("这是合作商家提交的需求，到期没上架已经关闭；请商家重新提交", HttpStatus.CONFLICT);
        }
        String at = deadline.format(KgOnlineTaskService.DT);
        String by = StaffSupport.cut(operator, 64);
        db.update("update kg_online_task set deadline_time = :d, update_by = :by, update_time = now() where job_id = :id", p("d", at + ":00", "by", by, "id", jobId));
        String to = published ? "published" : "draft";
        if (db.update("update kg_job set status = :to, close_time = null, deliver_desc = :dd, " + (published ? "publish_time = now(), " : "")
                + "update_by = :by, update_time = now() where job_id = :id and status = 'closed' and del_flag = '0'",
                p("to", to, "dd", KgOnlineTaskService.deliverDesc(deadline), "by", by, "id", jobId)) == 0)
        {
            throw new ServiceException("任务状态刚刚变化了，请刷新后再看", HttpStatus.CONFLICT);
        }
        String title = KgJdbc.asStr(j.get("title"));
        Long store = KgJdbc.asLong(j.get("storeId"));
        support.notifyStore(store, "system", "线上任务已继续开启", "「" + title + "」由" + who + "继续开启，新的预计完成时间 " + at + (published ? "，重新开始接单" : "，等平台审核上架"),
                "job", jobId, "/pages/staff/online-jobs?jobId=" + jobId + "&storeId=" + store);
        // 只发给还在做的接单人：报名进行中、还没有结算单（验收通过就有结算单，已结算完成的之前也收到「可以继续提交成果」）。
        // 已交成果在等验收的现在交不了，说成果等验收；其余的才说可以继续提交
        for (Map<String, Object> a : db.list("select a.app_id, a.app_user_id, (select x.status from kg_online_submission x where x.app_id = a.app_id order by x.round desc limit 1) sub "
                + "from kg_application a where a.job_id = :j and a.del_flag = '0' and a.status in (:w) "
                + "and not exists (select 1 from kg_online_settlement s where s.app_id = a.app_id)",
                p("j", jobId, "w", KgOnlineTaskService.WORKING)))
        {
            Long appId = KgJdbc.asLong(a.get("appId"));
            boolean waitingReview = "submitted".equals(KgJdbc.asStr(a.get("sub")));
            support.notifyUser(KgJdbc.asLong(a.get("appUserId")), "apply", "线上任务时间延长了", "「" + title + "」预计完成时间延长到 " + at
                    + (waitingReview ? "，你已提交的成果等门店验收" : "，可以继续提交成果"),
                    "application", appId, "/pages/jobs/deliver?appId=" + appId);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("jobId", jobId);
        out.put("title", title);
        out.put("status", to);
        out.put("deadline", at);
        out.put("message", published ? "已继续开启，预计 " + at + " 完成" : "已重新提交平台审核，预计 " + at + " 完成");
        return out;
    }
}
