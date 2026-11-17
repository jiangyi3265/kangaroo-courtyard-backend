package com.ruoyi.kangaroo.dto;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import com.ruoyi.kangaroo.security.StaffGrantView;

/**
 * 下发给小程序的账号对象。
 *
 * 字段名严格对齐小程序 common/experience.js 中 acceptAuthenticatedAccount(account) 的既有用法：
 *   account.id            —— 账号标识，用于本地存储 key 前缀 kangaroo:{id}:xxx
 *   account.staffGrants[] —— 员工授权数组，元素含 id / storeName / roleName / active
 * 前端 hasStaffAccess 依据 staffGrants.some(g => g.active === true) 判定，
 * 因此 active 必须由服务端判定后下发，不能让前端自己推断。
 *
 * 不下发 openid、unionid、sessionKey。
 */
public class AppAccountVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 账号标识（字符串形式的 kg_app_user.user_id） */
    private String id;

    private String nickname;

    private String avatar;

    /** 脱敏手机号，如 186****8888；未绑定为 null */
    private String phone;

    /** 是否已绑定手机号 */
    private Boolean phoneBound;

    /** 手机号是否经过微信 / 短信验证（phone_verified_time 非空）。「成为商家」等页面靠它提示先去验证 */
    private Boolean phoneVerified;

    private String city;

    private List<StaffGrantView> staffGrants = new ArrayList<StaffGrantView>();

    public Boolean getPhoneVerified() { return phoneVerified; }
    public void setPhoneVerified(Boolean phoneVerified) { this.phoneVerified = phoneVerified; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public Boolean getPhoneBound() { return phoneBound; }
    public void setPhoneBound(Boolean phoneBound) { this.phoneBound = phoneBound; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public List<StaffGrantView> getStaffGrants() { return staffGrants; }
    public void setStaffGrants(List<StaffGrantView> staffGrants) { this.staffGrants = staffGrants; }
}
