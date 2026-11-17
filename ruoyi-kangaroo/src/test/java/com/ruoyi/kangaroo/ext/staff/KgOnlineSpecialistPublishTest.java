package com.ruoyi.kangaroo.ext.staff;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.method.HandlerMethod;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.controller.app.AppStaffExtController;
import com.ruoyi.kangaroo.controller.app.AppStaffOpsController;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgCommunityGuard;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppAuthInterceptor;
import com.ruoyi.kangaroo.security.AppIdentityLoader;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppRateLimiter;
import com.ruoyi.kangaroo.security.AppTokenService;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.security.StaffGrantView;
import com.ruoyi.kangaroo.service.impl.KgJobServiceImpl;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 2026-10 主人拍板：线上任务专员可以发布本店的线上任务（和主理人一样先落草稿、平台审核上架），发布后通知本店主理人；
 * 线下岗位仍不能发，专员也不能暂停 / 关闭 / 修改任务（不连库）。
 */
class KgOnlineSpecialistPublishTest
{
    @AfterEach
    void tearDown()
    {
        AppUserContext.remove();
    }

    private static StaffGrantView grant(long storeId, String role)
    {
        StaffGrantView g = new StaffGrantView();
        g.setStoreId(storeId);
        g.setStoreName("门店" + storeId);
        g.setRoleCode(role);
        return g;
    }

    private static AppLoginUser user(StaffGrantView... grants)
    {
        AppLoginUser u = new AppLoginUser();
        u.setUserId(42L);
        u.setNickname("小林");
        u.setStaffGrants(new ArrayList<StaffGrantView>(Arrays.asList(grants)));
        return u;
    }

    private static StaffSupport.Ctx ctx(String role)
    {
        StaffSupport.Ctx c = new StaffSupport.Ctx();
        c.me = user(grant(10L, role));
        c.storeId = 10L;
        c.storeName = "门店10";
        c.role = role;
        return c;
    }

    private static Map<String, Object> form()
    {
        Map<String, Object> b = new HashMap<String, Object>();
        b.put("title", " 探店图文 ");
        b.put("taskType", "内容写作");
        b.put("deliverables", "小红书图文 1 篇");
        b.put("criteria", "原创、800 字以上");
        b.put("commissionFen", 1250L);
        b.put("quota", 3L);
        b.put("deadline", java.time.LocalDateTime.now().plusDays(3).format(KgOnlineTaskService.DT));
        return b;
    }

    // ---------------------------------------------------------------- 权限矩阵

    @Test
    void permissionMatrixKeepsOfflineAndOnlineResponsibilitiesSeparate()
    {
        assertTrue(StaffPerms.allows(StaffRole.ONLINE_STAFF, "online", StaffPerms.EDIT));
        assertFalse(StaffPerms.allows(StaffRole.ONLINE_STAFF, "jobs", StaffPerms.EDIT), "专员仍不能发线下岗位");
        assertFalse(StaffPerms.allows(StaffRole.ONLINE_STAFF, "finance", StaffPerms.PAY), "登记付款仍只有主理人");
        assertFalse(StaffPerms.allows(StaffRole.STAFF, "online", StaffPerms.EDIT), "普通员工仍不能发布");
        assertFalse(StaffPerms.allows(StaffRole.MANAGER, "online", StaffPerms.EDIT));
        assertTrue(StaffPerms.allows(StaffRole.KEEPER, "online", StaffPerms.EDIT));
        // 管家保留两类岗位权限；换工主理人和普通员工不能借线下岗位权限发布线上任务。
        for (String role : new String[] { StaffRole.KEEPER })
        {
            for (String action : StaffPerms.ACTIONS.keySet())
            {
                assertEquals(StaffPerms.value(role, "jobs", action), StaffPerms.value(role, "online", action), role + "·" + action);
            }
        }
        assertEquals("线上任务（编辑=发布、验收）", StaffPerms.ITEMS.get("online"));
    }

    // ---------------------------------------------------------------- 接口：注解与判权

