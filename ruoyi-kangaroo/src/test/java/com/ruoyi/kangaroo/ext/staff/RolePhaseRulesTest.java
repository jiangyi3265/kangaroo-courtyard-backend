package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.controller.admin.KgStaffGrantController;
import com.ruoyi.kangaroo.controller.app.AppStaffActivityController;
import com.ruoyi.kangaroo.domain.KgAppUser;
import com.ruoyi.kangaroo.domain.KgStaffGrant;
import com.ruoyi.kangaroo.domain.KgStore;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgActivityService;
import com.ruoyi.kangaroo.ext.manage.KgHonorService;
import com.ruoyi.kangaroo.ext.manage.KgManageService;
import com.ruoyi.kangaroo.ext.manage.KgStats;
import com.ruoyi.kangaroo.ext.manage.KgWalletService;
import com.ruoyi.kangaroo.mapper.KgStaffGrantMapper;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppTokenService;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.security.StaffGrantView;
import com.ruoyi.kangaroo.service.IKgAppUserService;
import com.ruoyi.kangaroo.service.IKgMessageService;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;
import com.ruoyi.kangaroo.service.IKgStoreService;
import com.ruoyi.kangaroo.service.impl.KgStaffGrantServiceImpl;

/**
 * 两阶段行为：同一个人同一个动作，legacy 和今天一样，new 按新规则。重点：新换工主理人没有资金和管人权，城市负责人做不了日常。
 * 真实 service / 控制器 + Mock JDBC；SQL 由 KgRoleSwitchIT 和本地跑 jar 验。
 */
