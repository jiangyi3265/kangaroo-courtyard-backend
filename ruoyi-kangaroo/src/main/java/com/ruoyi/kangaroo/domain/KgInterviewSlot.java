package com.ruoyi.kangaroo.domain;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;

import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 面试时段 对象 kg_interview_slot
 *
 * 由 scratchpad/gen_domain.py 按 sql/kangaroo/V1__kangaroo_core.sql 的列定义生成，
 * 字段与列一一对应；公共列 create_by/create_time/update_by/update_time/remark 来自 BaseEntity。
 */
public class KgInterviewSlot extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    private String meetingInfo;
    public String getMeetingInfo() { return meetingInfo; }
    public void setMeetingInfo(String value) { meetingInfo=value; }

    /** 时段ID */
    @Excel(name = "时段ID")
    private Long slotId;

    /** 门店ID */
    @Excel(name = "门店ID")
    private Long storeId;

    /** 限定岗位 */
    private Long jobId;

    /** 开始时间 */
    @Excel(name = "开始时间", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date startTime;

    /** 结束时间 */
    @Excel(name = "结束时间", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date endTime;

    /** 状态（open/booked/closed） */
    @Excel(name = "状态", readConverterExp = "open=可选,booked=已占用,closed=已关闭")
    private String status;

    /** 占用该时段的报名ID */
    private Long bookedAppId;

    /** 删除标志 */
    private String delFlag;

    /** 门店名称（关联查询字段，非本表列） */
    @Excel(name = "门店名称")
    private String storeName;

    public void setSlotId(Long slotId)
    {
        this.slotId = slotId;
    }

    public Long getSlotId()
    {
        return slotId;
    }

    public void setStoreId(Long storeId)
    {
        this.storeId = storeId;
    }

    public Long getStoreId()
    {
        return storeId;
    }

    public void setJobId(Long jobId)
    {
        this.jobId = jobId;
    }

    public Long getJobId()
    {
        return jobId;
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

    public void setBookedAppId(Long bookedAppId)
    {
        this.bookedAppId = bookedAppId;
    }

    public Long getBookedAppId()
    {
        return bookedAppId;
    }

    public void setDelFlag(String delFlag)
    {
        this.delFlag = delFlag;
    }

    public String getDelFlag()
    {
        return delFlag;
    }

    public void setStoreName(String storeName)
    {
        this.storeName = storeName;
    }

    public String getStoreName()
    {
        return storeName;
    }

}
