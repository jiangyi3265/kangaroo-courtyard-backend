package com.ruoyi.kangaroo.ext.staff;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.KgCityService;
import com.ruoyi.kangaroo.security.StaffGrantView;

/**
 * 城市负责人管本城全部门店（2026-11-01 主人拍板：全部权限，平时不做日常，下级不在时能顶上）。
 *
 * 只在切换后（RolePhase.isNew）生效，范围只从 kg_staff_grant 和 kg_store.city 推出来，不写库、不补授权、不信客户端传的任何东西：
 *   他有城市负责人授权的门店（门店正常）一定算；再加上和这些门店城市归一后相同的正常门店。没设城市（空、全国、不限）的门店不算同城。
 * 例外（这两种他在这家店不是城市负责人）：
 *   在这家店是合作商家：商家的钱和数据要有人挡，自己的店也不能自己审，这家店上他还是商家；
 *   在这家店的授权被平台撤销过：平台不让他碰这家店。系统自动收回的（关掉兼任、不再是本城换工主理人）不算。
 * 在这家店有别的二级角色（小院主理人、换工主理人、线上任务专员、商家服务人）的，权限是这些角色的并集，这家店上按城市负责人算。
 * 他不算进换工主理人、小院主理人的名单：派单、日报、接待席位这些查询查的还是 role_code，一个字不用改。
 */
@Component
public class CityLeadScope
{
    @Autowired
    private KgJdbc db;

    @Autowired
    private KgCityService cities;

    /** 按城市授权的换工主理人（standIns 用）；单测没注入时不算 */
    @Autowired(required = false)
    private CityGrantScope cityGrants;

    public String town(Object raw)
    {
        String t = cities.jobCity(raw == null ? "" : String.valueOf(raw).trim());
        t = t == null ? "" : t.trim();
        return t.isEmpty() || "全国".equals(t) || "不限".equals(t) ? "" : t;
    }

    private List<Map<String, Object>> leadRows(Long uid)
    {
        return db.list("select g.store_id, coalesce(c.name, s.city) city from kg_staff_grant g left join kg_store s on s.store_id = g.store_id left join kg_city c on c.city_id=g.city_id "
                + "where g.app_user_id = :u and g.role_code = 'city_lead' and g.status = '0' and g.del_flag = '0' and ((g.city_id is null and s.status = '0' and s.del_flag = '0') or (g.store_id is null and c.status='0')) order by g.grant_id",
                KgJdbc.p("u", uid));
    }

    /** 这个账号能以城市负责人身份操作的门店（storeId → 门店行：store_id, store_name, city, cover, address）。有授权的排前面，切换前或不是城市负责人时为空 */
    public Map<Long, Map<String, Object>> stores(Long uid)
    {
        Map<Long, Map<String, Object>> out = new LinkedHashMap<Long, Map<String, Object>>();
        if (uid == null || !RolePhase.isNew())
        {
            return out;
        }
        List<Map<String, Object>> leads = leadRows(uid);
        if (leads.isEmpty())
        {
            return out;
        }
        Set<Long> own = new LinkedHashSet<Long>();
        Set<String> towns = new LinkedHashSet<String>();
        for (Map<String, Object> g : leads)
        {
            Long ownStore = KgJdbc.asLong(g.get("storeId"));
            if (ownStore != null) own.add(ownStore);
            String t = town(g.get("city"));
            if (!t.isEmpty())
            {
                towns.add(t);
            }
        }
        Map<Long, Map<String, Object>> rows = new HashMap<Long, Map<String, Object>>();
        List<Long> sameCity = new ArrayList<Long>();
        for (Map<String, Object> s : db.list("select store_id, store_name, city, cover, address from kg_store where status = '0' and del_flag = '0' order by store_id", KgJdbc.p()))
        {
            Long id = KgJdbc.asLong(s.get("storeId"));
            rows.put(id, s);
            if (towns.contains(town(s.get("city"))))
            {
                sameCity.add(id);
            }
        }
        Set<Long> off = new LinkedHashSet<Long>();
        for (Map<String, Object> g : db.list("select store_id, role_code, status, del_flag, revoked_by from kg_staff_grant where app_user_id = :u", KgJdbc.p("u", uid)))
        {
            if (excludes(g))
            {
                off.add(KgJdbc.asLong(g.get("storeId")));
            }
        }
        for (Long id : own)
        {
            if (rows.containsKey(id))
            {
                out.put(id, rows.get(id));
            }
        }
        for (Long id : sameCity)
        {
            if (!off.contains(id))
            {
                out.put(id, rows.get(id));
            }
        }
        return out;
    }

