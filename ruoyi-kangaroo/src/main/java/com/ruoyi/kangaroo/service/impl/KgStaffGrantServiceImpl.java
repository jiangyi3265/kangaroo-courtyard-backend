package com.ruoyi.kangaroo.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.kangaroo.domain.KgAppUser;
import com.ruoyi.kangaroo.domain.KgStaffGrant;
import com.ruoyi.kangaroo.domain.KgStore;
import com.ruoyi.kangaroo.enums.MessageCategory;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.staff.KgMerchantRequestService;
import com.ruoyi.kangaroo.ext.staff.RolePhase;
import com.ruoyi.kangaroo.ext.staff.RolePhaseGuard;
import com.ruoyi.kangaroo.mapper.KgStaffGrantMapper;
import com.ruoyi.kangaroo.security.AppTokenService;
import com.ruoyi.kangaroo.security.StaffGrantView;
import com.ruoyi.kangaroo.service.IKgAppUserService;
import com.ruoyi.kangaroo.service.IKgMessageService;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;
import com.ruoyi.kangaroo.service.IKgStoreService;

@Service
public class KgStaffGrantServiceImpl implements IKgStaffGrantService
{
    /** 授权生效 */
    private static final String STATUS_ACTIVE = "0";

    /** 授权已撤销 */
    private static final String STATUS_REVOKED = "1";

    /** 逻辑删除标记 */
    private static final String DEL_FLAG_DELETED = "2";

    /** 小程序账号已停用 */
    private static final String APP_USER_DISABLED = "1";

    @Autowired
    private KgStaffGrantMapper kgStaffGrantMapper;

    @Autowired
    private com.ruoyi.kangaroo.ext.KgJdbc db;

    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.staff.CityLeadScope cityLeads;
    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.staff.CityGrantScope cityGrants;

    @Autowired
    private IKgStoreService kgStoreService;

    /** 登录流程会反过来读授权，用 @Lazy 断开可能的构建期循环依赖 */
    @Lazy
    @Autowired
    private IKgAppUserService kgAppUserService;

    @Autowired
    private AppTokenService appTokenService;

    @Autowired
    private IKgMessageService messages;

    /** 反过来依赖授权服务（@Lazy 断开）；单测没注入时不收回系统补的授权 */
    @Lazy
    @Autowired(required = false)
    private KgMerchantRequestService merchantRequests;

    /** 写授权前核对阶段；单测没注入时不查库 */
    @Autowired(required = false)
    private RolePhaseGuard phaseGuard;

    /** 授权通知（用户身份）点开去「员工入口」：重读一次授权，列出门店与角色，由本人点「进入员工工作台」 */
    private static final String IDENTITY_ROUTE = "/pages/auth/identity?choose=1";

    @Override
    public List<StaffGrantView> loadActiveGrantViews(Long appUserId)
    {
        List<StaffGrantView> views = new ArrayList<StaffGrantView>();
        if (appUserId == null)
        {
            return views;
        }
        List<KgStaffGrant> grants = kgStaffGrantMapper.selectActiveGrantsWithStore(appUserId);
        for (KgStaffGrant grant : grants)
        {
            if (!RolePhase.usable(grant.getRoleCode()))
            {
                continue;
            }
            StaffGrantView view = new StaffGrantView();
            view.setId(grant.getGrantId());
            view.setStoreId(grant.getStoreId());
            view.setCityId(grant.getCityId()); view.setCity(grant.getCity());
            view.setStoreName(grant.getStoreName());
            view.setRoleCode(grant.getRoleCode());
            view.setRoleName(StaffRole.label(grant.getRoleCode()));
            // 查询已按授权状态、删除标记与门店状态过滤，能走到这里的必然生效
            view.setActive(Boolean.TRUE);
            view.setAlsoService(grant.alsoServiceOn());
            views.add(view);
        }
        if (cityGrants != null) cityGrants.expand(appUserId, views);
        if (cityLeads != null)
        {
            cityLeads.expand(appUserId, views);
        }
        return views;
    }

    @Override
    public List<KgStaffGrant> selectKgStaffGrantList(KgStaffGrant kgStaffGrant)
    {
        return kgStaffGrantMapper.selectKgStaffGrantList(kgStaffGrant);
    }

    @Override
    public KgStaffGrant selectKgStaffGrantByGrantId(Long grantId)
    {
        return kgStaffGrantMapper.selectKgStaffGrantByGrantId(grantId);
    }

