package com.ruoyi.kangaroo.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.kangaroo.domain.KgInterview;

/**
 * 面试预约 Mapper
 */
public interface KgInterviewMapper
{
    /** 一条报名最多一场面试（表上有 uk_interview_app 唯一键），据此判断是否已预约 */
    KgInterview selectByAppId(@Param("appId") Long appId);

    /** 同 selectByAppId，但是加锁的当前读（不受事务快照影响） */
    KgInterview selectByAppIdForUpdate(@Param("appId") Long appId);

    /** 把这条报名还停在 booked 的面试作废（已完成的保持原状）。返回受影响行数 */
    int cancelBookedByApp(@Param("appId") Long appId);

    KgInterview selectKgInterviewByInterviewId(Long interviewId);

    int insertKgInterview(KgInterview kgInterview);

    int updateKgInterview(KgInterview kgInterview);

    List<KgInterview> selectKgInterviewList(KgInterview kgInterview);
}
