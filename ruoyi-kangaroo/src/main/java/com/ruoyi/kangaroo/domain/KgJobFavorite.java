package com.ruoyi.kangaroo.domain;

import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 岗位收藏 对象 kg_job_favorite
 *
 * 由 scratchpad/gen_domain.py 按 sql/kangaroo/V1__kangaroo_core.sql 的列定义生成，
 * 字段与列一一对应；公共列 create_by/create_time/update_by/update_time/remark 来自 BaseEntity。
 */
public class KgJobFavorite extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 收藏ID */
    private Long favId;

    /** 账号ID */
    private Long appUserId;

    /** 岗位ID */
    private Long jobId;

    public void setFavId(Long favId)
    {
        this.favId = favId;
    }

    public Long getFavId()
    {
        return favId;
    }

    public void setAppUserId(Long appUserId)
    {
        this.appUserId = appUserId;
    }

    public Long getAppUserId()
    {
        return appUserId;
    }

    public void setJobId(Long jobId)
    {
        this.jobId = jobId;
    }

    public Long getJobId()
    {
        return jobId;
    }

}
