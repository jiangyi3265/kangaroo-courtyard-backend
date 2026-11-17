package com.ruoyi.kangaroo.enums;

/** 岗位状态 */
public final class JobStatus
{
    /** 草稿：小程序端不可见 */
    public static final String DRAFT = "draft";
    /** 招聘中：小程序端可见且可报名 */
    public static final String PUBLISHED = "published";
    /** 暂停：小程序端可见但不可报名 */
    public static final String PAUSED = "paused";
    /** 已关闭：小程序端不可见 */
    public static final String CLOSED = "closed";

    /** 小程序端可见的状态 */
    public static boolean visibleToApp(String status)
    {
        return PUBLISHED.equals(status) || PAUSED.equals(status);
    }

    /** 是否允许报名 */
    public static boolean acceptsApplication(String status)
    {
        return PUBLISHED.equals(status);
    }

    public static boolean exists(String status)
    {
        return DRAFT.equals(status) || PUBLISHED.equals(status) || PAUSED.equals(status) || CLOSED.equals(status);
    }

    private JobStatus()
    {
    }
}
