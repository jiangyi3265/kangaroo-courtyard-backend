package com.ruoyi.kangaroo.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.method.HandlerMethod;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.controller.app.AppStaffController;
import com.ruoyi.kangaroo.controller.app.AppStaffDailyController;
import com.ruoyi.kangaroo.controller.app.AppStaffExtController;
import com.ruoyi.kangaroo.controller.app.AppStaffMerchantRequestController;
import com.ruoyi.kangaroo.controller.app.AppStaffOnlineMaterialController;
import com.ruoyi.kangaroo.controller.app.AppStaffOpsController;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.staff.StaffPerms;

/** 商家服务人、城市负责人默认拒绝：只有明确标注的接口才放行；权限矩阵的新行不影响老角色。 */
class MerchantRolesAccessTest
{
    static class Endpoints
    {
        @AppAuth(staff = true) public void plain() {}
        @AppAuth(staff = true, merchant = true, onlineStaff = true) public void otherOptIns() {}
        @AppAuth(staff = true, merchantService = true) public void service() {}
        @AppAuth(staff = true, cityLead = true) public void city() {}
        @AppAuth public void loggedInOnly() {}
        public void noAnnotation() {}
    }

    private static StaffGrantView grant(long store, String role)
    {
        StaffGrantView g = new StaffGrantView();
        g.setStoreId(store);
        g.setRoleCode(role);
        g.setActive(true);
        return g;
    }

    private static AppLoginUser user(StaffGrantView... grants)
    {
        AppLoginUser u = new AppLoginUser();
        u.setUserId(10L);
        u.setStaffGrants(new ArrayList<StaffGrantView>(Arrays.asList(grants)));
        return u;
    }

    private void invoke(AppLoginUser user, String method, String path) throws Exception
    {
        AppAuthInterceptor i = new AppAuthInterceptor();
        AppTokenService tokens = mock(AppTokenService.class);
        when(tokens.getLoginUser(any())).thenReturn(user);
        ReflectionTestUtils.setField(i, "appTokenService", tokens);
        ReflectionTestUtils.setField(i, "identities", mock(AppIdentityLoader.class));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        i.preHandle(request, new MockHttpServletResponse(), new HandlerMethod(new Endpoints(), Endpoints.class.getMethod(method)));
    }

    private void denied(AppLoginUser user, String method, String path)
    {
        ServiceException e = assertThrows(ServiceException.class, () -> invoke(user, method, path), method + " " + path);
        assertEquals(403, e.getCode());
        assertNull(AppUserContext.get(), "拒绝后线程上不能留身份");
    }

    @AfterEach
    void clear()
    {
        AppUserContext.remove();
    }

    @Test
    void bothRolesAreDeniedEverywhereUnlessMarked() throws Exception
    {
        for (String role : Arrays.asList(StaffRole.MERCHANT_SERVICE, StaffRole.CITY_LEAD))
        {
            AppLoginUser u = user(grant(1, role));
            denied(u, "plain", "/app/staff/jobs");
            denied(u, "otherOptIns", "/app/staff/jobs");
            denied(u, "noAnnotation", "/app/staff/anything");
            denied(u, "noAnnotation", "/app/manage/daily");
            denied(u, "loggedInOnly", "/app/manage/weekly");
            denied(u, "plain", "/app/other/needs-staff");
        }
    }

    @Test
    void eachRolePassesOnlyItsOwnMark() throws Exception
    {
        AppLoginUser service = user(grant(1, StaffRole.MERCHANT_SERVICE));
        invoke(service, "service", "/app/staff/service/requests");
        assertEquals(StaffRole.MERCHANT_SERVICE, AppUserContext.get().getStaffGrants().get(0).getRoleCode());
        denied(service, "city", "/app/staff/city/requests");

        AppLoginUser lead = user(grant(1, StaffRole.CITY_LEAD));
        invoke(lead, "city", "/app/staff/city/requests");
        assertEquals(StaffRole.CITY_LEAD, AppUserContext.get().getStaffGrants().get(0).getRoleCode());
        denied(lead, "service", "/app/staff/service/requests");
    }

