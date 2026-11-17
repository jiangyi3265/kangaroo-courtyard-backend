package com.ruoyi.kangaroo.security;

import java.io.Serializable;

/**
 * 员工授权快照。下发给小程序的字段名与 pages/auth/identity.vue 中
 * experience.account.staffGrants 的既有用法保持一致：id / storeName / roleName / active。
 */
public class StaffGrantView implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** kg_staff_grant.grant_id */
    private Long id;

    private Long storeId;
    private Long cityId;
    private String city;
    public Long getCityId() { return cityId; }
    public void setCityId(Long cityId) { this.cityId = cityId; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    private String storeName;

    /** 员工角色编码：manager 主理人 / keeper 门店管家 / staff 员工 */
    private String roleCode;

    private String roleName;

    /** 前端 hasStaffAccess 依据该字段，必须由服务端判定后下发 */
    private Boolean active;

    /** 小院主理人兼任商家服务人（kg_staff_grant.also_service = 1）；别的角色永远 false */
    private Boolean alsoService;

    /** 切换后的城市负责人：本城全部门店都有全部权限（CityLeadScope 算的）。小程序看到它才开完整工作台，老后端不给这个字段 */
    private Boolean fullAccess;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStoreId() { return storeId; }
    public void setStoreId(Long storeId) { this.storeId = storeId; }
    public String getStoreName() { return storeName; }
    public void setStoreName(String storeName) { this.storeName = storeName; }
    public String getRoleCode() { return roleCode; }
    public void setRoleCode(String roleCode) { this.roleCode = roleCode; }
    public String getRoleName() { return roleName; }
    public void setRoleName(String roleName) { this.roleName = roleName; }
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
    public Boolean getAlsoService() { return alsoService; }
    public void setAlsoService(Boolean alsoService) { this.alsoService = alsoService; }
    public Boolean getFullAccess() { return fullAccess; }
    public void setFullAccess(Boolean fullAccess) { this.fullAccess = fullAccess; }
}