    @Test
    void onlineCreateIsOpenToSpecialistsButOfflineJobCreateIsNot() throws Exception
    {
        Method create = AppStaffExtController.class.getMethod("onlineCreate", Long.class, Map.class);
        AppAuth auth = create.getAnnotation(AppAuth.class);
        assertTrue(auth.staff() && auth.onlineStaff());
        assertArrayEquals(new String[] { "/online/tasks" }, create.getAnnotation(PostMapping.class).value());

        Method offline = AppStaffOpsController.class.getMethod("createJob", Long.class, Map.class);
        assertArrayEquals(new String[] { "/jobs" }, offline.getAnnotation(PostMapping.class).value());
        AppAuth offlineAuth = offline.getAnnotation(AppAuth.class);
        if (offlineAuth == null)
        {
            offlineAuth = AppStaffOpsController.class.getAnnotation(AppAuth.class);
        }
        assertFalse(offlineAuth.onlineStaff(), "线下岗位发布不对专员开放");
        // 专员也不能暂停 / 关闭 / 修改任务：线上任务没有专员能用的改状态接口，岗位批量管理在 AppStaffOpsController（未开放专员）
        for (Method m : AppStaffExtController.class.getMethods())
        {
            AppAuth a = m.getAnnotation(AppAuth.class);
            if (a != null && a.onlineStaff() && m.getAnnotation(PostMapping.class) != null)
            {
                for (String path : m.getAnnotation(PostMapping.class).value())
                {
                    assertFalse(path.matches(".*/(pause|close|status|edit|update).*"), path);
                }
            }
        }
    }

    private AppStaffExtController controller(StaffSupport support, KgOnlineTaskService online)
    {
        AppStaffExtController ctl = new AppStaffExtController();
        ReflectionTestUtils.setField(ctl, "support", support);
        ReflectionTestUtils.setField(ctl, "online", online);
        ReflectionTestUtils.setField(ctl, "limiter", mock(AppRateLimiter.class));
        return ctl;
    }

    @Test
    void controllerChecksOnlineEditForEachRole()
    {
        for (String role : new String[] { StaffRole.ONLINE_STAFF, StaffRole.MANAGER, StaffRole.KEEPER, StaffRole.STAFF })
        {
            StaffSupport support = mock(StaffSupport.class);
            KgOnlineTaskService online = mock(KgOnlineTaskService.class);
            StaffSupport.Ctx c = ctx(role);
            when(support.ctx(10L)).thenReturn(c);
            doCallRealMethod().when(support).need(any(), anyString(), anyString(), anyString(), anyString());
            Map<String, Object> body = form();
            if (StaffRole.STAFF.equals(role) || StaffRole.MANAGER.equals(role))
            {
                ServiceException e = assertThrows(ServiceException.class, () -> controller(support, online).onlineCreate(10L, body));
                assertEquals(Integer.valueOf(403), e.getCode());
                verifyNoInteractions(online);
            }
            else
            {
                controller(support, online).onlineCreate(10L, body);
                verify(online).create(c, body);
            }
            verify(support).need(c, "online", StaffPerms.EDIT, "online_create", "发布线上任务");
        }
    }

    // ---------------------------------------------------------------- 门店范围：只认本人在所传门店上的授权

    @Test
    void storeScopeStillComesFromTheSpecialistsOwnGrant()
    {
        StaffSupport support = new StaffSupport();
        AppUserContext.set(user(grant(10L, StaffRole.ONLINE_STAFF)));
        assertEquals(StaffRole.ONLINE_STAFF, support.ctx(10L).role);
        ServiceException e = assertThrows(ServiceException.class, () -> support.ctx(20L));
        assertEquals(Integer.valueOf(403), e.getCode());

        // 混合身份：A 店专员、B 店主理人。按所传门店取角色，不会拿 B 店的主理人身份去 A 店，也不会反过来
        AppUserContext.set(user(grant(10L, StaffRole.ONLINE_STAFF), grant(20L, StaffRole.MANAGER)));
        assertEquals(StaffRole.ONLINE_STAFF, support.ctx(10L).role);
        assertEquals(StaffRole.MANAGER, support.ctx(20L).role);
        assertThrows(ServiceException.class, () -> support.ctx(30L));
    }

    private AppAuthInterceptor interceptor(AppLoginUser who)
    {
        AppAuthInterceptor i = new AppAuthInterceptor();
        AppTokenService tokens = mock(AppTokenService.class);
        when(tokens.getLoginUser(any())).thenReturn(who);
        ReflectionTestUtils.setField(i, "appTokenService", tokens);
        ReflectionTestUtils.setField(i, "identities", mock(AppIdentityLoader.class));
        return i;
    }

