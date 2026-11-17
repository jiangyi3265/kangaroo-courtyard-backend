package com.ruoyi.kangaroo.mapper;

import java.util.Date;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.kangaroo.domain.KgInterviewSlot;

/**
 * 面试时段 Mapper
 */
public interface KgInterviewSlotMapper
{
    KgInterviewSlot selectKgInterviewSlotBySlotId(Long slotId);

    /** 后台列表，左连 kg_store 带出门店名，避免前端再发一次查询 */
    List<KgInterviewSlot> selectKgInterviewSlotList(KgInterviewSlot kgInterviewSlot);

    /**
     * 小程序可选时段：只返回未开始且未被占用的时段。
     * jobId 为空时只命中门店通用时段（job_id is null），这是 SQL 中 job_id = null 恒不成立的自然结果。
     */
    List<KgInterviewSlot> selectOpenSlots(@Param("storeId") Long storeId, @Param("jobId") Long jobId);

    int insertKgInterviewSlot(KgInterviewSlot kgInterviewSlot);

    /**
     * 动态 SET 的局部更新：只写"传了值"的列，没传的列保持原值。
     * 留给"只改个别字段"的内部调用；后台整表单编辑不要用它，
     * 否则运营清空「限定岗位」时 job_id 会被整列跳过，表现成"弹了修改成功、库里没变"。
     */
    int updateKgInterviewSlot(KgInterviewSlot kgInterviewSlot);

    /**
     * 后台整表单编辑专用的全量更新：job_id / remark 这类可为空的列无条件 SET，清空即写 NULL。
     * booked_app_id 不在其中，占用关系只能走 bookSlotWithCas / releaseSlot。
     */
    /**
     * 后台整表单编辑。expectBookedAppId 是 Service 读到的占用者（不是 booked 时为 null），
     * 中间被别人抢约就命中 0 行，由调用方给出可读的报错。
     */
    int updateKgInterviewSlotByAdmin(@Param("slot") KgInterviewSlot slot,
            @Param("expectBookedAppId") Long expectBookedAppId);

    /** 逻辑删除 */
    /**
     * 逐条删除 + CAS。expectBookedAppId 是 Service 读到的占用者（不是 booked 时为 null），
     * 中间被别人抢约就命中 0 行，由调用方给出可读的报错。
     */
    int deleteSlotWithGuard(@Param("slotId") Long slotId,
            @Param("expectBookedAppId") Long expectBookedAppId,
            @Param("operator") String operator);

    /** 同门店时间区间是否与已有时段重叠（排除自身） */
    int countOverlap(@Param("storeId") Long storeId, @Param("startTime") Date startTime,
            @Param("endTime") Date endTime, @Param("excludeId") Long excludeId);

    /**
     * CAS 占用时段：把 status='open' 作为更新条件的一部分，
     * 返回 0 说明并发下已被别人抢走，调用方必须据此回滚并提示重选，不能只看异常。
     */
    int bookSlotWithCas(@Param("slotId") Long slotId, @Param("appId") Long appId);

    /**
     * 释放时段（取消预约后回到可选状态）。
     *
     * appId 不是可选参数：语句里 booked_app_id = #{appId} 保证只放得掉"自己占的"那个时段，
     * 传错 slotId 时影响 0 行，而不是把别人已约好的面试静默踢掉。
     * 调用方 KgApplicationServiceImpl.releaseSlotIfAny 手上一定有 appId。
     */
    int releaseSlot(@Param("slotId") Long slotId, @Param("appId") Long appId);

    /** 释放这条报名占着的时段（按 booked_app_id，不依赖可能过期的 slotId）。返回受影响行数，没有占用时为 0 */
    int releaseSlotsByApp(@Param("appId") Long appId);

    /**
     * 运营侧的强制释放：不带 appId 条件。
     *
     * 只在 Service 已经确认"占用它的那条报名不存在/已软删/已到终态"之后才调用 ——
     * 也就是这个占用已经失效、没有任何在途面试会被它影响。
     * 正常业务的释放一律走上面带 appId 的那条，别拿这个图省事。
     */
    int forceReleaseSlot(@Param("slotId") Long slotId, @Param("operator") String operator);
}
