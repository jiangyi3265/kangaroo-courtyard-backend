package com.ruoyi.kangaroo.service;

import java.util.List;

import com.ruoyi.kangaroo.domain.KgApplication;
import com.ruoyi.kangaroo.domain.KgApplicationLog;
import com.ruoyi.kangaroo.domain.KgInterview;
import com.ruoyi.kangaroo.dto.ApplicationSubmitDTO;
import com.ruoyi.kangaroo.dto.OperatorDTO;

/**
 * 岗位报名履约服务。
 *
 * 所有状态变更都必须经过 ApplicationStatus 状态机校验，并落一条 kg_application_log。
 * 每个方法都自行校验操作人对该申请的权限，不依赖调用方（更不依赖前端）的判断。
 */
public interface IKgApplicationService
{
    // ---------- 查询 ----------

    KgApplication selectByAppId(Long appId);

    /** 取申请，不存在则抛业务异常 */
    KgApplication requireApplication(Long appId);

    /** 取申请并校验归属：必须是本人的申请，否则抛 403（防止改 ID 越权查看他人报名） */
    KgApplication requireOwnedByUser(Long appId, Long appUserId);

    /** 取申请并校验门店归属：操作人必须对该申请所属门店有员工授权 */
    KgApplication requireOwnedByStore(Long appId, OperatorDTO operator);

    List<KgApplication> selectKgApplicationList(KgApplication query);

    List<KgApplication> selectMyApplications(Long appUserId, String status);

    List<KgApplicationLog> selectLogs(Long appId);

    KgInterview selectInterview(Long appId);

    // ---------- 用户侧 ----------

    /** 提交报名。返回新建的申请（重复提交同一幂等键时返回首次创建的那条） */
    KgApplication submit(ApplicationSubmitDTO dto, Long appUserId, String nickname);

    /** 用户撤回报名 */
    int withdraw(Long appId, OperatorDTO operator);

    /** 用户选择面试时段 */
    KgInterview bookInterview(Long appId, Long slotId, OperatorDTO operator);

    /** 用户申请改期：换一个开放时段，原时段还回可约池 */
    KgInterview rescheduleInterview(Long appId, Long slotId, String reason, OperatorDTO operator);

    /** 结束面试（记录通话时长与面试后问卷） */
    int finishInterview(Long appId, Integer durationSeconds, String understandJob, String acceptBoard,
            String confirmArrival, String feedbackNote, OperatorDTO operator);

    /** 面试后用户表态：accept 愿意前往 / pending 还想考虑 / reject 不合适 */
    int userConfirm(Long appId, String intent, OperatorDTO operator);

    /** 用户签署责任书 */
    int signContract(Long appId, OperatorDTO operator);

    // ---------- 门店 / 后台侧 ----------

    /** 审核报名：pass=true 通过，false 驳回（驳回必须给原因） */
    int audit(Long appId, boolean pass, String reason, OperatorDTO operator);

    /** 商家确认录用 */
    int storeConfirm(Long appId, OperatorDTO operator);

    /** 标记入职 */
    int onboard(Long appId, OperatorDTO operator);

    /** 终止申请 */
    int terminate(Long appId, String reason, OperatorDTO operator);

    /**
     * 商家确认超时：用户表态愿意前往后，商家要在 confirmDeadlineHours（默认 24）小时内确认录用，过期由系统终止。
     * 状态机早就允许 user_confirmed → terminated（system），但没有任何代码触发：报名会一直停在「用户已确认」，
     * 用户不知道要不要继续等。定时任务逐条调用本方法（每条自己一个事务）。
     *
     * @return 1 = 本次终止了；0 = 不满足条件（状态已变 / 还没到期）
     */
    int terminateConfirmTimeout(Long appId);

    // ---------- 岗位换店 / 关闭时的连带处理（岗位管理调用，与岗位本身的修改在同一事务里） ----------

    /**
     * 岗位改了所属门店：把这个岗位还没走完流程的报名，连同它们占用的面试时段、投保申请，
     * 以及本岗位还没开始的空面试时段，一起转到新门店。已结束的报名（含已入职）留在原门店备查。
     * 线上任务还有没结算完的接单时拒绝（结算单是两家门店之间说不清的钱）。
     *
     * @return 转过去的报名条数
     */
    int moveOpenApplications(Long jobId, Long fromStoreId, Long toStoreId, OperatorDTO operator);

    /**
     * 岗位被关闭：还没录用的报名一并结束（未审核的按驳回、其余按终止，原因都是「岗位已关闭」）并通知候选人。
     * 已确认录用 / 已签责任书的不动，照常签约、入职；线上任务只结束还没审核的接单申请。
     *
     * @return 结束的报名条数
     */
    int endOpenApplicationsOfClosedJob(Long jobId, OperatorDTO operator);
}
