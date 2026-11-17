package com.ruoyi.kangaroo.dto;

import java.io.Serializable;

import com.ruoyi.kangaroo.enums.OperatorType;

/**
 * 状态流转的操作人。
 *
 * 由各端 Controller 从各自的登录上下文构造，业务层不自己去猜是谁在操作，
 * 也不接受请求参数里传入的操作人。
 */
public class OperatorDTO implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 见 OperatorType */
    private final String type;

    private final String id;

    private final String name;

    /** 门店员工操作时的门店ID；管理后台为 null（后台可跨门店） */
    private final Long storeId;

    public OperatorDTO(String type, String id, String name, Long storeId)
    {
        this.type = type;
        this.id = id;
        this.name = name;
        this.storeId = storeId;
    }

    /** 小程序 C 端用户本人 */
    public static OperatorDTO user(Long appUserId, String nickname)
    {
        return new OperatorDTO(OperatorType.USER, String.valueOf(appUserId), nickname, null);
    }

    /** 小程序端门店员工 */
    public static OperatorDTO staff(Long appUserId, String nickname, Long storeId)
    {
        return new OperatorDTO(OperatorType.STAFF, String.valueOf(appUserId), nickname, storeId);
    }

    /** 管理后台账号 */
    public static OperatorDTO admin(String username)
    {
        return new OperatorDTO(OperatorType.ADMIN, username, username, null);
    }

    /** 系统自动 */
    public static OperatorDTO system()
    {
        return new OperatorDTO(OperatorType.SYSTEM, "system", "系统", null);
    }

    public String getType() { return type; }
    public String getId() { return id; }
    public String getName() { return name; }
    public Long getStoreId() { return storeId; }
}
