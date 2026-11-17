package com.ruoyi.kangaroo.service.impl;

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.kangaroo.domain.KgJob;
import com.ruoyi.kangaroo.domain.KgStore;
import com.ruoyi.kangaroo.domain.JobCompensation;
import com.ruoyi.kangaroo.domain.JobExtras;
import com.ruoyi.kangaroo.domain.JobFilterFields;
import com.ruoyi.kangaroo.domain.JobImages;
import com.ruoyi.kangaroo.dto.OperatorDTO;
import com.ruoyi.kangaroo.enums.JobStatus;
import com.ruoyi.kangaroo.enums.JobType;
import com.ruoyi.kangaroo.enums.SalaryType;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.KgOnlineTaskService;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.mapper.KgApplicationMapper;
import com.ruoyi.kangaroo.mapper.KgJobMapper;
import com.ruoyi.kangaroo.mapper.KgStoreMapper;
import com.ruoyi.kangaroo.service.IKgApplicationService;
import com.ruoyi.kangaroo.service.IKgJobService;

@Service
public class KgJobServiceImpl implements IKgJobService
{
    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.staff.KgPublicationFeeService publicationFees;
    /**
     * 允许的状态流转，key 为 "原状态->目标状态"。
     * 用白名单而不是在代码里逐个 if：状态机是产品规则，集中在一处才好对照需求审查，
     * 且默认拒绝，新增状态时不会因为漏写分支而放行。
     * 注意 closed 是终态（岗位关闭后只能新建），published 不能直接回到 draft。
     */
    private static final Set<String> ALLOWED_TRANSITIONS = new HashSet<String>();

    /** 不计薪，此时薪资区间可以留空 */
    private static final String SALARY_TYPE_NONE = "none";

    static
    {
        ALLOWED_TRANSITIONS.add(JobStatus.DRAFT + "->" + JobStatus.PUBLISHED);
        ALLOWED_TRANSITIONS.add(JobStatus.DRAFT + "->" + JobStatus.CLOSED);
        ALLOWED_TRANSITIONS.add(JobStatus.PUBLISHED + "->" + JobStatus.PAUSED);
        ALLOWED_TRANSITIONS.add(JobStatus.PUBLISHED + "->" + JobStatus.CLOSED);
        ALLOWED_TRANSITIONS.add(JobStatus.PAUSED + "->" + JobStatus.PUBLISHED);
        ALLOWED_TRANSITIONS.add(JobStatus.PAUSED + "->" + JobStatus.CLOSED);

    }

    @Autowired
    private KgJobMapper kgJobMapper;

    @Autowired
    private KgStoreMapper kgStoreMapper;

    @Autowired
    private KgApplicationMapper kgApplicationMapper;

    @Autowired(required = false)
    private KgJdbc jdbc;

    @Autowired(required = false)
    private StaffSupport staffSupport;

    @Autowired(required = false)
    private KgOnlineTaskService onlineTasks;

    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.core.KgVolunteerKinds volunteerKinds;

    /** 住宿视频写入时核对文件确实在（V66）；手工 new 的单测没注入时不查 */
    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.core.KgVideoUploads videos;

    /** 岗位换店 / 关闭时连带处理报名（报名的状态流转只能经过报名服务的状态机） */
    @Autowired(required = false)
    private IKgApplicationService applications;

    @Override
    public KgJob selectKgJobByJobId(Long jobId)
    {
        return kgJobMapper.selectKgJobByJobId(jobId);
    }

    @Override
    public KgJob requireJob(Long jobId)
    {
        if (jobId == null)
        {
            throw new ServiceException("岗位ID不能为空");
        }
        KgJob job = kgJobMapper.selectKgJobByJobId(jobId);
        if (job == null)
        {
            throw new ServiceException("岗位不存在或已删除");
        }
        return job;
    }

    @Override
    public List<KgJob> selectKgJobList(KgJob job)
    {
        return kgJobMapper.selectKgJobList(job);
    }

    @Override
    public List<KgJob> selectAppJobList(KgJob job)
    {
        return kgJobMapper.selectAppJobList(job);
    }