    @Override
    public KgStaffGrant requireGrant(Long grantId)
    {
        if (grantId == null)
        {
            throw new ServiceException("授权ID不能为空");
        }
        KgStaffGrant grant = kgStaffGrantMapper.selectKgStaffGrantByGrantId(grantId);
        if (grant == null)
        {
            throw new ServiceException("授权记录不存在或已删除");
        }
        return grant;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int grant(KgStaffGrant kgStaffGrant)
    {
        checkPhase();
        Long appUserId = kgStaffGrant.getAppUserId();
        Long storeId = kgStaffGrant.getStoreId();
        if (appUserId == null)
        {
            throw new ServiceException("小程序账号ID不能为空");
        }
        // 门店与账号都必须真实存在，否则会造出永远无法生效的僵尸授权
        Long cityId = kgStaffGrant.getCityId();
        if (cityId != null && storeId != null) throw new ServiceException("城市授权与门店授权只能选择一种");
        if (cityId != null && !StaffRole.exists(kgStaffGrant.getRoleCode())) throw new ServiceException("城市授权请选择有效员工角色");
        String cityName = null;
        if (cityId != null) {
            java.util.Map<String,Object> city = db.one("select name from kg_city where city_id=:id and status='0'", com.ruoyi.kangaroo.ext.KgJdbc.p("id", cityId));
            cityName = city == null || city.get("name") == null ? "" : String.valueOf(city.get("name")).trim();
            if (cityName.isEmpty() || "全国".equals(cityName) || "不限".equals(cityName) || "全部城市".equals(cityName)) throw new ServiceException("请选择已启用的具体城市");
        }
        KgStore store = cityId == null ? kgStoreService.requireStore(storeId) : null;
        KgAppUser appUser = kgAppUserService.requireUser(appUserId);
        // requireUser 已拦停用账号，这里再兜一道：员工身份是安全边界，不把判断寄托在上游实现细节上
        if (APP_USER_DISABLED.equals(appUser.getStatus()))
        {
            throw new ServiceException("账号已停用，不能授予员工身份");
        }
        // 切换前不传角色还是按员工算（行为不变）；切换后员工退役，必须选一个，不能默默落到某个角色上
        if (RolePhase.isNew() && StringUtils.isEmpty(kgStaffGrant.getRoleCode()))
        {
            throw new ServiceException("请选择员工角色");
        }
        String roleCode = StringUtils.isEmpty(kgStaffGrant.getRoleCode()) ? StaffRole.STAFF : kgStaffGrant.getRoleCode();
        if (!StaffRole.exists(roleCode))
        {
            throw new ServiceException("员工角色不合法：" + roleCode);
        }
        requireNotRetired(roleCode);

        // 兼任只有小院主理人能开，别的角色传了也存 0
        String alsoService = alsoServiceValue(roleCode, kgStaffGrant.getAlsoService());

        String operator = SecurityUtils.getUsername();
        int rows;
        KgStaffGrant exists = cityId == null ? kgStaffGrantMapper.selectByUserAndStore(appUserId, storeId) : kgStaffGrantMapper.selectByUserAndCity(appUserId, cityId);
        if (exists != null)
        {
            // uk_grant_user_store 对已撤销、已逻辑删除的行同样生效，再 insert 必然撞唯一键，只能就地复活
            if (STATUS_ACTIVE.equals(exists.getStatus()) && !DEL_FLAG_DELETED.equals(exists.getDelFlag()))
            {
                throw new ServiceException("该账号在此范围已有生效授权，如需调整请使用修改角色");
            }
            // reactivateGrant 一律把兼任清成 0（撤销前开过的不能跟着复活），这次勾了再写一次
            rows = kgStaffGrantMapper.reactivateGrant(exists.getGrantId(), roleCode, operator);
            kgStaffGrant.setGrantId(exists.getGrantId());
            if ("1".equals(alsoService))
            {
                KgStaffGrant also = new KgStaffGrant();
                also.setGrantId(exists.getGrantId());
                also.setAlsoService(alsoService);
                also.setUpdateBy(operator);
                kgStaffGrantMapper.updateKgStaffGrant(also);
            }
        }
        else
        {
            kgStaffGrant.setRoleCode(roleCode);
            kgStaffGrant.setAlsoService(alsoService);
            // 状态与授权/撤销痕迹一律由服务端写入，不接受前端传值
            kgStaffGrant.setStatus(STATUS_ACTIVE);
            kgStaffGrant.setGrantedBy(operator);
            kgStaffGrant.setGrantedTime(new Date());
            kgStaffGrant.setRevokedBy(null);
            kgStaffGrant.setRevokedTime(null);
            kgStaffGrant.setCreateBy(operator);
            rows = kgStaffGrantMapper.insertKgStaffGrant(kgStaffGrant);
        }
        refreshAppSession(appUserId);
        // 之前后台授权一条通知都不发，新员工不知道自己已经有了员工身份
        notifyGrantChange(appUserId, storeId, "你已获得员工身份", "平台已授予你「" + (cityId == null ? storeLabel(store.getStoreName()) : cityName) + "」的「"
                + StaffRole.label(roleCode) + ("1".equals(alsoService) ? " + " + StaffRole.label(StaffRole.MERCHANT_SERVICE) : "") + "」角色，切换到员工身份即可使用工作台。", kgStaffGrant.getGrantId());
        return rows;
    }

    private void checkPhase()
    {
        if (phaseGuard != null)
        {
            phaseGuard.beforeGrantWrite();
        }
    }

    /** 切换后「员工」不能再选（打开着的老后台页面、直接调接口都到这里） */
    private static void requireNotRetired(String roleCode)
    {
        if (RolePhase.isNew() && StaffRole.STAFF.equals(roleCode))
        {
            throw new ServiceException("「员工」角色已取消，请选择换工主理人或别的角色");
        }
    }

    /** 兼任开关落库的值：不是小院主理人一律 0；后台页面传 "1"，接口直接调的传 true 也认 */
    private static String alsoServiceValue(String roleCode, String raw)
    {
        return StaffRole.KEEPER.equals(roleCode) && ("1".equals(raw) || "true".equalsIgnoreCase(raw)) ? "1" : "0";
    }

    /** 关掉兼任、撤销、换角色之后：系统在商家店补的商家服务人、换工主理人授权一起收回（要在刷新会话之前） */
    private void releaseServiceGrants(Long appUserId)
    {
        if (merchantRequests != null)
        {
            merchantRequests.releaseServiceGrants(appUserId);
            merchantRequests.releaseManagerGrants(appUserId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateRole(KgStaffGrant kgStaffGrant)
    {
        checkPhase();
        KgStaffGrant current = requireGrant(kgStaffGrant.getGrantId());
        if (!StaffRole.exists(kgStaffGrant.getRoleCode()))
        {
            throw new ServiceException("员工角色不合法：" + kgStaffGrant.getRoleCode());
        }
        requireNotRetired(kgStaffGrant.getRoleCode());
        if (STATUS_REVOKED.equals(current.getStatus()))
        {
            throw new ServiceException("授权已撤销，请重新授予后再调整角色");
        }

        // 只放行角色与备注：改 app_user_id / store_id 等于把授权转给别人，必须走撤销 + 重新授予才有完整留痕
        KgStaffGrant update = new KgStaffGrant();
        update.setGrantId(current.getGrantId());
        update.setRoleCode(kgStaffGrant.getRoleCode());
        // 换成别的角色一律清掉兼任。还是小院主理人、请求没带这个字段（打开着的老后台页面）时不动，免得改备注把开关关了
        boolean keepSwitch = StaffRole.KEEPER.equals(kgStaffGrant.getRoleCode()) && kgStaffGrant.getAlsoService() == null;
        update.setAlsoService(keepSwitch ? null : alsoServiceValue(kgStaffGrant.getRoleCode(), kgStaffGrant.getAlsoService()));
        update.setRemark(kgStaffGrant.getRemark());
        update.setUpdateBy(SecurityUtils.getUsername());
        int rows = kgStaffGrantMapper.updateKgStaffGrant(update);
        releaseServiceGrants(current.getAppUserId());
        refreshAppSession(current.getAppUserId());
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int revoke(Long[] grantIds)
    {
        if (grantIds == null || grantIds.length == 0)
        {
            return 0;
        }
        // 先整体校验再落库，避免批量撤销做了一半才报错
        List<Long> affectedUsers = new ArrayList<Long>();
        List<KgStaffGrant> revoked = new ArrayList<KgStaffGrant>();
        for (Long grantId : grantIds)
        {
            KgStaffGrant current = requireGrant(grantId);
            if (STATUS_REVOKED.equals(current.getStatus()))
            {
                throw new ServiceException("授权已撤销，请勿重复操作");
            }
            if (current.getAppUserId() != null && !affectedUsers.contains(current.getAppUserId()))
            {
                affectedUsers.add(current.getAppUserId());
            }
            revoked.add(current);
        }
        int rows = kgStaffGrantMapper.revokeByGrantIds(grantIds, SecurityUtils.getUsername());
        for (Long appUserId : affectedUsers)
        {
            releaseServiceGrants(appUserId);
            refreshAppSession(appUserId);
        }
        // 撤销也告诉本人（每家门店一条），免得工作台突然进不去却不知道原因
        for (KgStaffGrant g : revoked)
        {
            notifyGrantChange(g.getAppUserId(), g.getStoreId(), "员工身份已撤销", "你在「" + (g.getCityId() == null ? storeLabel(g.getStoreName()) : g.getCity()) + "」的「"
                    + StaffRole.label(g.getRoleCode()) + "」身份已由平台撤销，该授权范围的工作台已不能使用。如有疑问请联系平台运营。", g.getGrantId());
        }
        return rows;
    }

    @Override
    public boolean hasActiveGrant(Long appUserId, Long storeId)
    {
        if (appUserId == null || storeId == null)
        {
            return false;
        }
        return kgStaffGrantMapper.countActive(appUserId, storeId) > 0;
    }

    /**
     * 新员工被授权时还在用户身份下，而消息中心、红点都按身份分开：只发员工身份的消息他看不到，
     * 所以一定发一条到用户身份；在其他门店本来就有员工身份的人平时多半停在员工身份，再发一条到员工身份。
     */
    @Override
    public void notifyGrantChange(Long appUserId, Long storeId, String title, String content, Long bizId)
    {
        if (appUserId == null)
        {
            return;
        }
        messages.send(appUserId, "user", MessageCategory.SYSTEM, title, content, "grant", bizId, IDENTITY_ROUTE);
        for (StaffGrantView g : loadActiveGrantViews(appUserId))
        {
            if (storeId == null || !storeId.equals(g.getStoreId()))
            {
                messages.send(appUserId, "staff", MessageCategory.SYSTEM, title, content, "grant", bizId, "/pages/staff/index");
                return;
            }
        }
    }

    private static String storeLabel(String storeName)
    {
        return StringUtils.isEmpty(storeName) ? "门店" : storeName;
    }

    /**
     * 授权变更后立即覆盖 Redis 中的小程序登录态。
     * 少了这一步，已登录员工手里的旧 token 仍带着变更前的授权快照，
     * 在令牌自然过期（默认 7 天）之前都能继续访问员工侧接口。
     */
    private void refreshAppSession(Long appUserId)
    {
        if (appUserId == null)
        {
            return;
        }
        appTokenService.refreshGrants(appUserId, loadActiveGrantViews(appUserId));
    }

    @Override
    public List<Long> listActiveStaffUserIds(Long storeId)
    {
        if (storeId == null)
        {
            return new java.util.ArrayList<Long>();
        }
        // 本城按城市授权的员工（不含城市负责人、合作商家）也算这家店的员工；这家店已有门店授权或撤销记录的人以门店授权为准
        return withCityHolders(kgStaffGrantMapper.selectActiveStaffUserIds(storeId), storeId, null);
    }

    @Override
    public List<Long> listMerchantServiceUserIds(Long storeId)
    {
        if (storeId == null)
        {
            return new java.util.ArrayList<Long>();
        }
        // 和上面同一口径：按城市授权的商家服务人也要从门店线下业务消息里去掉
        return withCityHolders(kgStaffGrantMapper.selectActiveMerchantServiceUserIds(storeId), storeId, java.util.Collections.singletonList(StaffRole.MERCHANT_SERVICE));
    }

    /** 门店名单补上本城按城市授权的人（CityGrantScope.holderIds）；roles 为空不限角色 */
    private List<Long> withCityHolders(List<Long> own, Long storeId, List<String> roles)
    {
        List<Long> extra = cityGrants == null ? null : cityGrants.holderIds(storeId, roles);
        if (extra == null || extra.isEmpty())
        {
            return own;
        }
        List<Long> out = own == null ? new ArrayList<Long>() : new ArrayList<Long>(own);
        for (Long uid : extra)
        {
            if (uid != null && !out.contains(uid))
            {
                out.add(uid);
            }
        }
        return out;
    }
}
