package com.ruoyi.kangaroo.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.kangaroo.domain.KgApplication;

/**
 * 报名申请 Mapper
 */
public interface KgApplicationMapper
{
    KgApplication selectKgApplicationByAppId(Long appId);

    List<KgApplication> selectKgApplicationList(KgApplication kgApplication);

    /**
     * 小程序"我的报名"列表。
     * status 允许传英文逗号分隔的多个状态，页面上的"进行中/已结束"分组 Tab 才不用拆成多次查询。
     */
    List<KgApplication> selectAppUserApplications(@Param("appUserId") Long appUserId, @Param("status") String status);

    int insertKgApplication(KgApplication kgApplication);

    int updateKgApplication(KgApplication kgApplication);

    /** 门店下的报名单数量，门店停用/删除前做占用校验 */
    int countByStoreId(@Param("storeId") Long storeId);

    /** 岗位下的报名单数量，岗位关闭/删除前做占用校验 */
    int countByJobId(@Param("jobId") Long jobId);

    /** 同一用户对同一岗位仍在进行中的报名数，用于拦截重复报名（已拒绝/已撤回/已终止不算占用） */
    int countActiveByUserAndJob(@Param("appUserId") Long appUserId, @Param("jobId") Long jobId);

    /** 加锁读取报名当前状态（for update）。事务内需要据此做后续决定、又不能被事务快照骗到的地方用 */
    String selectStatusForUpdate(@Param("appId") Long appId);

    /**
     * CAS 乐观更新状态：库中当前状态仍等于 fromStatus 时才写入 toStatus。
     *
     * 返回 0 表示已被其它请求抢先改过状态，调用方必须据此拒绝本次操作。
     * 只做"先 select 校验再 update"是不够的：两条语句之间的窗口正是并发重复审核、
     * 重复确认录用的入口，必须把状态判断压进同一条 update 里由数据库保证原子性。
     */
    int updateStatusWithCas(@Param("appId") Long appId, @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus, @Param("updateBy") String updateBy);
}
