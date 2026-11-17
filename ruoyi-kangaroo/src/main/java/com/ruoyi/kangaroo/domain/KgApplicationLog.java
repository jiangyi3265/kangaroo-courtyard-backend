package com.ruoyi.kangaroo.domain;

import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 报名状态流转日志 对象 kg_application_log
 *
 * 由 scratchpad/gen_domain.py 按 sql/kangaroo/V1__kangaroo_core.sql 的列定义生成，
 * 字段与列一一对应；公共列 create_by/create_time/update_by/update_time/remark 来自 BaseEntity。
 */
public class KgApplicationLog extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 日志ID */
    private Long logId;

    /** 报名ID */
    private Long appId;

    /** 原状态 */
    @Excel(name = "原状态")
    private String fromStatus;

    /** 新状态 */
    @Excel(name = "新状态")
    private String toStatus;

    /** 操作方（user/staff/admin/system） */
    @Excel(name = "操作方")
    private String operatorType;

    /** 操作人标识 */
    private String operatorId;

    /** 操作人名称 */
    @Excel(name = "操作人")
    private String operatorName;

    /** 原因 */
    @Excel(name = "原因")
    private String reason;

    public void setLogId(Long logId)
    {
        this.logId = logId;
    }

    public Long getLogId()
    {
        return logId;
    }

    public void setAppId(Long appId)
    {
        this.appId = appId;
    }

    public Long getAppId()
    {
        return appId;
    }

    public void setFromStatus(String fromStatus)
    {
        this.fromStatus = fromStatus;
    }

    public String getFromStatus()
    {
        return fromStatus;
    }

    public void setToStatus(String toStatus)
    {
        this.toStatus = toStatus;
    }

    public String getToStatus()
    {
        return toStatus;
    }

    public void setOperatorType(String operatorType)
    {
        this.operatorType = operatorType;
    }

    public String getOperatorType()
    {
        return operatorType;
    }

    public void setOperatorId(String operatorId)
    {
        this.operatorId = operatorId;
    }

    public String getOperatorId()
    {
        return operatorId;
    }

    public void setOperatorName(String operatorName)
    {
        this.operatorName = operatorName;
    }

    public String getOperatorName()
    {
        return operatorName;
    }

    public void setReason(String reason)
    {
        this.reason = reason;
    }

    public String getReason()
    {
        return reason;
    }

}
