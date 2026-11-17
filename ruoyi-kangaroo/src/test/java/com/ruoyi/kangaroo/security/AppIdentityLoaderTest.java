package com.ruoyi.kangaroo.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Collections;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.domain.KgAppUser;
import com.ruoyi.kangaroo.mapper.KgAppUserMapper;
import com.ruoyi.kangaroo.mapper.KgStaffGrantMapper;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.method.HandlerMethod;

class AppIdentityLoaderTest
{
    private KgAppUserMapper users;
    private KgStaffGrantMapper grants;
    private AppIdentityLoader loader;
    private AppLoginUser session;
    @BeforeEach void setup() {
        users=mock(KgAppUserMapper.class);grants=mock(KgStaffGrantMapper.class);loader=new AppIdentityLoader(users,grants);
        session=new AppLoginUser();session.setUserId(42L);session.setStaffGrants(Collections.singletonList(new StaffGrantView()));
    }
    private KgAppUser active() {KgAppUser u=new KgAppUser();u.setUserId(42L);u.setStatus("0");u.setDelFlag("0");when(users.selectKgAppUserByUserId(42L)).thenReturn(u);return u;}
    @Test void committedGrantRevocationReplacesStaleSessionAuthority() {
        KgAppUser u=active();u.setNickname("current name");u.setPhone("13800138000");
        when(grants.selectActiveGrantsWithStore(42L)).thenReturn(Collections.emptyList());
        loader.reload(session);assertFalse(session.hasStaffAccess());assertEquals("current name",session.getNickname());assertEquals(u.getPhone(),session.getPhone());
    }
    @Test void stoppedDeletedAndMissingUsersAreRejected() {
        assertEquals(401,assertThrows(ServiceException.class,()->loader.reload(session)).getCode());
        KgAppUser u=active();u.setDelFlag("2");assertEquals(401,assertThrows(ServiceException.class,()->loader.reload(session)).getCode());
        u.setDelFlag("0");u.setStatus("1");assertEquals(403,assertThrows(ServiceException.class,()->loader.reload(session)).getCode());
        verifyNoInteractions(grants);
    }
    private static com.ruoyi.kangaroo.domain.KgStaffGrant row(String role, String also) {
        com.ruoyi.kangaroo.domain.KgStaffGrant g=new com.ruoyi.kangaroo.domain.KgStaffGrant();g.setGrantId(1L);g.setStoreId(9L);g.setRoleCode(role);g.setAlsoService(also);return g;
    }
    @Test void alsoServiceIsTrueOnlyForAKeeperWithTheSwitchOnAndAlwaysABoolean() {
        when(grants.selectActiveGrantsWithStore(42L)).thenReturn(java.util.Arrays.asList(row("keeper","1"),row("keeper","0"),row("keeper",null),row("staff","1"),row("merchant_service","1")));
        java.util.List<StaffGrantView> views=loader.grants(42L);
        assertEquals(Boolean.TRUE,views.get(0).getAlsoService());
        for (int i=1;i<views.size();i++) assertEquals(Boolean.FALSE,views.get(i).getAlsoService(),views.get(i).getRoleCode());
    }
    static class Endpoint {@AppAuth(staff=true) public void staff() {}}
    @Test void databaseFailureCannotFallBackToCachedGrantsOrLeakContext() throws Exception {
        active();when(grants.selectActiveGrantsWithStore(42L)).thenThrow(new IllegalStateException("database unavailable"));
        AppTokenService tokens=mock(AppTokenService.class);when(tokens.getLoginUser(any())).thenReturn(session);
        AppAuthInterceptor interceptor=new AppAuthInterceptor();ReflectionTestUtils.setField(interceptor,"appTokenService",tokens);ReflectionTestUtils.setField(interceptor,"identities",loader);
        AppUserContext.set(session);
        try {assertThrows(IllegalStateException.class,()->interceptor.preHandle(new MockHttpServletRequest(),new MockHttpServletResponse(),new HandlerMethod(new Endpoint(),Endpoint.class.getMethod("staff"))));assertNull(AppUserContext.get());}
        finally {AppUserContext.remove();}
    }
}
