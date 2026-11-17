package com.ruoyi.kangaroo.ext.staff;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.method.HandlerMethod;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.controller.app.AppStaffActivityController;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgActivityService;
import com.ruoyi.kangaroo.ext.user.KgCityService;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppAuthInterceptor;
import com.ruoyi.kangaroo.security.AppIdentityLoader;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppTokenService;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.security.StaffGrantView;

/** 城市负责人全部权限（2026-11-01）：范围只从授权和门店城市推出来；别的城市、没设城市、他是商家的店、平台撤过的店都不算；切换前一切照旧。 */
class CityLeadScopeTest
{
    private static final long LEAD = 6L;
    private static final long LIJIANG_A = 1L;
    private static final long LIJIANG_B = 2L;
    private static final long LIJIANG_MERCHANT = 3L;
    private static final long LIJIANG_REVOKED = 4L;
    private static final long LIJIANG_AUTO_REVOKED = 5L;
    private static final long PINGTAN = 7L;
    private static final long NO_CITY = 8L;
    private static final long NATIONWIDE = 9L;

    private final KgJdbc db = mock(KgJdbc.class);
    private final KgCityService cities = mock(KgCityService.class);
    private final CityLeadScope scope = new CityLeadScope();
    private final List<Map<String, Object>> grantRows = new ArrayList<Map<String, Object>>();
    private long activeManagers;

    private static Map<String, Object> grant(long store, String role, String status, String by)
    {
        return new HashMap<String, Object>(KgJdbc.p("storeId", store, "roleCode", role, "status", status, "delFlag", "0", "revokedBy", by, "city", cityOf(store), "appUserId", LEAD));
    }

    private static String cityOf(long store)
    {
        if (store == PINGTAN) return "平潭";
        if (store == NO_CITY) return "";
        if (store == NATIONWIDE) return "全国";
        return store == LIJIANG_B ? "丽江市" : "丽江";
    }

    private static Map<String, Object> store(long id)
    {
        return new HashMap<String, Object>(KgJdbc.p("storeId", id, "storeName", "店" + id, "city", cityOf(id)));
    }

