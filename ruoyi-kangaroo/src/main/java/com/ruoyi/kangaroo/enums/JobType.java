package com.ruoyi.kangaroo.enums;

/** 岗位分类，取值与小程序 /pages/jobs/list?type= 参数一致 */
public final class JobType
{
    /** 换工换宿 */
    public static final String VOLUNTEER = "volunteer";
    /** 兼职旅行 */
    public static final String PARTTIME = "parttime";
    /** 全职旅行 */
    public static final String FULLTIME = "fulltime";
    /** 在线兼职 */
    public static final String ONLINE = "online";

    public static boolean exists(String type)
    {
        return VOLUNTEER.equals(type) || PARTTIME.equals(type) || FULLTIME.equals(type) || ONLINE.equals(type);
    }

    private JobType()
    {
    }
}
