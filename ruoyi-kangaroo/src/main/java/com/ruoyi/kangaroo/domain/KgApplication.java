package com.ruoyi.kangaroo.domain;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;

import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 岗位报名申请 对象 kg_application
 *
 * 由 scratchpad/gen_domain.py 按 sql/kangaroo/V1__kangaroo_core.sql 的列定义生成，
 * 字段与列一一对应；公共列 create_by/create_time/update_by/update_time/remark 来自 BaseEntity。
 */
public class KgApplication extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 报名ID */
    @Excel(name = "报名ID")
    private Long appId;

    /** 报名单号 */
    @Excel(name = "报名单号")
    private String appNo;

    /** 岗位ID */
    @Excel(name = "岗位ID")
    private Long jobId;

    /** 门店ID */
    @Excel(name = "门店ID")
    private Long storeId;

    /** 报名人账号ID */
    @Excel(name = "账号ID")
    private Long appUserId;

    /** 姓名 */
    @Excel(name = "姓名")
    private String realName;

    /** 年龄 */
    @Excel(name = "年龄")
    private Integer age;

    /** 联系手机号 */
    @Excel(name = "手机号")
    private String phone;

    /** 技能与经历 */
    private String skills;

    /** 预计到岗日期 */
    @Excel(name = "到岗日期", width = 30, dateFormat = "yyyy-MM-dd")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date arrivalDate;

    /** 可停留天数 */
    @Excel(name = "停留天数")
    private Integer stayDays;

    /** 补充说明 */
    private String note;

    /** 申请状态 */
    @Excel(name = "申请状态", readConverterExp = "submitted=已提交,review_passed=审核通过,review_rejected=审核未通过,withdrawn=已撤回,interview_booked=已预约面试,interview_done=面试已完成,user_confirmed=用户已确认,store_confirmed=商家已确认,contract_signed=已签署,onboarded=已入职,terminated=已终止")
    private String status;

    /** 提交时间 */
    @Excel(name = "提交时间", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date submitTime;

    /** 审核时间 */
    @Excel(name = "审核时间", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date auditTime;

    /** 审核人 */
    @Excel(name = "审核人")
    private String auditBy;

    /** 审核人类型（staff/admin） */
    @Excel(name = "审核人类型", readConverterExp = "staff=门店员工,admin=后台管理员")
    private String auditByType;

    /** 未通过原因 */
    @Excel(name = "未通过原因")
    private String rejectReason;

    /** 撤回时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date withdrawTime;

    /** 面试后意向（accept/pending/reject） */
    @Excel(name = "面试后意向", readConverterExp = "accept=愿意前往,pending=还在考虑,reject=不合适")
    private String userIntent;

    /** 用户确认时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date userConfirmTime;

    /** 商家确认时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date storeConfirmTime;

    /** 商家确认截止时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date confirmDeadline;

    /** 终止原因 */
    private String terminateReason;

    /** 入职时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date onboardTime;

    /** 删除标志 */
    private String delFlag;

    /** 岗位标题（关联查询字段，非本表列） */
    @Excel(name = "岗位标题")
    private String jobTitle;

    /** 岗位类型（关联查询字段，用于区分线上接单与到店流程） */
    private String jobType;
    public String getJobType() { return jobType; }
    public void setJobType(String jobType) { this.jobType = jobType; }

    /** 门店名称（关联查询字段，非本表列） */
    @Excel(name = "门店名称")
    private String storeName;

    /** 报名人昵称（关联查询字段，非本表列） */
    @Excel(name = "报名人昵称")
    private String nickname;

    /** 线上任务接单的结算单状态（关联查询字段，kg_online_settlement.status；没有结算单时为空） */
    private String onlineSettleStatus;
    public String getOnlineSettleStatus() { return onlineSettleStatus; }
    public void setOnlineSettleStatus(String onlineSettleStatus) { this.onlineSettleStatus = onlineSettleStatus; }

    public void setAppId(Long appId)
    {
        this.appId = appId;
    }

    public Long getAppId()
    {
        return appId;
    }

    public void setAppNo(String appNo)
    {
        this.appNo = appNo;
    }

    public String getAppNo()
    {
        return appNo;
    }

    public void setJobId(Long jobId)
    {
        this.jobId = jobId;
    }

    public Long getJobId()
    {
        return jobId;
    }

    public void setStoreId(Long storeId)
    {
        this.storeId = storeId;
    }

    public Long getStoreId()
    {
        return storeId;
    }

    public void setAppUserId(Long appUserId)
    {
        this.appUserId = appUserId;
    }

    public Long getAppUserId()
    {
        return appUserId;
    }

    public void setRealName(String realName)
    {
        this.realName = realName;
    }

    public String getRealName()
    {
        return realName;
    }

    public void setAge(Integer age)
    {
        this.age = age;
    }

    public Integer getAge()
    {
        return age;
    }

    public void setPhone(String phone)
    {
        this.phone = phone;
    }

    public String getPhone()
    {
        return phone;
    }

    public void setSkills(String skills)
    {
        this.skills = skills;
    }

    public String getSkills()
    {
        return skills;
    }

    public void setArrivalDate(Date arrivalDate)
    {
        this.arrivalDate = arrivalDate;
    }

    public Date getArrivalDate()
    {
        return arrivalDate;
    }

    public void setStayDays(Integer stayDays)
    {
        this.stayDays = stayDays;
    }

    public Integer getStayDays()
    {
        return stayDays;
    }

    public void setNote(String note)
    {
        this.note = note;
    }

    public String getNote()
    {
        return note;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }

    public String getStatus()
    {
        return status;
    }

    public void setSubmitTime(Date submitTime)
    {
        this.submitTime = submitTime;
    }

    public Date getSubmitTime()
    {
        return submitTime;
    }

    public void setAuditTime(Date auditTime)
    {
        this.auditTime = auditTime;
    }

    public Date getAuditTime()
    {
        return auditTime;
    }

    public void setAuditBy(String auditBy)
    {
        this.auditBy = auditBy;
    }

    public String getAuditBy()
    {
        return auditBy;
    }

    public void setAuditByType(String auditByType)
    {
        this.auditByType = auditByType;
    }

    public String getAuditByType()
    {
        return auditByType;
    }

    public void setRejectReason(String rejectReason)
    {
        this.rejectReason = rejectReason;
    }

    public String getRejectReason()
    {
        return rejectReason;
    }

    public void setWithdrawTime(Date withdrawTime)
    {
        this.withdrawTime = withdrawTime;
    }

    public Date getWithdrawTime()
    {
        return withdrawTime;
    }

    public void setUserIntent(String userIntent)
    {
        this.userIntent = userIntent;
    }

    public String getUserIntent()
    {
        return userIntent;
    }

    public void setUserConfirmTime(Date userConfirmTime)
    {
        this.userConfirmTime = userConfirmTime;
    }

    public Date getUserConfirmTime()
    {
        return userConfirmTime;
    }

    public void setStoreConfirmTime(Date storeConfirmTime)
    {
        this.storeConfirmTime = storeConfirmTime;
    }

    public Date getStoreConfirmTime()
    {
        return storeConfirmTime;
    }

    public void setConfirmDeadline(Date confirmDeadline)
    {
        this.confirmDeadline = confirmDeadline;
    }

    public Date getConfirmDeadline()
    {
        return confirmDeadline;
    }

    public void setTerminateReason(String terminateReason)
    {
        this.terminateReason = terminateReason;
    }

    public String getTerminateReason()
    {
        return terminateReason;
    }

    public void setOnboardTime(Date onboardTime)
    {
        this.onboardTime = onboardTime;
    }

    public Date getOnboardTime()
    {
        return onboardTime;
    }

    public void setDelFlag(String delFlag)
    {
        this.delFlag = delFlag;
    }

    public String getDelFlag()
    {
        return delFlag;
    }

    public void setJobTitle(String jobTitle)
    {
        this.jobTitle = jobTitle;
    }

    public String getJobTitle()
    {
        return jobTitle;
    }

    public void setStoreName(String storeName)
    {
        this.storeName = storeName;
    }

    public String getStoreName()
    {
        return storeName;
    }

    public void setNickname(String nickname)
    {
        this.nickname = nickname;
    }

    public String getNickname()
    {
        return nickname;
    }

}