    @Test
    void interceptorKeepsSpecialistGrantForOnlineCreateAndStripsItForOfflineJobs() throws Exception
    {
        HandlerMethod onlineCreate = new HandlerMethod(new AppStaffExtController(), AppStaffExtController.class.getMethod("onlineCreate", Long.class, Map.class));
        HandlerMethod createJob = new HandlerMethod(new AppStaffOpsController(), AppStaffOpsController.class.getMethod("createJob", Long.class, Map.class));

        AppLoginUser specialist = user(grant(10L, StaffRole.ONLINE_STAFF));
        assertTrue(interceptor(specialist).preHandle(new MockHttpServletRequest("POST", "/app/staff/online/tasks"), new MockHttpServletResponse(), onlineCreate));
        assertEquals(StaffRole.ONLINE_STAFF, AppUserContext.get().getStaffGrants().get(0).getRoleCode());
        AppUserContext.remove();

        ServiceException e = assertThrows(ServiceException.class, () -> interceptor(specialist)
                .preHandle(new MockHttpServletRequest("POST", "/app/staff/jobs"), new MockHttpServletResponse(), createJob));
        assertEquals(Integer.valueOf(403), e.getCode());
        assertNull(AppUserContext.get());

        // 混合身份去发线下岗位：专员那条授权被去掉，只剩 B 店主理人，A 店照样 403（StaffSupport.ctx）
        AppLoginUser mixed = user(grant(10L, StaffRole.ONLINE_STAFF), grant(20L, StaffRole.MANAGER));
        assertTrue(interceptor(mixed).preHandle(new MockHttpServletRequest("POST", "/app/staff/jobs"), new MockHttpServletResponse(), createJob));
        List<StaffGrantView> left = AppUserContext.get().getStaffGrants();
        assertEquals(1, left.size());
        assertEquals(StaffRole.MANAGER, left.get(0).getRoleCode());
        assertThrows(ServiceException.class, () -> new StaffSupport().ctx(10L));
        assertEquals(2, mixed.getStaffGrants().size(), "缓存里的完整授权不能被改");
    }

    // ---------------------------------------------------------------- 发布后通知主理人

    private KgOnlineTaskService service(KgJdbc db, StaffSupport support)
    {
        KgOnlineTaskService s = new KgOnlineTaskService();
        ReflectionTestUtils.setField(s, "db", db);
        ReflectionTestUtils.setField(s, "support", support);
        ReflectionTestUtils.setField(s, "guard", mock(KgCommunityGuard.class));
        Map<String, Object> store = new HashMap<String, Object>();
        store.put("cover", "/profile/store.jpg");
        when(db.must(contains("from kg_store"), anyMap(), anyString())).thenReturn(store);
        when(db.insert(eq("kg_job"), anyMap())).thenReturn(901L);
        return s;
    }

