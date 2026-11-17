package com.ruoyi.kangaroo.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.kangaroo.domain.KgAppUser;
import com.ruoyi.kangaroo.domain.KgStaffGrant;
import com.ruoyi.kangaroo.domain.KgStore;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.mapper.KgStaffGrantMapper;
import com.ruoyi.kangaroo.security.AppTokenService;
import com.ruoyi.kangaroo.security.StaffGrantView;
import com.ruoyi.kangaroo.service.IKgAppUserService;
import com.ruoyi.kangaroo.service.IKgMessageService;
import com.ruoyi.kangaroo.service.IKgStoreService;

/** 后台授权页的「同时担任商家服务人」：只有小院主理人能开，别的角色一律存 0；改完刷新会话；复活旧授权不能带回旧开关。 */
class KgStaffGrantAlsoServiceTest
{
    private final KgStaffGrantMapper mapper = mock(KgStaffGrantMapper.class);
    private final IKgStoreService stores = mock(IKgStoreService.class);
    private final IKgAppUserService users = mock(IKgAppUserService.class);
    private final AppTokenService tokens = mock(AppTokenService.class);
    private final IKgMessageService messages = mock(IKgMessageService.class);
    private final KgStaffGrantServiceImpl service = new KgStaffGrantServiceImpl();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "kgStaffGrantMapper", mapper);
        ReflectionTestUtils.setField(service, "kgStoreService", stores);
        ReflectionTestUtils.setField(service, "kgAppUserService", users);
        ReflectionTestUtils.setField(service, "appTokenService", tokens);
        ReflectionTestUtils.setField(service, "messages", messages);
        KgStore store = new KgStore();
        store.setStoreName("喜洲小院");
        when(stores.requireStore(anyLong())).thenReturn(store);
        KgAppUser user = new KgAppUser();
        user.setStatus("0");
        when(users.requireUser(anyLong())).thenReturn(user);
        SysUser sys = new SysUser();
        sys.setUserName("admin");
        LoginUser login = new LoginUser();
        login.setUser(sys);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login, null, Collections.emptyList()));
    }

    @AfterEach
    void clear()
    {
        SecurityContextHolder.clearContext();
    }

    private static KgStaffGrant input(String role, String also)
    {
        KgStaffGrant g = new KgStaffGrant();
        g.setAppUserId(7L);
        g.setStoreId(9L);
        g.setRoleCode(role);
        g.setAlsoService(also);
        return g;
    }

    private KgStaffGrant inserted()
    {
        ArgumentCaptor<KgStaffGrant> row = ArgumentCaptor.forClass(KgStaffGrant.class);
        verify(mapper).insertKgStaffGrant(row.capture());
        return row.getValue();
    }

    private KgStaffGrant updated()
    {
        ArgumentCaptor<KgStaffGrant> row = ArgumentCaptor.forClass(KgStaffGrant.class);
        verify(mapper).updateKgStaffGrant(row.capture());
        return row.getValue();
    }

    private void onExisting(long grantId, String role, String also)
    {
        KgStaffGrant current = input(role, also);
        current.setGrantId(grantId);
        current.setStatus("0");
        when(mapper.selectKgStaffGrantByGrantId(grantId)).thenReturn(current);
    }

    @Test
    void newKeeperGrantStoresTheSwitch()
    {
        service.grant(input(StaffRole.KEEPER, "1"));
        assertEquals("1", inserted().getAlsoService());
        verify(tokens).refreshGrants(eq(7L), any());
        // 通知里写清楚两个身份，他才知道工作台为什么多了东西
        verify(messages).send(eq(7L), eq("user"), anyString(), anyString(), contains("小院主理人 + 商家服务人"), anyString(), any(), anyString());
    }

    @Test
    void cityLeadCanBeGrantedBeforeAnyStoreExists() {
        com.ruoyi.kangaroo.ext.KgJdbc db = mock(com.ruoyi.kangaroo.ext.KgJdbc.class);
        ReflectionTestUtils.setField(service, "db", db);
        when(db.one(anyString(), anyMap())).thenReturn(com.ruoyi.kangaroo.ext.KgJdbc.p("name", "大连"));
        KgStaffGrant g = input(StaffRole.CITY_LEAD, "0"); g.setStoreId(null); g.setCityId(20L);
        service.grant(g);
        assertNull(inserted().getStoreId()); assertEquals(20L, inserted().getCityId());
        verify(stores, never()).requireStore(any());
        verify(mapper).selectByUserAndCity(7L,20L);
    }

    @Test
    void cityGrantRejectsUnknownRoleAndMixedScope() {
        KgStaffGrant g = input("super_admin", "0"); g.setStoreId(null); g.setCityId(20L);
        assertThrows(com.ruoyi.common.exception.ServiceException.class, () -> service.grant(g));
        g.setRoleCode(StaffRole.CITY_LEAD); g.setStoreId(9L);
        assertThrows(com.ruoyi.common.exception.ServiceException.class, () -> service.grant(g));
        verify(mapper, never()).insertKgStaffGrant(any());
    }

    @Test
    void allCurrentRolesCanBeAuthorizedBeforeStoresExist() {
        com.ruoyi.kangaroo.ext.KgJdbc db = mock(com.ruoyi.kangaroo.ext.KgJdbc.class);
        ReflectionTestUtils.setField(service, "db", db);
        when(db.one(anyString(), anyMap())).thenReturn(com.ruoyi.kangaroo.ext.KgJdbc.p("name", "大连"));
        for (String role : Arrays.asList(StaffRole.MANAGER, StaffRole.KEEPER, StaffRole.ONLINE_STAFF, StaffRole.MERCHANT_SERVICE, StaffRole.CITY_LEAD, StaffRole.MERCHANT)) {
            clearInvocations(mapper);
            KgStaffGrant g = input(role, "1"); g.setStoreId(null); g.setCityId(20L);
            service.grant(g);
            KgStaffGrant row = inserted();
            assertNull(row.getStoreId()); assertEquals(20L, row.getCityId()); assertEquals(role, row.getRoleCode());
            assertEquals(StaffRole.KEEPER.equals(role) ? "1" : "0", row.getAlsoService());
        }
        verify(stores, never()).requireStore(any());
    }

    @Test
    void cityRoleMayChangeAndRevokeStillRefreshesSession() {
        KgStaffGrant current = input(StaffRole.MANAGER, "0"); current.setStoreId(null); current.setCityId(20L); current.setGrantId(50L); current.setStatus("0");
        when(mapper.selectKgStaffGrantByGrantId(50L)).thenReturn(current);
        KgStaffGrant change = new KgStaffGrant(); change.setGrantId(50L); change.setRoleCode(StaffRole.MERCHANT);
        service.updateRole(change);
        assertEquals(StaffRole.MERCHANT, updated().getRoleCode());
        clearInvocations(tokens);
        service.revoke(new Long[] { 50L });
        verify(tokens).refreshGrants(eq(7L), any());
    }

    @Test
    void disabledOrBroadCityIsRejected() {
        com.ruoyi.kangaroo.ext.KgJdbc db = mock(com.ruoyi.kangaroo.ext.KgJdbc.class);
        ReflectionTestUtils.setField(service, "db", db);
        KgStaffGrant g = input(StaffRole.CITY_LEAD, "0"); g.setStoreId(null); g.setCityId(20L);
        assertThrows(com.ruoyi.common.exception.ServiceException.class, () -> service.grant(g));
        when(db.one(anyString(), anyMap())).thenReturn(com.ruoyi.kangaroo.ext.KgJdbc.p("name", "全国"));
        assertThrows(com.ruoyi.common.exception.ServiceException.class, () -> service.grant(g));
        verify(mapper, never()).insertKgStaffGrant(any());
    }

    @Test
    void newGrantWithoutTheSwitchIsZero()
    {
        service.grant(input(StaffRole.KEEPER, null));
        assertEquals("0", inserted().getAlsoService());
    }

    @Test
    void otherRolesAlwaysStoreZero()
    {
        for (String role : Arrays.asList(StaffRole.MANAGER, StaffRole.STAFF, StaffRole.ONLINE_STAFF, StaffRole.MERCHANT, StaffRole.MERCHANT_SERVICE, StaffRole.CITY_LEAD))
        {
            KgStaffGrantMapper m = mock(KgStaffGrantMapper.class);
            ReflectionTestUtils.setField(service, "kgStaffGrantMapper", m);
            service.grant(input(role, "1"));
            ArgumentCaptor<KgStaffGrant> row = ArgumentCaptor.forClass(KgStaffGrant.class);
            verify(m).insertKgStaffGrant(row.capture());
            assertEquals("0", row.getValue().getAlsoService(), role);
        }
    }

    @Test
    void reactivationClearsTheOldSwitchUnlessAskedAgain()
    {
        KgStaffGrant old = input(StaffRole.KEEPER, "1");
        old.setGrantId(50L);
        old.setStatus("1");
        when(mapper.selectByUserAndStore(7L, 9L)).thenReturn(old);

        service.grant(input(StaffRole.KEEPER, null));
        verify(mapper).reactivateGrant(50L, StaffRole.KEEPER, "admin");
        verify(mapper, never()).updateKgStaffGrant(any());

        KgStaffGrantMapper m = mock(KgStaffGrantMapper.class);
        when(m.selectByUserAndStore(7L, 9L)).thenReturn(old);
        ReflectionTestUtils.setField(service, "kgStaffGrantMapper", m);
        service.grant(input(StaffRole.KEEPER, "1"));
        verify(m).reactivateGrant(50L, StaffRole.KEEPER, "admin");
        ArgumentCaptor<KgStaffGrant> row = ArgumentCaptor.forClass(KgStaffGrant.class);
        verify(m).updateKgStaffGrant(row.capture());
        assertEquals(50L, row.getValue().getGrantId());
        assertEquals("1", row.getValue().getAlsoService());
        assertNull(row.getValue().getRoleCode(), "只写开关");
    }

    @Test
    void reactivationAsAnotherRoleNeverTurnsItOn()
    {
        KgStaffGrant old = input(StaffRole.KEEPER, "1");
        old.setGrantId(50L);
        old.setStatus("1");
        when(mapper.selectByUserAndStore(7L, 9L)).thenReturn(old);
        service.grant(input(StaffRole.STAFF, "1"));
        verify(mapper).reactivateGrant(50L, StaffRole.STAFF, "admin");
        verify(mapper, never()).updateKgStaffGrant(any());
    }

    @Test
    void updateAndRevokeReleaseTheSystemRowsBeforeTheSessionIsRefreshed()
    {
        com.ruoyi.kangaroo.ext.staff.KgMerchantRequestService requests = mock(com.ruoyi.kangaroo.ext.staff.KgMerchantRequestService.class);
        ReflectionTestUtils.setField(service, "merchantRequests", requests);
        onExisting(50L, StaffRole.KEEPER, "1");

        KgStaffGrant off = new KgStaffGrant();
        off.setGrantId(50L);
        off.setRoleCode(StaffRole.KEEPER);
        off.setAlsoService("0");
        service.updateRole(off);
        org.mockito.InOrder update = inOrder(requests, tokens);
        update.verify(requests).releaseServiceGrants(7L);
        update.verify(tokens).refreshGrants(eq(7L), any());

        org.mockito.Mockito.clearInvocations(requests, tokens);
        service.revoke(new Long[] { 50L });
        org.mockito.InOrder revoke = inOrder(requests, tokens);
        revoke.verify(requests).releaseServiceGrants(7L);
        revoke.verify(tokens).refreshGrants(eq(7L), any());
    }

    @Test
    void updateTurnsItOnAndOffAndRefreshesTheSession()
    {
        onExisting(50L, StaffRole.KEEPER, "0");
        KgStaffGrant on = new KgStaffGrant();
        on.setGrantId(50L);
        on.setRoleCode(StaffRole.KEEPER);
        on.setAlsoService("1");
        service.updateRole(on);
        assertEquals("1", updated().getAlsoService());
        verify(tokens).refreshGrants(eq(7L), any());
    }

    @Test
    void changingToAnotherRoleClearsIt()
    {
        onExisting(50L, StaffRole.KEEPER, "1");
        KgStaffGrant change = new KgStaffGrant();
        change.setGrantId(50L);
        change.setRoleCode(StaffRole.STAFF);
        change.setAlsoService("1");
        service.updateRole(change);
        assertEquals("0", updated().getAlsoService());
    }

    @Test
    void keeperUpdateWithoutTheFieldLeavesItAlone()
    {
        onExisting(50L, StaffRole.KEEPER, "1");
        KgStaffGrant oldPage = new KgStaffGrant();
        oldPage.setGrantId(50L);
        oldPage.setRoleCode(StaffRole.KEEPER);
        service.updateRole(oldPage);
        assertNull(updated().getAlsoService(), "打开着的老后台页面不带这个字段，不能把开关关了");
    }

    @Test
    void viewsCarryABoolean()
    {
        KgStaffGrant on = input(StaffRole.KEEPER, "1");
        KgStaffGrant off = input(StaffRole.KEEPER, "0");
        when(mapper.selectActiveGrantsWithStore(7L)).thenReturn(Arrays.asList(on, off));
        List<StaffGrantView> views = service.loadActiveGrantViews(7L);
        assertEquals(Boolean.TRUE, views.get(0).getAlsoService());
        assertEquals(Boolean.FALSE, views.get(1).getAlsoService());
    }

    @Test
    void mapperWritesAndReadsTheColumnAndReactivationResetsIt() throws Exception
    {
        Path dir = Paths.get("").toAbsolutePath();
        Path xml = dir.resolve("src/main/resources/mapper/kangaroo/KgStaffGrantMapper.xml");
        if (!Files.exists(xml))
        {
            xml = dir.resolve("ruoyi-kangaroo/src/main/resources/mapper/kangaroo/KgStaffGrantMapper.xml");
        }
        String m = new String(Files.readAllBytes(xml), StandardCharsets.UTF_8);
        assertTrue(m.contains("column=\"also_service\""));
        assertTrue(m.contains("g.role_code, g.also_service, g.status, s.store_name"), "小程序登录态的查询列是写死的");
        assertTrue(m.contains("g.role_code, g.also_service, g.status,\r\n") || m.contains("g.role_code, g.also_service, g.status,\n"), "后台列表和详情");
        assertTrue(m.contains("also_service = #{alsoService}"));
        int reactivate = m.indexOf("id=\"reactivateGrant\"");
        assertTrue(reactivate > 0 && m.indexOf("also_service = '0'", reactivate) > reactivate && m.indexOf("also_service = '0'", reactivate) < m.indexOf("</update>", reactivate));
    }
}
