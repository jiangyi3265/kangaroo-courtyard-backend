package com.ruoyi.kangaroo.service.impl;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.kangaroo.config.KangarooProperties;
import com.ruoyi.kangaroo.domain.KgApplication;
import com.ruoyi.kangaroo.domain.KgApplicationLog;
import com.ruoyi.kangaroo.domain.KgInterview;
import com.ruoyi.kangaroo.domain.KgInterviewSlot;
import com.ruoyi.kangaroo.domain.KgJob;
import com.ruoyi.kangaroo.dto.ApplicationSubmitDTO;
import com.ruoyi.kangaroo.dto.OperatorDTO;
import com.ruoyi.kangaroo.enums.ApplicationStatus;
import com.ruoyi.kangaroo.enums.JobStatus;
import com.ruoyi.kangaroo.enums.MessageCategory;
import com.ruoyi.kangaroo.enums.OperatorType;
import com.ruoyi.kangaroo.ext.staff.StaffPerms;
import com.ruoyi.kangaroo.security.StaffGrantView;
import com.ruoyi.kangaroo.mapper.KgApplicationLogMapper;
import com.ruoyi.kangaroo.mapper.KgApplicationMapper;
import com.ruoyi.kangaroo.mapper.KgInterviewMapper;
import com.ruoyi.kangaroo.mapper.KgInterviewSlotMapper;
import com.ruoyi.kangaroo.mapper.KgJobMapper;
import com.ruoyi.kangaroo.service.IKgApplicationService;
import com.ruoyi.kangaroo.service.IKgIdempotencyService;
import com.ruoyi.kangaroo.service.IKgMessageService;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;
import com.ruoyi.kangaroo.util.MaskUtils;

/**
 * 岗位报名履约服务实现。
 *
 * 关于消息里的 route：只能指向已经接通后端接口的页面。
 * （/pages/jobs/contract 早已接通责任书阅读、签署、到店信息三组接口，「商家已确认录用」的通知直达签约页；
 *  这段注释此前还写着"一个接口都没调"，于是确认录用后用户找不到签约入口——2026-09-28 复核发现。）
 *
 * /pages/jobs/interview 曾经也在这个名单里，但它后来接上了
 * apiApplicationDetail / apiFinishInterview / apiConfirmIntent —— 这段注释没跟上，
 * 于是「面试时间已确认」的 route 一直指向 progress；而 progress 页在选完时段后
 * 跳到面试页的那条 replace 也在接线时被删了。两边都不给入口，结果是面试页
 * 在整个小程序里无路可达，finish-interview 与 confirm 永远发不出去，
 * 报名卡死在 interview_booked。现在这条消息直接落到面试候场页。
 *
 * 设计要点：
 * 1. 状态变更统一走 transfer()，内部先做状态机校验，再用 CAS 更新（where status = fromStatus），
 *    CAS 影响行数为 0 表示并发下状态已被别人改掉，直接拒绝。这样同一申请被两个员工同时审核时，
 *    只有一个成功，不会出现两条审核日志或重复扣名额。
 * 2. 权限在服务层校验：用户只能操作自己的申请，门店员工只能操作本门店的申请。
 * 3. 任何流转都写 kg_application_log，形成可追溯的审计链。
 */
@Service
public class KgApplicationServiceImpl implements IKgApplicationService
{
    private static final String BIZ_TYPE = "application_submit";

    /**
     * 幂等键占用后多久还没绑上业务主键，就认定是上次提交崩在半路留下的孤儿键。
     * 取 120 秒：远大于一次正常提交的耗时，又不至于让用户等太久才能重试。
     */
    private static final int STALE_KEY_SECONDS = 120;

    /** kg_interview.status：已约到时段，面试还没发生 */
    private static final String INTERVIEW_STATUS_BOOKED = "booked";

    /** kg_interview.status：面试确实发生过，这条记录是历史事实 */
    private static final String INTERVIEW_STATUS_DONE = "done";

    /** kg_interview.status：约了但没面成 */
    private static final String INTERVIEW_STATUS_CANCELED = "canceled";

    /** 用户最多申请改期的次数 */
    private static final int MAX_RESCHEDULE = 2;

    @Autowired
    private KgApplicationMapper kgApplicationMapper;

    @Autowired
    private KgApplicationLogMapper kgApplicationLogMapper;

    @Autowired
    private KgJobMapper kgJobMapper;

    @Autowired
    private KgInterviewMapper kgInterviewMapper;

    @Autowired
    private KgInterviewSlotMapper kgInterviewSlotMapper;

    @Autowired
    private IKgMessageService kgMessageService;

    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.staff.CityLeadScope cityLeads;

    /** 线上任务的接单通知只发给能管线上任务的人（StaffSupport.onlineRecipients），@Lazy 避免循环依赖 */
    @Lazy
    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.staff.StaffSupport staffSupport;

    @Autowired
    private IKgIdempotencyService idempotencyService;

    @Autowired
    private IKgStaffGrantService kgStaffGrantService;

    @Autowired
    private KangarooProperties properties;

    // ==================== 查询 ====================

    @Override
    public KgApplication selectByAppId(Long appId)
    {
        return kgApplicationMapper.selectKgApplicationByAppId(appId);
    }

    @Override
    public KgApplication requireApplication(Long appId)
    {
        if (appId == null)
        {
            throw new ServiceException("报名ID不能为空");
        }
        KgApplication app = kgApplicationMapper.selectKgApplicationByAppId(appId);
        if (app == null)
        {
            throw new ServiceException("报名记录不存在");
        }
        return app;
    }

    @Override
    public KgApplication requireOwnedByUser(Long appId, Long appUserId)
    {
        KgApplication app = requireApplication(appId);
        if (appUserId == null || !appUserId.equals(app.getAppUserId()))
        {
            // 不区分"不存在"与"不属于你"，避免通过错误信息枚举他人报名ID
            throw new ServiceException("报名记录不存在", Integer.valueOf(HttpStatus.FORBIDDEN));
        }
        return app;
    }

    @Override
    public KgApplication requireOwnedByStore(Long appId, OperatorDTO operator)
    {
        KgApplication app = requireApplication(appId);
        if (OperatorType.ADMIN.equals(operator.getType()))
        {
            // 管理后台账号已通过 @PreAuthorize 做过功能权限校验，可跨门店操作
            return app;
        }
        if (OperatorType.STAFF.equals(operator.getType()))
        {
            Long appUserId = parseLong(operator.getId());
            if (appUserId != null)
            {
                for (StaffGrantView g : kgStaffGrantService.loadActiveGrantViews(appUserId))
                    if (app.getStoreId().equals(g.getStoreId()) && Boolean.TRUE.equals(g.getActive())
                            && (!com.ruoyi.kangaroo.enums.StaffRole.CITY_LEAD.equals(g.getRoleCode()) || com.ruoyi.kangaroo.ext.staff.RolePhase.isNew())
                            && (!com.ruoyi.kangaroo.enums.StaffRole.onlineOnly(g.getRoleCode()) || isOnlineJob(app.getJobId()))) return app;
            }
            throw new ServiceException("没有该门店的操作权限", Integer.valueOf(HttpStatus.FORBIDDEN));
        }
        throw new ServiceException("没有权限执行该操作", Integer.valueOf(HttpStatus.FORBIDDEN));
    }

    /**
     * 不许员工处理自己提交的报名。
     *
     * requireOwnedByStore 只回答"这个人对这家门店有没有授权"，
     * 而门店员工完全可以用同一个微信账号报本店的岗位 —— 两个条件同时成立时，
     * 他就能自己通过自己的审核、自己确认录用自己、自己给自己标记入职。
     * 名额、食宿、保险都挂在这几个动作上，这是一条必须堵的路。
     *
     * 只拦写动作，不拦读：看自己那条报名的详情是正当的。
     * 后台管理员（ADMIN）走的是另一套账号体系，不可能是应聘者本人，不在此列。
     */
    private void assertNotSelfDealing(KgApplication app, OperatorDTO operator, String action)
    {
        if (operator == null || !OperatorType.STAFF.equals(operator.getType()))
        {
            return;
        }
        Long actorId = parseLong(operator.getId());
        if (actorId != null && actorId.equals(app.getAppUserId()))
        {
            throw new ServiceException(
                    "不能" + action + "自己提交的报名，请由本店其他员工或平台运营处理",
                    Integer.valueOf(HttpStatus.FORBIDDEN));
        }
    }

    /** 招聘写操作必须按报名所属门店的实时授权判权，不能借用另一门店上的主理人身份。 */
    private void requireHiringAction(KgApplication app, OperatorDTO operator, String permission)
    {
        if (!OperatorType.STAFF.equals(operator.getType()))
        {
            // 调用方已完成 requireOwnedByStore；后台管理仍沿用其功能权限。
            return;
        }
        List<StaffGrantView> grants = kgStaffGrantService.loadActiveGrantViews(parseLong(operator.getId()));
        if (grants != null)
        {
            for (StaffGrantView grant : grants)
            {
                if (app.getStoreId().equals(grant.getStoreId()) && Boolean.TRUE.equals(grant.getActive())
                        && StaffPerms.allows(grant.getRoleCode(), StaffPerms.AUDIT.equals(permission) && isOnlineJob(app.getJobId()) ? "online" : "jobs", permission))
                {
                    return;
                }
            }
        }
        throw new ServiceException("普通员工仅可查看报名，请由本门店主理人或店长处理", HttpStatus.FORBIDDEN);
    }

