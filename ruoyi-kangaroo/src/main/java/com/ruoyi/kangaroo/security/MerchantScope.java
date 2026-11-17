package com.ruoyi.kangaroo.security;
import java.util.ArrayList;
import com.ruoyi.kangaroo.enums.StaffRole;
import org.springframework.beans.BeanUtils;
/** 请求级授权副本：不能以其他门店的主理人身份借用商家门店权限。 */
public final class MerchantScope {
    private MerchantScope() {}
    public static AppLoginUser withoutMerchantGrants(AppLoginUser user) {
        return withoutRole(user, StaffRole.MERCHANT);
    }
    /** 去掉某个角色的授权；没有这个角色时原样返回同一个对象，不污染缓存里的完整授权。 */
    public static AppLoginUser withoutRole(AppLoginUser user, String role) {
        if (user.getStaffGrants().stream().noneMatch(g -> role.equals(g.getRoleCode()))) return user;
        AppLoginUser copy = new AppLoginUser();
        BeanUtils.copyProperties(user, copy);
        ArrayList<StaffGrantView> grants = new ArrayList<>();
        for (StaffGrantView grant : user.getStaffGrants())
            if (!role.equals(grant.getRoleCode())) grants.add(grant);
        copy.setStaffGrants(grants);
        return copy;
    }
}