class RolePhaseRulesTest
{
    private static final long STORE = 2919L;
    private final KgJdbc db = mock(KgJdbc.class);
    private final IKgMessageService messages = mock(IKgMessageService.class);
    private final IKgStaffGrantService grantService = mock(IKgStaffGrantService.class);
    private final StaffSupport support = new StaffSupport();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(support, "db", db);
        ReflectionTestUtils.setField(support, "messages", messages);
        ReflectionTestUtils.setField(support, "grants", grantService);
        when(db.one(anyString(), anyMap())).thenReturn(null);
    }

    @AfterEach
    void clear()
    {
        RolePhase.set(RolePhase.LEGACY);
        AppUserContext.remove();
    }

    private static StaffGrantView grant(long store, String role)
    {
        StaffGrantView g = new StaffGrantView();
        g.setStoreId(store);
        g.setStoreName("店" + store);
        g.setRoleCode(role);
        g.setActive(true);
        return g;
    }

    private AppLoginUser login(long uid, StaffGrantView... grants)
    {
        AppLoginUser u = new AppLoginUser();
        u.setUserId(uid);
        u.setNickname("用户" + uid);
        u.setStaffGrants(new ArrayList<StaffGrantView>(Arrays.asList(grants)));
        AppUserContext.set(u);
        return u;
    }

    private StaffSupport.Ctx ctx(String role)
    {
        login(7073L, grant(STORE, role));
        return support.ctx(STORE);
    }

    private static int code(Runnable r)
    {
        return assertThrows(ServiceException.class, r::run).getCode();
    }

    // ------------------------------------------------------------------ 资金判断

    @Test
    void needManagerBothPhases()
    {
        assertDoesNotThrow(() -> support.needManager(ctx(StaffRole.MANAGER), "x", "x"), "legacy：老主理人能动钱");
        assertEquals(403, code(() -> support.needManager(ctx(StaffRole.STAFF), "x", "x")));
        assertEquals(403, code(() -> support.needManager(ctx(StaffRole.KEEPER), "x", "x")));
        when(db.one(contains("select granted_by"), anyMap())).thenReturn(p("grantedBy", KgMerchantRequestService.GRANTED_BY));
        assertEquals(403, code(() -> support.needManager(ctx(StaffRole.MANAGER), "x", "x")), "legacy：派单补的授权不能动钱");

        RolePhase.set(RolePhase.NEW);
        when(db.one(contains("select granted_by"), anyMap())).thenReturn(null);
        ServiceException e = assertThrows(ServiceException.class, () -> support.needManager(ctx(StaffRole.MANAGER), "x", "x"));
        assertEquals(403, e.getCode());
        assertTrue(e.getMessage().contains("城市负责人"), e.getMessage());
    }

    @Test
    void canFundFollowsThePhase()
    {
        assertTrue(StaffSupport.canFund(ctx(StaffRole.MANAGER)));
        assertTrue(StaffSupport.canFund(ctx(StaffRole.STAFF)), "legacy 老员工有活动·支付");
        assertFalse(StaffSupport.canFund(ctx(StaffRole.KEEPER)));
        assertFalse(StaffSupport.canFund(ctx(StaffRole.CITY_LEAD)));
        RolePhase.set(RolePhase.NEW);
        assertFalse(StaffSupport.canFund(ctx(StaffRole.MANAGER)));
        assertEquals(403, code(() -> ctx(StaffRole.STAFF)), "new：残留的 staff 行连门店上下文都拿不到");
        StaffSupport.Ctx leftover = new StaffSupport.Ctx();
        leftover.role = StaffRole.STAFF;
        assertFalse(StaffSupport.canFund(leftover), "就算拿到了也不能动钱");
        assertFalse(StaffSupport.canFund(ctx(StaffRole.KEEPER)));
        assertTrue(StaffSupport.canFund(ctx(StaffRole.CITY_LEAD)));
    }

    // ------------------------------------------------------------------ 活动接口：每种角色走一遍

    private AppStaffActivityController activityController(KgActivityService activities)
    {
        AppStaffActivityController c = new AppStaffActivityController();
        ReflectionTestUtils.setField(c, "activities", activities);
        ReflectionTestUtils.setField(c, "support", support);
        ReflectionTestUtils.setField(c, "db", db);
        return c;
    }

    private KgActivityService activityMock(long signupCount, long feeOrSigned)
    {
        KgActivityService a = mock(KgActivityService.class);
        when(db.one(contains("select store_id from kg_activity"), anyMap())).thenReturn(p("storeId", STORE));
        when(db.count(contains("kg_activity_signup"), anyMap())).thenReturn(signupCount);
        when(db.count(contains("fee_fen > 0 or signed > 0"), anyMap())).thenReturn(feeOrSigned);
        when(a.ownStoreActivity(any(), anyLong())).thenReturn(new HashMap<String, Object>(p("activityId", 5L)));
        when(a.transition(anyMap(), anyString(), any(), anyString(), anyBoolean())).thenReturn("ok");
        when(a.approveRefund(anyLong(), anyString())).thenReturn("ok");
        return a;
    }

    @Test
    void refundApprovalByRoleAndPhase()
    {
        KgActivityService a = activityMock(1, 0);
        AppStaffActivityController c = activityController(a);
        ctx(StaffRole.MANAGER);
        assertEquals(200, c.refund(5L, 9L, "approve", null).get("code"), "legacy 老主理人");
        ctx(StaffRole.STAFF);
        assertEquals(200, c.refund(5L, 9L, "approve", null).get("code"), "legacy 老员工（活动·支付 yes，逐格和今天相同）");
        ctx(StaffRole.KEEPER);
        assertEquals(403, code(() -> c.refund(5L, 9L, "approve", null)), "legacy 小院主理人");

        RolePhase.set(RolePhase.NEW);
        clearInvocations(a);
        for (String role : Arrays.asList(StaffRole.MANAGER, StaffRole.STAFF, StaffRole.KEEPER, StaffRole.MERCHANT, StaffRole.CITY_LEAD))
        {
            login(7073L, grant(STORE, role));
            assertEquals(403, code(() -> c.refund(5L, 9L, "approve", null)), "new：老接口谁都不能审 " + role);
        }
        verify(a, never()).approveRefund(anyLong(), anyString());
    }

    @Test
    void cancellingAPaidActivityByRoleAndPhase()
    {
        KgActivityService a = activityMock(0, 1);
        AppStaffActivityController c = activityController(a);
        ctx(StaffRole.MANAGER);
        assertEquals(200, c.action(5L, "cancel", p("reason", "下雨")).get("code"));
        verify(a).transition(anyMap(), eq("cancel"), any(), anyString(), eq(true));
        ctx(StaffRole.KEEPER);
        assertEquals(403, code(() -> c.action(5L, "cancel", p("reason", "下雨"))), "legacy 小院主理人");

        RolePhase.set(RolePhase.NEW);
        clearInvocations(a);
        ctx(StaffRole.MANAGER);
        assertEquals(403, code(() -> c.action(5L, "cancel", p("reason", "下雨"))), "new 换工主理人不能取消收费活动");
        ctx(StaffRole.KEEPER);
        assertEquals(403, code(() -> c.action(5L, "cancel", p("reason", "下雨"))));
        verify(a, never()).transition(anyMap(), anyString(), any(), anyString(), anyBoolean());
    }

    @Test
    void cancellingAnEmptyActivityStaysWithDailyRoles()
    {
        KgActivityService a = activityMock(0, 0);
        AppStaffActivityController c = activityController(a);
        RolePhase.set(RolePhase.NEW);
        ctx(StaffRole.MANAGER);
        assertEquals(200, c.action(5L, "cancel", p("reason", "没人")).get("code"), "没人报名的免费活动换工主理人能取消");
        // 加锁后那一道：不是资金角色，有人抢着报名就不让取消
        verify(a).transition(anyMap(), eq("cancel"), any(), anyString(), eq(false));
        ctx(StaffRole.KEEPER);
        assertEquals(200, c.action(5L, "cancel", p("reason", "没人")).get("code"));
    }

    @Test
    void pauseAndEndStayWithDailyRolesInBothPhases()
    {
        KgActivityService a = activityMock(0, 1);
        AppStaffActivityController c = activityController(a);
        for (String phase : Arrays.asList(RolePhase.LEGACY, RolePhase.NEW))
        {
            RolePhase.set(phase);
            ctx(StaffRole.MANAGER);
            assertEquals(200, c.action(5L, "pause", null).get("code"), phase);
            assertEquals(200, c.action(5L, "end", null).get("code"), phase);
            ctx(StaffRole.KEEPER);
            assertEquals(200, c.action(5L, "pause", null).get("code"), phase);
        }
    }

    @Test
    void detailAdvertisesPayOnlyToFundRoles()
    {
        KgActivityService a = activityMock(0, 0);
        when(a.manageView(anyMap())).thenReturn(new HashMap<String, Object>());
        AppStaffActivityController c = activityController(a);
        ctx(StaffRole.MANAGER);
        assertEquals(Boolean.TRUE, ((Map<?, ?>) c.detail(5L).get("data")).get("canPay"));
        ctx(StaffRole.STAFF);
        assertEquals(Boolean.TRUE, ((Map<?, ?>) c.detail(5L).get("data")).get("canPay"), "legacy 和今天相同");
        RolePhase.set(RolePhase.NEW);
        ctx(StaffRole.MANAGER);
        assertEquals(Boolean.FALSE, ((Map<?, ?>) c.detail(5L).get("data")).get("canPay"));
    }

    // ------------------------------------------------------------------ 管人

    private KgStaffTeamService team()
    {
        KgStaffTeamService team = new KgStaffTeamService();
        ReflectionTestUtils.setField(team, "db", db);
        ReflectionTestUtils.setField(team, "messages", messages);
        ReflectionTestUtils.setField(team, "grants", grantService);
        ReflectionTestUtils.setField(team, "support", support);
        ReflectionTestUtils.setField(team, "tokens", mock(AppTokenService.class));
        return team;
    }

    @Test
    void miniProgramCannotAddOrRemoveMembersAfterTheSwitch()
    {
        KgStaffTeamService team = team();
        StaffSupport.Ctx manager = ctx(StaffRole.MANAGER);
        assertDoesNotThrow(() -> team.requireManager(manager), "legacy 老主理人能管人");
        assertEquals(403, code(() -> team.requireManager(ctx(StaffRole.STAFF))));

        RolePhase.set(RolePhase.NEW);
        StaffSupport.Ctx manager2 = ctx(StaffRole.MANAGER);
        ServiceException e = assertThrows(ServiceException.class, () -> team.grant(manager2, "7089", "keeper"));
        assertEquals(403, e.getCode());
        assertTrue(e.getMessage().contains("平台"), e.getMessage());
        assertEquals(403, code(() -> team.revoke(manager2, 5L)));
        assertEquals(403, code(() -> team.requireManager(ctx(StaffRole.KEEPER))));
        verify(db, never()).insert(eq("kg_staff_grant"), anyMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    void rolesPageFollowsThePhase()
    {
        KgStaffTeamService team = team();
        when(db.list(anyString(), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        Map<String, Object> legacy = team.roles(ctx(StaffRole.MANAGER), null);
        assertEquals(Boolean.TRUE, legacy.get("canManage"));
        List<Map<String, Object>> opts = (List<Map<String, Object>>) legacy.get("grantOptions");
        assertEquals(Arrays.asList(StaffRole.STAFF, StaffRole.KEEPER, StaffRole.ONLINE_STAFF), Arrays.asList(opts.get(0).get("code"), opts.get(1).get("code"), opts.get(2).get("code")));
        List<Object> codes = new ArrayList<Object>();
        Object managerIcon = null;
        for (Map<String, Object> r : (List<Map<String, Object>>) legacy.get("roles"))
        {
            codes.add(r.get("code"));
            managerIcon = StaffRole.MANAGER.equals(r.get("code")) ? r.get("icon") : managerIcon;
        }
        assertTrue(codes.contains(StaffRole.STAFF));
        assertEquals("crown", managerIcon);

        RolePhase.set(RolePhase.NEW);
        Map<String, Object> now = team.roles(ctx(StaffRole.MANAGER), StaffRole.STAFF);
        assertEquals(Boolean.FALSE, now.get("canManage"));
        assertTrue(((List<?>) now.get("grantOptions")).isEmpty(), "空数组时小程序整个入口隐藏");
        assertEquals(StaffRole.MANAGER, now.get("selected"), "员工这一行不能再选");
        codes.clear();
        for (Map<String, Object> r : (List<Map<String, Object>>) now.get("roles"))
        {
            codes.add(r.get("code"));
            managerIcon = StaffRole.MANAGER.equals(r.get("code")) ? r.get("icon") : managerIcon;
        }
        assertFalse(codes.contains(StaffRole.STAFF));
        assertEquals("user-round", managerIcon);

        Map<String, Object> lead = team.roles(ctx(StaffRole.CITY_LEAD), null);
        assertEquals(Boolean.FALSE, lead.get("canManage"));
        assertTrue(((List<?>) lead.get("grantOptions")).isEmpty());
        assertEquals("yes", ((List<?>) ((Map<?, ?>) ((List<?>) lead.get("permissions")).get(8)).get("values")).get(3), "城市负责人的矩阵页多财务");
    }

    @Test
    @SuppressWarnings("unchecked")
    void storeManagerRowFollowsThePhase()
    {
        KgStaffTeamService team = team();
        when(db.one(contains("from kg_store"), anyMap())).thenReturn(new HashMap<String, Object>(p("storeId", STORE, "storeName", "店", "city", "大理", "address", "", "opType", "direct")));
        List<Map<String, Object>> keeperOnly = new ArrayList<Map<String, Object>>(Arrays.asList(p("nickname", "小院小王")));
        ArgumentCaptor<Map<String, Object>> ps = ArgumentCaptor.forClass(Map.class);
        when(db.list(contains("g.role_code = :r"), ps.capture())).thenReturn(keeperOnly);
        team.stores(ctx(StaffRole.MANAGER), null);
        assertEquals(StaffRole.MANAGER, ps.getValue().get("r"));
        RolePhase.set(RolePhase.NEW);
        Map<String, Object> out = team.stores(ctx(StaffRole.MANAGER), null);
        assertEquals(StaffRole.KEEPER, ps.getValue().get("r"), "门店页店长切换后显示小院主理人");
        assertEquals("小院小王", ((List<String[]>) out.get("info")).get(1)[1]);
    }

    // ------------------------------------------------------------------ 后台授权入口

    private KgStaffGrantServiceImpl grantImpl(KgStaffGrantMapper mapper, KgMerchantRequestService requests)
    {
        KgStaffGrantServiceImpl s = new KgStaffGrantServiceImpl();
        ReflectionTestUtils.setField(s, "kgStaffGrantMapper", mapper);
        IKgStoreService stores = mock(IKgStoreService.class);
        when(stores.requireStore(anyLong())).thenReturn(new KgStore());
        IKgAppUserService users = mock(IKgAppUserService.class);
        when(users.requireUser(anyLong())).thenReturn(new KgAppUser());
        ReflectionTestUtils.setField(s, "kgStoreService", stores);
        ReflectionTestUtils.setField(s, "kgAppUserService", users);
        ReflectionTestUtils.setField(s, "appTokenService", mock(AppTokenService.class));
        ReflectionTestUtils.setField(s, "messages", messages);
        ReflectionTestUtils.setField(s, "merchantRequests", requests);
        return s;
    }

    private static void adminLogin()
    {
        SysUser sys = new SysUser();
        sys.setUserName("boss");
        LoginUser login = new LoginUser();
        login.setUser(sys);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login, null, Collections.emptyList()));
    }

    private static KgStaffGrant input(String role)
    {
        KgStaffGrant g = new KgStaffGrant();
        g.setAppUserId(1L);
        g.setStoreId(2L);
        g.setRoleCode(role);
        return g;
    }

    @Test
    void adminGrantRefusesStaffAndMissingRoleOnlyAfterTheSwitch()
    {
        KgStaffGrantMapper mapper = mock(KgStaffGrantMapper.class);
        when(mapper.selectActiveGrantsWithStore(anyLong())).thenReturn(new ArrayList<KgStaffGrant>());
        KgStaffGrantServiceImpl s = grantImpl(mapper, null);
        adminLogin();
        try
        {
            KgStaffGrant noRole = input(null);
            s.grant(noRole);
            assertEquals(StaffRole.STAFF, noRole.getRoleCode(), "legacy 不传角色和今天一样落员工");
            s.grant(input(StaffRole.STAFF));

            RolePhase.set(RolePhase.NEW);
            clearInvocations(mapper);
            assertThrows(ServiceException.class, () -> s.grant(input(null)), "new 必须选");
            assertThrows(ServiceException.class, () -> s.grant(input("")));
            assertThrows(ServiceException.class, () -> s.grant(input(StaffRole.STAFF)));
            assertThrows(ServiceException.class, () -> s.grant(input("nobody")));
            verify(mapper, never()).insertKgStaffGrant(any());
            s.grant(input(StaffRole.MANAGER));
            s.grant(input(StaffRole.CITY_LEAD));
            verify(mapper, times(2)).insertKgStaffGrant(any());

            when(mapper.selectKgStaffGrantByGrantId(5L)).thenReturn(existing());
            KgStaffGrant change = new KgStaffGrant();
            change.setGrantId(5L);
            change.setRoleCode(StaffRole.STAFF);
            assertThrows(ServiceException.class, () -> s.updateRole(change), "new 不能改成员工");
        }
        finally
        {
            SecurityContextHolder.clearContext();
        }
    }

    private static KgStaffGrant existing()
    {
        KgStaffGrant g = input(StaffRole.MANAGER);
        g.setGrantId(5L);
        g.setStatus("0");
        return g;
    }

    @Test
    void changingOrRevokingAGrantReleasesAutoManagerGrants()
    {
        KgStaffGrantMapper mapper = mock(KgStaffGrantMapper.class);
        when(mapper.selectActiveGrantsWithStore(anyLong())).thenReturn(new ArrayList<KgStaffGrant>());
        when(mapper.selectKgStaffGrantByGrantId(5L)).thenReturn(existing());
        KgMerchantRequestService requests = mock(KgMerchantRequestService.class);
        KgStaffGrantServiceImpl s = grantImpl(mapper, requests);
        adminLogin();
        try
        {
            KgStaffGrant change = new KgStaffGrant();
            change.setGrantId(5L);
            change.setRoleCode(StaffRole.KEEPER);
            s.updateRole(change);
            verify(requests).releaseManagerGrants(1L);
            s.revoke(new Long[] { 5L });
            verify(requests, times(2)).releaseManagerGrants(1L);
        }
        finally
        {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminRolesEndpointSeparatesDisplayAndSelectable()
    {
        KgStaffGrantController c = new KgStaffGrantController();
        Map<String, Object> legacy = (Map<String, Object>) c.roles().get("data");
        assertTrue(((List<String>) legacy.get("selectable")).contains(StaffRole.STAFF));
        assertEquals(7, ((List<?>) legacy.get("roles")).size());
        assertTrue(String.valueOf(legacy.get("hint")).contains("切换后变城市负责人"));
        assertEquals(RolePhase.LEGACY, legacy.get("phase"));

        RolePhase.set(RolePhase.NEW);
        Map<String, Object> now = (Map<String, Object>) c.roles().get("data");
        assertEquals(Arrays.asList("city_lead", "keeper", "manager", "online_staff", "merchant_service", "merchant"), now.get("selectable"));
        assertEquals(6, ((List<?>) now.get("roles")).size());
        assertEquals("员工", ((Map<String, String>) now.get("labels")).get("staff"), "老记录还要能显示");
        assertEquals("", now.get("hint"));
    }

    // ------------------------------------------------------------------ 通知

    @Test
    void refundPendingGoesToThisStoresCityLeadsAfterTheSwitch()
    {
        StaffSupport s = mock(StaffSupport.class);
        KgActivityService a = new KgActivityService();
        ReflectionTestUtils.setField(a, "support", s);
        Map<String, Object> activity = p("storeId", STORE, "activityId", 5L, "title", "徒步");

        ReflectionTestUtils.invokeMethod(a, "notifyRefundApprovers", activity, "小李", 1000L, 99L);
        verify(s).notifyStore(eq(STORE), eq("order"), eq("活动退款待审批"), anyString(), eq("activity"), eq(5L), contains("/pages/staff/activities"));

        RolePhase.set(RolePhase.NEW);
        clearInvocations(s);
        when(s.cityLeadsOfStore(STORE)).thenReturn(Arrays.asList(11L, 12L));
        ReflectionTestUtils.invokeMethod(a, "notifyRefundApprovers", activity, "小李", 1000L, 99L);
        verify(s).notifyStaff(eq(11L), eq("order"), eq("活动退款待审批"), anyString(), eq("activity_refund"), eq(5L), contains("/pages/staff/city-finance"));
        verify(s).notifyStaff(eq(12L), eq("order"), anyString(), anyString(), eq("activity_refund"), eq(5L), anyString());
        verify(s, never()).notifyStore(anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());

        clearInvocations(s);
        when(s.cityLeadsOfStore(STORE)).thenReturn(Collections.<Long>emptyList());
        ReflectionTestUtils.invokeMethod(a, "notifyRefundApprovers", activity, "小李", 1000L, 99L);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(s).notifyStore(eq(STORE), anyString(), anyString(), text.capture(), anyString(), anyLong(), anyString());
        assertTrue(text.getValue().contains("平台后台"), "没有城市负责人要写明走平台：" + text.getValue());
    }

    @Test
    void refundNoticeNeverGoesOnlyToTheApplicantAndMasksTheName()
    {
        StaffSupport s = mock(StaffSupport.class);
        KgActivityService a = new KgActivityService();
        ReflectionTestUtils.setField(a, "support", s);
        Map<String, Object> activity = p("storeId", STORE, "activityId", 5L, "title", "徒步");
        RolePhase.set(RolePhase.NEW);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);

        // 这家店唯一的城市负责人取消自己的报名：他审不了，不能只发给他
        when(s.cityLeadsOfStore(STORE)).thenReturn(Arrays.asList(11L));
        ReflectionTestUtils.invokeMethod(a, "notifyRefundApprovers", activity, "张三丰", 1000L, 11L);
        verify(s, never()).notifyStaff(anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
        verify(s).notifyStore(eq(STORE), anyString(), anyString(), text.capture(), anyString(), anyLong(), anyString());
        assertTrue(text.getValue().contains("平台后台"), text.getValue());

        // 还有别的城市负责人：发给别人，不发给本人
        clearInvocations(s);
        when(s.cityLeadsOfStore(STORE)).thenReturn(Arrays.asList(11L, 12L));
        ReflectionTestUtils.invokeMethod(a, "notifyRefundApprovers", activity, "张三丰", 1000L, 11L);
        verify(s, never()).notifyStaff(eq(11L), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
        verify(s).notifyStaff(eq(12L), anyString(), anyString(), text.capture(), anyString(), anyLong(), anyString());
        verify(s, never()).notifyStore(anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
        assertFalse(text.getValue().contains("张三丰"), "列表是打码的，消息也不能露全名：" + text.getValue());

        // 切换前的文字一个字不变
        RolePhase.set(RolePhase.LEGACY);
        clearInvocations(s);
        ReflectionTestUtils.invokeMethod(a, "notifyRefundApprovers", activity, "张三丰", 1000L, 11L);
        verify(s).notifyStore(eq(STORE), anyString(), anyString(), text.capture(), anyString(), anyLong(), anyString());
        assertEquals("张三丰 取消了「徒步」，申请退款 ¥10。", text.getValue());
    }

    private KgManageService manageWith(Map<String, Object> dailyView, StaffSupport s)
    {
        KgManageService m = spy(new KgManageService());
        ReflectionTestUtils.setField(m, "support", s);
        ReflectionTestUtils.setField(m, "db", db);
        doReturn(dailyView).when(m).daily(any(), any());
        return m;
    }

    private static Map<String, Object> dailyView(List<Map<String, Object>> people)
    {
        return new HashMap<String, Object>(p("ledger", "stay", "managers", people, "totals", new long[] { 1L, 2L, 3L }, "dateText", "10月30日", "storeName", "店", "storeId", STORE));
    }

    @Test
    void stayDailyReportSendsAndFindsNobodyWithoutFailingAfterTheSwitch()
    {
        login(7L, grant(STORE, StaffRole.KEEPER));
        StaffSupport s = mock(StaffSupport.class);
        List<Map<String, Object>> one = new ArrayList<Map<String, Object>>(Arrays.asList(p("userId", 99L)));
        assertEquals("已发送给 1 位主理人", manageWith(dailyView(one), s).dailySend(STORE, null).get("message"));
        assertEquals(422, code(() -> manageWith(dailyView(new ArrayList<Map<String, Object>>()), s).dailySend(STORE, null)), "legacy 和今天相同");

        RolePhase.set(RolePhase.NEW);
        assertEquals("已发送给 1 位城市负责人", manageWith(dailyView(one), s).dailySend(STORE, null).get("message"));
        Map<String, Object> none = manageWith(dailyView(new ArrayList<Map<String, Object>>()), s).dailySend(STORE, null);
        assertEquals(0, none.get("sent"), "本城没有城市负责人不能把小院主理人拦住");
        assertTrue(String.valueOf(none.get("message")).contains("城市负责人"));
        verify(s, times(2)).notifyStaff(eq(99L), anyString(), anyString(), anyString(), eq("daily_report"), anyLong(), isNull());
    }

    @Test
    void weeklyOpsNoticeRolesFollowThePhase()
    {
        assertEquals("'manager','keeper'", ReflectionTestUtils.invokeMethod(KgManageService.class, "opsWeeklyRoles"));
        RolePhase.set(RolePhase.NEW);
        assertEquals("'keeper'", ReflectionTestUtils.invokeMethod(KgManageService.class, "opsWeeklyRoles"));
    }

    @Test
    void walletNoticeAndLabelFollowThePhase()
    {
        KgWalletService w = new KgWalletService();
        StaffSupport s = mock(StaffSupport.class);
        ReflectionTestUtils.setField(w, "db", db);
        ReflectionTestUtils.setField(w, "support", s);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(db.count(sql.capture(), anyMap())).thenReturn(1L);
        ReflectionTestUtils.invokeMethod(w, "notifyPayee", 9L, "t", "c", "wallet_withdraw", 1L, "/x");
        assertTrue(sql.getValue().contains("'online_staff','merchant_service','city_lead'"), "legacy 城市负责人不收员工身份消息");
        RolePhase.set(RolePhase.NEW);
        ReflectionTestUtils.invokeMethod(w, "notifyPayee", 9L, "t", "c", "wallet_withdraw", 1L, "/x");
        assertFalse(sql.getValue().contains("city_lead"), "new 老主理人钱包跟人走，员工身份也要收到");
    }

    @Test
    void messageVisibilityOpensTheMoneyTypesOnlyToCityLeadAccounts() throws Exception
    {
        String xml = new String(Files.readAllBytes(Paths.get("src/main/resources/mapper/kangaroo/KgMessageMapper.xml")), StandardCharsets.UTF_8);
        int i = xml.indexOf("'daily_report','activity_refund','refund_trouble','wallet','wallet_withdraw'");
        assertTrue(i > 0);
        assertTrue(xml.substring(i, i + 400).contains("lead_grant.role_code='city_lead'"), "放行只对有城市负责人授权的账号，别的账号可见范围不变");
        assertTrue(xml.substring(i, i + 400).contains("switched.phase='new'"), "切换前纯城市负责人的可见范围要和上一版相同");
    }

    // ------------------------------------------------------------------ 荣誉

    private KgHonorService honor()
    {
        KgHonorService h = new KgHonorService();
        ReflectionTestUtils.setField(h, "db", db);
        ReflectionTestUtils.setField(h, "stats", mock(KgStats.class));
        ReflectionTestUtils.setField(h, "manage", mock(KgManageService.class));
        ReflectionTestUtils.setField(h, "support", support);
        when(db.one(contains("from kg_app_user"), anyMap())).thenReturn(p("nickname", "x", "registerTime", "2025-01-01 00:00:00", "birthday", String.format("%02d-01", LocalDate.now().getMonthValue())));
        when(db.count(contains("kg_weekly_report"), anyMap())).thenReturn(1L);
        when(db.list(anyString(), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        return h;
    }

    private void managerSince(String date, long count)
    {
        when(db.count(contains("role_code = 'manager'"), anyMap())).thenReturn(count);
        when(db.one(contains("min(granted_time) t from kg_staff_grant where app_user_id = :u and role_code = 'manager'"), anyMap())).thenReturn(p("t", date));
    }

    private void leadSince(String date)
    {
        when(db.one(contains("role_code = 'city_lead'"), anyMap())).thenReturn(date == null ? null : p("t", date));
    }

    private static boolean ok(Map<String, Object> birthday, int i)
    {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conds = (List<Map<String, Object>>) birthday.get("conditions");
        return Boolean.TRUE.equals(conds.get(i).get("ok"));
    }

    @Test
    void birthdayEligibilityFollowsTheOwnersRule()
    {
        KgHonorService h = honor();
        login(7L, grant(STORE, StaffRole.MANAGER));
        managerSince("2025-01-01 00:00:00", 1);
        leadSince(null);
        assertTrue(ok(h.birthday(), 1), "legacy：老主理人按原授权时间");
        managerSince(null, 0);
        leadSince("2025-01-01 00:00:00");
        assertFalse(ok(h.birthday(), 0), "legacy：城市负责人没有资格（这个接口他本来也进不来）");

        RolePhase.set(RolePhase.NEW);
        when(db.one(contains("select switched_time t"), anyMap())).thenReturn(p("t", LocalDate.now().toString() + " 10:00:00"));
        // 老主理人 = 城市负责人：保留资格，按原授权时间
        managerSince(null, 0);
        leadSince("2025-01-01 00:00:00");
        Map<String, Object> lead = h.birthday();
        assertTrue(ok(lead, 0));
        assertTrue(ok(lead, 1));
        // 老员工 = 换工主理人：原授权时间早也不算，从切换当天起算
        managerSince("2025-01-01 00:00:00", 1);
        leadSince(null);
        Map<String, Object> staff = h.birthday();
        assertTrue(ok(staff, 0));
        assertFalse(ok(staff, 1), "切换当天起算 90 天");
        // 切换后才开的换工主理人：按自己的授权时间（比切换晚）
        when(db.one(contains("select switched_time t"), anyMap())).thenReturn(p("t", LocalDate.now().minusDays(200).toString() + " 10:00:00"));
        managerSince(LocalDate.now().minusDays(100).toString() + " 00:00:00", 1);
        assertTrue(ok(h.birthday(), 1));
        managerSince(LocalDate.now().minusDays(10).toString() + " 00:00:00", 1);
        assertFalse(ok(h.birthday(), 1));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> conditionTexts(Map<String, Object> birthday)
    {
        List<Object> texts = new ArrayList<Object>();
        for (Map<String, Object> c : (List<Map<String, Object>>) birthday.get("conditions"))
        {
            texts.add(c.get("text"));
        }
        return texts;
    }

    @Test
    void cityLeadIsNotHeldBackByAWeeklyReportHeCannotWrite()
    {
        KgHonorService h = honor();
        login(7L, grant(STORE, StaffRole.CITY_LEAD));
        RolePhase.set(RolePhase.NEW);
        when(db.one(contains("select switched_time t"), anyMap())).thenReturn(p("t", LocalDate.now().toString() + " 10:00:00"));
        when(db.count(contains("kg_weekly_report"), anyMap())).thenReturn(0L);
        when(db.list(anyString(), anyMap())).thenReturn(Collections.<Map<String, Object>>emptyList());
        // 老主理人 = 纯城市负责人：周报这一条不算，他只有 3 条
        managerSince(null, 0);
        leadSince("2025-01-01 00:00:00");
        Map<String, Object> lead = h.birthday();
        assertEquals(3, conditionTexts(lead).size(), String.valueOf(conditionTexts(lead)));
        assertFalse(String.valueOf(conditionTexts(lead)).contains("周报"));
        assertFalse(String.valueOf(conditionTexts(lead)).contains("担任主理人"), "他不是主理人了");

        // 换工主理人：这一条还在
        managerSince(LocalDate.now().minusDays(100).toString() + " 00:00:00", 1);
        leadSince(null);
        assertTrue(String.valueOf(conditionTexts(h.birthday())).contains("周报"));

        // 两种身份都有：城市负责人那边不够 90 天，要靠换工主理人那边，周报还是要交
        leadSince(LocalDate.now().minusDays(10).toString() + " 00:00:00");
        assertTrue(String.valueOf(conditionTexts(h.birthday())).contains("周报"));
        // 城市负责人那边够 90 天：不用交
        leadSince(LocalDate.now().minusDays(100).toString() + " 00:00:00");
        assertFalse(String.valueOf(conditionTexts(h.birthday())).contains("周报"));

        // 切换前一个字不变
        RolePhase.set(RolePhase.LEGACY);
        managerSince("2025-01-01 00:00:00", 1);
        List<Object> legacy = conditionTexts(h.birthday());
        assertEquals(4, legacy.size());
        assertTrue(String.valueOf(legacy.get(1)).startsWith("担任主理人满 90 天"));
        assertTrue(String.valueOf(legacy.get(3)).startsWith("本月已提交至少 1 份周报"));
    }

    @Test
    void honorEndpointsOpenToCityLeadOnlyAfterTheSwitch() throws Exception
    {
        for (String m : Arrays.asList("wall", "growth", "birthday"))
        {
            com.ruoyi.kangaroo.security.AppAuth a = com.ruoyi.kangaroo.controller.app.AppWalletController.class.getMethod(m).getAnnotation(com.ruoyi.kangaroo.security.AppAuth.class);
            assertTrue(a.cityLeadAfterSwitch(), m);
            assertFalse(a.cityLead(), "cityLead = true 会在 legacy 就放开 " + m);
        }
    }

    // ------------------------------------------------------------------ 住客群码、线上任务

    @Test
    void groupQrReminderRolesFollowThePhase() throws Exception
    {
        Class<?> c = Class.forName("com.ruoyi.kangaroo.ext.stay.KgStayGroupQrReminder");
        assertEquals(Arrays.asList(StaffRole.MANAGER, StaffRole.KEEPER), ReflectionTestUtils.getField(c, "ROLES"));
        assertEquals(Collections.singletonList(StaffRole.KEEPER), ReflectionTestUtils.getField(c, "ROLES_AFTER_SWITCH"));
    }

    @Test
    void specialistTaskNoticeGoesToWhoeverPays()
    {
        KgOnlineTaskService online = new KgOnlineTaskService();
        ReflectionTestUtils.setField(online, "db", db);
        StaffSupport s = mock(StaffSupport.class);
        ReflectionTestUtils.setField(online, "support", s);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(db.list(sql.capture(), anyMap())).thenReturn(new ArrayList<Map<String, Object>>(Arrays.asList(p("appUserId", 55L))));
        StaffSupport.Ctx c = ctx(StaffRole.ONLINE_STAFF);
        Map<String, Object> body = p("title", "写推文", "commissionFen", 100L, "quota", 1L);
        assertEquals(1, (int) ReflectionTestUtils.invokeMethod(online, "notifyManagersOfSpecialistTask", c, 5L, body));
        assertTrue(sql.getValue().contains("role_code = 'manager'"));
        verify(s).notifyStaff(eq(55L), anyString(), anyString(), contains("由你线下付款"), anyString(), anyLong(), anyString());

        RolePhase.set(RolePhase.NEW);
        clearInvocations(s);
        assertEquals(1, (int) ReflectionTestUtils.invokeMethod(online, "notifyManagersOfSpecialistTask", c, 5L, body));
        assertTrue(sql.getValue().contains("role_code in ('merchant','merchant_service')"), sql.getValue());
        verify(s).notifyStaff(eq(55L), anyString(), anyString(), contains("请线下付款并登记"), anyString(), anyLong(), anyString());
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(KgOnlineTaskService.class, "canCorrectPayout", StaffRole.MANAGER));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(KgOnlineTaskService.class, "canCorrectPayout", StaffRole.MERCHANT));
        RolePhase.set(RolePhase.LEGACY);
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(KgOnlineTaskService.class, "canCorrectPayout", StaffRole.MANAGER));
    }

    // ------------------------------------------------------------------ 工作台角标

    @Test
    void hubShowsCityLeadRefundCountOnlyAfterTheSwitch()
    {
        KgMerchantRequestService requests = new KgMerchantRequestService();
        ReflectionTestUtils.setField(requests, "db", db);
        ReflectionTestUtils.setField(requests, "cities", mock(com.ruoyi.kangaroo.ext.user.KgCityService.class));
        when(db.count(contains("refund_pending"), anyMap())).thenReturn(3L);
        when(db.one(contains("select city from kg_store"), anyMap())).thenReturn(p("city", ""));
        StaffSupport.Ctx c = ctx(StaffRole.CITY_LEAD);
        assertFalse(requests.hubCounts(c).containsKey("cityRefundPending"));
        RolePhase.set(RolePhase.NEW);
        assertEquals(3L, requests.hubCounts(c).get("cityRefundPending"));
    }

    // ------------------------------------------------------------------ 残留 staff 行、派单补的授权

    @Test
    void leftoverStaffAndUnknownRolesStopCountingOnlyAfterTheSwitch()
    {
        assertTrue(RolePhase.usable(StaffRole.STAFF));
        assertTrue(RolePhase.usable("weird"), "切换前行为不变：未知角色按员工算");
        login(7073L, grant(STORE, StaffRole.STAFF));
        assertEquals(StaffRole.STAFF, support.ctx(STORE).role);
        login(7073L, grant(STORE, "weird"));
        assertEquals(StaffRole.STAFF, support.ctx(STORE).role);

        RolePhase.set(RolePhase.NEW);
        assertFalse(RolePhase.usable(StaffRole.STAFF));
        assertFalse(RolePhase.usable("weird"));
        assertTrue(RolePhase.usable(StaffRole.MANAGER));
        assertEquals(403, code(() -> support.ctx(STORE)), "未知角色不再按员工算");
        login(7073L, grant(STORE, StaffRole.STAFF));
        assertEquals(403, code(() -> support.ctx(STORE)));
        assertEquals(403, code(() -> support.ctx(null)));
        // 别家店的正常授权不受影响，不传门店时不会落到残留行上
        login(7073L, grant(STORE, StaffRole.STAFF), grant(STORE + 1, StaffRole.KEEPER));
        assertEquals(StaffRole.KEEPER, support.ctx(null).role);
    }

    private static Map<String, Long> longs(Object... kv)
    {
        Map<String, Long> m = new HashMap<String, Long>();
        for (int i = 0; i < kv.length; i += 2)
        {
            m.put((String) kv[i], (Long) kv[i + 1]);
        }
        return m;
    }

    @Test
    void workbenchHidesMoneyOnlyOnStoresGrantedByRouting()
    {
        KgWorkbenchService w = new KgWorkbenchService();
        KgStats stats = mock(KgStats.class);
        StaffSupport s = spy(support);
        ReflectionTestUtils.setField(w, "db", db);
        ReflectionTestUtils.setField(w, "support", s);
        ReflectionTestUtils.setField(w, "stats", stats);
        Map<String, Long> rev = longs("total", 88888L, "orders", 2L, "shop", 777L, "activity", 0L, "stay", 0L);
        when(stats.revenue(anyList(), any(), any())).thenReturn(rev);
        when(stats.goodRate(anyList(), any(), any())).thenReturn(new HashMap<String, Long>());
        when(stats.applications(anyList(), any(), any())).thenReturn(longs("n", 1L));
        when(stats.activities(anyList(), any(), any())).thenReturn(longs("people", 1L, "held", 1L));
        when(stats.dailyRevenue(anyList(), any(), any())).thenReturn(new long[7]);
        when(stats.dailyOrders(anyList(), any(), any())).thenReturn(new long[7]);
        when(stats.dailyRoomNights(anyList(), any(), any())).thenReturn(new long[7]);
        when(stats.dailyActivityPeople(anyList(), any(), any())).thenReturn(new long[7]);
        login(7073L, grant(STORE, StaffRole.MANAGER));
        RolePhase.set(RolePhase.NEW);

        doReturn(true).when(s).autoManager(any());
        assertFalse(String.valueOf(w.metrics(STORE).get("metrics")).contains("888.88"), "派单补的商家店不露营收");
        assertFalse(String.valueOf(w.metrics(STORE).get("metrics")).contains("7.77"));

        doReturn(false).when(s).autoManager(any());
        assertTrue(String.valueOf(w.metrics(STORE).get("metrics")).contains("888.88"), "自己门店的营收照旧");

        // 切换前没有这种授权，也不去查
        RolePhase.set(RolePhase.LEGACY);
        doReturn(true).when(s).autoManager(any());
        assertTrue(String.valueOf(w.metrics(STORE).get("metrics")).contains("888.88"));
    }

    @Test
    void operationLogsAreStoreWideOnlyForTheOldManager()
    {
        KgStaffTeamService team = new KgStaffTeamService();
        ReflectionTestUtils.setField(team, "db", db);
        ReflectionTestUtils.setField(team, "support", support);
        when(db.one(contains("from kg_staff_op_log"), anyMap())).thenReturn(p("total", 0L, "failed", 0L, "exports", 0L, "funds", 0L));
        when(db.list(anyString(), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        StaffSupport.Ctx manager = ctx(StaffRole.MANAGER);
        assertEquals("本店全部员工", team.opLogs(manager, null, 7, null).get("scope"));
        assertEquals("仅本人", team.opLogs(ctx(StaffRole.STAFF), null, 7, null).get("scope"));

        RolePhase.set(RolePhase.NEW);
        assertEquals("仅本人", team.opLogs(manager, null, 7, null).get("scope"), "切换后 manager 是原来的员工，不看全店（里面有资金操作）");
        verify(db, atLeastOnce()).list(contains("app_user_id = :u"), anyMap());
    }

    @Test
    void grantWriteStopsWhenThePhaseChangedWhileWaiting()
    {
        RolePhaseGuard guard = new RolePhaseGuard();
        ReflectionTestUtils.setField(guard, "db", db);
        when(db.one(contains("lock in share mode"), anyMap())).thenReturn(p("phase", "legacy"));
        guard.beforeGrantWrite();
        when(db.one(contains("lock in share mode"), anyMap())).thenReturn(p("phase", "new"));
        assertEquals(409, code(guard::beforeGrantWrite), "进来时 legacy、等到锁时已切换：不能再写 staff 行");
        RolePhase.set(RolePhase.NEW);
        guard.beforeGrantWrite();
        when(db.one(contains("lock in share mode"), anyMap())).thenReturn(p("phase", "legacy"));
        assertEquals(409, code(guard::beforeGrantWrite), "进来时 new、等到锁时已退回：manager 不能写成老主理人");
        when(db.one(contains("lock in share mode"), anyMap())).thenReturn(null);
        guard.beforeGrantWrite();
    }

    @Test
    void versionHeaderCannotBreakTheSwitchPage()
    {
        assertFalse(KgRoleSwitchService.atLeast("99999999999999999999", "2026.10.30.2"), "超出 long 算旧，不抛错");
        assertFalse(KgRoleSwitchService.atLeast("2026.99999999999999999999", "2026.10.30.2"));
        assertTrue(KgRoleSwitchService.atLeast("2026.10.30.2", "2026.10.30.2"));
        assertTrue(KgRoleSwitchService.atLeast("2026.10.31", "2026.10.30.2"));
        assertFalse(KgRoleSwitchService.atLeast("2026.10.30.1", "2026.10.30.2"));

        KgStaffClientSeen seen = new KgStaffClientSeen();
        ReflectionTestUtils.setField(seen, "db", db);
        AppLoginUser u = login(7073L, grant(STORE, StaffRole.MANAGER));
        ArgumentCaptor<Map<String, Object>> args = ArgumentCaptor.forClass(Map.class);
        for (String bad : Arrays.asList("release/99999999999999999999", "release/abc", "release/1.2-beta", "release/1234567890.1", "release/1.2.3.4.5.6.7.8.9.10.11.12.13.14.15.16.17", "beta/1.2"))
        {
            clearInvocations(db);
            seen.record(u, bad);
            verify(db, atMost(1)).update(anyString(), args.capture());
            if (!args.getAllValues().isEmpty())
            {
                assertEquals("none", args.getValue().get("c"), bad);
                assertEquals("", args.getValue().get("v"), bad);
            }
        }
        clearInvocations(db);
        seen.record(u, "release/2026.10.30.2");
        verify(db).update(anyString(), args.capture());
        assertEquals("release", args.getValue().get("c"));
        assertEquals("2026.10.30.2", args.getValue().get("v"));
    }
}
