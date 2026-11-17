package com.ruoyi.kangaroo.service.impl;

import java.util.Date;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.kangaroo.domain.KgApplication;
import com.ruoyi.kangaroo.domain.KgInterviewSlot;
import com.ruoyi.kangaroo.enums.ApplicationStatus;
import com.ruoyi.kangaroo.mapper.KgApplicationMapper;
import com.ruoyi.kangaroo.mapper.KgInterviewSlotMapper;
import com.ruoyi.kangaroo.service.IKgInterviewSlotService;
import com.ruoyi.kangaroo.service.IKgStoreService;

@Service
public class KgInterviewSlotServiceImpl implements IKgInterviewSlotService
{
    /** 可选 */
    private static final String STATUS_OPEN = "open";

    /** 已被报名占用 */
    private static final String STATUS_BOOKED = "booked";

    /** 已关闭（不再放出，但保留历史） */
    private static final String STATUS_CLOSED = "closed";

    @Autowired
    private KgInterviewSlotMapper kgInterviewSlotMapper;

    @Autowired
    private IKgStoreService kgStoreService;

    @Autowired
    private KgApplicationMapper kgApplicationMapper;

    /** 新增时段后提醒还在等时段的候选人（与小程序门店端加时段同一套逻辑）；可选依赖，缺了只是不发提醒 */
    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.staff.KgStaffSlotService staffSlots;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KgInterviewSlotServiceImpl.class);

    @Override
    public KgInterviewSlot selectKgInterviewSlotBySlotId(Long slotId)
    {
        return kgInterviewSlotMapper.selectKgInterviewSlotBySlotId(slotId);
    }

    @Override
    public KgInterviewSlot requireSlot(Long slotId)
    {
        if (slotId == null)
        {
            throw new ServiceException("时段ID不能为空");
        }
        KgInterviewSlot slot = kgInterviewSlotMapper.selectKgInterviewSlotBySlotId(slotId);
        if (slot == null)
        {
            throw new ServiceException("面试时段不存在或已删除");
        }
        return slot;
    }

    @Override
    public List<KgInterviewSlot> selectKgInterviewSlotList(KgInterviewSlot kgInterviewSlot)
    {
        return kgInterviewSlotMapper.selectKgInterviewSlotList(kgInterviewSlot);
    }

    @Override
    public List<KgInterviewSlot> selectOpenSlots(Long storeId, Long jobId)
    {
        if (storeId == null)
        {
            throw new ServiceException("门店ID不能为空");
        }
        return kgInterviewSlotMapper.selectOpenSlots(storeId, jobId);
    }

    @Override
    public int insertKgInterviewSlot(KgInterviewSlot kgInterviewSlot)
    {
        if (kgInterviewSlot.getMeetingInfo()!=null && kgInterviewSlot.getMeetingInfo().length()>500) throw new ServiceException("会议信息最多 500 字");
        validate(kgInterviewSlot, null);
        // 排一个已经过去的时段没有任何意义，只会污染小程序的可选列表
        if (kgInterviewSlot.getStartTime().before(new Date()))
        {
            throw new ServiceException("不能创建已过去的时段");
        }
        kgInterviewSlot.setCreateBy(SecurityUtils.getUsername());
        // 新建的时段一定是空闲的；占用状态只能由报名预约流程（CAS）写入
        kgInterviewSlot.setStatus(STATUS_OPEN);
        kgInterviewSlot.setBookedAppId(null);
        int rows;
        try
        {
            rows = kgInterviewSlotMapper.insertKgInterviewSlot(kgInterviewSlot);
        }
        catch (DuplicateKeyException e)
        {
            // uk_slot_store_range 兜住并发：countOverlap 判重与 insert 之间有竞态窗口。
            // 唯一索引只能挡完全重合的区间，部分重叠仍由 countOverlap 负责。
            throw new ServiceException("该门店已存在相同时间段的面试时段");
        }
        // 后台补的时段也要告诉还在等时段的候选人（之前一个可约时段都没有的人；指定了岗位就只提醒这个岗位的报名人），
        // 否则他们只能自己回来刷新。时段已经建好，提醒发送失败只记日志，不让这次新增变成报错
        if (rows > 0 && staffSlots != null && kgInterviewSlot.getSlotId() != null)
        {
            try
            {
                staffSlots.notifyWaiting(kgInterviewSlot.getStoreId(), kgInterviewSlot.getJobId(), kgInterviewSlot.getSlotId());
            }
            catch (Exception e)
            {
                log.warn("新增面试时段 {} 后提醒等待中的候选人失败：{}", kgInterviewSlot.getSlotId(), e.getMessage());
            }
        }
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateKgInterviewSlot(KgInterviewSlot kgInterviewSlot)
    {
        KgInterviewSlot origin = requireSlot(kgInterviewSlot.getSlotId());
        // 只有"占用仍然有效"才拒绝。被一条已终止/已撤回/已软删的报名占着的时段，
        // 如果也拒绝，运营就再也动不了它了 —— 既改不了也删不了，
        // 而 V4 的唯一索引又不允许在同门店同区间重建一条，等于这个时间点被永久占死。
        assertBookingNotActive(origin, "修改");
        // 前端可能只提交改动的字段，这里补齐后再校验，保证校验的就是落库后的最终区间
        if (kgInterviewSlot.getStoreId() == null)
        {
            kgInterviewSlot.setStoreId(origin.getStoreId());
        }
        if (kgInterviewSlot.getStartTime() == null)
        {
            kgInterviewSlot.setStartTime(origin.getStartTime());
        }
        if (kgInterviewSlot.getEndTime() == null)
        {
            kgInterviewSlot.setEndTime(origin.getEndTime());
        }
        if (kgInterviewSlot.getMeetingInfo()!=null && kgInterviewSlot.getMeetingInfo().length()>500) throw new ServiceException("会议信息最多 500 字");
        validate(kgInterviewSlot, kgInterviewSlot.getSlotId());
        // 只在真正改了开始时间时才卡未来时间，否则历史时段连备注都改不了
        if (kgInterviewSlot.getStartTime().getTime() != origin.getStartTime().getTime()
                && kgInterviewSlot.getStartTime().before(new Date()))
        {
            throw new ServiceException("不能把时段改到已过去的时间");
        }
        // 这条校验的本意是"不许把一个非占用时段手工改成占用"。
        // 但后台表单是整对象回填提交：booked 行取回来的 form.status 就是 'booked'
        // （状态单选组里没有 booked 这个选项，运营不主动改就一直是它），
        // 原样比对会让**每一次**编辑已占用时段都撞在这里。
        // 所以只在真的改变了状态时才拦 —— 原样回传不算"手工设置"。
        if (STATUS_BOOKED.equals(kgInterviewSlot.getStatus())
                && !STATUS_BOOKED.equals(origin.getStatus()))
        {
            throw new ServiceException("占用状态只能由报名预约产生，不能手工设置");
        }
        // 全部校验都过了，才动库。
        //
        // 顺序很要紧：forceReleaseSlot 原先排在上面那几个抛出点之前，而本方法当时没有事务，
        // 于是"保存失败"的同时时段已经被释放掉了 —— 用户那边面试没了，运营那边还以为没改成。
        // 现在方法带事务，并且把释放挪到这里，失败会整体回滚。
        String operator = SecurityUtils.getUsername();
        if (STATUS_BOOKED.equals(origin.getStatus()))
        {
            // 走到这里说明占用已失效（assertBookingNotActive 没抛），把它放回可约池，
            // 否则改完状态还是 booked，下一次仍要走这条补救路径。
            kgInterviewSlotMapper.forceReleaseSlot(origin.getSlotId(), operator);
            // 释放之后这一行已经不是 booked 了，落库时的 CAS 期望值随之变成"无人占用"
            origin.setBookedAppId(null);
            origin.setStatus(STATUS_OPEN);
            // 表单是整对象回填提交的，里面那个 status 还是取回来时的 'booked'。
            // 不改掉它，下面的全量覆盖会把刚释放掉的状态又写回 booked ——
            // 结果是 booked_app_id 为空、status 却是 booked 的脏行，比修之前更糟。
            // 回归用例 T6 就是在这里抓到的。
            if (STATUS_BOOKED.equals(kgInterviewSlot.getStatus()))
            {
                kgInterviewSlot.setStatus(STATUS_OPEN);
            }
        }
        kgInterviewSlot.setUpdateBy(operator);
        // 占用关系只走 bookSlotWithCas / releaseSlot，这里即便传了也丢弃
        kgInterviewSlot.setBookedAppId(null);
        // 后台编辑是整表单提交，走全量语句：请求里没有的可选字段就是运营清空了它。
        // 「限定岗位」清空后 jobId 为 null，这条语句会把 job_id 写成 NULL，
        // 即产品语义上的"门店通用时段"；动态语句 updateKgInterviewSlot 会整列跳过，做不到这点。
        // 上面已把 storeId / startTime / endTime 从原记录补齐，全量覆盖不会写坏这三个 NOT NULL 列。
        // 带上刚读到的占用者做 CAS：校验与落库之间，小程序仍可能把这个时段约走。
        // 删除路径（deleteSlotWithGuard）这一轮已经加了同款保护，编辑路径当时漏了 ——
        // 少了它，一次编辑会改掉一个刚被预约的时段的起止时间，
        // 而 kg_interview 里发给用户的是预约当时的快照，两边从此对不上：
        // 用户按旧时间来，门店按新时间等。
        int rows;
        try
        {
            rows = kgInterviewSlotMapper.updateKgInterviewSlotByAdmin(
                    kgInterviewSlot, origin.getBookedAppId());
        }
        catch (DuplicateKeyException e)
        {
            // 新增路径早就兜住了这个异常，编辑路径漏了：
            // 全量 SET 会写 start_time / end_time，撞上 uk_slot_store_range 时
            // 抛出来的是原始 SQL 报错（含表名、索引名、完整 SQL），直接吐给运营。
            // 这里换成和新增路径同一句话。
            throw new ServiceException("该门店已存在相同时间段的面试时段（"
                    + kgInterviewSlot.getStartTime() + " 起），请换一个时间或先删除原有时段");
        }
        if (rows == 0)
        {
            throw new ServiceException("时段的占用状态刚刚发生变化（多半是有人抢先预约），请刷新后重试");
        }
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteKgInterviewSlotBySlotIds(Long[] slotIds)
    {
        if (slotIds == null || slotIds.length == 0)
        {
            return 0;
        }
        String operator = SecurityUtils.getUsername();
        int rows = 0;
        for (Long slotId : slotIds)
        {
            KgInterviewSlot slot = requireSlot(slotId);
            assertBookingNotActive(slot, "删除");
            // 带上刚读到的占用者做 CAS：判定与落库之间小程序可能抢先约走这个时段
            int n = kgInterviewSlotMapper.deleteSlotWithGuard(
                    slotId, slot.getBookedAppId(), operator);
            if (n == 0)
            {
                throw new ServiceException("时段 " + slotId + " 的占用状态刚刚发生变化"
                        + "（多半是有人抢先预约），请刷新后重试");
            }
            rows += n;
        }
        return rows;
    }

    /**
     * 占用是否仍然有效。无效就放行运营的操作。
     *
     * 判定顺序即"无效"的三种形态：
     *   1. 没有占用者（booked_app_id 为空）——状态可能是脏数据，直接放行；
     *   2. 占用它的报名查不到或已软删——那条报名已经不在业务里了；
     *   3. 占用它的报名已到终态（入职/驳回/撤回/终止）——面试环节已经结束。
     * 只有"报名还活着且没走完流程"才真的拦下来，并把是哪条报名说清楚，
     * 运营知道该去处理哪一条，而不是对着一句"已被预约占用"无从下手。
     */
    private void assertBookingNotActive(KgInterviewSlot slot, String action)
    {
        if (!STATUS_BOOKED.equals(slot.getStatus()) || slot.getBookedAppId() == null)
        {
            return;
        }
        KgApplication app = kgApplicationMapper.selectKgApplicationByAppId(slot.getBookedAppId());
        if (app == null || ApplicationStatus.isFinal(app.getStatus()))
        {
            return;
        }
        String who = StringUtils.isNotEmpty(app.getAppNo())
                ? app.getAppNo() : String.valueOf(app.getAppId());
        throw new ServiceException("时段正被报名 " + who
                + "（" + ApplicationStatus.label(app.getStatus()) + "）占用，不能" + action
                + "。请先在报名管理里处理该报名，或等应聘者自行改约。");
    }

    /**
     * 公共校验：门店存在、时间区间合法、同门店不重叠。
     *
     * @param excludeId 修改时排除自身，避免时段和自己冲突
     */
    private void validate(KgInterviewSlot slot, Long excludeId)
    {
        // 门店不存在时 left join 查不出门店名，运营在列表里只会看到空白，这里提前拦住
        kgStoreService.requireStore(slot.getStoreId());
        if (slot.getStartTime() == null || slot.getEndTime() == null)
        {
            throw new ServiceException("开始时间与结束时间不能为空");
        }
        if (!slot.getStartTime().before(slot.getEndTime()))
        {
            throw new ServiceException("开始时间必须早于结束时间");
        }
        if (StringUtils.isNotEmpty(slot.getStatus())
                && !STATUS_OPEN.equals(slot.getStatus())
                && !STATUS_BOOKED.equals(slot.getStatus())
                && !STATUS_CLOSED.equals(slot.getStatus()))
        {
            throw new ServiceException("时段状态非法：" + slot.getStatus());
        }
        // 重叠的时段会让同一个面试官被约两次，必须在入库前挡掉
        if (kgInterviewSlotMapper.countOverlap(slot.getStoreId(), slot.getStartTime(),
                slot.getEndTime(), excludeId) > 0)
        {
            throw new ServiceException("该门店在此时间段已存在面试时段，请勿重叠安排");
        }
    }
}