    @Test
    void mixedAccountKeepsOnlyOtherStoresAndCacheIsNotMutated() throws Exception
    {
        AppLoginUser u = user(grant(1, StaffRole.CITY_LEAD), grant(2, StaffRole.MANAGER), grant(3, StaffRole.MERCHANT_SERVICE));
        invoke(u, "plain", "/app/staff/jobs");
        assertEquals(3, u.getStaffGrants().size(), "缓存里的完整授权不能被改");
        List<StaffGrantView> scoped = AppUserContext.get().getStaffGrants();
        assertEquals(1, scoped.size());
        assertEquals(2L, scoped.get(0).getStoreId());

        invoke(u, "service", "/app/staff/service/requests");
        assertEquals(2, AppUserContext.get().getStaffGrants().size(), "商家服务人接口留下商家服务人和常规角色，角色把关在服务里");
    }

    @Test
    void keeperWithTheSwitchPassesServiceEndpointsAndKeepsEverythingElse() throws Exception
    {
        StaffGrantView keeper = grant(1, StaffRole.KEEPER);
        keeper.setAlsoService(true);
        AppLoginUser dual = user(keeper);
        // 一个账号一条授权：不被当成商家服务人剥掉，门店的接口照常
        invoke(dual, "plain", "/app/staff/jobs");
        assertEquals(1, AppUserContext.get().getStaffGrants().size());
        assertEquals(Boolean.TRUE, AppUserContext.get().getStaffGrants().get(0).getAlsoService());
        invoke(dual, "service", "/app/staff/service/requests");
        assertEquals(1, AppUserContext.get().getStaffGrants().size());
        assertTrue(dual.hasStoreAccess(1L), "小院主理人的门店权限不变");
        // 矩阵不收也不放：兼任不是靠改小院主理人那一行
        assertEquals(Arrays.asList("no", "no", "no", "no", "no"), values(StaffRole.KEEPER, "intake"));
        assertFalse(StaffPerms.canRegisterOnlinePayout(StaffRole.KEEPER));
    }

    @Test
    void newServiceGrantAtAMerchantStoreDoesNotStripTheKeeperGrant() throws Exception
    {
        StaffGrantView keeper = grant(1, StaffRole.KEEPER);
        keeper.setAlsoService(true);
        AppLoginUser u = user(keeper, grant(2, StaffRole.MERCHANT_SERVICE));
        invoke(u, "plain", "/app/staff/jobs");
        List<StaffGrantView> scoped = AppUserContext.get().getStaffGrants();
        assertEquals(1, scoped.size(), "门店接口只去掉商家店上那条");
        assertEquals(1L, scoped.get(0).getStoreId());
        assertEquals(Boolean.TRUE, scoped.get(0).getAlsoService());
        invoke(u, "service", "/app/staff/service/requests");
        assertEquals(2, AppUserContext.get().getStaffGrants().size());
    }

    @Test
    void loggedInOnlyEndpointsOutsideStaffPathsStillWork() throws Exception
    {
        AppLoginUser u = user(grant(1, StaffRole.CITY_LEAD));
        invoke(u, "loggedInOnly", "/app/message/list");
        assertEquals(1, AppUserContext.get().getStaffGrants().size());
    }

    @Test
    void newRolesAreNotCountedAsStoreAccess()
    {
        assertFalse(user(grant(1, StaffRole.MERCHANT_SERVICE)).hasStoreAccess(1L));
        assertFalse(user(grant(1, StaffRole.CITY_LEAD)).hasStoreAccess(1L));
        assertFalse(user(grant(1, StaffRole.ONLINE_STAFF)).hasStoreAccess(1L));
        assertTrue(user(grant(1, StaffRole.MERCHANT)).hasStoreAccess(1L));
        assertTrue(user(grant(1, StaffRole.MANAGER)).hasStoreAccess(1L));
    }

