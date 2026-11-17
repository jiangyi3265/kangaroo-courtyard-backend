package com.ruoyi.kangaroo.ext.staff;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.KgCityService;
import com.ruoyi.kangaroo.security.StaffGrantView;

/** Explicit city grants keep their role. Merchant preparation never grants rights to unrelated stores. */
@Component
public class CityGrantScope {
    @Autowired private KgJdbc db;
    @Autowired private KgCityService cities;

    private String town(Object raw) {
        String value = cities.jobCity(raw == null ? "" : String.valueOf(raw).trim());
        value = value == null ? "" : value.trim();
        return value.isEmpty() || "全国".equals(value) || "不限".equals(value) || "全部城市".equals(value) ? "" : value;
    }

    public void expand(Long uid, List<StaffGrantView> views) {
        if (uid == null || views == null) return;
        List<StaffGrantView> cityGrants = new ArrayList<>();
        for (StaffGrantView grant : views) {
            if (Boolean.TRUE.equals(grant.getActive()) && grant.getStoreId() == null && grant.getCityId() != null && StaffRole.exists(grant.getRoleCode()) && RolePhase.usable(grant.getRoleCode())
                    && !StaffRole.MERCHANT.equals(grant.getRoleCode()) && !StaffRole.CITY_LEAD.equals(grant.getRoleCode())) cityGrants.add(grant);
        }
        if (cityGrants.isEmpty()) return;
        // Any explicit store assignment (including a revoked one) takes precedence over a city grant.
        Set<Long> explicit = new HashSet<>();
        for (Map<String,Object> row : db.list("select store_id from kg_staff_grant where app_user_id=:u and store_id is not null", KgJdbc.p("u",uid))) {
            Long id = KgJdbc.asLong(row.get("storeId"));
            if (id != null) explicit.add(id);
        }
        for (StaffGrantView view : views) if (view.getStoreId() != null) explicit.add(view.getStoreId());
        List<Map<String,Object>> stores = db.list("select store_id,store_name,city from kg_store where status='0' and del_flag='0' order by store_id", KgJdbc.p());
        for (StaffGrantView city : cityGrants) {
            String scope = town(city.getCity());
            if (scope.isEmpty()) continue;
            for (Map<String,Object> store : stores) {
                Long id = KgJdbc.asLong(store.get("storeId"));
                if (id == null || explicit.contains(id) || !scope.equals(town(store.get("city")))) continue;
                StaffGrantView view = new StaffGrantView();
                view.setStoreId(id); view.setStoreName(StaffSupport.str(store.get("storeName")));
                view.setCityId(city.getCityId()); view.setCity(StaffSupport.str(store.get("city")));
                view.setRoleCode(city.getRoleCode()); view.setRoleName(StaffRole.label(city.getRoleCode()));
                view.setActive(Boolean.TRUE); view.setAlsoService(city.getAlsoService());
                view.setFullAccess(Boolean.FALSE);
                views.add(view); explicit.add(id);
            }
        }
    }

    /**
     * 「还有员工身份」：和登录下发的授权同一口径（KgStaffGrantMapper.selectActiveGrantsWithStore）。
     * 门店授权看门店正常；城市授权（store_id 为空）看城市没停用，本城还没门店也算。参数 :u
     */
    public static final String COUNT_ACTIVE_IDENTITY = "select count(*) from kg_staff_grant g left join kg_store s on s.store_id = g.store_id left join kg_city c on c.city_id = g.city_id "
            + "where g.app_user_id = :u and g.status = '0' and g.del_flag = '0' and ((g.city_id is null and s.status = '0' and s.del_flag = '0') "
            + "or (g.store_id is null and g.role_code in ('city_lead','manager','keeper','online_staff','merchant_service','merchant','staff') and c.status = '0'))";

