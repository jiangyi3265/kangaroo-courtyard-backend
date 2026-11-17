package com.ruoyi.kangaroo.domain;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;

import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 小程序账号 对象 kg_app_user
 *
 * 由 scratchpad/gen_domain.py 按 sql/kangaroo/V1__kangaroo_core.sql 的列定义生成，
 * 字段与列一一对应；公共列 create_by/create_time/update_by/update_time/remark 来自 BaseEntity。
 */
public class KgAppUser extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 账号ID */
    @Excel(name = "账号ID")
    private Long userId;

    /** 微信 openid */
    private String openid;

    /** 微信 unionid */
    private String unionid;

    /** 昵称 */
    @Excel(name = "昵称")
    private String nickname;

    /** 头像 */
    private String avatar;

    /** 手机号 */
    @Excel(name = "手机号")
    private String phone;

    /** 真实姓名 */
    @Excel(name = "真实姓名")
    private String realName;

    /** 性别（0未知 1男 2女） */
    @Excel(name = "性别", readConverterExp = "0=未知,1=男,2=女")
    private String gender;

    /** 常驻城市 */
    @Excel(name = "常驻城市")
    private String city;

    /** 状态（0正常 1停用） */
    @Excel(name = "状态", readConverterExp = "0=正常,1=停用")
    private String status;

    /** 注册时间 */
    @Excel(name = "注册时间", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date registerTime;

    /** 最后登录时间 */
    @Excel(name = "最后登录时间", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date lastLoginTime;

    /** 最后登录IP */
    private String lastLoginIp;

    /** 删除标志 */
    private String delFlag;

    public void setUserId(Long userId)
    {
        this.userId = userId;
    }

    public Long getUserId()
    {
        return userId;
    }

    public void setOpenid(String openid)
    {
        this.openid = openid;
    }

    public String getOpenid()
    {
        return openid;
    }

    public void setUnionid(String unionid)
    {
        this.unionid = unionid;
    }

    public String getUnionid()
    {
        return unionid;
    }

    public void setNickname(String nickname)
    {
        this.nickname = nickname;
    }

    public String getNickname()
    {
        return nickname;
    }

    public void setAvatar(String avatar)
    {
        this.avatar = avatar;
    }

    public String getAvatar()
    {
        return avatar;
    }

    public void setPhone(String phone)
    {
        this.phone = phone;
    }

    public String getPhone()
    {
        return phone;
    }

    public void setRealName(String realName)
    {
        this.realName = realName;
    }

    public String getRealName()
    {
        return realName;
    }

    public void setGender(String gender)
    {
        this.gender = gender;
    }

    public String getGender()
    {
        return gender;
    }

    public void setCity(String city)
    {
        this.city = city;
    }

    public String getCity()
    {
        return city;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }

    public String getStatus()
    {
        return status;
    }

    public void setRegisterTime(Date registerTime)
    {
        this.registerTime = registerTime;
    }

    public Date getRegisterTime()
    {
        return registerTime;
    }

    public void setLastLoginTime(Date lastLoginTime)
    {
        this.lastLoginTime = lastLoginTime;
    }

    public Date getLastLoginTime()
    {
        return lastLoginTime;
    }

    public void setLastLoginIp(String lastLoginIp)
    {
        this.lastLoginIp = lastLoginIp;
    }

    public String getLastLoginIp()
    {
        return lastLoginIp;
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
