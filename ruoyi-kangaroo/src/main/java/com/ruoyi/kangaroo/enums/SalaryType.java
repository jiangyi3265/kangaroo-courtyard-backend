package com.ruoyi.kangaroo.enums;

/** 计薪周期与单位。计件保留由业务填写的单位，如元/篇。 */
public final class SalaryType
{
    public static String unit(String type)
    {
        if ("hourly".equals(type)) return "元/小时";
        if ("daily".equals(type)) return "元/天";
        if ("weekly".equals(type)) return "元/周";
        if ("monthly".equals(type)) return "元/月";
        return null;
    }

    public static boolean isTimeBased(String type)
    {
        return unit(type) != null;
    }

    public static boolean exists(String type)
    {
        return isTimeBased(type) || "piece".equals(type) || "none".equals(type);
    }

    private SalaryType() { }
}
