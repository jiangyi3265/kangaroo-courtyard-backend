package com.ruoyi.kangaroo.security;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.*;
import org.springframework.web.method.HandlerMethod;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.staff.StaffPerms;
import com.ruoyi.kangaroo.controller.app.AppStaffSlotController;
class MerchantAccessTest {
    static class Endpoints {
        @AppAuth(staff=true) public void finance(){}
        @AppAuth(staff=true,merchant=true) public void jobs(){}
    }
    private StaffGrantView grant(long id,String role){StaffGrantView g=new StaffGrantView();g.setStoreId(id);g.setRoleCode(role);g.setActive(true);return g;}
    private AppLoginUser user(StaffGrantView... grants){AppLoginUser u=new AppLoginUser();u.setUserId(10L);u.setStaffGrants(Arrays.asList(grants));return u;}
    private void invoke(AppLoginUser user,String method)throws Exception{
        invoke(user,new HandlerMethod(new Endpoints(),Endpoints.class.getMethod(method)));
    }
    private void invoke(AppLoginUser user,HandlerMethod handler)throws Exception{
        AppAuthInterceptor i=new AppAuthInterceptor();AppTokenService tokens=mock(AppTokenService.class);
        when(tokens.getLoginUser(any())).thenReturn(user);
        ReflectionTestUtils.setField(i,"appTokenService",tokens);
        ReflectionTestUtils.setField(i,"identities",mock(AppIdentityLoader.class));
        i.preHandle(new MockHttpServletRequest("GET","/app/staff/test"),new MockHttpServletResponse(),handler);
    }
    @AfterEach void clear(){AppUserContext.remove();}
    @Test void merchantCannotUseUnapprovedStaffEndpoint()throws Exception{
        AppLoginUser u=user(grant(1,"merchant"));
        assertThrows(ServiceException.class,()->invoke(u,"finance"));assertNull(AppUserContext.get());
        invoke(u,"jobs");assertEquals(1,AppUserContext.get().getStaffGrants().size());
    }
    @Test void mixedRolesKeepOnlyAuthorizedStoresWithoutMutatingCachedIdentity()throws Exception{
        AppLoginUser u=user(grant(1,"merchant"),grant(2,"manager"));invoke(u,"finance");
        assertEquals(2,u.getStaffGrants().size());assertEquals(1,AppUserContext.get().getStaffGrants().size());
        assertEquals(2L,AppUserContext.get().getStaffGrants().get(0).getStoreId());
    }
    @Test void merchantCanSubmitButCannotApproveListingOrPay(){
        assertTrue(StaffRole.exists("merchant"));assertTrue(StaffPerms.allows("merchant","jobs","edit"));
        assertTrue(StaffPerms.allows("merchant","online","edit"));
        for(String item:List.of("jobs","rooms","activities"))assertFalse(StaffPerms.allows("merchant",item,"audit"));
        for(String item:List.of("finance","dispatch","dashboard","insurance"))assertFalse(StaffPerms.allows("merchant",item,"view"));
        assertFalse(StaffPerms.allows("merchant","online","pay"));
    }
    @Test void merchantCanReachInterviewSlotEndpoints()throws Exception{
        AppStaffSlotController controller=new AppStaffSlotController(null);
        AppLoginUser u=user(grant(1,"merchant"));
        for(java.lang.reflect.Method method:AppStaffSlotController.class.getDeclaredMethods()){
            invoke(u,new HandlerMethod(controller,method));
            assertEquals("merchant",AppUserContext.get().getStaffGrants().get(0).getRoleCode());
        }
        assertTrue(StaffPerms.allows("merchant","jobs","view"));
        assertTrue(StaffPerms.allows("merchant","jobs","edit"));
    }
}
