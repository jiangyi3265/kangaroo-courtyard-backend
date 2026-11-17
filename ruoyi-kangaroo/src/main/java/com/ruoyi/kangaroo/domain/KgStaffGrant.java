package com.ruoyi.kangaroo.domain;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;

import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 员工授权 对象 kg_staff_grant
 *
 * 由 scratchpad/gen_domain.py 按 sql/kangaroo/V1__kangaroo_core.sql 的列定义生成，
 * 字段与列一一对应；公共列 create_by/create_time/update_by/update_time/remark 来自 BaseEntity。
 */
public class KgStaffGrant extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 授权ID */
    @Excel(name = "授权ID")
    private Long grantId;

    /** 小程序账号ID */
    @Excel(name = "账号ID")
    private Long appUserId;

    /** 门店ID */
    @Excel(name = "门店ID")
    private Long storeId;
    /** 独立城市授权，不依赖门店；目前仅城市负责人可使用。 */
    private Long cityId;
    private String city;
    public Long getCityId() { return cityId; }
    public void setCityId(Long cityId) { this.cityId = cityId; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    /** 员工角色（manager/keeper/staff） */
    @Excel(name = "员工角色", readConverterExp = "manager=换工主理人,keeper=小院主理人,staff=员工,online_staff=线上任务专员,merchant=合作商家,merchant_service=商家服务人,city_lead=城市负责人")
    private String roleCode;

    /** 小院主理人兼任商家服务人（1是 0否；别的角色一律 0） */
    @Excel(name = "兼任商家服务人", readConverterExp = "0=否,1=是")
    private String alsoService;

    /** 授权状态（0生效 1停用） */
    @Excel(name = "状态", readConverterExp = "0=正常,1=停用")
    private String status;

    /** 授权人 */
    @Excel(name = "授权人")
    private String grantedBy;

    /** 授权时间 */
    @Excel(name = "授权时间", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date grantedTime;

    /** 撤销人 */
    private String revokedBy;

    /** 撤销时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date revokedTime;

    /** 删除标志 */
    private String delFlag;

    /** 账号昵称（关联查询字段，非本表列） */
    @Excel(name = "账号昵称")
    private String nickname;

    /** 账号手机号（关联查询字段，非本表列） */
    @Excel(name = "账号手机号")
    private String phone;

    /** 门店名称（关联查询字段，非本表列） */
    @Excel(name = "门店名称")
    private String storeName;

    public void setGrantId(Long grantId)
    {
        this.grantId = grantId;
    }

    public Long getGrantId()
    {
        return grantId;
    }

    public void setAppUserId(Long appUserId)
    {
        this.appUserId = appUserId;
    }

    public Long getAppUserId()
    {
        return appUserId;
    }

    public void setStoreId(Long storeId)
    {
        this.storeId = storeId;
    }

    public Long getStoreId()
    {
        return storeId;
    }

    public void setRoleCode(String roleCode)
    {
        this.roleCode = roleCode;
    }

    public String getRoleCode()
    {
        return roleCode;
    }

    public void setAlsoService(String alsoService)
    {
        this.alsoService = alsoService;
    }

    public String getAlsoService()
    {
        return alsoService;
    }

    /** 下发给小程序、判权限用：只有角色还是小院主理人、开关开着才算（不用 is 前缀，免得多序列化一个字段给后台） */
    public boolean alsoServiceOn()
    {
        return "keeper".equals(roleCode) && "1".equals(alsoService);
    }

    public void setStatus(String status)
    {
        this.status = status;
    }

    public String getStatus()
    {
        return status;
    }

    public void setGrantedBy(String grantedBy)
    {
        this.grantedBy = grantedBy;
    }

    public String getGrantedBy()
    {
        return grantedBy;
    }

    public void setGrantedTime(Date grantedTime)
    {
        this.grantedTime = grantedTime;
    }

    public Date getGrantedTime()
    {
        return grantedTime;
    }

    public void setRevokedBy(String revokedBy)
    {
        this.revokedBy = revokedBy;
    }

    public String getRevokedBy()
    {
        return revokedBy;
    }

    public void setRevokedTime(Date revokedTime)
    {
        this.revokedTime = revokedTime;
    }

    public Date getRevokedTime()
    {
        return revokedTime;
    }

    public void setDelFlag(String delFlag)
    {
        this.delFlag = delFlag;
    }

    public String getDelFlag()
    {
        return delFlag;
    }

    public void setNickname(String nickname)
    {
        this.nickname = nickname;
    }

    public String getNickname()
    {
        return nickname;
    }

    public void setPhone(String phone)
    {
        this.phone = phone;
    }

    public String getPhone()
    {
        return phone;
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
