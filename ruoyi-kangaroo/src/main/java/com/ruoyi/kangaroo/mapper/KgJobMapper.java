package com.ruoyi.kangaroo.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.kangaroo.domain.KgJob;

/**
 * 岗位 Mapper
 */
public interface KgJobMapper
{
    KgJob selectKgJobByJobId(Long jobId);

    /** 在事务中锁住岗位，报名占位与后台调整总名额共用此锁。 */
    Long lockRecruitment(Long jobId);

    /** 后台列表：联表带出门店名与安心认证标记，避免前端二次查询门店 */
    List<KgJob> selectKgJobList(KgJob job);

    /** 小程序列表：只暴露 published/paused，草稿与已关闭岗位对外不可见 */
    List<KgJob> selectAppJobList(KgJob job);

    int insertKgJob(KgJob job);

    /**
     * 动态 SET 的局部更新：只写"传了值"的列，没传的列保持原值。
     * 内部的局部更新走这里（changeStatus 只带 jobId/status/publishTime 就调它），
     * 不要用它承接后台的整表单编辑 —— 那会让运营"清空可选字段"的动作被整列跳过。
     */
    int updateKgJob(KgJob job);

    /**
     * 后台整表单编辑专用的全量更新：运营可编辑且可为空的列一律无条件 SET，清空即写 NULL。
     * 与 updateKgJob 分成两条语句的原因见 KgJobMapper.xml 里的注释。
     * 统计列 hired_count/applied_count 与状态、时间戳列不在其中，由各自的专用接口维护。
     */
    int updateKgJobByAdmin(KgJob job);

    /** 逻辑删除 */
    int deleteKgJobByJobIds(@Param("jobIds") Long[] jobIds, @Param("updateBy") String updateBy);

    /** 门店下未删除的岗位数，门店删除前置校验用 */
    int countByStoreId(@Param("storeId") Long storeId);

    /** 报名成功后累加，统计字段只走这里，不接受外部整体覆盖 */
    int increaseAppliedCount(@Param("jobId") Long jobId);

    /**
     * 录用人数自增。
     * 名额判断放在 where 里由数据库保证原子性，并发录用时只有一方能更新成功；
     * 返回 0 表示名额已满，调用方据此拒绝录用，不要改成先查后改。
     */
    int increaseHiredCount(@Param("jobId") Long jobId);

    /**
     * 释放一个已录用名额。带 hired_count > 0 下限保护，任何情况下都不会减成负数。
     * 与 increaseHiredCount 成对使用：报名从"已扣名额"的状态退回终态时必须调用，
     * 否则名额会只减不增地永久泄漏，岗位最终既招不到人也不接受报名。
     */
    int decreaseHiredCount(@Param("jobId") Long jobId);

    /** 按报名表实际数据重算某岗位的录用/报名计数，用于存量数据纠偏与运维兜底 */
    int recalcCounters(@Param("jobId") Long jobId);
}