    @BeforeEach
    void setup()
    {
        RolePhase.set(RolePhase.NEW);
        ReflectionTestUtils.setField(scope, "db", db);
        ReflectionTestUtils.setField(scope, "cities", cities);
        when(cities.jobCity(anyString())).thenAnswer(i -> {
            String n = i.getArgument(0);
            return n.endsWith("市") ? n.substring(0, n.length() - 1) : n;
        });
        grantRows.add(grant(LIJIANG_A, StaffRole.CITY_LEAD, "0", null));
        when(db.list(contains("g.role_code = 'city_lead' and g.status = '0'"), anyMap())).thenAnswer(i -> {
            List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> g : grantRows)
            {
                if (StaffRole.CITY_LEAD.equals(g.get("roleCode")) && "0".equals(g.get("status")))
                {
                    out.add(g);
                }
            }
            return out;
        });
        when(db.list(contains("from kg_store where status = '0'"), anyMap())).thenAnswer(i -> Arrays.asList(store(LIJIANG_A), store(LIJIANG_B), store(LIJIANG_MERCHANT),
                store(LIJIANG_REVOKED), store(LIJIANG_AUTO_REVOKED), store(PINGTAN), store(NO_CITY), store(NATIONWIDE)));
        when(db.list(contains("revoked_by from kg_staff_grant"), anyMap())).thenAnswer(i -> grantRows);
        when(db.count(contains("g.role_code = 'manager'"), anyMap())).thenAnswer(i -> activeManagers);
    }

    @AfterEach
    void clear()
    {
        RolePhase.set(RolePhase.LEGACY);
        AppUserContext.remove();
    }

    @Test
    void coversSameCityStoresAfterNormalizingTheName()
    {
        assertTrue(scope.covers(LEAD, LIJIANG_A));
        assertTrue(scope.covers(LEAD, LIJIANG_B), "丽江市和丽江是一个城市");
    }

    @Test
    void directCityGrantWithoutStoreCoversOnlyThatCity() {
        grantRows.clear();
        grantRows.add(new HashMap<String,Object>(KgJdbc.p("storeId", null, "city", "丽江", "roleCode", StaffRole.CITY_LEAD, "status", "0", "delFlag", "0", "appUserId", LEAD)));
        assertTrue(scope.covers(LEAD, LIJIANG_A));
        assertFalse(scope.covers(LEAD, PINGTAN));
        assertFalse(scope.covers(LEAD, NO_CITY));
        assertFalse(scope.covers(LEAD, null));
        StaffGrantView city = view(0L, StaffRole.CITY_LEAD); city.setStoreId(null); city.setCityId(10L); city.setCity("丽江");
        List<StaffGrantView> views = new ArrayList<>(Arrays.asList(city));
        scope.expand(LEAD, views);
        assertTrue(views.contains(city));
        assertTrue(city.getFullAccess());
        assertTrue(views.stream().anyMatch(v -> Long.valueOf(LIJIANG_A).equals(v.getStoreId())));
    }

    @Test
    void unopenedCityRetainsIdentityButHasNoStoreAccess() {
        grantRows.clear();
        grantRows.add(new HashMap<String,Object>(KgJdbc.p("storeId", null, "city", "大连", "roleCode", StaffRole.CITY_LEAD, "status", "0", "delFlag", "0")));
        assertTrue(scope.stores(LEAD).isEmpty());
        StaffGrantView city = view(0L, StaffRole.CITY_LEAD); city.setStoreId(null); city.setCityId(10L);
        AppLoginUser me = new AppLoginUser(); me.setUserId(LEAD); me.setStaffGrants(Arrays.asList(city));
        AppUserContext.set(me);
        assertThrows(ServiceException.class, () -> new StaffSupport().ctx(null));
    }

    @Test
    void anotherCityIsDenied()
    {
        assertFalse(scope.covers(LEAD, PINGTAN));
    }

    @Test
    void storeWithoutACityNeverMatches()
    {
        assertFalse(scope.covers(LEAD, NO_CITY));
        assertFalse(scope.covers(LEAD, NATIONWIDE));
    }

    @Test
    void leadOnAStorelessCityStillOnlyGetsThatOneStore()
    {
        grantRows.clear();
        grantRows.add(grant(NO_CITY, StaffRole.CITY_LEAD, "0", null));
        assertEquals(java.util.Collections.singleton(NO_CITY), scope.stores(LEAD).keySet(), "没设城市的授权只算这一家，不把另一家没设城市的带进来");
    }

    @Test
    void storeWhereHeIsAMerchantIsNotCovered()
    {
        grantRows.add(grant(LIJIANG_MERCHANT, StaffRole.MERCHANT, "0", null));
        assertFalse(scope.covers(LEAD, LIJIANG_MERCHANT), "商家的钱和数据要有人挡");
        assertTrue(scope.covers(LEAD, LIJIANG_B));
    }

    @Test
    void platformRevokedStoreIsNotCoveredButAutoRevokedIs()
    {
        grantRows.add(grant(LIJIANG_REVOKED, StaffRole.KEEPER, "1", "admin"));
        grantRows.add(grant(LIJIANG_AUTO_REVOKED, StaffRole.MERCHANT_SERVICE, "1", KgMerchantRequestService.REVOKED_NOT_SERVICE));
        assertFalse(scope.covers(LEAD, LIJIANG_REVOKED));
        assertTrue(scope.covers(LEAD, LIJIANG_AUTO_REVOKED));
    }

    @Test
    void legacyDoesNotExpandAnything()
    {
        RolePhase.set(RolePhase.LEGACY);
        assertTrue(scope.stores(LEAD).isEmpty());
        assertTrue(scope.leadsOf(LIJIANG_A).isEmpty());
        assertTrue(scope.standIns(LIJIANG_A).isEmpty());
        List<StaffGrantView> views = new ArrayList<StaffGrantView>(Arrays.asList(view(LIJIANG_A, StaffRole.CITY_LEAD)));
        scope.expand(LEAD, views);
        assertEquals(1, views.size());
        assertNull(views.get(0).getFullAccess());
        verifyNoInteractions(db);
    }

    @Test
    void expandAddsVirtualRowsAndUpgradesSameCityRolesButKeepsMerchant()
    {
        grantRows.add(grant(LIJIANG_B, StaffRole.KEEPER, "0", null));
        grantRows.add(grant(LIJIANG_MERCHANT, StaffRole.MERCHANT, "0", null));
        grantRows.add(grant(PINGTAN, StaffRole.MANAGER, "0", null));
        List<StaffGrantView> views = new ArrayList<StaffGrantView>(Arrays.asList(view(LIJIANG_A, StaffRole.CITY_LEAD), view(LIJIANG_B, StaffRole.KEEPER),
                view(LIJIANG_MERCHANT, StaffRole.MERCHANT), view(PINGTAN, StaffRole.MANAGER)));
        scope.expand(LEAD, views);
        Map<Long, StaffGrantView> at = views.stream().collect(Collectors.toMap(StaffGrantView::getStoreId, v -> v));
        assertEquals(StaffRole.CITY_LEAD, at.get(LIJIANG_A).getRoleCode());
        assertEquals(Boolean.TRUE, at.get(LIJIANG_A).getFullAccess());
        assertEquals(StaffRole.CITY_LEAD, at.get(LIJIANG_B).getRoleCode(), "同城别的二级角色这家店按城市负责人算");
        assertEquals(StaffRole.MERCHANT, at.get(LIJIANG_MERCHANT).getRoleCode(), "商家还是商家");
        assertNull(at.get(LIJIANG_MERCHANT).getFullAccess());
        assertEquals(StaffRole.MANAGER, at.get(PINGTAN).getRoleCode(), "别的城市的换工主理人还是换工主理人");
        assertNull(at.get(PINGTAN).getFullAccess());
        assertTrue(at.containsKey(LIJIANG_AUTO_REVOKED), "同城没授权的店补一行");
        assertNull(at.get(LIJIANG_AUTO_REVOKED).getId(), "这行库里没有");
        assertEquals(Boolean.TRUE, at.get(LIJIANG_AUTO_REVOKED).getActive());
        Set<Long> ids = at.keySet();
        assertFalse(ids.contains(NO_CITY));
        assertFalse(ids.contains(NATIONWIDE));
    }

    @Test
    void accountWithoutACityLeadGrantIsUntouched()
    {
        grantRows.clear();
        grantRows.add(grant(LIJIANG_A, StaffRole.MANAGER, "0", null));
        List<StaffGrantView> views = new ArrayList<StaffGrantView>(Arrays.asList(view(LIJIANG_A, StaffRole.MANAGER)));
        scope.expand(LEAD, views);
        assertEquals(1, views.size());
        assertEquals(StaffRole.MANAGER, views.get(0).getRoleCode(), "老换工主理人不受影响");
        assertNull(views.get(0).getFullAccess());
        assertTrue(scope.stores(LEAD).isEmpty());
    }

    @Test
    void expandNeverWritesTheDatabase()
    {
        List<StaffGrantView> views = new ArrayList<StaffGrantView>(Arrays.asList(view(LIJIANG_A, StaffRole.CITY_LEAD)));
        scope.expand(LEAD, views);
        verify(db, never()).insert(anyString(), anyMap());
        verify(db, never()).update(anyString(), anyMap());
    }

    @Test
    void storeNoticesReachTheLeadOnlyWhenNoManagerIsThere()
    {
        activeManagers = 0;
        assertEquals(Arrays.asList(LEAD), scope.standIns(LIJIANG_B), "丽江没有换工主理人：黑子收");
        assertTrue(scope.standIns(PINGTAN).isEmpty(), "别的城市不收");
        activeManagers = 1;
        assertTrue(scope.standIns(LIJIANG_B).isEmpty(), "有换工主理人的店不打扰他");
    }

    // ------------------------------------------------------------------ 拦截器：另一个城市、老版本

    static class Endpoints
    {
        @AppAuth(staff = true) public void plain() {}
    }

    private static StaffGrantView view(long store, String role)
    {
        StaffGrantView g = new StaffGrantView();
        g.setStoreId(store);
        g.setRoleCode(role);
        g.setActive(true);
        return g;
    }

    private AppLoginUser passInterceptor(String phase, StaffGrantView... grants) throws Exception
    {
        RolePhase.set(phase);
        AppLoginUser user = new AppLoginUser();
        user.setUserId(LEAD);
        user.setStaffGrants(new ArrayList<StaffGrantView>(Arrays.asList(grants)));
        AppAuthInterceptor i = new AppAuthInterceptor();
        AppTokenService tokens = mock(AppTokenService.class);
        when(tokens.getLoginUser(any())).thenReturn(user);
        ReflectionTestUtils.setField(i, "appTokenService", tokens);
        ReflectionTestUtils.setField(i, "identities", mock(AppIdentityLoader.class));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/staff/stay/orders");
        request.setServletPath("/app/staff/stay/orders");
        i.preHandle(request, new MockHttpServletResponse(), new HandlerMethod(new Endpoints(), Endpoints.class.getMethod("plain")));
        return AppUserContext.get();
    }

    @Test
    void storeAccessFollowsTheExpandedRowsOnly() throws Exception
    {
        AppLoginUser me = passInterceptor(RolePhase.NEW, view(LIJIANG_A, StaffRole.CITY_LEAD));
        assertTrue(me.hasStoreAccess(LIJIANG_A));
        assertFalse(me.hasStoreAccess(PINGTAN), "另一个城市的门店没有这一行，接口进不去");
    }

    @Test
    void legacyInterceptorStillDropsTheLead()
    {
        ServiceException e = assertThrows(ServiceException.class, () -> passInterceptor(RolePhase.LEGACY, view(LIJIANG_A, StaffRole.CITY_LEAD)));
        assertEquals(403, e.getCode());
    }

    @Test
    void merchantGrantStaysOutOfStaffEndpointsForALead() throws Exception
    {
        AppLoginUser me = passInterceptor(RolePhase.NEW, view(LIJIANG_A, StaffRole.CITY_LEAD), view(LIJIANG_MERCHANT, StaffRole.MERCHANT));
        assertFalse(me.hasStoreAccess(LIJIANG_MERCHANT), "没标 merchant 的接口用不到商家授权，当了城市负责人也一样");
    }

    // ------------------------------------------------------------------ 活动退款：自己的报名不能自己审

    private AppStaffActivityController refundController(StaffSupport support, KgActivityService activities, long signupOwner)
    {
        AppStaffActivityController c = new AppStaffActivityController();
        KgJdbc jdbc = mock(KgJdbc.class);
        when(jdbc.one(contains("select store_id from kg_activity"), anyMap())).thenReturn(KgJdbc.p("storeId", LIJIANG_A));
        when(jdbc.count(contains("signup_id = :s and activity_id = :a"), anyMap())).thenReturn(1L);
        when(jdbc.count(contains("signup_id = :s and app_user_id = :u"), anyMap())).thenAnswer(i -> {
            Map<String, Object> ps = i.getArgument(1);
            return Long.valueOf(signupOwner).equals(ps.get("u")) ? 1L : 0L;
        });
        ReflectionTestUtils.setField(c, "db", jdbc);
        ReflectionTestUtils.setField(c, "support", support);
        ReflectionTestUtils.setField(c, "activities", activities);
        StaffSupport.Ctx ctx = new StaffSupport.Ctx();
        AppLoginUser me = new AppLoginUser();
        me.setUserId(LEAD);
        ctx.me = me;
        ctx.storeId = LIJIANG_A;
        ctx.role = StaffRole.CITY_LEAD;
        when(support.ctx(LIJIANG_A)).thenReturn(ctx);
        return c;
    }

    @Test
    void leadCannotApproveHisOwnActivityRefund()
    {
        StaffSupport support = mock(StaffSupport.class);
        KgActivityService activities = mock(KgActivityService.class);
        ServiceException e = assertThrows(ServiceException.class, () -> refundController(support, activities, LEAD).refund(50L, 60L, "approve", null));
        assertEquals(403, e.getCode());
        verify(activities, never()).approveRefund(any(), anyString());
    }

    @Test
    void leadCanApproveSomeoneElsesActivityRefund()
    {
        StaffSupport support = mock(StaffSupport.class);
        KgActivityService activities = mock(KgActivityService.class);
        refundController(support, activities, 999L).refund(50L, 60L, "approve", null);
        verify(activities).approveRefund(eq(60L), eq("staff:" + LEAD));
    }
}