    @Override
    public int insertKgJob(KgJob job)
    {
        // 线上任务的截止时间、交付物、验收标准和佣金都在 kg_online_task，截止判断和结算金额只认那张表；
        // 岗位管理的表单没有这些字段，从这里建出的 online 岗位没有扩展行：不卡截止、没有验收标准，
        // 结算还回落到薪资下限。所以只能走「线上任务」页面的 createByAdmin。
        if (JobType.ONLINE.equals(job.getJobType()))
        {
            throw new ServiceException("线上任务请到「门店运营扩展 → 线上任务」页面新增：那里会一并登记截止时间、交付物、验收标准和佣金，岗位管理不能直接新增线上任务");
        }
        validate(job);
        applyWorkKind(job, null);
        applyRecruitStore(job, null);
        applyFilterFields(job, null);
        com.ruoyi.kangaroo.domain.JobGenderCounts.apply(job, null);
        applyLocation(job, null);
        applyImages(job, null);
        applyExtras(job, null);
        job.setCreateBy(SecurityUtils.getUsername());
        // 新增一律落草稿：岗位是否对小程序可见只由上下架接口决定，
        // 否则运营在编辑表单里填一个 status 就能绕过发布审查
        job.setStatus(JobStatus.DRAFT);
        job.setPublishTime(null);
        job.setCloseTime(null);
        // 统计字段由报名/录用流程累加，建单时交给数据库默认值 0
        job.setHiredCount(null);
        job.setAppliedCount(null);
        return kgJobMapper.insertKgJob(job);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public int updateKgJob(KgJob job)
    {
        kgJobMapper.lockRecruitment(job.getJobId());
        KgJob exist = requireJob(job.getJobId());
        // 线上任务的接单、验收、结算都挂在 kg_online_task 上：改成别的分类会留下孤立的扩展行，
        // 别的分类改成线上任务则没有扩展行（同新增的理由）。分类为空交给下面的 validate() 报"不能为空"。
        boolean online = JobType.ONLINE.equals(exist.getJobType());
        if (job.getJobType() != null && online != JobType.ONLINE.equals(job.getJobType()))
        {
            throw new ServiceException(online ? "线上任务不能改成其他岗位分类" : "不能把岗位改成线上任务，线上任务请到「门店运营扩展 → 线上任务」页面新增");
        }
        if (online)
        {
            // 线上任务固定按单计佣（与 createTask 一致）。要在 validate() 之前定下来：
            // 表单若把计薪方式改成「无薪资」，validate() 会先把价格清掉，结算单却还按扩展行里的旧佣金出
            job.setSalaryType("piece");
        }
        // 后台编辑是"整表单提交"，落库走 updateKgJobByAdmin 全量覆盖：
        // 请求里没有的可选字段就是运营把它清空了，必须写成 NULL，而不是保持原值。
        //
        // 正因为是全量覆盖，校验只能直接做在入参上，不能再像以前那样把缺失字段回落到库里的旧值：
        // 否则一个缺了 title 的半截请求会先"借"旧值通过必填校验，再被全量语句把 title 覆盖成 NULL。
        // validate() 只卡必填项（title/city/jobType/storeId/headcount 与选了计薪方式时的薪资区间），
        // 可选字段为空一律放行 —— 这正是这次要修的"清空后保存不生效"。
        // 它同时会做归一化（补 salaryType 默认值、把缺失的 salaryMax 回填成 salaryMin），
        // 现在直接作用在待落库的对象上，通过校验的值与真正写进库的值天然一致。
        validate(job);
        applyRecruitStore(job, exist);
        applyWorkKind(job, exist);
        applyFilterFields(job, exist);
        com.ruoyi.kangaroo.domain.JobGenderCounts.apply(job, exist);
        applyLocation(job, exist);
        applyImages(job, exist);
        applyExtras(job, exist);
        // 与报名共用岗位锁；总名额不能小于已占位人数，防止报名和后台缩名额相互穿透。
        if (JobType.VOLUNTEER.equals(job.getJobType()) && exist.getReservedCount() != null
                && job.getHeadcount() < exist.getReservedCount())
        {
            throw new ServiceException("招聘名额不能小于当前已报名占用人数 " + exist.getReservedCount());
        }
        // 录用人数仍单独校验，其他岗位分类沿用此口径；岗位锁阻止并发录用穿过本次编辑，
        // updateKgJobByAdmin 的 where 再对实际写入做下限保护。
        if (job.getHeadcount() != null && exist.getHiredCount() != null
                && job.getHeadcount().intValue() < exist.getHiredCount().intValue())
        {
            throw new ServiceException("招聘名额不能小于已录用人数 " + exist.getHiredCount());
        }
        if (online)
        {
            syncOnlineTask(job, exist);
        }
        job.setUpdateBy(SecurityUtils.getUsername());
        // 状态与时间戳只认 changeStatus。updateKgJobByAdmin 的 SET 列表里本就没有这几列，
        // 这里再置空一次是双保险：万一以后有人把它们加进语句，后台编辑也不会悄悄改掉岗位状态。
        job.setStatus(null);
        job.setPublishTime(null);
        job.setCloseTime(null);
        // 统计字段一律静默丢弃，不再"试图修改就报错"。
        // 后台编辑表单里根本没有这两个输入框（index.vue 只在列表里只读展示），
        // updateKgJobByAdmin 的 SET 列表里也没有这两列，所以拒绝换不来任何安全性；
        // 它唯一会触发的场景是整对象回填时表单里那份计数已经过期（编辑期间有人报名/录用），
        // 也就是把一次完全合法的编辑报成"你在篡改统计字段"。置空即可，反正写不进去。
        // 计数真出了偏差由「重算名额」纠正，那才是它存在的意义。
        job.setHiredCount(null);
        job.setAppliedCount(null);
        // 全量语句：可清空的运营字段无条件 SET，清空即写 NULL；
        // changeStatus 这类内部局部更新仍走动态语句 updateKgJob，不受影响。
        int rows = kgJobMapper.updateKgJobByAdmin(job);
        if (rows == 0)
        {
            // 进到这里之前 requireJob 已经确认岗位存在，所以 0 行只可能是
            // where 里的 del_flag 或名额兜底在这期间不成立了，回查一次分辨是哪一种
            throw explainFailedAdminUpdate(job);
        }
        // 改了所属门店（主人 10-13 定的合作店拆分：新建门店 → 岗位改到新门店 → 给负责人开主理人）：
        // 报名的门店是提交时的快照，只改 kg_job 的话，新门店的主理人看不到、处理不了这些报名，
        // 原门店却还在处理一个已经不在自己岗位列表里的报名。还没走完流程的报名连同面试时段、投保申请
        // 在同一事务里一起转过去，两边门店各收一条通知；已结束的报名留在原门店备查。
        if (exist.getStoreId() != null && !exist.getStoreId().equals(job.getStoreId()))
        {
            if (applications == null)
            {
                throw new ServiceException("报名服务未就绪，暂不能改所属门店");
            }
            int moved = applications.moveOpenApplications(job.getJobId(), exist.getStoreId(), job.getStoreId(),
                    OperatorDTO.admin(SecurityUtils.getUsername()));
            job.getParams().put("movedApps", moved);
            notifyStoreMoved(job, exist.getStoreId(), moved);
        }
        syncMerchantRequestText(job);
        return rows;
    }

    /** 岗位管理改了标题、内容：商家需求页和商家「我发布的」读的是需求行，跟着改（上架和用户端用的是岗位，两边不能对不上） */
    private void syncMerchantRequestText(KgJob job)
    {
        if (jdbc == null || StringUtils.isEmpty(job.getTitle()))
        {
            return;
        }
        String title = job.getTitle().length() > 64 ? job.getTitle().substring(0, 64) : job.getTitle();
        String content = job.getContent() == null ? null : job.getContent().length() > 2000 ? job.getContent().substring(0, 2000) : job.getContent();
        jdbc.update("update kg_merchant_request set title = :t, content = coalesce(:c, content), update_by = :by, update_time = now() where job_id = :j and del_flag = '0'",
                KgJdbc.p("t", title, "c", content, "by", SecurityUtils.getUsername(), "j", job.getJobId()));
    }

    /** 岗位换了门店：原门店、新门店的员工各收一条（staff 身份，工作台里看） */
    private void notifyStoreMoved(KgJob job, Long fromStoreId, int moved)
    {
        if (staffSupport == null)
        {
            return;
        }
        KgStore from = kgStoreMapper.selectKgStoreByStoreId(fromStoreId);
        KgStore to = kgStoreMapper.selectKgStoreByStoreId(job.getStoreId());
        String fromName = from == null ? "原门店" : from.getStoreName();
        String toName = to == null ? "新门店" : to.getStoreName();
        staffSupport.notifyStore(fromStoreId, "system", "岗位已转到其他门店",
                "平台已把「" + job.getTitle() + "」转到「" + toName + "」"
                        + (moved > 0 ? "，" + moved + " 条还没走完流程的报名（含已约的面试、投保申请）也一起转过去了" : "")
                        + "，之后由对方门店处理。", "job", job.getJobId(), "/pages/staff/jobs");
        // 报名列表带上门店：两家店都有授权的员工（合作店拆分时很常见）点开就落在新门店，不会停在原门店看不到这些报名
        staffSupport.notifyStore(job.getStoreId(), "system", "有岗位转到本店",
                "平台已把「" + job.getTitle() + "」从「" + fromName + "」转到本店"
                        + (moved > 0 ? "，" + moved + " 条还没走完流程的报名一并转来，请到报名管理及时处理。" : "。"),
                // 带上岗位类型：小院主理人的报名列表平时只看线上任务，转来的换工报名要靠 linked 才看得到（报名页 linkedType）
                "job", job.getJobId(), moved > 0 ? "/pages/staff/applications?storeId=" + job.getStoreId() + (StringUtils.isEmpty(job.getJobType()) ? "" : "&linked=1&jobType=" + job.getJobType()) : "/pages/staff/jobs");
    }

    /**
     * 线上任务：kg_online_task 才是佣金与截止时间的准绳（结算单金额、能否提交成果都读它），小程序展示读的是 kg_job。
     * 后台改价必须两边在同一事务里一起改，否则用户看到的价格和最后结算的金额对不上。
     * 此时 validate() 已跑过：计薪方式已固定为 piece、薪资下限非空、留空的上限已补成下限。
     */
    private void syncOnlineTask(KgJob job, KgJob exist)
    {
        if (onlineTasks == null)
        {
            // 只有手工 new 出来的单元测试会缺这个 bean；宁可拒绝也不能只改 kg_job 一边
            throw new ServiceException("线上任务服务未就绪，暂不能修改线上任务");
        }
        // 按单计佣只有一个价：上下限不同就说不清结算按哪个数
        if (job.getSalaryMax().compareTo(job.getSalaryMin()) != 0)
        {
            throw new ServiceException("线上任务按单计佣，薪资上下限必须相同");
        }
        // 与发布时同一条规则（1–5000 元、最多两位小数），精确换算成分，不截断
        long fen = KgOnlineTaskService.commissionFen(job.getSalaryMin());
        BigDecimal price = BigDecimal.valueOf(fen, 2);
        job.setSalaryMin(price);
        job.setSalaryMax(price);
        job.setSalaryUnit("元/单");
        String deliver = onlineTasks.syncFromJobEdit(job.getJobId(), fen, SecurityUtils.getUsername());
        // 交付日期由截止时间生成，表单里填什么都不认；没有扩展行的老数据保持库里原值
        job.setDeliverDesc(deliver != null ? deliver : exist.getDeliverDesc());
    }

    /**
     * updateKgJobByAdmin 影响 0 行时，回查一次把"到底是哪条 where 没成立"翻译成运营能照着做的话。
     * 不回查就只能笼统报一句"保存失败"，运营不知道该去刷新页面还是该改名额。
     * 回查只在失败路径上发生，正常保存不多一次查询。
     *
     * 分支必须和语句里的 where 逐条对齐。现在那条语句的 where 只剩
     * 主键 + del_flag + 名额兜底三项，所以这里也只有三种可能，
     * 不能再留"版本号对不上"这种语句里已经不存在的解释。
     */
    private ServiceException explainFailedAdminUpdate(KgJob job)
    {
        KgJob now = kgJobMapper.selectKgJobByJobId(job.getJobId());
        if (now == null)
        {
            // selectKgJobByJobId 自带 del_flag = '0'，查不到即对应 where 里的 del_flag 没成立
            return new ServiceException("岗位不存在或已删除");
        }
        if (job.getHeadcount() != null && now.getHiredCount() != null
                && job.getHeadcount().intValue() < now.getHiredCount().intValue())
        {
            return new ServiceException("招聘名额不能小于已录用人数 " + now.getHiredCount()
                    + "：你编辑期间又有人被录用，请刷新页面后重新编辑。");
        }
        // 回查时行还在、名额也对得上，说明这两者是在 update 与回查之间发生变化的
        //（例如同一瞬间有人的录用被终止、名额被归还）。只能如实说"刚刚变过、请重试"，
        // 不要替它编一个具体原因 —— 猜错的解释比笼统的解释更容易把人带偏。
        return new ServiceException("保存失败：岗位数据刚刚发生变化，请刷新页面后重试。");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteKgJobByJobIds(Long[] jobIds)
    {
        if (jobIds == null || jobIds.length == 0)
        {
            return 0;
        }
        for (Long jobId : jobIds)
        {
            KgJob job = requireJob(jobId);
            // 发布过的岗位小程序端可能已被收藏、分享，删除会让用户侧出现断链，只允许关闭
            if (!JobStatus.DRAFT.equals(job.getStatus()))
            {
                throw new ServiceException("岗位[" + job.getTitle() + "]不是草稿状态，只能关闭不能删除");
            }
            if (kgApplicationMapper.countByJobId(jobId) > 0)
            {
                throw new ServiceException("岗位[" + job.getTitle() + "]已有报名记录，不能删除");
            }
        }
        int deleted = kgJobMapper.deleteKgJobByJobIds(jobIds, SecurityUtils.getUsername());
        closeRequestsOfDeletedJobs(jobIds);
        if (publicationFees != null) for (Long jobId : jobIds) publicationFees.closed(jobId);
        return deleted;
    }

    /** 删掉的草稿如果有商家需求行：同一事务记关闭、通知商家。否则换工主理人的角标一直多一条，点开 404，商家那边一直显示处理中 */
    private void closeRequestsOfDeletedJobs(Long[] jobIds)
    {
        if (jdbc == null)
        {
            return;
        }
        java.util.List<Long> ids = java.util.Arrays.asList(jobIds);
        java.util.List<java.util.Map<String, Object>> open = jdbc.list("select request_id, merchant_user_id, store_id, title from kg_merchant_request "
                + "where job_id in (:ids) and del_flag = '0' and status not in ('done', 'closed', 'published')", KgJdbc.p("ids", ids));
        if (open == null || open.isEmpty())
        {
            return;
        }
        jdbc.update("update kg_merchant_request set status = 'closed', return_reason = '平台已删除', version = version + 1, handle_time = now(), update_by = :by, update_time = now() "
                + "where job_id in (:ids) and del_flag = '0' and status not in ('done', 'closed', 'published')", KgJdbc.p("by", SecurityUtils.getUsername(), "ids", ids));
        if (staffSupport == null)
        {
            return;
        }
        for (java.util.Map<String, Object> r : open)
        {
            Long merchant = KgJdbc.asLong(r.get("merchantUserId"));
            if (merchant != null)
            {
                staffSupport.notifyStaff(merchant, "system", "需求已关闭", "「" + r.get("title") + "」已被平台删除。有疑问可以联系客服", "merchant_request", KgJdbc.asLong(r.get("requestId")),
                        "/pages/staff/index" + (r.get("storeId") == null ? "" : "?storeId=" + r.get("storeId")));
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int changeStatus(Long jobId, String status, String reason)
    {
        kgJobMapper.lockRecruitment(jobId);
        KgJob job = requireJob(jobId);
        if (StringUtils.isEmpty(status) || !JobStatus.exists(status))
        {
            throw new ServiceException("岗位状态不合法：" + status);
        }
        if (!ALLOWED_TRANSITIONS.contains(job.getStatus() + "->" + status))
        {
            throw new ServiceException("岗位不允许从 " + job.getStatus() + " 变更为 " + status);
        }
        if (JobStatus.PUBLISHED.equals(status) && !JobType.ONLINE.equals(job.getJobType()))
        {
            JobImages.require(job.getImages());
            applyRecruitStore(job, job);
        }
        // 线上任务上架 / 恢复前先看截止时间：已过截止的不能再上架，草稿离截止不到 2 小时也不让上架（409 + 中文原因）
        if (JobStatus.PUBLISHED.equals(status) && JobType.ONLINE.equals(job.getJobType()) && onlineTasks != null)
        {
            onlineTasks.checkPublishable(jobId, job.getStatus());
        }
        // 门店提交的草稿审核不通过：关闭是终态，门店只能按意见改了重新发布，不说原因就不知道改什么
        //（房源、帖子、Vlog、投保、结算的驳回都要填原因，这里原先只发一句"如有疑问请联系平台运营"）
        boolean reject = JobStatus.DRAFT.equals(job.getStatus()) && JobStatus.CLOSED.equals(status) && "staff".equals(job.getSource());
        String why = reason == null ? "" : reason.trim();
        if (reject && why.isEmpty())
        {
            throw new ServiceException("请填写不通过的原因，会发给门店");
        }
        if (reject && why.length() > 200)
        {
            throw new ServiceException("原因不超过 200 字（当前 " + why.length() + " 字）");
        }
        KgJob update = new KgJob();
        update.setJobId(jobId);
        update.setStatus(status);
        if (JobStatus.PUBLISHED.equals(status))
        {
            // 重新上架视为重新发布：小程序列表按 publish_time 倒序，刷新后恢复的岗位才会重新曝光
            update.setPublishTime(new Date());
        }
        if (JobStatus.CLOSED.equals(status))
        {
            update.setCloseTime(new Date());
        }
        if (reject)
        {
            // 原因也记进岗位备注（放在原备注前面），后台回看时知道当初为什么没通过
            String old = job.getRemark() == null ? "" : job.getRemark().trim();
            String remark = "审核不通过：" + why + (old.isEmpty() ? "" : "；" + old);
            update.setRemark(remark.length() > 500 ? remark.substring(0, 500) : remark);
        }
        update.setUpdateBy(SecurityUtils.getUsername());
        if (kgJobMapper.updateKgJob(update) == 0)
        {
            throw new ServiceException("岗位不存在或已删除");
        }
        // 关闭后岗位再也上不了架，门店也就确认不了录用：还没录用的报名不处理的话，候选人还会去约面试、表态，
        // 最后被 24 小时超时以「商家未确认」终止。这里一并结束并通知候选人；已录用的照常签约入职
        int ended = 0;
        if (JobStatus.CLOSED.equals(status) && applications != null)
        {
            ended = applications.endOpenApplicationsOfClosedJob(jobId, OperatorDTO.admin(SecurityUtils.getUsername()));
            if (ended > 0 && staffSupport != null)
            {
                staffSupport.notifyStore(job.getStoreId(), "system", "岗位已关闭",
                        "平台已关闭「" + job.getTitle() + "」，" + ended + (JobType.ONLINE.equals(job.getJobType())
                                ? " 条还没审核的接单申请已自动结束并通知了申请人；已通过的接单照常交付、结算。"
                                : " 条还没录用的报名已自动结束并通知了候选人；已确认录用的不受影响。"),
                        "job", jobId, "/pages/staff/applications?storeId=" + job.getStoreId());
            }
        }
        notifyStaffJob(jobId, job.getStatus(), status, why);
        if (publicationFees != null) {
            if (JobStatus.PUBLISHED.equals(status)) publicationFees.published(jobId);
            if (JobStatus.CLOSED.equals(status)) publicationFees.closed(jobId);
        }
        syncMerchantRequest(jobId, job.getStatus(), status, JobType.ONLINE.equals(job.getJobType()), why);
        return ended;
    }

    /**
     * 后台岗位管理直接处理商家草稿（老板权限最高，V63）：上架时线上任务记 done、换工兼职全职记 published；关闭记 closed。
     * 和岗位状态同一事务；没有需求行（老流程的草稿）时一行都不改。
     */
    private void syncMerchantRequest(Long jobId, String from, String to, boolean online, String why)
    {
        if (jdbc == null || !JobStatus.DRAFT.equals(from))
        {
            return;
        }
        String target = JobStatus.CLOSED.equals(to) ? "closed" : (JobStatus.PUBLISHED.equals(to) ? (online ? "done" : "published") : null);
        if (target == null)
        {
            return;
        }
        jdbc.update("update kg_merchant_request set status = :st, return_reason = :why, version = version + 1, handle_time = now(), "
                + "update_by = :by, update_time = now() where job_id = :j and del_flag = '0' and status not in ('done', 'closed', 'published')",
                KgJdbc.p("st", target, "why", "closed".equals(target) && why != null ? why : "", "by", SecurityUtils.getUsername(), "j", jobId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publishMerchantDraft(Long jobId, String operator)
    {
        kgJobMapper.lockRecruitment(jobId);
        KgJob job = requireJob(jobId);
        if (!JobStatus.DRAFT.equals(job.getStatus()))
        {
            throw new ServiceException("岗位已经不是待上架状态，请刷新后查看", 409);
        }
        if (JobType.ONLINE.equals(job.getJobType()))
        {
            if (onlineTasks != null)
            {
                onlineTasks.checkPublishable(jobId, job.getStatus());
            }
        }
        else
        {
            JobImages.require(job.getImages());
            // 自动开通的商家店没有地址，发布时又没选工作地点的老草稿：上架了用户看到的工作地点是空的
            if (StringUtils.isEmpty(job.getAddress()) || job.getAddress().trim().isEmpty())
            {
                throw new ServiceException("工作地点还没填，请先完善岗位再上架", 422);
            }
            applyRecruitStore(job, job);
        }
        KgJob update = new KgJob();
        update.setJobId(jobId);
        update.setStatus(JobStatus.PUBLISHED);
        update.setPublishTime(new Date());
        update.setUpdateBy(operator);
        if (kgJobMapper.updateKgJob(update) == 0)
        {
            throw new ServiceException("岗位不存在或已删除");
        }
        if (publicationFees != null) publicationFees.published(jobId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void closeMerchantDraft(Long jobId, String operator, String reason)
    {
        kgJobMapper.lockRecruitment(jobId);
        KgJob job = requireJob(jobId);
        if (!JobStatus.DRAFT.equals(job.getStatus()))
        {
            throw new ServiceException("岗位已经不是待上架状态，请刷新后查看", 409);
        }
        String why = reason == null ? "" : reason.trim();
        KgJob update = new KgJob();
        update.setJobId(jobId);
        update.setStatus(JobStatus.CLOSED);
        update.setCloseTime(new Date());
        String old = job.getRemark() == null ? "" : job.getRemark().trim();
        String remark = "审核不通过：" + why + (old.isEmpty() ? "" : "；" + old);
        update.setRemark(remark.length() > 500 ? remark.substring(0, 500) : remark);
        update.setUpdateBy(operator);
        if (kgJobMapper.updateKgJob(update) == 0)
        {
            throw new ServiceException("岗位不存在或已删除");
        }
        if (publicationFees != null) publicationFees.closed(jobId);
    }

    /** 员工提交的岗位（来源 staff）经平台审核：上架或不通过时通知门店员工（消息失败不影响审核本身） */
    private void notifyStaffJob(Long jobId, String from, String to, String reason)
    {
        if (jdbc == null || staffSupport == null || !JobStatus.DRAFT.equals(from))
        {
            return;
        }
        try
        {
            java.util.Map<String, Object> j = jdbc.one("select store_id, title, source, job_type from kg_job where job_id = :id", KgJdbc.p("id", jobId));
            if (j == null || !"staff".equals(KgJdbc.asStr(j.get("source"))) || j.get("storeId") == null)
            {
                return;
            }
            boolean ok = JobStatus.PUBLISHED.equals(to);
            Long storeId = KgJdbc.asLong(j.get("storeId"));
            // 线上任务的审核结果点开是这个任务的详情（带门店）：线上任务专员也能发布了，他们进不了岗位管理，
            // 消息中心也只对落在线上任务页的这类通知给专员放行（KgMessageMapper currentChatReception）。线下岗位照旧去岗位管理
            boolean online = JobType.ONLINE.equals(KgJdbc.asStr(j.get("jobType")));
            String route = online ? "/pages/staff/online-jobs?jobId=" + jobId + "&storeId=" + storeId : "/pages/staff/jobs";
            // 通过的文字也跟着走：专员打不开岗位管理，线上任务就说去线上任务里看；线下岗位文字不变
            staffSupport.notifyStore(storeId, "system", ok ? "岗位已通过平台审核" : "岗位未通过平台审核",
                    "「" + j.get("title") + "」" + (ok ? (online ? "已上线，可以在线上任务里查看和分享" : "已上架，可以在岗位管理里分享招募")
                            : StringUtils.isEmpty(reason) ? "未通过审核，如有疑问请联系平台运营"
                            : "未通过审核，原因：" + reason + "。可以按意见调整后重新发布岗位。"), "job", jobId, route);
        }
        catch (RuntimeException e)
        {
            // 通知是附属动作，不能让审核失败
        }
    }

    /**
     * 落库前的业务校验。
     * 同时兼做入参归一化（补默认计薪方式、补齐薪资上限），让数据库里的数据形态保持一致，
     * 小程序端展示薪资区间时就不用再处理"上限为空"的分支。
     *
     * 只卡必填项，可选字段为空一律放行 —— 后台编辑走全量覆盖，"可选字段为空"是合法的清空意图。
     * 原先 updateKgJob 会先用 mergeForValidate() 把缺失字段回落到库里的旧值再校验，
     * 那是为了让局部更新也能过必填校验；改成全量覆盖后它反而有害
     *（缺字段的请求借旧值通过校验，再被全量语句把该列写成 NULL），所以已删除。
     */
    private void applyRecruitStore(KgJob job, KgJob existing)
    {
        if (!job.isRecruitStoreProvided() && existing != null) job.setRecruitStoreId(existing.getRecruitStoreId());
        Long id = job.getRecruitStoreId();
        if (id == null) return;
        if (JobType.ONLINE.equals(job.getJobType())) throw new ServiceException("线上任务不设置实际招聘门店");
        KgStore recruiting = kgStoreMapper.selectKgStoreByStoreId(id);
        if (recruiting == null || !"0".equals(recruiting.getStatus())) throw new ServiceException("实际招聘门店不存在或已停用");
        if (id.equals(job.getStoreId())) job.setRecruitStoreId(null);
    }

    private void validate(KgJob job)
    {
        if (job.getSortNo() != null && (job.getSortNo() < 0 || job.getSortNo() > 9999))
        {
            throw new ServiceException("展示顺序请填写 0 至 9999 的整数");
        }
        if (StringUtils.isEmpty(job.getTitle()))
        {
            throw new ServiceException("岗位标题不能为空");
        }
        if (StringUtils.isEmpty(job.getCity()))
        {
            throw new ServiceException("城市不能为空");
        }
        if (StringUtils.isEmpty(job.getJobType()))
        {
            throw new ServiceException("岗位分类不能为空");
        }
        if (!JobType.exists(job.getJobType()))
        {
            throw new ServiceException("岗位分类不合法：" + job.getJobType());
        }
        // store_id 是 not null 列，提前拦截比等数据库报错更容易定位
        if (job.getStoreId() == null)
        {
            throw new ServiceException("所属门店不能为空");
        }
        if (kgStoreMapper.selectKgStoreByStoreId(job.getStoreId()) == null)
        {
            throw new ServiceException("所属门店不存在或已删除");
        }
        if (job.getHeadcount() == null || job.getHeadcount().intValue() < 1)
        {
            throw new ServiceException("招聘名额至少为 1");
        }
        if (StringUtils.isEmpty(job.getSalaryType()))
        {
            job.setSalaryType(SALARY_TYPE_NONE);
        }
        if (!SalaryType.exists(job.getSalaryType()))
        {
            throw new ServiceException("计薪方式不合法：" + job.getSalaryType());
        }
        if (SalaryType.isTimeBased(job.getSalaryType()))
        {
            job.setSalaryUnit(SalaryType.unit(job.getSalaryType()));
        }
        // 食宿选了"不包"，食宿说明也必须清掉：
        // 后台那个输入框同样只是 disabled，整对象回填提交会把
        // "包吃包住（独立宿舍 · Wi-Fi · 洗衣机）" 原样写回，
        // 库里留下 board_type='none' 却写着包吃包住的矛盾行，而小程序详情页就展示它。
        if ("none".equals(job.getBoardType()))
        {
            job.setBoardDesc(null);
        }
        if (SALARY_TYPE_NONE.equals(job.getSalaryType()))
        {
            // 无底薪时清除固定金额；奖金、提成独立保留。
            //
            // 后台表单里这四个输入框只是 disabled，值还在；而表单是整对象回填提交的，
            // 全量 update 语句又会无条件写这四列 —— 于是库里留下
            // salary_type='none' 却带着 120 元/天的矛盾行，小程序照着它渲染。
            // 这一层是所有写入路径的必经之地，兜底放在这里才管用。
            job.setSalaryMin(null);
            job.setSalaryMax(null);
            job.setSalaryUnit(null);
            job.setSettleDesc(null);
        }
        job.setBonusDesc(JobCompensation.rule(job.getBonusDesc(), "奖金"));
        job.setCommissionDesc(JobCompensation.rule(job.getCommissionDesc(), "提成"));
        if (!SALARY_TYPE_NONE.equals(job.getSalaryType()))
        {
            if (job.getSalaryMin() == null)
            {
                throw new ServiceException("选择了计薪方式时薪资下限不能为空");
            }
            if (job.getSalaryMin().compareTo(BigDecimal.ZERO) < 0)
            {
                throw new ServiceException("薪资下限不能为负数");
            }
            if (job.getSalaryMax() == null)
            {
                // 只填一个数时按固定薪资处理，避免出现"3000 元起"这种没有上限的模糊展示
                job.setSalaryMax(job.getSalaryMin());
            }
            if (job.getSalaryMax().compareTo(job.getSalaryMin()) < 0)
            {
                throw new ServiceException("薪资上限不能低于薪资下限");
            }
        }
        if (job.getMinDays() != null && job.getMinDays().intValue() < 1)
        {
            throw new ServiceException("最短停留天数至少为 1 天");
        }
        // 上限和门店发布同一口径（JobFilterFields.MIN_DAYS_MAX）：之前只有后台页面的输入框限了 365，直接调接口能存进 1000
        if (job.getMinDays() != null && job.getMinDays().intValue() > com.ruoyi.kangaroo.domain.JobFilterFields.MIN_DAYS_MAX)
        {
            throw new ServiceException("最短停留天数最多 " + com.ruoyi.kangaroo.domain.JobFilterFields.MIN_DAYS_MAX + " 天");
        }
        // 做满一个月有红包（V44，0 有 / 1 没有）：没传 = 新增按库默认「没有」、修改保持原值（老版本后台表单不带这个字段）
        if (StringUtils.isNotEmpty(job.getMonthRedpacket()) && !"0".equals(job.getMonthRedpacket()) && !"1".equals(job.getMonthRedpacket()))
        {
            throw new ServiceException("满月红包选项不合法：" + job.getMonthRedpacket());
        }
        // 线上任务按单结算，没有「做满一个月」一说：一律存「没有」（1）。之前后台表单也给线上任务显示了这个勾选框，
        // 勾上后小程序的线上任务卡片挂着「满月红包」标签，「满月红包」筛选也会把它列出来
        if (JobType.ONLINE.equals(job.getJobType()))
        {
            job.setMonthRedpacket("1");
        }
    }

    /**
     * 换工类型（kg_job.work_kind，字典 kg_volunteer_kind）。之前没有这一列，用户只能按标题关键词猜类型；
     * 现在后台可以给换工岗位选类型：
     *  · 非换工岗位一律存空串（换工改成兼职时，表单里残留的类型不能跟过去）；
     *  · 修改时请求里没有这个字段（老版本后台页面），保持库里原值，不当成清空；
     *  · 选了就必须是启用中的类型，原来就挂着的已停用类型照样放行（见 KgVolunteerKinds.normalize）。
     */
    private void applyWorkKind(KgJob job, KgJob exist)
    {
        if (!JobType.VOLUNTEER.equals(job.getJobType()))
        {
            job.setWorkKind("");
            return;
        }
        if (job.getWorkKind() == null)
        {
            if (exist == null)
            {
                job.setWorkKind("");
            }
            return;
        }
        if (volunteerKinds != null)
        {
            job.setWorkKind(volunteerKinds.normalize(job.getJobType(), job.getWorkKind(), exist == null ? null : exist.getWorkKind()));
        }
    }

    /**
     * 岗位筛选条件（V48：性别偏好 gender_pref、休息排班 rest_mode、住宿房型 room_type，取值与适用范围见 JobFilterFields）：
     *  · 选了就必须在白名单里（代码或中文都认，别的 422）；
     *  · 修改时请求里没有这个字段（老版本后台页面），按库里原值再收口一次；新增没传 = ''（没填）；
     *  · 按岗位类型和食宿收口：性别只留给换工换宿，线上任务没有排班，不包住的岗位没有房型。
     *    换工改成兼职、食宿改成不包时，表单里残留的值不会跟过去（小程序筛「女」「单人间」不会再挂着它）。
     */
    private void applyFilterFields(KgJob job, KgJob exist)
    {
        String gender = job.getGenderPref() != null ? JobFilterFields.gender(job.getGenderPref()) : kept(JobFilterFields.GENDERS, exist == null ? null : exist.getGenderPref());
        String rest = job.getRestMode() != null ? JobFilterFields.restMode(job.getRestMode()) : kept(JobFilterFields.REST_MODES, exist == null ? null : exist.getRestMode());
        String room = job.getRoomType() != null ? JobFilterFields.roomType(job.getRoomType()) : kept(JobFilterFields.ROOM_TYPES, exist == null ? null : exist.getRoomType());
        // 食宿：表单没带时 updateKgJobByAdmin 保持库里原值，这里也按原值判断包不包住
        String board = StringUtils.isNotEmpty(job.getBoardType()) ? job.getBoardType() : (exist == null ? null : exist.getBoardType());
        job.setGenderPref(JobFilterFields.genderFor(job.getJobType(), gender));
        job.setRestMode(JobFilterFields.restModeFor(job.getJobType(), rest));
        job.setRoomType(JobFilterFields.roomTypeFor(job.getJobType(), board, room));
    }

    /** 库里原值：认识的照旧，不认识的（只可能是手改库）当没填 */
    private static String kept(java.util.Map<String, String> dict, String v)
    {
        return v != null && dict.containsKey(v) ? v : "";
    }

    /**
     * 工作地点坐标（V42，kg_job.latitude / longitude）。后台表单里没有坐标输入框，但打开编辑时 getJob 会把库里的坐标随整个对象带回来、
     * 保存时原样提交；更早打开的老页面则根本不带这两个字段。规则：
     *  · 线上任务没有工作地点（远程），一律不存坐标；
     *  · 带了坐标：要成对、在范围内、不能是 0,0（和房源同一套校验 StayLocation.normalize），保留 6 位小数；
     *  · 工作地点文字没改：没带坐标就保留库里的点 —— 不能一次后台保存就把门店在小程序里选好的点清掉；
     *  · 工作地点文字改了（含换门店时地址跟着换）：没带坐标、或带回来的还是库里那个旧点，说明点没跟着改，清掉，
     *    免得岗位详情、导航还指向旧地址。后台没有地图选点，要重新定位请门店在小程序「岗位管理 → 修改工作地点」里选。
     */
    private void applyLocation(KgJob job, KgJob exist)
    {
        if (JobType.ONLINE.equals(job.getJobType()))
        {
            job.setLatitude(null);
            job.setLongitude(null);
            return;
        }
        boolean sent = job.getLatitude() != null || job.getLongitude() != null;
        if (sent)
        {
            if (job.getLatitude() == null || job.getLongitude() == null)
            {
                throw new ServiceException("工作地点的纬度和经度要同时填写", 422);
            }
            java.util.Map<String, Object> point = new java.util.HashMap<String, Object>();
            point.put("latitude", job.getLatitude());
            point.put("longitude", job.getLongitude());
            com.ruoyi.kangaroo.ext.stay.StayLocation.normalize(point);
            job.setLatitude((BigDecimal) point.get("latitude"));
            job.setLongitude((BigDecimal) point.get("longitude"));
        }
        if (exist == null)
        {
            return;
        }
        if (sameText(job.getAddress(), exist.getAddress()))
        {
            if (!sent)
            {
                job.setLatitude(exist.getLatitude());
                job.setLongitude(exist.getLongitude());
            }
            return;
        }
        if (!sent || (samePoint(job.getLatitude(), exist.getLatitude()) && samePoint(job.getLongitude(), exist.getLongitude())))
        {
            job.setLatitude(null);
            job.setLongitude(null);
        }
    }

    /**
     * 岗位照片（V49，kg_job.images，最多 9 张）。后台表单用图片上传组件，带来的是逗号分隔的路径（也认 JSON 数组）：
     *  · 到店岗位至少一张；编辑未带字段时只允许保留已有的合格照片，新增和清空不允许无图；
     *  · 带了：只收本平台上传的 /profile/upload/… 路径，最多 9 张，不合格 422（JobImages.parse），存成 JSON 数组；
     *  · 封面跟着第一张走：有照片时 cover = 第一张（首页匹配、我的报名这些直接读 cover 的地方也就一致了）；
     *    照片清空、而封面还是原来第一张照片时，封面退回门店封面（运营手填的别的封面不动）。
     */
    private void applyImages(KgJob job, KgJob exist)
    {
        List<String> images = JobImages.parse(job.getImages());
        if (!JobType.ONLINE.equals(job.getJobType()))
        {
            images = JobImages.require(images == null && exist != null ? exist.getImages() : images);
        }
        if (images == null)
        {
            return;
        }
        job.setImages(JobImages.toJson(images));
        if (!images.isEmpty())
        {
            job.setCover(images.get(0));
            return;
        }
        List<String> before = exist == null ? java.util.Collections.<String>emptyList() : JobImages.read(exist.getImages());
        if (!before.isEmpty() && sameText(job.getCover(), before.get(0)))
        {
            KgStore store = job.getStoreId() == null ? null : kgStoreMapper.selectKgStoreByStoreId(job.getStoreId());
            job.setCover(store == null || StringUtils.isEmpty(store.getCover()) ? null : store.getCover());
        }
    }

    /**
     * 餐补、宠物、住宿照片和视频（V66，规则见 JobExtras）。后台表单把图片带成逗号分隔或 JSON 数组（同 images）：
     *  · 带了（不是 null）：校验（不合格 422），按岗位类型和食宿收口，图片存成 JSON 数组；
     *  · 没带（老版本后台表单）：保持 null，updateKgJobByAdmin 不动这一列。
     */
    private void applyExtras(KgJob job, KgJob exist)
    {
        java.util.Map<String, Object> in = new java.util.LinkedHashMap<String, Object>();
        in.put("mealAllowance", job.getMealAllowance());
        in.put("mealAllowanceUnit", job.getMealAllowanceUnit());
        in.put("petStatus", job.getPetStatus());
        in.put("petImages", job.getPetImages());
        in.put("lodgingImages", job.getLodgingImages());
        in.put("lodgingVideo", job.getLodgingVideo());
        in.put("lodgingVideoCover", job.getLodgingVideoCover());
        // 食宿：表单没带时 updateKgJobByAdmin 保持库里原值，这里也按原值判断（同 applyFilterFields）
        String board = StringUtils.isNotEmpty(job.getBoardType()) ? job.getBoardType() : (exist == null ? null : exist.getBoardType());
        java.util.Map<String, Object> cols = JobExtras.columns(in, job.getJobType(), board, videos == null ? null : videos::exists);
        if (cols.containsKey("meal_allowance")) job.setMealAllowance((String) cols.get("meal_allowance"));
        if (cols.containsKey("meal_allowance_unit")) job.setMealAllowanceUnit((String) cols.get("meal_allowance_unit"));
        if (cols.containsKey("pet_status")) job.setPetStatus((String) cols.get("pet_status"));
        if (cols.containsKey("pet_images")) job.setPetImages((String) cols.get("pet_images"));
        if (cols.containsKey("lodging_images")) job.setLodgingImages((String) cols.get("lodging_images"));
        if (cols.containsKey("lodging_video")) job.setLodgingVideo((String) cols.get("lodging_video"));
        if (cols.containsKey("lodging_video_cover")) job.setLodgingVideoCover((String) cols.get("lodging_video_cover"));
    }

    private static boolean sameText(String a, String b)
    {
        return (a == null ? "" : a.trim()).equals(b == null ? "" : b.trim());
    }

    /** 坐标按数值比（库里读出来是 6 位小数，表单带回来的可能是 25.1 这种短写法） */
    private static boolean samePoint(BigDecimal a, BigDecimal b)
    {
        return a != null && b != null && a.compareTo(b) == 0;
    }

    @Override
    public int recalcCounters(Long jobId)
    {
        if (jobId != null)
        {
            requireJob(jobId);
        }
        return kgJobMapper.recalcCounters(jobId);
    }
}