    private static Map<String, Object> manager(long id)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("appUserId", id);
        return m;
    }

    @Test
    @SuppressWarnings("unchecked")
    void specialistPublishNotifiesEveryActiveManagerOfTheStore()
    {
        KgJdbc db = mock(KgJdbc.class);
        StaffSupport support = mock(StaffSupport.class);
        when(db.list(contains("from kg_staff_grant"), anyMap())).thenReturn(Arrays.asList(manager(5L), manager(6L)));
        StaffSupport.Ctx c = ctx(StaffRole.ONLINE_STAFF);

        Map<String, Object> out = service(db, support).create(c, form());
        assertEquals(901L, out.get("jobId"));

        // 仍是草稿、来源 staff、发布人记专员本人
        ArgumentCaptor<Map<String, Object>> row = ArgumentCaptor.forClass(Map.class);
        verify(db).insert(eq("kg_job"), row.capture());
        assertEquals("draft", row.getValue().get("status"));
        assertEquals("staff", row.getValue().get("source"));
        assertEquals("staff:42", row.getValue().get("create_by"));
        assertEquals("小林", row.getValue().get("publisher_name"));

        // 只查本店、在职、未删除的主理人
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> ps = ArgumentCaptor.forClass(Map.class);
        verify(db).list(sql.capture(), ps.capture());
        assertTrue(sql.getValue().contains("role_code = 'manager'"));
        assertTrue(sql.getValue().contains("status = '0'") && sql.getValue().contains("del_flag = '0'"));
        assertEquals(10L, ps.getValue().get("s"));

        String content = "专员 小林 提交了线上任务「探店图文」，佣金 ¥12.50 × 3 人，平台审核通过后上线；验收后由你线下付款";
        String route = "/pages/staff/online-jobs?jobId=901&storeId=10";
        verify(support).notifyStaff(5L, "system", "专员提交了线上任务", content, "job", 901L, route);
        verify(support).notifyStaff(6L, "system", "专员提交了线上任务", content, "job", 901L, route);
        verify(support, never()).notifyStore(anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
        verify(support).op(c, "online_create", "发布线上任务", "general", " 探店图文 ", true, null);
    }

    @Test
    void managerOrKeeperPublishDoesNotNotify()
    {
        for (String role : new String[] { StaffRole.MANAGER, StaffRole.KEEPER })
        {
            KgJdbc db = mock(KgJdbc.class);
            StaffSupport support = mock(StaffSupport.class);
            service(db, support).create(ctx(role), form());
            verify(db, never()).list(anyString(), anyMap());
            verify(support, never()).notifyStaff(anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
        }
    }

    @Test
    void invalidFormFailsBeforeAnyNotice()
    {
        KgJdbc db = mock(KgJdbc.class);
        StaffSupport support = mock(StaffSupport.class);
        ServiceException e = assertThrows(ServiceException.class, () -> service(db, support).create(ctx(StaffRole.ONLINE_STAFF), new HashMap<String, Object>()));
        assertEquals(Integer.valueOf(422), e.getCode());
        verify(db, never()).insert(anyString(), anyMap());
        verify(db, never()).list(anyString(), anyMap());
        verifyNoInteractions(support);
    }

    @Test
    void noManagerMeansNoNoticeAndPublishStillSucceeds()
    {
        KgJdbc db = mock(KgJdbc.class);
        StaffSupport support = mock(StaffSupport.class);
        when(db.list(contains("from kg_staff_grant"), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        assertEquals(901L, service(db, support).create(ctx(StaffRole.ONLINE_STAFF), form()).get("jobId"));
        verify(support, never()).notifyStaff(anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    // ---------------------------------------------------------------- 平台审核结果：线上任务点开去任务详情

    @Test
    void reviewNoticeOfOnlineTaskOpensTheTaskDetail()
    {
        for (String type : new String[] { "online", "volunteer" })
        {
            KgJobServiceImpl jobs = new KgJobServiceImpl();
            KgJdbc jdbc = mock(KgJdbc.class);
            StaffSupport support = mock(StaffSupport.class);
            ReflectionTestUtils.setField(jobs, "jdbc", jdbc);
            ReflectionTestUtils.setField(jobs, "staffSupport", support);
            Map<String, Object> j = new HashMap<String, Object>();
            j.put("storeId", 10L);
            j.put("title", "探店图文");
            j.put("source", "staff");
            j.put("jobType", type);
            when(jdbc.one(contains("from kg_job"), anyMap())).thenReturn(j);
            ReflectionTestUtils.invokeMethod(jobs, "notifyStaffJob", 901L, "draft", "published", null);
            String route = "online".equals(type) ? "/pages/staff/online-jobs?jobId=901&storeId=10" : "/pages/staff/jobs";
            // 专员打不开岗位管理：线上任务通过的文字指去线上任务；线下岗位还是原来的文字
            String text = "online".equals(type) ? "「探店图文」已上线，可以在线上任务里查看和分享" : "「探店图文」已上架，可以在岗位管理里分享招募";
            verify(support).notifyStore(eq(10L), eq("system"), eq("岗位已通过平台审核"), eq(text), eq("job"), eq(901L), eq(route));
        }
    }

    @Test
    void rejectedNoticeKeepsItsTextAndNeverPointsSpecialistsToJobManagement()
    {
        for (String type : new String[] { "online", "parttime" })
        {
            KgJobServiceImpl jobs = new KgJobServiceImpl();
            KgJdbc jdbc = mock(KgJdbc.class);
            StaffSupport support = mock(StaffSupport.class);
            ReflectionTestUtils.setField(jobs, "jdbc", jdbc);
            ReflectionTestUtils.setField(jobs, "staffSupport", support);
            Map<String, Object> j = new HashMap<String, Object>();
            j.put("storeId", 10L);
            j.put("title", "探店图文");
            j.put("source", "staff");
            j.put("jobType", type);
            when(jdbc.one(contains("from kg_job"), anyMap())).thenReturn(j);
            ReflectionTestUtils.invokeMethod(jobs, "notifyStaffJob", 901L, "draft", "closed", "佣金太低");
            ReflectionTestUtils.invokeMethod(jobs, "notifyStaffJob", 901L, "draft", "closed", "");
            String route = "online".equals(type) ? "/pages/staff/online-jobs?jobId=901&storeId=10" : "/pages/staff/jobs";
            ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
            verify(support, times(2)).notifyStore(eq(10L), eq("system"), eq("岗位未通过平台审核"), text.capture(), eq("job"), eq(901L), eq(route));
            assertEquals(Arrays.asList("「探店图文」未通过审核，原因：佣金太低。可以按意见调整后重新发布岗位。", "「探店图文」未通过审核，如有疑问请联系平台运营"), text.getAllValues());
            for (String t : text.getAllValues())
            {
                assertFalse(t.contains("岗位管理"), t);
            }
        }
    }
}
