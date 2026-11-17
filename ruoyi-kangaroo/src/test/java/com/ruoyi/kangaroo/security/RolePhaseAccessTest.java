package com.ruoyi.kangaroo.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.method.HandlerMethod;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.controller.app.AppAuthController;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.KgStaffClientSeen;
import com.ruoyi.kangaroo.ext.staff.RolePhase;

/** 拦截器的阶段行为：cityLeadAfterSwitch 只在 new 阶段放行；切换后老版本的老主理人看到「请更新」；版本记录只记录，不影响请求。 */
class RolePhaseAccessTest
{
    static class Endpoints
    {
        @AppAuth(staff = true) public void plain() {}
        @AppAuth(staff = true, cityLead = true) public void city() {}
        @AppAuth(staff = true, cityLeadAfterSwitch = true) public void afterSwitch() {}
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

    private void invoke(AppLoginUser user, String method, String path, KgStaffClientSeen seen, String header) throws Exception
    {
        AppAuthInterceptor i = new AppAuthInterceptor();
        AppTokenService tokens = mock(AppTokenService.class);
        when(tokens.getLoginUser(any())).thenReturn(user);
        ReflectionTestUtils.setField(i, "appTokenService", tokens);
        ReflectionTestUtils.setField(i, "identities", mock(AppIdentityLoader.class));
        ReflectionTestUtils.setField(i, "clientSeen", seen);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        if (header != null)
        {
            request.addHeader("X-App-Client", header);
        }
        i.preHandle(request, new MockHttpServletResponse(), new HandlerMethod(new Endpoints(), Endpoints.class.getMethod(method)));
    }

    private ServiceException denied(AppLoginUser user, String method, String path)
    {
        ServiceException e = assertThrows(ServiceException.class, () -> invoke(user, method, path, null, null));
        assertEquals(403, e.getCode());
        return e;
    }

    @AfterEach
    void clear()
    {
        RolePhase.set(RolePhase.LEGACY);
        AppUserContext.remove();
    }

    @Test
    void afterSwitchMarkIsClosedInLegacyAndOpenInNew() throws Exception
    {
        AppLoginUser lead = user(grant(1, StaffRole.CITY_LEAD));
        denied(lead, "afterSwitch", "/app/staff/city-finance/refunds");
        RolePhase.set(RolePhase.NEW);
        invoke(lead, "afterSwitch", "/app/staff/city-finance/refunds", null, null);
        assertEquals(StaffRole.CITY_LEAD, AppUserContext.get().getStaffGrants().get(0).getRoleCode());
    }

    @Test
    void otherEndpointsClosedToCityLeadOnlyBeforeTheSwitch() throws Exception
    {
        AppLoginUser lead = user(grant(1, StaffRole.CITY_LEAD));
        denied(lead, "plain", "/app/staff/activities");
        denied(lead, "plain", "/app/manage/daily");
        invoke(lead, "city", "/app/staff/city/requests", null, null);
        // 切换后全部权限（2026-11-01）：接口不再挡他，能不能做看权限矩阵和各接口自己的角色判断
        RolePhase.set(RolePhase.NEW);
        invoke(lead, "plain", "/app/staff/activities", null, null);
        invoke(lead, "plain", "/app/manage/daily", null, null);
        invoke(lead, "city", "/app/staff/city/requests", null, null);
    }

    @Test
    void deniedMessagesStayPlain() throws Exception
    {
        AppLoginUser lead = user(grant(1, StaffRole.CITY_LEAD));
        assertEquals("当前角色不能使用这项功能", denied(lead, "plain", "/app/staff/jobs").getMessage(), "legacy 文字不变");
        RolePhase.set(RolePhase.NEW);
        invoke(lead, "plain", "/app/staff/jobs", null, null);
        AppLoginUser service = user(grant(1, StaffRole.MERCHANT_SERVICE));
        assertEquals("当前角色不能使用这项功能", denied(service, "plain", "/app/staff/jobs").getMessage(), "商家服务人的角色没变，不说这句");
    }

