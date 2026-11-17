package com.ruoyi.kangaroo.security;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.domain.KgAppUser;
import com.ruoyi.kangaroo.domain.KgStaffGrant;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.mapper.KgAppUserMapper;
import com.ruoyi.kangaroo.mapper.KgStaffGrantMapper;

/** Redis 只证明会话存在；每次请求从已提交数据库状态重读账号与角色。 */
@Component
public class AppIdentityLoader
{
    private final KgAppUserMapper users;
    private final KgStaffGrantMapper grants;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ruoyi.kangaroo.ext.staff.CityLeadScope cityLeads;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ruoyi.kangaroo.ext.staff.CityGrantScope cityGrants;
    public AppIdentityLoader(KgAppUserMapper users, KgStaffGrantMapper grants)
    {
        this.users = users; this.grants = grants;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<StaffGrantView> grants(Long userId)
    {
        List<StaffGrantView> result = new ArrayList<StaffGrantView>();
        for (KgStaffGrant grant : grants.selectActiveGrantsWithStore(userId))
        {
            if (!com.ruoyi.kangaroo.ext.staff.RolePhase.usable(grant.getRoleCode()))
            {
                continue;
            }
            StaffGrantView view = new StaffGrantView();
            view.setId(grant.getGrantId()); view.setStoreId(grant.getStoreId());
            view.setCityId(grant.getCityId()); view.setCity(grant.getCity());
            view.setStoreName(grant.getStoreName()); view.setRoleCode(grant.getRoleCode());
            view.setRoleName(StaffRole.label(grant.getRoleCode())); view.setActive(true);
            view.setAlsoService(grant.alsoServiceOn());
            result.add(view);
        }
        if (cityGrants != null) cityGrants.expand(userId, result);
        if (cityLeads != null)
        {
            cityLeads.expand(userId, result);
        }
        return result;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public void reload(AppLoginUser session)
    {
        KgAppUser user = users.selectKgAppUserByUserId(session.getUserId());
        if (user == null || "2".equals(user.getDelFlag())) throw new ServiceException("账号不存在", 401);
        if (!"0".equals(user.getStatus())) throw new ServiceException("账号已被停用，请联系客服", 403);
        session.setPhone(user.getPhone()); session.setNickname(user.getNickname()); session.setAvatar(user.getAvatar());
        session.setStaffGrants(grants(session.getUserId()));
    }
}