    @Override
    public List<KgApplication> selectKgApplicationList(KgApplication query)
    {
        return kgApplicationMapper.selectKgApplicationList(query);
    }

    @Override
    public List<KgApplication> selectMyApplications(Long appUserId, String status)
    {
        return kgApplicationMapper.selectAppUserApplications(appUserId, status);
    }

    @Override
    public List<KgApplicationLog> selectLogs(Long appId)
    {
        return kgApplicationLogMapper.selectByAppId(appId);
    }

    @Override
    public KgInterview selectInterview(Long appId)
    {
        return kgInterviewMapper.selectByAppId(appId);
    }

    // ==================== 用户侧 ====================

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public KgApplication submit(ApplicationSubmitDTO dto, Long appUserId, String nickname)
    {
        com.ruoyi.kangaroo.ext.community.KgCommunityFeature.requireRecruitment(kgJdbcForQuota);
        if (dto == null || dto.getJobId() == null)
        {
            throw new ServiceException("请选择要报名的岗位");
        }

        com.ruoyi.kangaroo.util.AccountState.requireActive(kgJdbcForQuota, appUserId);

        // 幂等：同一个 idemKey 重复提交只产生一条报名。
        //
        // idemKey 完全由客户端提供，服务端必须假设它可能重复、可能是别人用过的。
        // 命中已有键时不能无条件把那张单子返回：
        //   - 归属不符 -> 会把别人的 appId/appNo 泄漏给当前调用方，且当前用户其实什么都没提交
        //   - 岗位不符 -> 用户以为报了 B 岗位，拿到的是自己之前 A 岗位的旧单
        //   - 记录已不存在 -> 直接 return null，上层解引用 NPE，异常原文会被 toast 给用户看
        boolean acquired = idempotencyService.tryAcquire(dto.getIdemKey(), BIZ_TYPE);
        if (!acquired)
        {
            Long existedId = idempotencyService.findBizId(dto.getIdemKey());
            if (existedId == null)
            {
                // biz_id 还是空，两种可能，必须分开处理，不能一律删键：
                //   a) 另一个请求正在提交中（连点、弱网重发）—— 删了键就等于关掉幂等保护；
                //   b) 上一次提交崩在 tryAcquire 与 commit 之间（进程被杀/部署重启），
                //      留下永远不会被绑定的孤儿键 —— 不删就永远卡在同一个报错上。
                // 用键的存活时长区分：超过 STALE_KEY_SECONDS 还没绑上，按孤儿处理。
                Integer age = idempotencyService.ageSeconds(dto.getIdemKey());
                if (age != null && age >= STALE_KEY_SECONDS)
                {
                    idempotencyService.release(dto.getIdemKey());
                    throw new ServiceException("上次提交没有完成，请重新提交");
                }
                throw new ServiceException("报名正在提交中，请勿重复操作");
            }
            KgApplication existed = kgApplicationMapper.selectKgApplicationByAppId(existedId);
            if (existed == null)
            {
                // biz_id 只在业务事务提交后才可见（bindBizId 跟随外层事务），
                // 所以走到这里说明那张单子确实存在过、之后被逻辑删除或归档了。
                // 释放键让用户能重新提交，否则重试多少次都还是这个错。
                idempotencyService.release(dto.getIdemKey());
                throw new ServiceException("上次提交的报名已失效，请重新提交");
            }
            if (!appUserId.equals(existed.getAppUserId()) || !dto.getJobId().equals(existed.getJobId()))
            {
                throw new ServiceException("提交凭证无效，请返回重试");
            }
            return existed;
        }

        // 记录"幂等键是否已经绑上业务主键"。绑定之后这一行被外层事务锁着，
        // 失败回收路径必须绕开它，详见下面 catch 分支的说明。
        boolean bound = false;
        try
        {
            // 同岗位报名排队检查剩余名额，直到本次报名提交才放锁。
            // READ_COMMITTED 保证等待锁后读取最新已提交的报名数。
            kgJobMapper.lockRecruitment(dto.getJobId());
            KgJob job = kgJobMapper.selectKgJobByJobId(dto.getJobId());
            if (job == null || "2".equals(job.getDelFlag()))
            {
                throw new ServiceException("岗位不存在或已下架");
            }
            // 同一用户对同一岗位只能有一条进行中的报名。先判重复、再判状态和名额：
            // 之前名额判在前，已报过名的人再点报名被告知「名额已满」（自己那条正占着名额），小程序也就没法引导去看已有报名
            if (kgApplicationMapper.countActiveByUserAndJob(appUserId, dto.getJobId()) > 0)
            {
                throw new ServiceException("你已报名该岗位，请勿重复报名");
            }
            if (!JobStatus.acceptsApplication(job.getStatus()))
            {
                throw new ServiceException("该岗位当前不接受报名");
            }
            // 线上任务过了截止时间不再接单：之前仍是 published，照样受理报名、门店照样能通过，但接单人之后永远提交不了成果
            if ("online".equals(job.getJobType())
                    && kgJdbcForQuota.count("select count(*) from kg_online_task where job_id = :j and deadline_time is not null and deadline_time < now()",
                            com.ruoyi.kangaroo.ext.KgJdbc.p("j", job.getJobId())) > 0)
            {
                throw new ServiceException("这个任务已过截止时间，不再接单");
            }
            // 门店停用 / 删除后没有人能处理这条报名，也不应该再受理
            java.util.Map<String, Object> jobStore = kgJdbcForQuota.one("select status, del_flag from kg_store where store_id = :s",
                    com.ruoyi.kangaroo.ext.KgJdbc.p("s", job.getStoreId()));
            if (jobStore == null || !"0".equals(com.ruoyi.kangaroo.ext.KgJdbc.asStr(jobStore.get("status")))
                    || "2".equals(com.ruoyi.kangaroo.ext.KgJdbc.asStr(jobStore.get("delFlag"))))
            {
                throw new ServiceException("该门店暂停接待，这个岗位暂时不接受报名");
            }
            if (job.getRecruitStoreId() != null)
            {
                java.util.Map<String, Object> recruitingStore = kgJdbcForQuota.one("select status, del_flag from kg_store where store_id = :s",
                        com.ruoyi.kangaroo.ext.KgJdbc.p("s", job.getRecruitStoreId()));
                if (recruitingStore == null || !"0".equals(com.ruoyi.kangaroo.ext.KgJdbc.asStr(recruitingStore.get("status")))
                        || !"0".equals(com.ruoyi.kangaroo.ext.KgJdbc.asStr(recruitingStore.get("delFlag"))))
                    throw new ServiceException("招聘门店暂停接待，这个岗位暂时不接受报名");
            }
            if (job.getRemainingCount() != null && job.getRemainingCount() == 0)
            {
                throw new ServiceException("该岗位名额已满");
            }

            KgApplication app = new KgApplication();
            app.setJobId(job.getJobId());
            // 门店ID取岗位当前所属门店的快照；之后后台给岗位换门店时，还没走完流程的报名
            // 由 moveOpenApplications 一起转过去，已结束的留在原门店
            app.setStoreId(job.getStoreId());
            app.setAppUserId(appUserId);
            boolean online = "online".equals(job.getJobType());
            // 2026-10-18 报名改成「用简历投递」：新版报名页不再单独填姓名 / 年龄 / 手机 / 技能，没传的从「我的简历」（kg_resume）取，
            // 简历里没留手机号时用账号绑定的手机号；老版本小程序照旧自己传，传了的以传的为准。取完照旧走下面同一套校验
            java.util.Map<String, Object> resume = resumeFallback(dto, online, appUserId);
            app.setRealName(requireText(orResume(dto.getName(), resume, "name"), "姓名", 40));
            String phone = orResume(dto.getPhone(), resume, "phone");
            app.setPhone(requirePhone(StringUtils.isEmpty(phone) ? com.ruoyi.kangaroo.ext.KgJdbc.asStr(resume.get("userPhone")) : phone));
            // 线上任务只收姓名、手机号和接单说明，忽略旧客户端带来的年龄、技能和到岗草稿。
            // 类型只取真实岗位，客户端不能用该分支跳过线下岗位的到岗校验。
            if (!online)
            {
                Long resumeAge = com.ruoyi.kangaroo.ext.KgJdbc.asLong(resume.get("age"));
                app.setAge(requireAge(dto.getAge() != null ? dto.getAge() : (resumeAge == null ? null : Integer.valueOf(resumeAge.intValue()))));
                app.setSkills(limit(orResume(dto.getSkills(), resume, "skillText"), 500));
                app.setArrivalDate(requireArrivalDate(dto.getArrival(), job));
                app.setStayDays(requireStayDays(dto.getDays(), job));
            }
            app.setNote(limit(dto.getNote(), 500));
            app.setStatus(ApplicationStatus.SUBMITTED);
            app.setSubmitTime(new Date());
            app.setAppNo(buildAppNo(job.getJobId(), appUserId));
            app.setCreateBy("app:" + appUserId);
            try
            {
                kgApplicationMapper.insertKgApplication(app);
            }
            catch (DuplicateKeyException e)
            {
                // uk_app_active_user_job 兜住了并发：上面的 countActiveByUserAndJob 判重
                // 与这里的 insert 之间有竞态窗口，只有数据库能真正保证唯一。
                throw new ServiceException("你已报名该岗位，请勿重复报名");
            }

            kgJobMapper.increaseAppliedCount(job.getJobId());

            writeLog(app.getAppId(), null, ApplicationStatus.SUBMITTED,
                    OperatorDTO.user(appUserId, nickname), "提交报名");

            idempotencyService.bindBizId(dto.getIdemKey(), app.getAppId());
            // 过了这一行，kg_idempotency 那一行就被**外层事务**锁住了。
            // 之后再失败绝不能走 release()：它是 REQUIRES_NEW，会另开连接删同一行，
            // 而外层事务此刻被挂起、不会先放锁，两边互等到 innodb_lock_wait_timeout（50 秒）。
            bound = true;

            // 给用户的回执
            kgMessageService.send(appUserId, "user", MessageCategory.APPLY,
                    "报名已提交",
                    "你报名的「" + job.getTitle() + "」已提交，等待商家审核。",
                    "application", app.getAppId(), "/pages/jobs/progress?appId=" + app.getAppId());

            // 给门店员工的待办
            notifyStaff(job.getStoreId(), MessageCategory.APPLY,
                    "新的报名待审核",
                    MaskUtils.name(app.getRealName()) + " 报名了「" + job.getTitle() + "」，请及时审核。",
                    app.getAppId(), "/pages/staff/applications?appId=" + app.getAppId());

            return app;
        }
        catch (RuntimeException e)
        {
            // 只有"还没绑定业务主键"时才释放幂等键 —— 这时外层事务对这一行没有锁，
            // 独立事务删得掉，用户改完参数可以用同一个键立刻重试。
            //
            // 已经绑过之后再失败，整个外层事务会回滚，biz_id 自己变回 NULL，
            // 键仍被 tryAcquire 占着。那正是"上次提交没完成"的形态，
            // 由本方法开头的 STALE_KEY_SECONDS 超时分支兜底。
            // 在这里硬删只会撞上自己持有的锁，白等 50 秒再抛一个与真实原因无关的异常。
            if (!bound)
            {
                idempotencyService.release(dto.getIdemKey());
            }
            throw e;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int withdraw(Long appId, OperatorDTO operator)
    {
        KgApplication app = requireOwnedByUser(appId, parseLong(operator.getId()));
        int rows = transfer(app, ApplicationStatus.WITHDRAWN, operator, "用户撤回报名");

        KgApplication update = new KgApplication();
        update.setAppId(app.getAppId());
        update.setWithdrawTime(new Date());
        kgApplicationMapper.updateKgApplication(update);

        // 面试时段的释放已由 transfer() 统一处理，这里不再重复调用

        notifyStaff(app.getStoreId(), MessageCategory.APPLY, "报名已被撤回",
                MaskUtils.name(app.getRealName()) + " 撤回了报名。", app.getAppId(),
                "/pages/staff/applications?appId=" + app.getAppId());
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KgInterview bookInterview(Long appId, Long slotId, OperatorDTO operator)
    {
        KgApplication app = requireOwnedByUser(appId, parseLong(operator.getId()));
        if (isOnlineJob(app.getJobId()))
        {
            // 线上任务没有视频面试这一步：之前审核通过后系统还会提示「请选择面试时间」，接单人真能占用门店的面试时段
            throw new ServiceException("线上任务不需要预约面试，请按任务要求完成并提交成果");
        }
        if (slotId == null)
        {
            throw new ServiceException("请选择一个面试时段");
        }

        KgInterviewSlot slot = kgInterviewSlotMapper.selectKgInterviewSlotBySlotId(slotId);
        if (slot == null || "2".equals(slot.getDelFlag()))
        {
            throw new ServiceException("面试时段不存在");
        }
        if (!slot.getStoreId().equals(app.getStoreId()))
        {
            throw new ServiceException("面试时段与报名门店不匹配");
        }
        if (slot.getJobId() != null && !slot.getJobId().equals(app.getJobId()))
        {
            throw new ServiceException("该时段不适用于本岗位");
        }
        if (slot.getStartTime() != null && slot.getStartTime().before(new Date()))
        {
            throw new ServiceException("该面试时段已过期");
        }

        // 先抢时段（CAS），抢不到说明已被别人占用，直接拒绝，避免两个人约到同一时段
        if (kgInterviewSlotMapper.bookSlotWithCas(slotId, appId) == 0)
        {
            throw new ServiceException("该时段刚刚被占用，请选择其他时段");
        }

        transfer(app, ApplicationStatus.INTERVIEW_BOOKED, operator, "预约面试时段");

        KgInterview interview = new KgInterview();
        interview.setAppId(appId);
        interview.setSlotId(slotId);
        interview.setStartTime(slot.getStartTime());
        interview.setEndTime(slot.getEndTime());
        interview.setStatus(INTERVIEW_STATUS_BOOKED);
        interview.setCreateBy("app:" + operator.getId());
        kgInterviewMapper.insertKgInterview(interview);

        kgMessageService.send(app.getAppUserId(), "user", MessageCategory.INTERVIEW,
                "面试时间已确认",
                "你的视频面试时间为 " + formatDateTime(slot.getStartTime()) + "，请准时参加。",
                "application", appId, "/pages/jobs/interview?appId=" + appId);

        // 直接打开这场面试的详情（带上门店，多店员工也落在对的门店），填会议号、改期都在那里
        notifyStaff(app.getStoreId(), MessageCategory.INTERVIEW, "面试已预约",
                MaskUtils.name(app.getRealName()) + " 预约了 " + formatDateTime(slot.getStartTime()) + " 的面试。",
                appId, "/pages/staff/interviews?id=" + interview.getInterviewId() + "&storeId=" + app.getStoreId());

        return interview;
    }

    /**
     * 用户申请改期：换一个还开放的时段，把原时段还回可约池。
     *
     * 约束都是为了不让门店白等：开始前 2 小时以内不能改（门店已经在准备），最多改 2 次，
     * 新时段必须是同门店、同岗位（或不限岗位）的未过期空位。抢新时段用 CAS，抢不到就原样返回，
     * 原时段在抢到之前不会释放 —— 不会出现"旧的还了、新的没抢到"的空窗。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public KgInterview rescheduleInterview(Long appId, Long slotId, String reason, OperatorDTO operator)
    {
        KgApplication app = requireOwnedByUser(appId, parseLong(operator.getId()));
        // 先锁报名行、用当前读重新确认状态：上面 requireOwnedByUser 是快照读，与撤回 / 终止并发时状态可能已经变了。
        // 锁住之后，那两条路径的 CAS 要等本事务提交才能继续，它们释放时段时看到的就是改期后的新时段
        String lockedStatus = kgApplicationMapper.selectStatusForUpdate(appId);
        if (!ApplicationStatus.INTERVIEW_BOOKED.equals(lockedStatus))
        {
            throw new ServiceException("当前状态不能申请改期");
        }
        KgInterview interview = kgInterviewMapper.selectByAppIdForUpdate(appId);
        if (interview == null || !INTERVIEW_STATUS_BOOKED.equals(interview.getStatus()))
        {
            throw new ServiceException("没有待进行的面试");
        }
        int used = interview.getRescheduleCount() == null ? 0 : interview.getRescheduleCount().intValue();
        if (used >= MAX_RESCHEDULE)
        {
            throw new ServiceException("最多只能改期 " + MAX_RESCHEDULE + " 次，请直接联系门店");
        }
        if (interview.getStartTime() != null && interview.getStartTime().getTime() - System.currentTimeMillis() < 2 * 3600_000L)
        {
            throw new ServiceException("面试开始前 2 小时内不能改期，请直接联系门店");
        }
        if (slotId == null)
        {
            throw new ServiceException("请选择新的面试时段");
        }
        if (slotId.equals(interview.getSlotId()))
        {
            throw new ServiceException("新时段和原时段一样");
        }
        KgInterviewSlot slot = kgInterviewSlotMapper.selectKgInterviewSlotBySlotId(slotId);
        if (slot == null || "2".equals(slot.getDelFlag()))
        {
            throw new ServiceException("面试时段不存在");
        }
        if (!slot.getStoreId().equals(app.getStoreId()))
        {
            throw new ServiceException("面试时段与报名门店不匹配");
        }
        if (slot.getJobId() != null && !slot.getJobId().equals(app.getJobId()))
        {
            throw new ServiceException("该时段不适用于本岗位");
        }
        if (slot.getStartTime() != null && slot.getStartTime().before(new Date()))
        {
            throw new ServiceException("该面试时段已过期");
        }
        // kg_interview_slot 上有 uk_slot_booked(booked_app_id)：一条报名同时只能占一个时段，
        // 所以必须先还旧的再抢新的，反过来会撞唯一键。
        // 抢不到时抛异常，整个方法的事务回滚，旧时段原样留在 booked —— 不会出现"旧的还了、新的没抢到"。
        Date oldStart = interview.getStartTime();
        if (interview.getSlotId() != null)
        {
            kgInterviewSlotMapper.releaseSlot(interview.getSlotId(), appId);
        }
        if (kgInterviewSlotMapper.bookSlotWithCas(slotId, appId) == 0)
        {
            throw new ServiceException("该时段刚刚被占用，请选择其他时段");
        }
        KgInterview update = new KgInterview();
        update.setInterviewId(interview.getInterviewId());
        update.setSlotId(slotId);
        update.setStartTime(slot.getStartTime());
        update.setEndTime(slot.getEndTime());
        update.setRescheduleCount(Integer.valueOf(used + 1));
        update.setRescheduledTime(new Date());
        update.setUpdateBy("app:" + operator.getId());
        // 门店为原时间单独填的会议信息（存在 remark）改期后作废，清空后显示新时段预设的，并提醒门店重填
        boolean hadOwnMeeting = interview.getRemark() != null && !interview.getRemark().trim().isEmpty();
        update.setRemark("");
        kgInterviewMapper.updateKgInterview(update);

        String note = (reason == null || reason.trim().isEmpty() ? "" : "，原因：" + reason.trim())
                + (hadOwnMeeting ? "。原来填的会议信息已清空，请重新填写" : "");
        kgMessageService.send(app.getAppUserId(), "user", MessageCategory.INTERVIEW, "面试已改期",
                "你的视频面试已改到 " + formatDateTime(slot.getStartTime()) + "，请准时参加。",
                "application", appId, "/pages/jobs/interview?appId=" + appId);
        notifyStaff(app.getStoreId(), MessageCategory.INTERVIEW, "面试改期",
                MaskUtils.name(app.getRealName()) + " 把 " + formatDateTime(oldStart) + " 的面试改到了 " + formatDateTime(slot.getStartTime()) + note,
                appId, "/pages/staff/interviews?id=" + interview.getInterviewId() + "&storeId=" + app.getStoreId());

        interview.setSlotId(slotId);
        interview.setStartTime(slot.getStartTime());
        interview.setEndTime(slot.getEndTime());
        interview.setRescheduleCount(Integer.valueOf(used + 1));
        return interview;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int finishInterview(Long appId, Integer durationSeconds, String understandJob, String acceptBoard,
            String confirmArrival, String feedbackNote, OperatorDTO operator)
    {
        KgApplication app;
        if (OperatorType.USER.equals(operator.getType()))
        {
            app = requireOwnedByUser(appId, parseLong(operator.getId()));
        }
        else
        {
            app = requireOwnedByStore(appId, operator);
            requireHiringAction(app, operator, StaffPerms.EDIT);
            assertNotSelfDealing(app, operator, "完成面试");
        }

        KgInterview interview = kgInterviewMapper.selectByAppId(appId);
        if (interview == null)
        {
            throw new ServiceException("尚未预约面试");
        }
        // 门店先点了「标记结束」（已经是面试已完成）：候选人还没交过问卷时，只补交问卷，不再流转状态。
        // 之前问卷只能随 interview_booked → interview_done 这一次落库，门店先结束后就再也交不上；交过的不能改
        if (OperatorType.USER.equals(operator.getType()) && ApplicationStatus.INTERVIEW_DONE.equals(app.getStatus()))
        {
            KgInterview locked = kgInterviewMapper.selectByAppIdForUpdate(appId);
            if (locked == null || questionnaireSubmitted(locked))
            {
                throw new ServiceException("面试问卷已提交过，不能重复提交", 409);
            }
            KgInterview answers = new KgInterview();
            answers.setInterviewId(locked.getInterviewId());
            answers.setUnderstandJob(limit(understandJob, 64));
            answers.setAcceptBoard(limit(acceptBoard, 64));
            answers.setConfirmArrival(limit(confirmArrival, 64));
            answers.setFeedbackNote(limit(feedbackNote, 200));
            answers.setUpdateBy("app:" + operator.getId());
            kgInterviewMapper.updateKgInterview(answers);
            return 1;
        }
        if (interview.getStartTime() == null || interview.getStartTime().after(new Date()))
        {
            throw new ServiceException("预约面试时间尚未开始，不能提交面试完成", 409);
        }

        int rows = transfer(app, ApplicationStatus.INTERVIEW_DONE, operator, "面试结束");

        KgInterview update = new KgInterview();
        update.setInterviewId(interview.getInterviewId());
        update.setStatus(INTERVIEW_STATUS_DONE);
        update.setFinishTime(new Date());
        update.setDurationSeconds(durationSeconds);
        update.setUnderstandJob(limit(understandJob, 64));
        update.setAcceptBoard(limit(acceptBoard, 64));
        update.setConfirmArrival(limit(confirmArrival, 64));
        update.setFeedbackNote(limit(feedbackNote, 200));
        kgInterviewMapper.updateKgInterview(update);

        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int userConfirm(Long appId, String intent, OperatorDTO operator)
    {
        KgApplication app = requireOwnedByUser(appId, parseLong(operator.getId()));

        if (!"accept".equals(intent) && !"pending".equals(intent) && !"reject".equals(intent))
        {
            throw new ServiceException("意向取值不合法");
        }

        if ("pending".equals(intent))
        {
            // "还想考虑"不推进状态，只记录意向，保持在 interview_done 等待用户再次表态
            if (!ApplicationStatus.INTERVIEW_DONE.equals(app.getStatus()))
            {
                throw new ServiceException("当前状态【" + ApplicationStatus.label(app.getStatus()) + "】不能提交面试意向");
            }
            KgApplication update = new KgApplication();
            update.setAppId(appId);
            update.setUserIntent(intent);
            kgApplicationMapper.updateKgApplication(update);
            writeLog(appId, app.getStatus(), app.getStatus(), operator, "用户选择：还想考虑");
            return 1;
        }

        if ("reject".equals(intent))
        {
            int rows = transfer(app, ApplicationStatus.TERMINATED, operator, "用户表示不合适");
            KgApplication update = new KgApplication();
            update.setAppId(appId);
            update.setUserIntent(intent);
            update.setTerminateReason("用户面试后表示不合适");
            kgApplicationMapper.updateKgApplication(update);
            notifyStaff(app.getStoreId(), MessageCategory.APPLY, "应聘者已婉拒",
                    MaskUtils.name(app.getRealName()) + " 面试后表示不合适。", appId,
                    "/pages/staff/applications?appId=" + appId);
            return rows;
        }

        // accept
        int rows = transfer(app, ApplicationStatus.USER_CONFIRMED, operator, "用户确认愿意前往");

        Calendar cal = Calendar.getInstance();
        Date confirmTime = cal.getTime();
        cal.add(Calendar.HOUR_OF_DAY, properties.getConfirmDeadlineHours());
        Date deadline = cal.getTime();

        KgApplication update = new KgApplication();
        update.setAppId(appId);
        update.setUserIntent(intent);
        update.setUserConfirmTime(confirmTime);
        update.setConfirmDeadline(deadline);
        kgApplicationMapper.updateKgApplication(update);

        notifyStaff(app.getStoreId(), MessageCategory.APPLY, "待确认录用",
                MaskUtils.name(app.getRealName()) + " 已确认愿意前往，请在 "
                        + properties.getConfirmDeadlineHours() + " 小时内完成确认。",
                appId, "/pages/staff/applications?appId=" + appId);

        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int signContract(Long appId, OperatorDTO operator)
    {
        KgApplication app = requireOwnedByUser(appId, parseLong(operator.getId()));
        if (kgJdbcForQuota.count("select count(*) from kg_contract_sign where app_id=:a and app_user_id=:u "
                + "and content_hash is not null and signed_time is not null", com.ruoyi.kangaroo.ext.KgJdbc.p("a", appId, "u", app.getAppUserId())) != 1)
        {
            throw new ServiceException("请在责任书页面阅读并签署完整条款", 409);
        }
        int rows = transfer(app, ApplicationStatus.CONTRACT_SIGNED, operator, "签署责任书");

        kgMessageService.send(app.getAppUserId(), "user", MessageCategory.CONTRACT,
                "责任书已签署",
                "你已完成责任书签署，请按约定日期到店。",
                "application", appId, "/pages/jobs/progress?appId=" + appId);

        notifyStaff(app.getStoreId(), MessageCategory.CONTRACT, "责任书已签署",
                MaskUtils.name(app.getRealName()) + " 已完成签署。", appId,
                "/pages/staff/applications?appId=" + appId);
        return rows;
    }

    // ==================== 门店 / 后台侧 ====================

    @org.springframework.beans.factory.annotation.Autowired
    private com.ruoyi.kangaroo.ext.KgJdbc kgJdbcForQuota;

    /** 自己的代理：定时任务里调本类的事务方法必须经过代理，否则 @Transactional 不生效 */
    @Lazy
    @Autowired
    private IKgApplicationService selfProxy;

    private static final org.slf4j.Logger timeoutLog = org.slf4j.LoggerFactory.getLogger(KgApplicationServiceImpl.class);

    /** 每 10 分钟扫一次超过确认期限的 user_confirmed；间隔可用 kangaroo.application.timeout-scan-ms 调（联调时调短） */
    @Scheduled(fixedDelayString = "${kangaroo.application.timeout-scan-ms:600000}", initialDelayString = "${kangaroo.application.timeout-initial-ms:120000}")
    public void scanConfirmTimeouts()
    {
        for (java.util.Map<String, Object> r : kgJdbcForQuota.list("select app_id from kg_application where status = 'user_confirmed' "
                + "and confirm_deadline is not null and confirm_deadline < now() and del_flag = '0' order by confirm_deadline limit 100",
                com.ruoyi.kangaroo.ext.KgJdbc.p()))
        {
            try
            {
                selfProxy.terminateConfirmTimeout(com.ruoyi.kangaroo.ext.KgJdbc.asLong(r.get("appId")));
            }
            catch (RuntimeException e)
            {
                timeoutLog.warn("确认超时终止失败 app={}：{}", r.get("appId"), e.getMessage());
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int terminateConfirmTimeout(Long appId)
    {
        KgApplication app = kgApplicationMapper.selectKgApplicationByAppId(appId);
        if (app == null || !ApplicationStatus.USER_CONFIRMED.equals(app.getStatus()) || app.getConfirmDeadline() == null
                || !app.getConfirmDeadline().before(new Date()))
        {
            return 0;
        }
        // 岗位已被关闭（关闭前就停在这一步的老报名）：门店本来就确认不了，原因如实写「岗位已关闭」，不能说成门店没确认
        boolean jobClosed = kgJdbcForQuota.count("select count(*) from kg_job where job_id = :j and status = 'closed'",
                com.ruoyi.kangaroo.ext.KgJdbc.p("j", app.getJobId())) > 0;
        String reason = jobClosed ? CLOSED_JOB_REASON
                : "商家未在 " + properties.getConfirmDeadlineHours() + " 小时内确认录用，系统自动终止";
        transfer(app, ApplicationStatus.TERMINATED, OperatorDTO.system(), reason);
        KgApplication update = new KgApplication();
        update.setAppId(appId);
        update.setTerminateReason(limit(reason, 255));
        kgApplicationMapper.updateKgApplication(update);
        kgMessageService.send(app.getAppUserId(), "user", MessageCategory.APPLY, "报名已终止",
                jobClosed ? "这个岗位已停止招募，这次报名已结束。你可以看看其他岗位。"
                        : "门店没有在确认期限内确认录用，这次报名已自动结束。你可以看看其他岗位。",
                "application", appId, "/pages/jobs/progress?appId=" + appId);
        notifyStaff(app.getStoreId(), MessageCategory.APPLY, jobClosed ? "报名已随岗位关闭结束" : "确认录用已超时",
                MaskUtils.name(app.getRealName()) + (jobClosed ? " 报名的岗位已关闭，这条报名已自动结束。" : " 的报名超过确认期限未被确认，系统已自动终止。"),
                appId, "/pages/staff/applications?appId=" + appId);
        return 1;
    }

    /** 该岗位是不是线上任务（线上任务没有面试环节） */
    private boolean isOnlineJob(Long jobId)
    {
        java.util.Map<String, Object> r = kgJdbcForQuota.one("select job_type from kg_job where job_id = :j", com.ruoyi.kangaroo.ext.KgJdbc.p("j", jobId));
        return r != null && "online".equals(String.valueOf(r.get("jobType")));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int audit(Long appId, boolean pass, String reason, OperatorDTO operator)
    {
        KgApplication app = requireOwnedByStore(appId, operator);
        requireHiringAction(app, operator, StaffPerms.AUDIT);
        assertNotSelfDealing(app, operator, "审核");

        if (!pass && StringUtils.isEmpty(reason))
        {
            throw new ServiceException("驳回时必须填写原因");
        }

        boolean onlineTask = false;
        if (pass)
        {
            // 线上任务的接单人不经过 store_confirmed，hired_count 永远是 0，名额从来不生效：名额为 1 的任务能同时接 2 人、各自结算
            // （2026-09-28 复核发现）。审核通过时锁住岗位行，统计进行中的接单人数，已满就拒绝。
            java.util.Map<String, Object> jobRow = kgJdbcForQuota.one("select job_type, headcount from kg_job where job_id = :j for update",
                    com.ruoyi.kangaroo.ext.KgJdbc.p("j", app.getJobId()));
            onlineTask = jobRow != null && "online".equals(String.valueOf(jobRow.get("jobType")));
            if (onlineTask && kgJdbcForQuota.count("select count(*) from kg_online_task where job_id = :j and deadline_time is not null and deadline_time < now()",
                    com.ruoyi.kangaroo.ext.KgJdbc.p("j", app.getJobId())) > 0)
            {
                throw new ServiceException("这个任务已过截止时间，不能再通过接单；请驳回", 409);
            }
            if (onlineTask)
            {
                long working = kgJdbcForQuota.count("select count(*) from kg_application where job_id = :j and del_flag = '0' and app_id <> :a and status in ("
                        + "'review_passed','interview_booked','interview_done','store_confirmed','user_confirmed','contract_signed','onboarded')",
                        com.ruoyi.kangaroo.ext.KgJdbc.p("j", app.getJobId(), "a", appId));
                Number hc = (Number) jobRow.get("headcount");
                if (hc != null && working >= hc.longValue())
                {
                    throw new ServiceException("这个任务的名额已满（" + hc + " 人），不能再通过新的接单", 409);
                }
            }
        }

        String target = pass ? ApplicationStatus.REVIEW_PASSED : ApplicationStatus.REVIEW_REJECTED;
        int rows = transfer(app, target, operator, pass ? "审核通过" : ("审核驳回：" + reason));

        KgApplication update = new KgApplication();
        update.setAppId(appId);
        update.setAuditTime(new Date());
        update.setAuditBy(operator.getName());
        update.setAuditByType(operator.getType());
        if (!pass)
        {
            update.setRejectReason(limit(reason, 255));
        }
        kgApplicationMapper.updateKgApplication(update);

        if (pass && onlineTask)
        {
            // 线上任务不约面试：通知和落地页都不能把接单人引到约面试屏。
            // 原先落到进度页，那一页按 review_passed 摆出"请选择视频面试时间"，接单人找不到提交成果的入口；
            // 直接进交付页，与「我的订单」里「提交成果 / 结算」按钮同一路由同一参数
            kgMessageService.send(app.getAppUserId(), "user", MessageCategory.APPLY,
                    "接单申请已通过",
                    "门店已通过你的接单申请，请按任务要求完成并提交成果。",
                    "application", appId, "/pages/jobs/deliver?appId=" + appId);
        }
        else if (pass)
        {
            kgMessageService.send(app.getAppUserId(), "user", MessageCategory.APPLY,
                    "报名审核通过",
                    "你的报名已通过审核，请选择视频面试时间。",
                    "application", appId, "/pages/jobs/progress?s=2&appId=" + appId);
        }
        else
        {
            kgMessageService.send(app.getAppUserId(), "user", MessageCategory.APPLY,
                    "报名未通过",
                    "未通过原因：" + reason,
                    "application", appId, "/pages/jobs/progress?s=3&appId=" + appId);
        }
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int storeConfirm(Long appId, OperatorDTO operator)
    {
        KgApplication app = requireOwnedByStore(appId, operator);
        requireHiringAction(app, operator, StaffPerms.EDIT);
        assertNotSelfDealing(app, operator, "确认录用");

        if (app.getConfirmDeadline() != null && app.getConfirmDeadline().before(new Date()))
        {
            throw new ServiceException("已超过确认截止时间，本次申请需重新沟通");
        }
        // 先按报名状态拦：已经随岗位关闭结束的报名，不能再报"岗位已关闭，请终止"这种对不上的话
        ApplicationStatus.assertCanTransfer(app.getStatus(), ApplicationStatus.STORE_CONFIRMED, operator.getType());

        // 名额校验放在扣减处用条件更新完成，避免并发下超额录用
        if (kgJobMapper.increaseHiredCount(app.getJobId()) == 0)
        {
            // 0 行有两种原因：名额已满，或岗位不在招（招聘中、暂停之外的状态）。给门店的提示要能区分。
            // 不在招实际上只会是「已关闭」：关闭是终态，上不了架，不能再叫人"重新上架"
            KgJob closedJob = kgJobMapper.selectKgJobByJobId(app.getJobId());
            if (closedJob != null && !"published".equals(closedJob.getStatus()) && !"paused".equals(closedJob.getStatus()))
            {
                throw new ServiceException(JobStatus.CLOSED.equals(closedJob.getStatus())
                        ? "该岗位已关闭，不能再确认录用；请终止这条报名，候选人会收到通知"
                        : "该岗位当前不在招聘中，无法确认录用");
            }
            throw new ServiceException("该岗位名额已满，无法确认录用");
        }

        int rows;
        try
        {
            rows = transfer(app, ApplicationStatus.STORE_CONFIRMED, operator, "商家确认录用");
        }
        catch (RuntimeException e)
        {
            // transfer 失败时事务会回滚，名额扣减也一并回滚
            throw e;
        }

        KgApplication update = new KgApplication();
        update.setAppId(appId);
        update.setStoreConfirmTime(new Date());
        kgApplicationMapper.updateKgApplication(update);

        kgMessageService.send(app.getAppUserId(), "user", MessageCategory.CONTRACT,
                "商家已确认录用",
                "双方确认已完成，可以签署责任书了。",
                "application", appId, "/pages/jobs/contract?appId=" + appId);
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int onboard(Long appId, OperatorDTO operator)
    {
        KgApplication app = requireOwnedByStore(appId, operator);
        requireHiringAction(app, operator, StaffPerms.EDIT);
        assertNotSelfDealing(app, operator, "标记入职");
        // 到预计到店日期当天才可确认；权限先校验，锁住报名后读取与用户页面相同的最新预计日期。
        String early = com.ruoyi.kangaroo.ext.staff.ArrivalConfirmationPolicy.waitReason(kgJdbcForQuota, appId, true);
        if (early != null)
        {
            throw new ServiceException(early, HttpStatus.CONFLICT);
        }
        int rows = transfer(app, ApplicationStatus.ONBOARDED, operator, "标记入职");

        KgApplication update = new KgApplication();
        update.setAppId(appId);
        update.setOnboardTime(new Date());
        kgApplicationMapper.updateKgApplication(update);

        kgMessageService.send(app.getAppUserId(), "user", MessageCategory.APPLY,
                "入职成功", "欢迎加入，祝你在小院有美好的旅居体验。",
                "application", appId, "/pages/jobs/progress?appId=" + appId);
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int terminate(Long appId, String reason, OperatorDTO operator)
    {
        KgApplication app = requireOwnedByStore(appId, operator);
        requireHiringAction(app, operator, StaffPerms.EDIT);
        assertNotSelfDealing(app, operator, "终止");
        // 线上任务已经结算完成（接单人确认收款）的不能再终止：钱已经两清，终止只会让接单人看到一条莫名其妙的「报名已终止」。
        // 员工端接口原先就拦了，后台的终止也走这里（放在权限检查之后，没权限的人照旧先收到 403）
        if (kgJdbcForQuota.count("select count(*) from kg_online_settlement where app_id = :a and status = 'confirmed'",
                com.ruoyi.kangaroo.ext.KgJdbc.p("a", appId)) > 0)
        {
            throw new ServiceException("这单线上任务已经结算完成，不能再终止", 409);
        }
        if (StringUtils.isEmpty(reason))
        {
            throw new ServiceException("终止申请必须填写原因");
        }
        int rows = transfer(app, ApplicationStatus.TERMINATED, operator, "终止：" + reason);

        KgApplication update = new KgApplication();
        update.setAppId(appId);
        update.setTerminateReason(limit(reason, 255));
        kgApplicationMapper.updateKgApplication(update);

        // 面试时段与录用名额的释放已由 transfer() 统一处理

        kgMessageService.send(app.getAppUserId(), "user", MessageCategory.APPLY,
                "报名已终止", "原因：" + reason,
                "application", appId, "/pages/jobs/progress?appId=" + appId);
        return rows;
    }

    // ==================== 岗位换店 / 关闭时的连带处理 ====================

    /** 岗位被关闭时结束报名的原因（写进报名的驳回 / 终止原因，候选人在进度页能看到） */
    private static final String CLOSED_JOB_REASON = "岗位已关闭";

    /** 已结束的报名（状态机里的终态）：岗位换店时留在原门店备查 */
    private static final String FINAL_STATUSES = "'review_rejected','withdrawn','terminated','onboarded'";

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int moveOpenApplications(Long jobId, Long fromStoreId, Long toStoreId, OperatorDTO operator)
    {
        if (jobId == null || fromStoreId == null || toStoreId == null || fromStoreId.equals(toStoreId))
        {
            return 0;
        }
        com.ruoyi.kangaroo.ext.KgJdbc db = kgJdbcForQuota;
        java.util.Map<String, Object> ps = com.ruoyi.kangaroo.ext.KgJdbc.p("j", jobId, "f", fromStoreId, "t", toStoreId,
                "u", operator.getType() + ":" + operator.getId());
        String open = " from kg_application a where a.job_id = :j and a.store_id = :f and a.del_flag = '0' and a.status not in (" + FINAL_STATUSES + ")";
        List<Long> ids = new java.util.ArrayList<Long>();
        List<java.util.Map<String, Object>> rows = java.util.Collections.emptyList();
        if (isOnlineJob(jobId))
        {
            // 线上任务的结算单记在验收门店名下（谁验收谁付款），接单转到新门店后这笔钱该谁付就说不清了。
            // 已经确认收款的接单算走完了，留在原门店
            long unsettled = db.count("select count(*)" + open
                    + " and not exists (select 1 from kg_online_settlement s where s.app_id = a.app_id and s.status = 'confirmed')", ps);
            if (unsettled > 0)
            {
                throw new ServiceException("这个线上任务还有 " + unsettled + " 单接单没有走完（待审核、待验收或待付款），暂不能改所属门店；"
                        + "等这些接单结算完成或终止后再改");
            }
        }
        else
        {
            // 加锁读：转移期间这些报名的审核、撤回、约面试都要等本事务结束，转完以后一律按新门店判权
            rows = db.list("select a.app_id, a.status" + open + " for update", ps);
            for (java.util.Map<String, Object> r : rows)
            {
                ids.add(com.ruoyi.kangaroo.ext.KgJdbc.asLong(r.get("appId")));
            }
        }
        ps.put("ids", ids);
        // 面试时段跟着走：这些报名已经占用的时段（含不限岗位的时段），加上本岗位还没开始的空时段。
        // 约面试、改期都要求时段和报名同属一家门店，时段留在原门店的话，新门店看不到这场面试，候选人也改不了期
        try
        {
            db.update("update kg_interview_slot set store_id = :t, update_by = :u, update_time = now() where store_id = :f and del_flag = '0' and ("
                    + (ids.isEmpty() ? "" : "booked_app_id in (:ids) or ") + "(job_id = :j and booked_app_id is null and start_time > now()))", ps);
        }
        catch (DuplicateKeyException e)
        {
            // uk_slot_store_range：同一家门店同一时间段只能有一个时段
            throw new ServiceException("新门店在同一时间段已经有面试时段，和这个岗位的时段重复了；请先在新门店删掉重复的时段，再改所属门店");
        }
        if (ids.isEmpty())
        {
            return 0;
        }
        // 投保申请跟着报名走：新门店要接着看投保进度、续保，原门店不再管这个人
        db.update("update kg_insurance_apply set store_id = :t, update_by = :u, update_time = now() "
                + "where app_id in (:ids) and store_id = :f and del_flag = '0'", ps);
        db.update("update kg_application set store_id = :t, update_by = :u, update_time = now() where app_id in (:ids) and store_id = :f", ps);
        java.util.Map<String, Object> store = db.one("select store_name from kg_store where store_id = :t", ps);
        String note = "岗位改由「" + (store == null ? "新门店" : store.get("storeName")) + "」负责，报名随岗位转过去";
        for (java.util.Map<String, Object> r : rows)
        {
            String st = com.ruoyi.kangaroo.ext.KgJdbc.asStr(r.get("status"));
            writeLog(com.ruoyi.kangaroo.ext.KgJdbc.asLong(r.get("appId")), st, st, operator, note);
        }
        return ids.size();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int endOpenApplicationsOfClosedJob(Long jobId, OperatorDTO operator)
    {
        java.util.Map<String, Object> job = kgJdbcForQuota.one("select title, job_type from kg_job where job_id = :j",
                com.ruoyi.kangaroo.ext.KgJdbc.p("j", jobId));
        if (job == null)
        {
            return 0;
        }
        boolean online = "online".equals(com.ruoyi.kangaroo.ext.KgJdbc.asStr(job.get("jobType")));
        // 已确认录用、已签责任书的占着名额，照常签约入职，不动；
        // 线上任务审核通过的接单人可能已经交了成果、在等结算，也不动，只结束还没审核的接单申请
        String statuses = online ? "'submitted'" : "'submitted','review_passed','interview_booked','interview_done','user_confirmed'";
        String tip = online
                ? "「" + job.get("title") + "」任务已关闭，这次接单申请已结束。可以看看其他任务。"
                : "「" + job.get("title") + "」已停止招募，这次报名已结束。可以看看其他岗位。";
        int ended = 0;
        for (java.util.Map<String, Object> r : kgJdbcForQuota.list("select app_id from kg_application where job_id = :j and del_flag = '0' "
                + "and status in (" + statuses + ")", com.ruoyi.kangaroo.ext.KgJdbc.p("j", jobId)))
        {
            Long appId = com.ruoyi.kangaroo.ext.KgJdbc.asLong(r.get("appId"));
            // 加锁读当前状态：与候选人撤回、约面试并发时以锁住之后的状态为准，下面流转的 CAS 也就不会落空
            String st = kgApplicationMapper.selectStatusForUpdate(appId);
            KgApplication app = kgApplicationMapper.selectKgApplicationByAppId(appId);
            if (st == null || app == null)
            {
                continue;
            }
            app.setStatus(st);
            KgApplication update = new KgApplication();
            update.setAppId(appId);
            if (ApplicationStatus.SUBMITTED.equals(st))
            {
                transfer(app, ApplicationStatus.REVIEW_REJECTED, operator, CLOSED_JOB_REASON);
                update.setAuditTime(new Date());
                update.setAuditBy(operator.getName());
                update.setAuditByType(operator.getType());
                update.setRejectReason(CLOSED_JOB_REASON);
                kgApplicationMapper.updateKgApplication(update);
                kgMessageService.send(app.getAppUserId(), "user", MessageCategory.APPLY, online ? "接单申请未通过" : "报名未通过", tip,
                        "application", appId, "/pages/jobs/progress?s=3&appId=" + appId);
            }
            else if (!online && !occupiesHeadcount(st) && ApplicationStatus.canTransfer(st, ApplicationStatus.TERMINATED))
            {
                transfer(app, ApplicationStatus.TERMINATED, operator, CLOSED_JOB_REASON);
                update.setTerminateReason(CLOSED_JOB_REASON);
                kgApplicationMapper.updateKgApplication(update);
                kgMessageService.send(app.getAppUserId(), "user", MessageCategory.APPLY, "报名已终止", tip,
                        "application", appId, "/pages/jobs/progress?appId=" + appId);
            }
            else
            {
                // 锁住时已经变成别的状态（撤回了 / 已录用），不用处理
                continue;
            }
            ended++;
        }
        return ended;
    }

    // ==================== 内部 ====================

    /**
     * 统一的状态流转：状态机校验 + CAS 更新 + 写日志。
     */
    private int transfer(KgApplication app, String target, OperatorDTO operator, String reason)
    {
        String from = app.getStatus();
        ApplicationStatus.assertCanTransfer(from, target, operator.getType());

        int rows = kgApplicationMapper.updateStatusWithCas(app.getAppId(), from, target,
                operator.getType() + ":" + operator.getId());
        if (rows == 0)
        {
            // 并发：状态在本次读取之后已被别人改掉
            throw new ServiceException("操作失败，报名状态已变化，请刷新后重试");
        }
        writeLog(app.getAppId(), from, target, operator, reason);
        releaseResourcesOnTerminal(app, from, target);
        app.setStatus(target);
        return rows;
    }

    /**
     * 进入终态时释放这条报名占用的资源。
     *
     * 刻意放在 transfer() 里而不是各个业务方法里：终态入口有 withdraw、terminate、onboard、
     * 审核驳回、用户面试后选"不合适"好几条，早先分散写导致 reject 那条漏了释放面试时段，
     * 时段被永久锁死、后台也改不了删不了。收敛到这里之后，将来再加终态分支也不会漏。
     *
     * 只有抢到 CAS 的那个线程才会走到这里，所以不会重复释放。
     *
     * 终态改问状态机的 isFinal，不再在这里逐个列举 —— 原先那份列举漏了 onboarded，
     * 于是走完整链路入职的报名从不释放时段，历史 booked 行只增不减。
     * 把判断交还给状态机之后，以后再加终态也不会重演这件事。
     *
     * "释放时段"和"归还名额"条件不同，必须分开判：
     * 时段对任何终态都要还回可约池；名额只在报名从"占名额"退到"不占名额"时才归还，
     * contract_signed -> onboarded 同样是终态流转，但入职仍然占着这个名额。
     *
     * @param from 流转前的状态。必须用它判断名额，因为 app.setStatus 还没执行。
     */
    private void releaseResourcesOnTerminal(KgApplication app, String from, String target)
    {
        if (!ApplicationStatus.isFinal(target))
        {
            return;
        }

        // 释放面试时段，让它回到可约池
        releaseSlotIfAny(app.getAppId());

        // 没走到入职就结束的报名（终止 / 撤回 / 驳回），还在待处理的投保申请要自动作废：
        // 否则平台登记保单号之后，前候选人会收到「用工保险已生效」，门店也看到一份有效保单
        if (!ApplicationStatus.ONBOARDED.equals(target))
        {
            // Do not acquire channel payment locks while holding application: callbacks take payment -> application.
            // Paid receipts are retained for the payer's explicit refund destination and platform review.
            kgJdbcForQuota.update("update kg_insurance_payment set refund_required=1 where apply_id in (select apply_id from kg_insurance_apply where app_id=:a and status in ('pending','pending_payment')) and status='paid'",
                    com.ruoyi.kangaroo.ext.KgJdbc.p("a",app.getAppId()));
            kgJdbcForQuota.update("update kg_insurance_apply set status='closed',handle_note='报名已终止；已付保费请申请退款',update_time=now() where app_id=:a and status in ('pending','pending_payment') and apply_id in (select apply_id from kg_insurance_payment)",
                    com.ruoyi.kangaroo.ext.KgJdbc.p("a",app.getAppId()));
            kgJdbcForQuota.update("update kg_insurance_apply set status = 'rejected', handle_note = '报名已终止，投保申请自动作废', handled_by = 'system', "
                    + "handled_time = now(), update_by = 'system', update_time = now() where app_id = :a and status = 'pending' and del_flag = '0'",
                    com.ruoyi.kangaroo.ext.KgJdbc.p("a", app.getAppId()));
        }

        // 只有真正扣过名额、且这次流转确实把名额让出来了才归还，
        // 否则入职（仍占名额）会被当成"退出录用"白白减掉一个名额
        if (occupiesHeadcount(from) && !occupiesHeadcount(target))
        {
            kgJobMapper.decreaseHiredCount(app.getJobId());
        }
    }

    /**
     * 该状态是否占着岗位的一个录用名额。
     *
     * 口径必须与 KgJobMapper.recalcCounters 里 hired_count 的统计口径逐字一致，
     * 否则运维一按「重算名额」，增减维护出来的计数就会和重算结果对不上，
     * 而那个按钮存在的意义恰恰是纠偏，两边口径不一反而会制造新的偏差。
     */
    private static boolean occupiesHeadcount(String status)
    {
        return ApplicationStatus.STORE_CONFIRMED.equals(status)
                || ApplicationStatus.CONTRACT_SIGNED.equals(status)
                || ApplicationStatus.ONBOARDED.equals(status);
    }

    private void writeLog(Long appId, String from, String to, OperatorDTO operator, String reason)
    {
        KgApplicationLog log = new KgApplicationLog();
        log.setAppId(appId);
        log.setFromStatus(from);
        log.setToStatus(to);
        log.setOperatorType(operator.getType());
        log.setOperatorId(operator.getId());
        log.setOperatorName(operator.getName());
        log.setReason(limit(reason, 255));
        kgApplicationLogMapper.insertKgApplicationLog(log);
    }

    /**
     * 报名进入终态时释放已占用的面试时段，避免时段被永久占住。
     *
     * "释放时段"与"作废面试记录"是两件事，早先被写成一件，于是没法区分两类终局：
     * 入职、以及面试之后才终止（用户婉拒 / 商家终止 / 逾期）的报名，那场面试是真发生过的，
     * 把 kg_interview 标成 canceled 等于把历史改错；但时段本身无论如何都得还回可约池，
     * 否则它永远停在 booked。所以这里无条件释放时段，只有还停在 booked
     *（约了、人没来面）的面试记录才作废，已经 done 的一律保持原状。
     *
     * 这里"先读后写"不另加 CAS 是安全的：kg_interview.status 全项目只有 finishInterview
     * 和本方法两处会写，而两者都排在 transfer() 的报名状态 CAS 之后 ——
     * 同一条报名的这两条路径互斥，抢不到报名状态 CAS 的那一方根本走不到这里。
     */
    private void releaseSlotIfAny(Long appId)
    {
        // 按 booked_app_id 释放，而不是「读出 kg_interview.slot_id 再释放那一个」：
        // 后者读的是事务快照，与用户改期并发时 slot_id 已是新时段、快照里还是旧时段，
        // 释放落空，新时段被这条已终止 / 已撤回的报名永久占住（uk_slot_booked 保证一条报名只占一个时段，所以按报名释放是精确的）。
        // 只会释放「这条报名自己占着的」，不会误伤别人的面试。
        kgInterviewSlotMapper.releaseSlotsByApp(appId);
        // 面试记录同理用带状态条件的更新，不先读后写
        kgInterviewMapper.cancelBookedByApp(appId);
    }

    /** 给门店所有生效员工发一条 staff 身份的消息 */
    private void notifyStaff(Long storeId, String category, String title, String content, Long appId, String route)
    {
        KgApplication personal = kgApplicationMapper.selectKgApplicationByAppId(appId);
        Long applicant = personal == null ? null : personal.getAppUserId();
        // 线上任务的接单（新的报名待审核等）：只发给有「线上任务·编辑」的人，和门店其他线上任务通知同一个口径（之前换工主理人收到点开 403）
        if (staffSupport != null && onlineApplication(appId))
        {
            for (Long uid : staffSupport.onlineRecipients(storeId, false, null))
            {
                if (!uid.equals(applicant)) kgMessageService.send(uid, "staff", category, title, content, "application", appId, route);
            }
            return;
        }
        List<Long> staffUserIds = kgStaffGrantService.listActiveStaffUserIds(storeId);
        if (staffUserIds == null)
        {
            return;
        }
        // 这家店的商家服务人只管线上任务：线下岗位的报名、面试消息不发给他（消息正文里有姓名，接口层他也查不了）
        List<Long> service = kgStaffGrantService.listMerchantServiceUserIds(storeId);
        if (service != null && !service.isEmpty() && !onlineApplication(appId))
        {
            staffUserIds = new java.util.ArrayList<Long>(staffUserIds);
            staffUserIds.removeAll(service);
        }
        // 这家店没有换工主理人时，报名、面试的通知也给能管这家店的城市负责人（丽江的缺口）
        if (cityLeads != null)
        {
            for (Long lead : cityLeads.standIns(storeId))
            {
                if (!staffUserIds.contains(lead))
                {
                    staffUserIds = new java.util.ArrayList<Long>(staffUserIds);
                    staffUserIds.add(lead);
                }
            }
        }
        for (Long uid : staffUserIds)
        {
            if (!uid.equals(applicant)) kgMessageService.send(uid, "staff", category, title, content, "application", appId, route);
        }
    }

    private boolean onlineApplication(Long appId)
    {
        KgApplication app = appId == null ? null : kgApplicationMapper.selectKgApplicationByAppId(appId);
        KgJob job = app == null ? null : kgJobMapper.selectKgJobByJobId(app.getJobId());
        return job != null && com.ruoyi.kangaroo.enums.JobType.ONLINE.equals(job.getJobType());
    }

    private static Long parseLong(String s)
    {
        try
        {
            return s == null ? null : Long.valueOf(s);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private static String limit(String s, int max)
    {
        if (s == null)
        {
            return null;
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** 候选人交过面试后问卷没有：三道单选、备注任一有内容就算交过 */
    public static boolean questionnaireSubmitted(KgInterview i)
    {
        return i != null && (StringUtils.isNotEmpty(i.getUnderstandJob()) || StringUtils.isNotEmpty(i.getAcceptBoard())
                || StringUtils.isNotEmpty(i.getConfirmArrival()) || StringUtils.isNotEmpty(i.getFeedbackNote()));
    }

    /**
     * 报名用的简历兜底（2026-10-18）：只有请求里缺了姓名 / 手机，或线下岗位缺了年龄 / 技能时才查一次库；
     * 取「我的简历」的称呼、年龄、手机、技能描述，外加账号绑定的手机号（userPhone，简历没留手机时用）。没有简历返回空表
     */
    private java.util.Map<String, Object> resumeFallback(ApplicationSubmitDTO dto, boolean online, Long appUserId)
    {
        boolean need = StringUtils.isEmpty(StringUtils.trim(dto.getName())) || StringUtils.isEmpty(StringUtils.trim(dto.getPhone()))
                || (!online && (dto.getAge() == null || StringUtils.isEmpty(StringUtils.trim(dto.getSkills()))));
        if (!need || kgJdbcForQuota == null)
        {
            return java.util.Collections.<String, Object>emptyMap();
        }
        java.util.Map<String, Object> r = kgJdbcForQuota.one("select r.name, r.age, r.phone, r.skill_text, u.phone user_phone from kg_app_user u "
                + "left join kg_resume r on r.app_user_id = u.user_id where u.user_id = :u", com.ruoyi.kangaroo.ext.KgJdbc.p("u", appUserId));
        return r == null ? java.util.Collections.<String, Object>emptyMap() : r;
    }

    /** 请求里有值（去掉空白后不空）用请求的，否则用简历里的那一项 */
    private static String orResume(String given, java.util.Map<String, Object> resume, String key)
    {
        if (StringUtils.isNotEmpty(StringUtils.trim(given)))
        {
            return given;
        }
        String v = com.ruoyi.kangaroo.ext.KgJdbc.asStr(resume.get(key));
        return v == null || v.trim().isEmpty() ? given : v.trim();
    }

    private static String requireText(String s, String label, int max)
    {
        if (StringUtils.isEmpty(s) || s.trim().length() == 0)
        {
            throw new ServiceException(label + "不能为空");
        }
        String t = s.trim();
        if (t.length() > max)
        {
            throw new ServiceException(label + "长度不能超过 " + max + " 个字");
        }
        return t;
    }

    private static Integer requireAge(Integer age)
    {
        if (age == null)
        {
            throw new ServiceException("年龄不能为空");
        }
        if (age.intValue() < 1 || age.intValue() > 120)
        {
            throw new ServiceException("请输入 1-120 之间的年龄");
        }
        return age;
    }

    private static String requirePhone(String phone)
    {
        if (StringUtils.isEmpty(phone))
        {
            throw new ServiceException("手机号不能为空");
        }
        String t = phone.trim();
        if (!t.matches("^1\\d{10}$"))
        {
            throw new ServiceException("请输入正确的 11 位手机号");
        }
        return t;
    }

    /**
     * 日期校验兼容入口；实际确认和员工端提示都由 ArrivalConfirmationPolicy 读取最新到店日期。
     * 预计日期当天起才能确认；中国日历日计算，没有预计日期的旧记录保持原行为。
     */
    public static String onboardTooEarly(Date arrival)
    {
        return onboardTooEarly(arrival, java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")));
    }

    static String onboardTooEarly(Date arrival, java.time.LocalDate today)
    {
        return com.ruoyi.kangaroo.ext.staff.ArrivalConfirmationPolicy.waitReason(arrival, today);
    }

    private static Date requireArrivalDate(String arrival, KgJob job)
    {
        if (StringUtils.isEmpty(arrival))
        {
            throw new ServiceException("请选择预计到岗日期");
        }
        Date date;
        try
        {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
            sdf.setLenient(false);
            date = sdf.parse(arrival.trim());
        }
        catch (ParseException e)
        {
            throw new ServiceException("到岗日期格式应为 yyyy-MM-dd");
        }
        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        if (date.before(today.getTime()))
        {
            throw new ServiceException("到岗日期不能早于今天");
        }
        if (job.getStartDate() != null && date.before(job.getStartDate()))
        {
            throw new ServiceException("该岗位最早可到岗日期为 " + formatDate(job.getStartDate()));
        }
        return date;
    }

    private static Integer requireStayDays(Integer days, KgJob job)
    {
        if (days == null)
        {
            throw new ServiceException("请填写可停留天数");
        }
        if (days.intValue() < 1 || days.intValue() > 365)
        {
            throw new ServiceException("可停留天数需在 1-365 之间");
        }
        if (job.getMinDays() != null && days.intValue() < job.getMinDays().intValue())
        {
            throw new ServiceException("该岗位要求最短停留 " + job.getMinDays() + " 天");
        }
        return days;
    }

    /** 报名单号：KA + yyyyMMdd + 岗位后4位 + 用户后4位 + 时间戳后4位 */
    private static String buildAppNo(Long jobId, Long userId)
    {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
        String ts = String.valueOf(System.currentTimeMillis());
        return "KA" + sdf.format(new Date())
                + pad(jobId) + pad(userId)
                + ts.substring(ts.length() - 4);
    }

    private static String pad(Long v)
    {
        String s = String.valueOf(v == null ? 0L : v.longValue());
        if (s.length() >= 4)
        {
            return s.substring(s.length() - 4);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = s.length(); i < 4; i++)
        {
            sb.append('0');
        }
        return sb.append(s).toString();
    }

    private static String formatDate(Date d)
    {
        return d == null ? "" : new SimpleDateFormat("yyyy-MM-dd").format(d);
    }

    private static String formatDateTime(Date d)
    {
        if(d==null)return "";
        SimpleDateFormat formatter=new SimpleDateFormat("yyyy-MM-dd HH:mm");
        formatter.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        return formatter.format(d)+"（北京时间）";
    }
}
