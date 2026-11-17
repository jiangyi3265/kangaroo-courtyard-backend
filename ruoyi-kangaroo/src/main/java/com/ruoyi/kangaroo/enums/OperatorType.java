package com.ruoyi.kangaroo.enums;

/** 状态流转的发起方类型 */
public final class OperatorType
{
    /** 小程序 C 端用户本人 */
    public static final String USER = "user";
    /** 小程序端具备门店员工授权的账号 */
    public static final String STAFF = "staff";
    /** 管理后台账号（sys_user） */
    public static final String ADMIN = "admin";
    /** 系统自动（超时终止等） */
    public static final String SYSTEM = "system";

    private OperatorType()
    {
    }
}
