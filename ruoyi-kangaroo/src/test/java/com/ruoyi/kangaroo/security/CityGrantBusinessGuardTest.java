package com.ruoyi.kangaroo.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.method.HandlerMethod;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.staff.RolePhase;

class CityGrantBusinessGuardTest {
    private final AppAuthInterceptor interceptor = new AppAuthInterceptor();
    private final AppTokenService tokens = mock(AppTokenService.class);
    private final AppIdentityLoader loader = mock(AppIdentityLoader.class);
    private final AppLoginUser login = new AppLoginUser();
    public static class Endpoint {
        @AppAuth(staff=true,merchant=true,merchantService=true,onlineStaff=true,cityLead=true) public void business() {}
        @AppAuth public void personal() {}
        /** 同 AppStaffTaskController 的注解 */
        @AppAuth(staff=true,onlineStaff=true,merchantService=true,cityGrant=true) public void tasks() {}
    }
    @BeforeEach void setup() {
        RolePhase.set(RolePhase.NEW); login.setUserId(7L);
        ReflectionTestUtils.setField(interceptor,"appTokenService",tokens); ReflectionTestUtils.setField(interceptor,"identities",loader);
        when(tokens.getLoginUser(any())).thenReturn(login);
    }
    @AfterEach void clear() { AppUserContext.remove(); RolePhase.set(RolePhase.LEGACY); }
    private StaffGrantView city(String role) { StaffGrantView g = new StaffGrantView(); g.setActive(true); g.setCityId(20L); g.setCity("大连"); g.setRoleCode(role); return g; }
    private boolean invoke(String path,String method) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",path); request.setServletPath(path);
        return interceptor.preHandle(request,new MockHttpServletResponse(),new HandlerMethod(new Endpoint(),Endpoint.class.getMethod(method)));
    }
    @Test void everyPendingRoleIsForbiddenFromBusinessAndPublishEvenByDirectUrl() {
        for(String role:Arrays.asList("manager","keeper","online_staff","merchant_service","merchant","city_lead")) {
            login.setStaffGrants(Arrays.asList(city(role)));
            for(String path:Arrays.asList("/app/staff/jobs","/app/staff/rooms","/app/manage/tasks","/app/staff/merchant/request")) {
                ServiceException e = assertThrows(ServiceException.class,()->invoke(path,"business"),role+path);
                assertEquals(403,e.getCode()); assertNull(AppUserContext.get()); assertEquals(1,login.getStaffGrants().size());
            }
        }
    }
    @Test void pendingIdentityCanReadOwnScopesAndPersonalProfile() throws Exception {
        login.setStaffGrants(Arrays.asList(city("merchant")));
        assertTrue(invoke("/app/staff/stores","business")); assertEquals(20L,AppUserContext.get().getStaffGrants().get(0).getCityId());
        assertTrue(invoke("/app/me","personal")); assertEquals(1,AppUserContext.get().getStaffGrants().size());
    }
    @Test void mixedAccountGetsOnlyRealStoreScopesWithoutMutatingCachedIdentity() throws Exception {
        StaffGrantView real = city("manager"); real.setStoreId(2L);
        login.setStaffGrants(Arrays.asList(city("merchant"),real));
        assertTrue(invoke("/app/staff/jobs","business"));
        assertEquals(1,AppUserContext.get().getStaffGrants().size()); assertTrue(AppUserContext.get().hasStoreAccess(2L));
        assertFalse(AppUserContext.get().hasStoreAccess(999L)); assertEquals(2,login.getStaffGrants().size());
    }
    @Test void revokedIdentityCannotAccessScopeListOrBusiness() {
        login.setStaffGrants(Collections.emptyList());
        assertEquals(403,assertThrows(ServiceException.class,()->invoke("/app/staff/stores","business")).getCode());
        assertEquals(403,assertThrows(ServiceException.class,()->invoke("/app/staff/jobs","business")).getCode());
    }
    @Test void storelessCityGrantCanHandlePlatformTasksOnly() throws Exception {
        for(String role:Arrays.asList("city_lead","manager","keeper","online_staff","merchant_service")) {
            login.setStaffGrants(Arrays.asList(city(role)));
            assertTrue(invoke("/app/staff/platform-tasks","tasks"),role);
            assertEquals(1,AppUserContext.get().getStaffGrants().size()); assertEquals(20L,AppUserContext.get().getStaffGrants().get(0).getCityId());
            AppUserContext.remove();
            ServiceException e = assertThrows(ServiceException.class,()->invoke("/app/staff/jobs","business"),role);
            assertEquals(403,e.getCode()); assertTrue(e.getMessage().contains("本城还没有开通门店"),e.getMessage());
        }
    }
    @Test void storelessMerchantStillCannotOpenPlatformTasks() {
        login.setStaffGrants(Arrays.asList(city("merchant")));
        assertEquals(403,assertThrows(ServiceException.class,()->invoke("/app/staff/platform-tasks","tasks")).getCode());
        ServiceException e = assertThrows(ServiceException.class,()->invoke("/app/staff/jobs","business"));
        assertTrue(e.getMessage().contains("门店还没绑定"),e.getMessage());
    }
    @Test void ordinaryUserTaskEndpointKeepsItsOwnArrivalBasedAuthorization() throws Exception {
        login.setStaffGrants(Collections.emptyList());
        assertTrue(invoke("/app/manage/tasks","personal"));
    }
}