    private static boolean excludes(Map<String, Object> g)
    {
        String status = StaffSupport.str(g.get("status"));
        String del = StaffSupport.str(g.get("delFlag"));
        if ("2".equals(del))
        {
            return false;
        }
        if ("0".equals(status))
        {
            return StaffRole.MERCHANT.equals(StaffSupport.str(g.get("roleCode")));
        }
        String by = StaffSupport.str(g.get("revokedBy"));
        return !KgMerchantRequestService.REVOKED_NOT_SERVICE.equals(by) && !KgMerchantRequestService.REVOKED_NOT_MANAGER.equals(by);
    }

    public boolean covers(Long uid, Long storeId)
    {
        return storeId != null && stores(uid).containsKey(storeId);
    }

    /** 能管这家店的城市负责人（账号正常）。活动退款待审批、退款异常发给他们；门店没设城市时只有在这家店有授权的 */
    public List<Long> leadsOf(Long storeId)
    {
        List<Long> out = new ArrayList<Long>();
        if (storeId == null || !RolePhase.isNew())
        {
            return out;
        }
        for (Map<String, Object> g : db.list("select distinct g.app_user_id from kg_staff_grant g left join kg_store s on s.store_id = g.store_id left join kg_city c on c.city_id=g.city_id join kg_app_user u on u.user_id = g.app_user_id "
                + "where g.role_code = 'city_lead' and g.status = '0' and g.del_flag = '0' and ((g.city_id is null and s.status = '0' and s.del_flag = '0') or (g.store_id is null and c.status='0')) and u.status = '0' and u.del_flag = '0' order by g.app_user_id",
                KgJdbc.p()))
        {
            Long uid = KgJdbc.asLong(g.get("appUserId"));
            if (uid != null && covers(uid, storeId))
            {
                out.add(uid);
            }
        }
        return out;
    }

    /**
     * 这家店没有换工主理人时，报名、面试这些门店通知要有人知道：发给能管这家店的城市负责人（丽江的缺口）。
     * 有换工主理人的店不发，城市负责人平时不做日常。
     */
    public List<Long> standIns(Long storeId)
    {
        if (storeId == null || !RolePhase.isNew()
                || db.count("select count(*) from kg_staff_grant g join kg_app_user u on u.user_id = g.app_user_id where g.store_id = :s and g.role_code = 'manager' "
                        + "and g.status = '0' and g.del_flag = '0' and u.status = '0' and u.del_flag = '0'", KgJdbc.p("s", storeId)) > 0
                // 本城按城市授权的换工主理人也算这家店有换工主理人
                || (cityGrants != null && !cityGrants.holderIds(storeId, java.util.Collections.singletonList(StaffRole.MANAGER)).isEmpty()))
        {
            return new ArrayList<Long>();
        }
        return leadsOf(storeId);
    }

    /**
     * 登录态里的授权：把城市负责人的范围展开。views 是这个账号生效中的真实授权，直接在上面改。
     * 展开出来的行 id 为空（库里没有这一行）。这些行每个请求都现算，不进任何表。
     */
    public void expand(Long uid, List<StaffGrantView> views)
    {
        if (uid == null || views == null || !RolePhase.isNew()
                || views.stream().noneMatch(g -> StaffRole.CITY_LEAD.equals(g.getRoleCode())))
        {
            return;
        }
        Map<Long, Map<String, Object>> stores = stores(uid);
        Set<Long> seen = new LinkedHashSet<Long>();
        for (StaffGrantView g : views)
        {
            if (g.getCityId() != null && g.getStoreId() == null && StaffRole.CITY_LEAD.equals(g.getRoleCode())) g.setFullAccess(Boolean.TRUE);
            seen.add(g.getStoreId());
            if (stores.containsKey(g.getStoreId()))
            {
                g.setRoleCode(StaffRole.CITY_LEAD);
                g.setRoleName(StaffRole.label(StaffRole.CITY_LEAD));
                g.setAlsoService(Boolean.FALSE);
                g.setFullAccess(Boolean.TRUE);
            }
        }
        for (Map.Entry<Long, Map<String, Object>> e : stores.entrySet())
        {
            if (seen.contains(e.getKey()))
            {
                continue;
            }
            StaffGrantView v = new StaffGrantView();
            v.setStoreId(e.getKey());
            v.setStoreName(StaffSupport.str(e.getValue().get("storeName")));
            v.setCity(StaffSupport.str(e.getValue().get("city")));
            v.setRoleCode(StaffRole.CITY_LEAD);
            v.setRoleName(StaffRole.label(StaffRole.CITY_LEAD));
            v.setActive(Boolean.TRUE);
            v.setAlsoService(Boolean.FALSE);
            v.setFullAccess(Boolean.TRUE);
            views.add(v);
        }
    }
}