    @Test
    void matrixRowsAreExactlyAsDecided()
    {
        assertEquals(Arrays.asList("yes", "yes", "yes", "no", "no"), values(StaffRole.MANAGER, "intake"));
        assertEquals(Arrays.asList("yes", "yes", "no", "no", "no"), values(StaffRole.MERCHANT_SERVICE, "intake"));
        assertEquals(Arrays.asList("yes", "no", "no", "no", "no"), values(StaffRole.CITY_LEAD, "intake"));
        for (String role : Arrays.asList(StaffRole.KEEPER, StaffRole.STAFF, StaffRole.ONLINE_STAFF, StaffRole.MERCHANT))
        {
            assertEquals(Arrays.asList("no", "no", "no", "no", "no"), values(role, "intake"), role);
        }
        assertEquals(Arrays.asList("no", "no", "no", "no", "no"), values(StaffRole.MANAGER, "online"), "10-29 的决定：换工主理人的线上任务行不动");
        assertEquals(Arrays.asList("yes", "yes", "yes", "no", "no"), values(StaffRole.MERCHANT_SERVICE, "online"));
        for (String item : Arrays.asList("rooms", "jobs", "dispatch", "activities", "reviews", "insurance", "finance", "dashboard"))
        {
            assertEquals(Arrays.asList("no", "no", "no", "no", "no"), values(StaffRole.MERCHANT_SERVICE, item), item);
            assertEquals(Arrays.asList("no", "no", "no", "no", "no"), values(StaffRole.CITY_LEAD, item), item);
        }
        assertEquals(Arrays.asList("no", "no", "no", "no", "no"), values(StaffRole.CITY_LEAD, "online"));
        assertTrue(StaffPerms.roles().containsAll(Arrays.asList(StaffRole.MERCHANT_SERVICE, StaffRole.CITY_LEAD)));
    }

    private static List<String> values(String role, String item)
    {
        List<String> out = new ArrayList<String>();
        for (String a : StaffPerms.ACTIONS.keySet())
        {
            out.add(StaffPerms.value(role, item, a));
        }
        return out;
    }

    @Test
    void onlinePayoutIsForThePayerAndServiceOnlyAmongTheNewlyOpened()
    {
        assertTrue(StaffPerms.canRegisterOnlinePayout(StaffRole.MERCHANT));
        assertTrue(StaffPerms.canRegisterOnlinePayout(StaffRole.MERCHANT_SERVICE));
        assertTrue(StaffPerms.canRegisterOnlinePayout(StaffRole.MANAGER), "原来有 finance·支付 的不变");
        assertFalse(StaffPerms.canRegisterOnlinePayout(StaffRole.KEEPER));
        assertFalse(StaffPerms.canRegisterOnlinePayout(StaffRole.STAFF));
        assertFalse(StaffPerms.canRegisterOnlinePayout(StaffRole.ONLINE_STAFF));
        assertFalse(StaffPerms.canRegisterOnlinePayout(StaffRole.CITY_LEAD));
        assertFalse(StaffPerms.allows(StaffRole.MERCHANT, "finance", StaffPerms.PAY), "不把财务整行开给商家");
    }

    @Test
    void labelsAndPlatformOnlyRoles()
    {
        assertTrue(StaffRole.exists(StaffRole.MERCHANT_SERVICE));
        assertTrue(StaffRole.exists(StaffRole.CITY_LEAD));
        assertEquals("商家服务人", StaffRole.label(StaffRole.MERCHANT_SERVICE));
        assertEquals("城市负责人", StaffRole.label(StaffRole.CITY_LEAD));
        for (String r : Arrays.asList(StaffRole.MANAGER, StaffRole.MERCHANT, StaffRole.MERCHANT_SERVICE, StaffRole.CITY_LEAD)) assertTrue(StaffRole.platformOnly(r), r);
        for (String r : Arrays.asList(StaffRole.KEEPER, StaffRole.STAFF, StaffRole.ONLINE_STAFF)) assertFalse(StaffRole.platformOnly(r), r);
        assertTrue(StaffRole.onlineOnly(StaffRole.MERCHANT_SERVICE));
        assertFalse(StaffRole.onlineOnly(StaffRole.CITY_LEAD));
    }

    // ------------------------------------------------------------------ 真实控制器上的标注

    private static String path(Method m)
    {
        GetMapping g = m.getAnnotation(GetMapping.class);
        if (g != null) return g.value().length == 0 ? "" : g.value()[0];
        PostMapping p = m.getAnnotation(PostMapping.class);
        if (p != null) return p.value().length == 0 ? "" : p.value()[0];
        PutMapping u = m.getAnnotation(PutMapping.class);
        if (u != null) return u.value().length == 0 ? "" : u.value()[0];
        return null;
    }

    @Test
    void merchantRequestControllerEntrancesOpenExactlyOneRole()
    {
        int seen = 0;
        for (Method m : AppStaffMerchantRequestController.class.getDeclaredMethods())
        {
            String p = path(m);
            if (p == null) continue;
            seen++;
            AppAuth a = m.getAnnotation(AppAuth.class);
            assertNotNull(a, p);
            assertTrue(a.staff(), p);
            assertFalse(a.onlineStaff(), p);
            assertEquals(p.startsWith("/service/"), a.merchantService(), p);
            assertEquals(p.startsWith("/city/"), a.cityLead(), p);
            assertEquals(p.startsWith("/merchant/requests"), a.merchant(), p);
        }
        assertTrue(seen >= 20, "接口都标注过");
    }

