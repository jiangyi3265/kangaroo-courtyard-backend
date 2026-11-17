package com.ruoyi.kangaroo.security;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 小程序端登录态，缓存在 Redis 中（key 前缀 kangaroo:app_login_tokens:）。
 * 与管理后台的 com.ruoyi.common.core.domain.model.LoginUser 完全隔离。
 */
public class AppLoginUser implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 登录令牌 uuid（写入 JWT 的 claim，不直接下发） */
    private String token;

    /** kg_app_user.user_id */
    private Long userId;

    /** 微信 openid */
    private String openid;

    /** 微信 unionid */
    private String unionid;

    private String nickname;

    private String avatar;

    /** 手机号（仅后端使用，下发前需脱敏） */
    private String phone;

    /** 登录时间戳 */
    private Long loginTime;

    /** 过期时间戳 */
    private Long expireTime;

    private String loginIp;

    /** 首次登录时间戳（loginTime 每次续期都会刷新，这个不会） */
    private Long createTime;

    /** 登录端（微信小程序 / 网页端等），用于"设备与登录"列表 */
    private String device;

    /** 生效中的员工授权快照 */
    private List<StaffGrantView> staffGrants = new ArrayList<StaffGrantView>();

    public boolean hasStaffAccess()
    {
        return staffGrants != null && !staffGrants.isEmpty();
    }

    /** 判断是否对指定门店具备员工授权 */
    public boolean hasStoreAccess(Long storeId)
    {
        if (storeId == null || staffGrants == null)
        {
            return false;
        }
        for (StaffGrantView g : staffGrants)
        {
            if (storeId.equals(g.getStoreId()) && !com.ruoyi.kangaroo.enums.StaffRole.ONLINE_STAFF.equals(g.getRoleCode())
                    && !com.ruoyi.kangaroo.enums.StaffRole.MERCHANT_SERVICE.equals(g.getRoleCode())
                    && (!com.ruoyi.kangaroo.enums.StaffRole.CITY_LEAD.equals(g.getRoleCode()) || com.ruoyi.kangaroo.ext.staff.RolePhase.isNew()))
            {
                return true;
            }
        }
        return false;
    }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getOpenid() { return openid; }
    public void setOpenid(String openid) { this.openid = openid; }
    public String getUnionid() { return unionid; }
    public void setUnionid(String unionid) { this.unionid = unionid; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public Long getLoginTime() { return loginTime; }
    public void setLoginTime(Long loginTime) { this.loginTime = loginTime; }
    public Long getExpireTime() { return expireTime; }
    public void setExpireTime(Long expireTime) { this.expireTime = expireTime; }
    public String getLoginIp() { return loginIp; }
    public void setLoginIp(String loginIp) { this.loginIp = loginIp; }
    public Long getCreateTime() { return createTime; }
    public void setCreateTime(Long createTime) { this.createTime = createTime; }
    public String getDevice() { return device; }
    public void setDevice(String device) { this.device = device; }
    public List<StaffGrantView> getStaffGrants() { return staffGrants; }
    public void setStaffGrants(List<StaffGrantView> staffGrants) { this.staffGrants = staffGrants; }
}
