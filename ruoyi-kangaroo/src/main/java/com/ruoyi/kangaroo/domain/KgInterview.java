package com.ruoyi.kangaroo.domain;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;

import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 面试预约 对象 kg_interview
 *
 * 由 scratchpad/gen_domain.py 按 sql/kangaroo/V1__kangaroo_core.sql 的列定义生成，
 * 字段与列一一对应；公共列 create_by/create_time/update_by/update_time/remark 来自 BaseEntity。
 */
public class KgInterview extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 面试ID */
    private Long interviewId;

    /** 报名ID */
    private Long appId;

    /** 时段ID */
    private Long slotId;

    /** 面试开始时间 */
    @Excel(name = "开始时间", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date startTime;

    /** 面试结束时间 */
    @Excel(name = "结束时间", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date endTime;

    /** 状态（booked/done/canceled） */
    @Excel(name = "状态")
    private String status;

    /** 实际结束时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date finishTime;

    /** 通话时长（秒） */
    private Integer durationSeconds;

    /** 面试后确认：岗位理解 */
    private String understandJob;

    /** 面试后确认：食宿条件 */
    private String acceptBoard;

    /** 面试后确认：到岗日期 */
    private String confirmArrival;

    /** 面试后备注 */
    private String feedbackNote;

    /** 删除标志 */
    private String delFlag;

    /** 用户申请改期的次数 */
    private Integer rescheduleCount;

    /** 最近一次改期时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date rescheduledTime;

    /** 员工侧面试评分 */
    private Integer staffRating;

    /** 员工侧面试记录 */
    private String staffNote;

    public Integer getRescheduleCount()
    {
        return rescheduleCount;
    }

    public void setRescheduleCount(Integer rescheduleCount)
    {
        this.rescheduleCount = rescheduleCount;
    }

    public Date getRescheduledTime()
    {
        return rescheduledTime;
    }

    public void setRescheduledTime(Date rescheduledTime)
    {
        this.rescheduledTime = rescheduledTime;
    }

    public Integer getStaffRating()
    {
        return staffRating;
    }

    public void setStaffRating(Integer staffRating)
    {
        this.staffRating = staffRating;
    }

    public String getStaffNote()
    {
        return staffNote;
    }

    public void setStaffNote(String staffNote)
    {
        this.staffNote = staffNote;
    }

    public void setInterviewId(Long interviewId)
    {
        this.interviewId = interviewId;
    }

    public Long getInterviewId()
    {
        return interviewId;
    }

    public void setAppId(Long appId)
    {
        this.appId = appId;
    }

    public Long getAppId()
    {
        return appId;
    }

    public void setSlotId(Long slotId)
    {
        this.slotId = slotId;
    }

    public Long getSlotId()
    {
        return slotId;
    }

    public void setStartTime(Date startTime)
    {
        this.startTime = startTime;
    }

    public Date getStartTime()
    {
        return startTime;
    }

    public void setEndTime(Date endTime)
    {
        this.endTime = endTime;
    }

    public Date getEndTime()
    {
        return endTime;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }

    public String getStatus()
    {
        return status;
    }

    public void setFinishTime(Date finishTime)
    {
        this.finishTime = finishTime;
    }

    public Date getFinishTime()
    {
        return finishTime;
    }

    public void setDurationSeconds(Integer durationSeconds)
    {
        this.durationSeconds = durationSeconds;
    }

    public Integer getDurationSeconds()
    {
        return durationSeconds;
    }

    public void setUnderstandJob(String understandJob)
    {
        this.understandJob = understandJob;
    }

    public String getUnderstandJob()
    {
        return understandJob;
    }

    public void setAcceptBoard(String acceptBoard)
    {
        this.acceptBoard = acceptBoard;
    }

    public String getAcceptBoard()
    {
        return acceptBoard;
    }

    public void setConfirmArrival(String confirmArrival)
    {
        this.confirmArrival = confirmArrival;
    }

    public String getConfirmArrival()
    {
        return confirmArrival;
    }

    public void setFeedbackNote(String feedbackNote)
    {
        this.feedbackNote = feedbackNote;
    }

    public String getFeedbackNote()
    {
        return feedbackNote;
    }

    public void setDelFlag(String delFlag)
    {
        this.delFlag = delFlag;
    }

    public String getDelFlag()
    {
        return delFlag;
    }

}
