package com.ruoyi.kangaroo.service;

import java.util.List;
import com.ruoyi.kangaroo.domain.KgJob;

/**
 * 岗位 Service
 */
public interface IKgJobService
{
    KgJob selectKgJobByJobId(Long jobId);

    /** 存在性校验：不存在或已删除时抛业务异常 */
    KgJob requireJob(Long jobId);

    List<KgJob> selectKgJobList(KgJob job);

    /** 小程序列表，只返回对 C 端可见的岗位 */
    List<KgJob> selectAppJobList(KgJob job);

    int insertKgJob(KgJob job);

    /**
     * 后台整表单修改。改了所属门店时，还没走完流程的报名（连同面试时段、投保申请）一起转到新门店，
     * 转过去的条数放在 job.params.movedApps 里带回给接口。
     */
    int updateKgJob(KgJob job);

    int deleteKgJobByJobIds(Long[] jobIds);

    /**
     * 上下架：岗位状态的唯一入口，非法流转直接拒绝。
     * 门店提交的草稿审核不通过（draft → closed）必须带原因，原因写进给门店的通知和岗位备注；
     * 关闭时还没录用的报名一并结束并通知候选人。
     *
     * @return 随岗位关闭一起结束的报名条数（不是关闭就是 0）
     */
    int changeStatus(Long jobId, String status, String reason);

    /**
     * 小程序里上架派给自己的商家草稿（换工主理人上架换工/兼职/全职、商家服务人上架线上任务，V63）。
     * 和 changeStatus 同一套上架校验（岗位照片、实际招聘门店、线上任务截止时间）；只接受草稿，其他状态 409。
     * 商家需求的状态由调用方（KgMerchantRequestService）维护。
     */
    void publishMerchantDraft(Long jobId, String operator);

    /** 换工主理人不通过派给他的商家草稿：关闭并把原因写进岗位备注；只接受草稿，其他状态 409。 */
    void closeMerchantDraft(Long jobId, String operator, String reason);

    /**
     * 按 kg_application 的实际数据重算岗位的录用/报名计数。
     * jobId 为空时重算全部岗位。用于纠正历史遗留的计数漂移，以及作为运维兜底手段。
     */
    int recalcCounters(Long jobId);
}
