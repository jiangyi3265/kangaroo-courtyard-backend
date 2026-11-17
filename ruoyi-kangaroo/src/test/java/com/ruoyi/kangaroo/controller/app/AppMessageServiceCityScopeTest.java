package com.ruoyi.kangaroo.controller.app;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.security.*;

class AppMessageServiceCityScopeTest
{
    private final AppMessageController controller = new AppMessageController();
    @AfterEach void cleanup() { AppUserContext.remove(); }
    private StaffGrantView grant(Long store, String city, String role, boolean active)
    {
        StaffGrantView grant = new StaffGrantView();
        grant.setStoreId(store); grant.setCity(city); grant.setRoleCode(role); grant.setActive(active);
        return grant;
    }
    private AppLoginUser user(StaffGrantView... grants)
    {
        AppLoginUser me = new AppLoginUser(); me.setUserId(1L); me.setStaffGrants(Arrays.asList(grants));
        AppUserContext.set(me); return me;
    }

    @Test void cityScopeCombinesOnlyActiveServiceGrantsInExactlyThatCity()
    {
        user(grant(1L,"平潭","merchant_service",true), grant(2L," 平潭 ","merchant_service",true),
            grant(3L,"丽江","merchant_service",true), grant(4L,"平潭","merchant",true),
            grant(5L,"平潭","city_lead",true), grant(6L,"平潭","merchant_service",false),
            grant(7L,null,"merchant_service",true));
        controller.selectMessageScope("service-city:%E5%B9%B3%E6%BD%AD");
        assertEquals(Arrays.asList(1L,2L),AppUserContext.messageStores());
        assertEquals("merchant_service",AppUserContext.messageRole());
        assertTrue(AppUserContext.messageStayStores().isEmpty());
    }

    @Test void inventedCityRoleBorrowingAndMalformedScopesAreRejected()
    {
        user(grant(1L,"平潭","merchant_service",true),grant(2L,"丽江","city_lead",true),grant(3L,"杭州","merchant",true));
        for(String scope:Arrays.asList("service-city:","service-city:%GG","service-city:%E4", "service-city:%E4%B8%BD%E6%B1%9F", "service-city:%E6%9D%AD%E5%B7%9E", "service-city:unknown"))
        {
            ServiceException e=assertThrows(ServiceException.class,()->controller.selectMessageScope(scope));
            assertEquals(403,e.getCode()); assertNull(AppUserContext.messageStores());
        }
    }

    @Test void encodingRoundTripsReservedCityCharactersAndDoesNotDecodeTwice()
    {
        StaffGrantView grant=grant(1L," A+B 城!(')*~% ","merchant_service",true);user(grant);
        String scope=ServiceCityMessageScope.key(grant);
        assertEquals("service-city:A%2BB%20%E5%9F%8E!(')*~%25",scope);
        controller.selectMessageScope(scope);assertEquals(Collections.singletonList(1L),AppUserContext.messageStores());
        assertThrows(ServiceException.class,()->controller.selectMessageScope("service-city:A%252BB%20%E5%9F%8E!(')*~%25"));
    }

    @Test void preauthorizedCityWithoutStoresCannotCreateAnUnscopedInbox()
    {
        user(grant(null,"平潭","merchant_service",true));
        controller.selectMessageScope("service-city:%E5%B9%B3%E6%BD%AD");
        assertEquals(Collections.singletonList(-1L),AppUserContext.messageStores());
    }

    @Test void oldStoreScopeRemainsStoreSpecificAndMissingCityIsNotAggregated()
    {
        StaffGrantView unknown=grant(3L,null,"merchant_service",true);
        user(grant(1L,"平潭","merchant_service",true),grant(2L,"平潭","merchant_service",true),unknown);
        controller.selectMessageScope("store:2:merchant_service");
        assertEquals(Collections.singletonList(2L),AppUserContext.messageStores());
        assertNull(ServiceCityMessageScope.key(unknown));
        controller.selectMessageScope("store:3:merchant_service");
        assertEquals(Collections.singletonList(3L),AppUserContext.messageStores());
    }

    @Test void revokedGrantCannotBeRecoveredByAStaleCityScope()
    {
        AppLoginUser me=user(grant(1L,"平潭","merchant_service",true),grant(2L,"平潭","merchant_service",true));
        controller.selectMessageScope("service-city:%E5%B9%B3%E6%BD%AD");
        me.setStaffGrants(Collections.singletonList(grant(2L,"平潭","merchant_service",true)));
        controller.selectMessageScope("service-city:%E5%B9%B3%E6%BD%AD");
        assertEquals(Collections.singletonList(2L),AppUserContext.messageStores());
        me.setStaffGrants(Collections.emptyList());
        assertThrows(ServiceException.class,()->controller.selectMessageScope("service-city:%E5%B9%B3%E6%BD%AD"));
    }
}