    @Test
    void cityLeadMixedAccountKeepsBothGrantsAfterSwitch() throws Exception
    {
        AppLoginUser mixed = user(grant(1, StaffRole.CITY_LEAD), grant(2, StaffRole.MANAGER));
        invoke(mixed, "plain", "/app/staff/jobs", null, null);
        assertEquals(1, AppUserContext.get().getStaffGrants().size(), "切换前去掉城市负责人那行");
        assertEquals(StaffRole.MANAGER, AppUserContext.get().getStaffGrants().get(0).getRoleCode());
        AppUserContext.remove();
        RolePhase.set(RolePhase.NEW);
        invoke(mixed, "plain", "/app/staff/jobs", null, null);
        assertEquals(2, AppUserContext.get().getStaffGrants().size());
    }

    @Test
    void clientSeenFailureDoesNotAffectTheRequest() throws Exception
    {
        KgStaffClientSeen seen = mock(KgStaffClientSeen.class);
        invoke(user(grant(1, StaffRole.MANAGER)), "plain", "/app/staff/jobs", seen, "release/2026.10.30.2");
        verify(seen).record(any(), eq("release/2026.10.30.2"));
    }

    // ---------- KgStaffClientSeen

    private KgStaffClientSeen seenWith(KgJdbc db)
    {
        KgStaffClientSeen s = new KgStaffClientSeen();
        ReflectionTestUtils.setField(s, "db", db);
        return s;
    }

    @Test
    void recordsOnlyStaffAccountsAndDedupes()
    {
        KgJdbc db = mock(KgJdbc.class);
        KgStaffClientSeen s = seenWith(db);
        s.record(user(), "release/2026.10.30.2");
        s.record(null, "release/2026.10.30.2");
        verifyNoInteractions(db);
        AppLoginUser u = user(grant(1, StaffRole.MANAGER));
        s.record(u, "release/2026.10.30.2");
        s.record(u, "release/2026.10.30.2");
        verify(db, times(1)).update(anyString(), anyMap());
        s.record(u, "release/2026.10.30.3");
        verify(db, times(2)).update(anyString(), argThat((Map<String, ?> m) -> "release".equals(m.get("c"))));
    }

    @Test
    void oldClientWithoutHeaderAndBadHeadersAreRecordedAsNone()
    {
        KgJdbc db = mock(KgJdbc.class);
        KgStaffClientSeen s = seenWith(db);
        s.record(user(grant(1, StaffRole.MANAGER)), null);
        verify(db).update(anyString(), argThat((Map<String, ?> m) -> "none".equals(m.get("c")) && "".equals(m.get("v"))));
        KgJdbc db2 = mock(KgJdbc.class);
        seenWith(db2).record(user(grant(1, StaffRole.MANAGER)), "release/<script>");
        verify(db2).update(anyString(), argThat((Map<String, ?> m) -> "none".equals(m.get("c"))));
    }

    @Test
    void databaseFailureIsSwallowedAndRetriedLater()
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.update(anyString(), anyMap())).thenThrow(new RuntimeException("db down")).thenReturn(1);
        KgStaffClientSeen s = seenWith(db);
        AppLoginUser u = user(grant(1, StaffRole.MANAGER));
        assertDoesNotThrow(() -> s.record(u, "release/1"));
        s.record(u, "release/1");
        verify(db, times(2)).update(anyString(), anyMap());
    }

    // ---------- ping

    @Test
    void pingReportsThePhase()
    {
        AppAuthController c = new AppAuthController();
        ReflectionTestUtils.setField(c, "wxMiniAppClient", mock(WxMiniAppClient.class));
        Map<?, ?> data = (Map<?, ?>) c.ping().get("data");
        assertEquals(RolePhase.LEGACY, data.get("rolePhase"));
        RolePhase.set(RolePhase.NEW);
        data = (Map<?, ?>) c.ping().get("data");
        assertEquals(RolePhase.NEW, data.get("rolePhase"));
    }
}