    /** 生效中、会按城市展开的城市授权（不含城市负责人、合作商家；账号正常、城市没停用） */
    private static final String ACTIVE_CITY = "select g.app_user_id, g.city_id, c.name city, g.role_code, g.also_service from kg_staff_grant g "
            + "join kg_city c on c.city_id = g.city_id join kg_app_user u on u.user_id = g.app_user_id "
            + "where g.store_id is null and g.status = '0' and g.del_flag = '0' and c.status = '0' and u.status = '0' and u.del_flag = '0' "
            + "and g.role_code in ('manager','keeper','online_staff','merchant_service','staff')";

    /**
     * 这个账号按城市授权能管的门店：storeId → 展开后的授权行（角色、兼任同城市授权）。
     * 和登录态同一套规则（expand）：同城正常门店；已有门店授权或撤销记录的店不在内。每次现算，不写库
     */
    public Map<Long, StaffGrantView> stores(Long uid) {
        Map<Long, StaffGrantView> out = new LinkedHashMap<>();
        if (uid == null) return out;
        List<StaffGrantView> views = new ArrayList<>();
        for (Map<String,Object> g : db.list(ACTIVE_CITY + " and g.app_user_id = :u order by g.grant_id", KgJdbc.p("u", uid))) {
            StaffGrantView v = new StaffGrantView();
            v.setCityId(KgJdbc.asLong(g.get("cityId"))); v.setCity(StaffSupport.str(g.get("city")));
            v.setRoleCode(StaffSupport.str(g.get("roleCode"))); v.setActive(Boolean.TRUE);
            v.setAlsoService("1".equals(StaffSupport.str(g.get("alsoService"))));
            views.add(v);
        }
        if (views.isEmpty()) return out;
        expand(uid, views);
        for (StaffGrantView v : views) if (v.getStoreId() != null) out.putIfAbsent(v.getStoreId(), v);
        return out;
    }

    /** 这个账号在这家店按城市授权的角色；没有（不同城、已有门店授权或撤销记录）为 null */
    public String roleAt(Long uid, Long storeId) {
        if (uid == null || storeId == null) return null;
        StaffGrantView v = stores(uid).get(storeId);
        return v == null ? null : v.getRoleCode();
    }

    /**
     * 按城市授权能管这家店的人（门店通知、名单用）：每人一行 appUserId, roleCode, alsoService。
     * 在这家店已有门店授权或撤销记录的人不算（以门店授权为准）；门店停用、没设城市时为空
     */
    public List<Map<String,Object>> holders(Long storeId) {
        List<Map<String,Object>> out = new ArrayList<>();
        if (storeId == null) return out;
        Map<String,Object> store = db.one("select city from kg_store where store_id = :s and status = '0' and del_flag = '0'", KgJdbc.p("s", storeId));
        String scope = store == null ? "" : town(store.get("city"));
        if (scope.isEmpty()) return out;
        Set<Long> seen = new HashSet<>();
        for (Map<String,Object> g : db.list(ACTIVE_CITY + " and not exists (select 1 from kg_staff_grant x where x.app_user_id = g.app_user_id and x.store_id = :s) order by g.grant_id",
                KgJdbc.p("s", storeId))) {
            Long uid = KgJdbc.asLong(g.get("appUserId"));
            String role = StaffSupport.str(g.get("roleCode"));
            if (uid == null || !StaffRole.exists(role) || !RolePhase.usable(role) || !scope.equals(town(g.get("city"))) || !seen.add(uid)) continue;
            out.add(g);
        }
        return out;
    }

    /** 同 holders，只要这几种角色的人；roles 为空时不限角色 */
    public List<Long> holderIds(Long storeId, Collection<String> roles) {
        List<Long> out = new ArrayList<>();
        for (Map<String,Object> g : holders(storeId)) {
            if (roles == null || roles.isEmpty() || roles.contains(StaffSupport.str(g.get("roleCode")))) out.add(KgJdbc.asLong(g.get("appUserId")));
        }
        return out;
    }
}