    @Test
    void cityLeadIsOpenOnlyOnTheStoreSwitcherEndpoints()
    {
        List<String> open = new ArrayList<String>();
        for (Class<?> c : Arrays.asList(AppStaffController.class, AppStaffExtController.class, AppStaffOpsController.class, AppStaffOnlineMaterialController.class))
        {
            for (Method m : c.getDeclaredMethods())
            {
                AppAuth a = m.getAnnotation(AppAuth.class);
                if (a != null && a.cityLead()) open.add(path(m));
            }
        }
        java.util.Collections.sort(open);
        assertEquals(Arrays.asList("/stores", "/team/hub", "/team/roles", "/team/stores", "/team/switch"), open);
    }

    @Test
    void serviceIsOpenOnTheOnlineTaskEndpointsButNotOnOfflineOnes()
    {
        for (Method m : AppStaffExtController.class.getDeclaredMethods())
        {
            String p = path(m);
            AppAuth a = m.getAnnotation(AppAuth.class);
            if (p == null || a == null) continue;
            assertEquals(p.startsWith("/online/"), a.merchantService(), p);
        }
        for (Method m : AppStaffOnlineMaterialController.class.getDeclaredMethods())
        {
            AppAuth a = m.getAnnotation(AppAuth.class);
            if (a != null) assertTrue(a.merchantService(), m.getName());
        }
        for (Method m : AppStaffController.class.getDeclaredMethods())
        {
            String p = path(m);
            AppAuth a = m.getAnnotation(AppAuth.class);
            if (p == null || a == null) continue;
            boolean mirrorsOnlineStaff = a.onlineStaff();
            assertEquals(mirrorsOnlineStaff, a.merchantService(), p);
        }
        for (Method m : AppStaffOpsController.class.getDeclaredMethods())
        {
            String p = path(m);
            AppAuth a = m.getAnnotation(AppAuth.class);
            if (p == null || a == null) continue;
            assertEquals(p.startsWith("/team/") && a.onlineStaff(), a.merchantService(), p);
        }
    }

    @Test
    void onlineOverviewIsTheOnlyDailyEndpointOpenToService()
    {
        int seen = 0;
        for (Method m : AppStaffDailyController.class.getDeclaredMethods())
        {
            String p = path(m);
            if (p == null) continue;
            seen++;
            // 这个控制器类上标了 @AppAuth(staff = true)：方法上没标的按类走，商家服务人的授权会被去掉
            AppAuth a = m.getAnnotation(AppAuth.class);
            if ("/online".equals(p))
            {
                assertNotNull(a, p);
                assertTrue(a.staff(), p);
                assertTrue(a.merchantService(), p);
                assertFalse(a.merchant(), p);
                assertFalse(a.onlineStaff(), p);
                assertFalse(a.cityLead(), p);
            }
            else
            {
                assertTrue(a == null || !a.merchantService(), p);
            }
        }
        assertEquals(3, seen);
    }

    @Test
    void serviceAuditAndReturnEndpointsExist()
    {
        List<String> open = new ArrayList<String>();
        for (Method m : AppStaffMerchantRequestController.class.getDeclaredMethods())
        {
            AppAuth a = m.getAnnotation(AppAuth.class);
            if (a != null && a.merchantService()) open.add(path(m));
        }
        assertTrue(open.contains("/service/activities"), open.toString());
        assertTrue(open.contains("/service/activities/{activityId:[0-9]+}/audit"), open.toString());
        assertTrue(open.contains("/service/requests/{requestId:[0-9]+}/return"), open.toString());
    }

    @Test
    void payoutEndpointIsOpenToMerchantAndService() throws Exception
    {
        Method m = null;
        for (Method x : AppStaffExtController.class.getDeclaredMethods())
        {
            if ("onlinePayout".equals(x.getName())) m = x;
        }
        assertNotNull(m);
        AppAuth a = m.getAnnotation(AppAuth.class);
        assertTrue(a.merchant());
        assertTrue(a.merchantService());
        assertFalse(a.onlineStaff());
        assertFalse(a.cityLead());
    }
}
