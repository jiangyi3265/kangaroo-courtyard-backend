package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.controller.admin.KgMerchantRequestAdminController;
import com.ruoyi.kangaroo.controller.app.AppStaffOpsController;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.ExtEntity;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgChatReception;
import com.ruoyi.kangaroo.ext.community.KgCommunityGuard;
import com.ruoyi.kangaroo.ext.user.KgCityService;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppRateLimiter;
import com.ruoyi.kangaroo.security.AppTokenService;
import com.ruoyi.kangaroo.service.IKgJobService;
import com.ruoyi.kangaroo.service.IKgMessageService;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;
import com.ruoyi.system.service.ISysConfigService;

/** 10-30 复查修复：自动授权的边界、自己审自己、被撤销的人、后台改派关闭、派单开关等。真实 service + Mock JDBC；SQL 另由本地验收库实跑。 */
class KgMerchantReviewFixesTest extends com.ruoyi.kangaroo.testsupport.WithJobPhoto
{
    private static final long STORE_X = 30L;
    private static final long HOME_STORE = 10L;
    private static final long MERCHANT = 1000L;
    private static final long MANAGER = 1001L;
    private static final long SERVICE = 1002L;

    private final KgJdbc db = mock(KgJdbc.class);
    private final StaffSupport support = mock(StaffSupport.class);
    private final KgCityService cities = mock(KgCityService.class);
    private final IKgStaffGrantService grants = mock(IKgStaffGrantService.class);
    private final AppTokenService tokens = mock(AppTokenService.class);
    private final IKgJobService jobs = mock(IKgJobService.class);
    private final KgStaffJobService staffJobs = mock(KgStaffJobService.class);
    private final KgOnlineTaskService onlineTasks = mock(KgOnlineTaskService.class);
    private final ISysConfigService configs = mock(ISysConfigService.class);
    private final KgMerchantRequestService service = new KgMerchantRequestService();
    private final Map<String, List<Map<String, Object>>> staffByRole = new HashMap<String, List<Map<String, Object>>>();

    private long currentUser = MANAGER;
    private String currentRole = StaffRole.MANAGER;

    @AfterEach
    void backToLegacy()
    {
        RolePhase.set(RolePhase.LEGACY);
    }

    @BeforeEach
    void setup()
    {
        RolePhase.set(RolePhase.NEW);
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "support", support);
        ReflectionTestUtils.setField(service, "cities", cities);
        ReflectionTestUtils.setField(service, "grants", grants);
        ReflectionTestUtils.setField(service, "tokens", tokens);
        ReflectionTestUtils.setField(service, "jobs", jobs);
        ReflectionTestUtils.setField(service, "staffJobs", staffJobs);
        ReflectionTestUtils.setField(service, "onlineTasks", onlineTasks);
        ReflectionTestUtils.setField(service, "configs", configs);
        when(cities.jobCity(anyString())).thenAnswer(i -> i.getArgument(0));
        staffByRole.put(StaffRole.MANAGER, new ArrayList<Map<String, Object>>(Collections.singletonList(person(MANAGER, HOME_STORE))));
        staffByRole.put(StaffRole.MERCHANT_SERVICE, new ArrayList<Map<String, Object>>(Collections.singletonList(person(SERVICE, HOME_STORE))));
        when(db.list(contains("g.role_code = :r"), anyMap())).thenAnswer(i -> {
            Map<String, Object> ps = i.getArgument(1);
            List<Map<String, Object>> l = staffByRole.get(ps.get("r"));
            return l == null ? new ArrayList<Map<String, Object>>() : l;
        });
        when(db.one(contains("select city from kg_store"), anyMap())).thenReturn(p("city", "大理"));
        when(db.must(contains("select store_id, store_name, city from kg_store"), anyMap(), anyString())).thenAnswer(i -> p("storeId", ((Map<?, ?>) i.getArgument(1)).get("s"), "storeName", "喜洲小院", "city", "大理"));
        when(db.one(contains("from kg_merchant_apply"), anyMap())).thenReturn(p("contactName", "阿杰", "phone", "13912345678"));
        when(db.one(contains("from kg_staff_grant where app_user_id"), anyMap())).thenReturn(null);
        when(db.update(contains("update kg_merchant_request set status"), anyMap())).thenReturn(1);
        when(db.insert(eq("kg_merchant_request"), anyMap())).thenReturn(500L);
        when(db.page(anyString(), anyMap(), any(), any())).thenReturn(new HashMap<String, Object>(p("rows", new ArrayList<Object>(), "total", 0L)));
        when(support.ctx(anyLong())).thenAnswer(i -> ctx(i.getArgument(0), currentUser, currentRole));
    }

    private static Map<String, Object> person(long uid, long store)
    {
        return p("appUserId", uid, "storeId", store, "city", "大理", "nickname", "用户" + uid);
    }

    private static StaffSupport.Ctx ctx(Long store, long uid, String role)
    {
        StaffSupport.Ctx c = new StaffSupport.Ctx();
        AppLoginUser me = new AppLoginUser();
        me.setUserId(uid);
        me.setNickname("用户" + uid);
        c.me = me;
        c.storeId = store;
        c.storeName = "门店" + store;
        c.role = role;
        return c;
    }

    private Map<String, Object> request(String type, String status)
    {
        Map<String, Object> r = new HashMap<String, Object>(p("requestId", 500L, "requestType", type, "status", status, "city", "大理", "storeId", STORE_X,
                "storeName", "喜洲小院", "merchantUserId", MERCHANT, "title", "写一篇推文", "contactPhone", "13912345678", "contactName", "阿杰", "content", "说明"));
        when(db.one(contains("from kg_merchant_request where request_id"), anyMap())).thenReturn(r);
        return r;
    }

    private void onGrantRow(long uid, String status, String role)
    {
        when(db.one(contains("from kg_staff_grant where app_user_id"), argThat(m -> m != null && Long.valueOf(uid).equals(m.get("u")))))
                .thenReturn(p("status", status, "delFlag", "0", "roleCode", role));
    }

    private static int code(org.junit.jupiter.api.function.Executable e)
    {
        return assertThrows(ServiceException.class, e).getCode();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> movedColumns(String to)
    {
        ArgumentCaptor<Map<String, Object>> ps = ArgumentCaptor.forClass(Map.class);
        verify(db, atLeastOnce()).update(contains("update kg_merchant_request set status"), ps.capture());
        for (Map<String, Object> m : ps.getAllValues())
        {
            if (to.equals(m.get("to")))
            {
                return m;
            }
        }
        return fail("没有改到状态 " + to);
    }

    // ------------------------------------------------------------------ 自动授权的边界

    private StaffSupport realSupport(KgJdbc jdbc)
    {
        StaffSupport s = new StaffSupport();
        ReflectionTestUtils.setField(s, "db", jdbc);
        ReflectionTestUtils.setField(s, "messages", mock(IKgMessageService.class));
        ReflectionTestUtils.setField(s, "grants", grants);
        return s;
    }

    @Test
    void autoManagerIsReadFromTheGrantRowAndOnlyForManagers()
    {
        KgJdbc jdbc = mock(KgJdbc.class);
        when(jdbc.one(contains("select granted_by from kg_staff_grant"), anyMap())).thenReturn(p("grantedBy", KgMerchantRequestService.GRANTED_BY));
        StaffSupport s = realSupport(jdbc);
        assertTrue(s.autoManager(ctx(STORE_X, MANAGER, StaffRole.MANAGER)));
        assertFalse(s.autoManager(ctx(STORE_X, MANAGER, StaffRole.KEEPER)), "别的角色不用查");
        when(jdbc.one(contains("select granted_by from kg_staff_grant"), anyMap())).thenReturn(p("grantedBy", "admin"));
        assertFalse(s.autoManager(ctx(STORE_X, MANAGER, StaffRole.MANAGER)), "平台开的换工主理人不受限");
    }

    @Test
    void autoManagerCannotDoManagerOnlyMoneyActions()
    {
        // 切换前的规则（切换后换工主理人一律不能动钱，见 RolePhaseRulesTest）
        RolePhase.set(RolePhase.LEGACY);
        KgJdbc jdbc = mock(KgJdbc.class);
        when(jdbc.one(contains("select granted_by from kg_staff_grant"), anyMap())).thenReturn(p("grantedBy", KgMerchantRequestService.GRANTED_BY));
        StaffSupport s = realSupport(jdbc);
        assertEquals(403, code(() -> s.needManager(ctx(STORE_X, MANAGER, StaffRole.MANAGER), "refund_approve", "同意退款")));
        when(jdbc.one(contains("select granted_by from kg_staff_grant"), anyMap())).thenReturn(p("grantedBy", "staff:1"));
        assertDoesNotThrow(() -> s.needManager(ctx(STORE_X, MANAGER, StaffRole.MANAGER), "refund_approve", "同意退款"));
    }

    private KgStaffTeamService team(KgJdbc jdbc, StaffSupport s)
    {
        KgStaffTeamService t = new KgStaffTeamService();
        ReflectionTestUtils.setField(t, "db", jdbc);
        ReflectionTestUtils.setField(t, "support", s);
        ReflectionTestUtils.setField(t, "grants", grants);
        ReflectionTestUtils.setField(t, "tokens", tokens);
        ReflectionTestUtils.setField(t, "messages", mock(IKgMessageService.class));
        return t;
    }

    @Test
    void autoManagerCannotGrantOrRevokeAndSeesNoManageButton()
    {
        // 切换前的规则（切换后小程序里谁都不能管人，见 RolePhaseRulesTest）
        RolePhase.set(RolePhase.LEGACY);
        KgJdbc jdbc = mock(KgJdbc.class);
        StaffSupport s = mock(StaffSupport.class);
        StaffSupport.Ctx c = ctx(STORE_X, MANAGER, StaffRole.MANAGER);
        when(s.autoManager(c)).thenReturn(true);
        KgStaffTeamService t = team(jdbc, s);
        assertEquals(403, code(() -> t.requireManager(c)), "控制器在事务外先挡");
        assertEquals(403, code(() -> t.grant(c, "13812345678", StaffRole.KEEPER)));
        assertEquals(403, code(() -> t.revoke(c, 5L)));
        verify(jdbc, never()).insert(anyString(), anyMap());
        verify(jdbc, never()).update(anyString(), anyMap());
        when(jdbc.list(anyString(), anyMap())).thenReturn(Collections.<Map<String, Object>>emptyList());
        assertEquals(Boolean.FALSE, t.roles(c, null).get("canManage"));

        StaffSupport.Ctx real = ctx(HOME_STORE, MANAGER, StaffRole.MANAGER);
        assertEquals(Boolean.TRUE, t.roles(real, null).get("canManage"), "平台开的还能管");
    }

    private KgChatReception reception(KgJdbc jdbc)
    {
        KgChatReception r = new KgChatReception();
        ReflectionTestUtils.setField(r, "db", jdbc);
        return r;
    }

    @Test
    void autoManagerCannotOpenOrSaveReceptionSettings()
    {
        KgJdbc jdbc = mock(KgJdbc.class);
        when(jdbc.one(contains("select g.role_code,g.granted_by"), anyMap())).thenReturn(p("roleCode", "manager", "grantedBy", KgMerchantRequestService.GRANTED_BY));
        assertEquals(403, code(() -> reception(jdbc).settings(MANAGER, STORE_X)));
        when(jdbc.one(contains("select g.role_code,g.granted_by"), anyMap())).thenReturn(p("roleCode", "manager", "grantedBy", "admin"));
        when(jdbc.list(anyString(), anyMap())).thenReturn(Collections.<Map<String, Object>>emptyList());
        assertDoesNotThrow(() -> reception(jdbc).settings(MANAGER, STORE_X));
    }

    @SuppressWarnings("unchecked")
    @Test
    void autoManagerIsSeatedInOfflineChatButNotOnline()
    {
        KgJdbc jdbc = mock(KgJdbc.class);
        when(jdbc.list(anyString(), anyMap())).thenReturn(Collections.<Map<String, Object>>emptyList());
        reception(jdbc).staff(STORE_X, true);
        reception(jdbc).staff(STORE_X, false);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).list(sql.capture(), anyMap());
        assertTrue(sql.getAllValues().get(0).contains("granted_by,'')='system:merchant_request'"), "线上会话不在席");
        assertFalse(sql.getAllValues().get(1).contains("system:merchant_request"), "线下报名的会话他要跟进，照旧在席");
    }

    @Test
    void messageVisibilityMatchesTheChatSeat() throws Exception
    {
        Path xml = Paths.get("src/main/resources/mapper/kangaroo/KgMessageMapper.xml");
        String text = new String(Files.readAllBytes(xml), StandardCharsets.UTF_8);
        assertTrue(text.contains("coalesce(g.granted_by,'')&lt;&gt;'system:merchant_request' or (c.source='progress'"), "消息列表的放行和会话席位一致");
    }

    // ------------------------------------------------------------------ 门店消息不发给商家服务人

    @Test
    void storeNoticesSkipMerchantServiceUnlessTheyAreOnline()
    {
        IKgMessageService messages = mock(IKgMessageService.class);
        KgJdbc jdbc = mock(KgJdbc.class);
        StaffSupport s = realSupport(jdbc);
        ReflectionTestUtils.setField(s, "messages", messages);
        when(grants.listActiveStaffUserIds(STORE_X)).thenReturn(Arrays.asList(MANAGER, SERVICE));
        when(grants.listMerchantServiceUserIds(STORE_X)).thenReturn(Collections.singletonList(SERVICE));
        // 线上任务的门店消息按权限矩阵挑人（StaffSupport.onlineRecipients）：读这家店在职授权的角色
        when(jdbc.list(contains("g.role_code, g.also_service"), anyMap())).thenReturn(Arrays.asList(
                p("appUserId", MANAGER, "roleCode", StaffRole.MANAGER, "alsoService", "0"),
                p("appUserId", SERVICE, "roleCode", StaffRole.MERCHANT_SERVICE, "alsoService", "0")));

        assertEquals(1, s.notifyStore(STORE_X, "system", "活动有新报名", "小王 报名了", "activity", 1L, "/pages/staff/activities?s=4&id=1"));
        verify(messages).send(eq(MANAGER), anyString(), anyString(), eq("活动有新报名"), anyString(), anyString(), anyLong(), anyString());
        verify(messages, never()).send(eq(SERVICE), anyString(), anyString(), eq("活动有新报名"), anyString(), anyString(), anyLong(), anyString());

        // 切换后换工主理人没有「线上任务」权限，点开是 403：线上任务的消息不再发给他，只发给能管的商家服务人
        assertEquals(1, s.notifyStore(STORE_X, "system", "线上任务有新成果", "收到成果", "online_submission", 2L, "/pages/staff/online-jobs?s=2"));
        verify(messages).send(eq(SERVICE), anyString(), anyString(), eq("线上任务有新成果"), anyString(), anyString(), anyLong(), anyString());
        verify(messages, never()).send(eq(MANAGER), anyString(), anyString(), eq("线上任务有新成果"), anyString(), anyString(), anyLong(), anyString());

        assertEquals(1, s.notifyStore(STORE_X, "system", "岗位已通过平台审核", "已上线", "job", 3L, "/pages/staff/online-jobs?jobId=3&storeId=30"));
        verify(messages).send(eq(SERVICE), anyString(), anyString(), eq("岗位已通过平台审核"), anyString(), anyString(), anyLong(), anyString());
        verify(messages, never()).send(eq(MANAGER), anyString(), anyString(), eq("岗位已通过平台审核"), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void onlinePayoutNoticesGoToWhoCanRegisterThePayout()
    {
        final long KEEPER = 1003L;
        final long LEAD = 1004L;
        IKgMessageService messages = mock(IKgMessageService.class);
        KgJdbc jdbc = mock(KgJdbc.class);
        StaffSupport s = realSupport(jdbc);
        ReflectionTestUtils.setField(s, "messages", messages);
        CityLeadScope leads = mock(CityLeadScope.class);
        ReflectionTestUtils.setField(s, "cityLeads", leads);
        when(leads.leadsOf(STORE_X)).thenReturn(Collections.singletonList(LEAD));
        // 自营店：小院主理人 + 换工主理人，都不能登记付款
        when(jdbc.list(contains("g.role_code, g.also_service"), anyMap())).thenReturn(Arrays.asList(
                p("appUserId", KEEPER, "roleCode", StaffRole.KEEPER, "alsoService", "0"),
                p("appUserId", MANAGER, "roleCode", StaffRole.MANAGER, "alsoService", "0")));

        assertEquals(1, s.notifyStore(STORE_X, "system", "线上任务待付款", "已验收", "online_settlement", 9L, "/pages/staff/online-jobs?appId=9"));
        verify(messages).send(eq(LEAD), anyString(), anyString(), eq("线上任务待付款"), anyString(), anyString(), anyLong(), anyString());
        verify(messages, never()).send(eq(KEEPER), anyString(), anyString(), eq("线上任务待付款"), anyString(), anyString(), anyLong(), anyString());
        verify(messages, never()).send(eq(MANAGER), anyString(), anyString(), eq("线上任务待付款"), anyString(), anyString(), anyLong(), anyString());

        // 新成果：小院主理人能验收，就发给他；城市负责人平时不做日常，换工主理人点开是 403，都不发
        assertEquals(1, s.notifyStore(STORE_X, "system", "线上任务有新成果", "收到成果", "online_submission", 9L, "/pages/staff/online-jobs?appId=9"));
        verify(messages).send(eq(KEEPER), anyString(), anyString(), eq("线上任务有新成果"), anyString(), anyString(), anyLong(), anyString());
        verify(messages, never()).send(eq(LEAD), anyString(), anyString(), eq("线上任务有新成果"), anyString(), anyString(), anyLong(), anyString());
        verify(messages, never()).send(eq(MANAGER), anyString(), anyString(), eq("线上任务有新成果"), anyString(), anyString(), anyLong(), anyString());

        // 合作店：商家自己付款，城市负责人不用顶上
        when(jdbc.list(contains("g.role_code, g.also_service"), anyMap())).thenReturn(Arrays.asList(
                p("appUserId", MERCHANT, "roleCode", StaffRole.MERCHANT, "alsoService", "0"),
                p("appUserId", KEEPER, "roleCode", StaffRole.KEEPER, "alsoService", "0")));
        assertEquals(1, s.notifyStore(STORE_X, "system", "接单人已确认收款", "结算完成", "online_settlement", 9L, "/pages/staff/online-jobs?appId=9"));
        verify(messages).send(eq(MERCHANT), anyString(), anyString(), eq("接单人已确认收款"), anyString(), anyString(), anyLong(), anyString());
        verify(messages, never()).send(eq(LEAD), anyString(), anyString(), eq("接单人已确认收款"), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void applicationNoticesSkipMerchantServiceOnOfflineJobs()
    {
        com.ruoyi.kangaroo.service.impl.KgApplicationServiceImpl apps = new com.ruoyi.kangaroo.service.impl.KgApplicationServiceImpl();
        IKgMessageService messages = mock(IKgMessageService.class);
        com.ruoyi.kangaroo.mapper.KgApplicationMapper appMapper = mock(com.ruoyi.kangaroo.mapper.KgApplicationMapper.class);
        com.ruoyi.kangaroo.mapper.KgJobMapper jobMapper = mock(com.ruoyi.kangaroo.mapper.KgJobMapper.class);
        ReflectionTestUtils.setField(apps, "kgStaffGrantService", grants);
        ReflectionTestUtils.setField(apps, "kgMessageService", messages);
        ReflectionTestUtils.setField(apps, "kgApplicationMapper", appMapper);
        ReflectionTestUtils.setField(apps, "kgJobMapper", jobMapper);
        when(grants.listActiveStaffUserIds(STORE_X)).thenReturn(Arrays.asList(MANAGER, SERVICE));
        when(grants.listMerchantServiceUserIds(STORE_X)).thenReturn(Collections.singletonList(SERVICE));
        com.ruoyi.kangaroo.domain.KgApplication app = new com.ruoyi.kangaroo.domain.KgApplication();
        app.setJobId(77L);
        when(appMapper.selectKgApplicationByAppId(9L)).thenReturn(app);
        com.ruoyi.kangaroo.domain.KgJob job = new com.ruoyi.kangaroo.domain.KgJob();
        job.setJobType("volunteer");
        when(jobMapper.selectKgJobByJobId(77L)).thenReturn(job);

        ReflectionTestUtils.invokeMethod(apps, "notifyStaff", STORE_X, "apply", "新的报名待审核", "小王 报名了", 9L, "/pages/staff/applications?appId=9");
        verify(messages).send(eq(MANAGER), anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
        verify(messages, never()).send(eq(SERVICE), anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());

        job.setJobType("online");
        ReflectionTestUtils.invokeMethod(apps, "notifyStaff", STORE_X, "apply", "新的接单申请", "小王 申请了", 9L, "/pages/staff/applications?appId=9");
        verify(messages).send(eq(SERVICE), anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    // ------------------------------------------------------------------ 活动的钱：自动授权不能动

    @Test
    void activityMoneyActionsGoThroughTheManagerCheck()
    {
        com.ruoyi.kangaroo.controller.app.AppStaffActivityController controller = new com.ruoyi.kangaroo.controller.app.AppStaffActivityController();
        KgJdbc jdbc = mock(KgJdbc.class);
        StaffSupport s = mock(StaffSupport.class);
        com.ruoyi.kangaroo.ext.community.KgActivityService activities = mock(com.ruoyi.kangaroo.ext.community.KgActivityService.class);
        ReflectionTestUtils.setField(controller, "db", jdbc);
        ReflectionTestUtils.setField(controller, "support", s);
        ReflectionTestUtils.setField(controller, "activities", activities);
        StaffSupport.Ctx c = ctx(STORE_X, MANAGER, StaffRole.MANAGER);
        when(jdbc.one(contains("from kg_activity"), anyMap())).thenReturn(p("storeId", STORE_X));
        when(s.ctx(STORE_X)).thenReturn(c);
        when(jdbc.count(anyString(), anyMap())).thenReturn(1L);
        doThrow(new ServiceException("这家店是派单给你的", 403)).when(s).needManager(any(), anyString(), anyString());

        assertEquals(403, code(() -> controller.action(5L, "cancel", new HashMap<String, Object>())));
        assertEquals(403, code(() -> controller.refund(5L, 6L, "approve", new HashMap<String, Object>())));
        verify(activities, never()).approveRefund(any(), anyString());
        verify(activities, never()).transition(any(), anyString(), any(), anyString(), anyBoolean());
    }

    // ------------------------------------------------------------------ 限频与内容审核

    @Test
    void createJobIsRateLimitedPerPerson()
    {
        AppStaffOpsController controller = new AppStaffOpsController();
        AppRateLimiter limiter = mock(AppRateLimiter.class);
        StaffSupport s = mock(StaffSupport.class);
        KgStaffJobService jobService = mock(KgStaffJobService.class);
        when(s.ctx(STORE_X)).thenReturn(ctx(STORE_X, MERCHANT, StaffRole.MERCHANT));
        when(jobService.create(any(), anyMap())).thenReturn(new HashMap<String, Object>());
        ReflectionTestUtils.setField(controller, "limiter", limiter);
        ReflectionTestUtils.setField(controller, "support", s);
        ReflectionTestUtils.setField(controller, "jobs", jobService);
        controller.createJob(STORE_X, new HashMap<String, Object>());
        verify(limiter).check(eq("staff:job-create"), eq(String.valueOf(MERCHANT)), eq(20), eq(3600), anyString());

        doThrow(new ServiceException("发布太频繁了，稍后再试", 429)).when(limiter).check(anyString(), anyString(), anyInt(), anyInt(), anyString());
        assertEquals(429, code(() -> controller.createJob(STORE_X, new HashMap<String, Object>())));
        verify(jobService, times(1)).create(any(), anyMap());
    }

    private KgStaffJobService jobService(KgJdbc jdbc, String storeAddress)
    {
        KgStaffJobService s = new KgStaffJobService();
        ReflectionTestUtils.setField(s, "db", jdbc);
        ReflectionTestUtils.setField(s, "support", mock(StaffSupport.class));
        when(jdbc.must(contains("from kg_store"), anyMap(), anyString())).thenReturn(p("storeName", "喜洲小院", "city", "大理", "address", storeAddress, "cover", ""));
        when(jdbc.insert(eq("kg_job"), anyMap())).thenReturn(77L);
        return s;
    }

    private static Map<String, Object> jobForm()
    {
        Map<String, Object> in = new HashMap<String, Object>();
        in.put("jobType", "volunteer");
        in.put("title", "招换工");
        in.put("work", "前台接待");
        in.put("hope", "开朗");
        in.put("startDate", "2026-12-01");
        in.put("headcount", 2);
        in.put("images", Collections.singletonList(PHOTO));
        return in;
    }

    @Test
    void merchantJobTextGoesThroughContentGuardOthersDoNot()
    {
        KgJdbc jdbc = mock(KgJdbc.class);
        KgStaffJobService s = jobService(jdbc, "喜洲");
        KgCommunityGuard guard = mock(KgCommunityGuard.class);
        ReflectionTestUtils.setField(s, "guard", guard);
        s.create(ctx(STORE_X, MERCHANT, StaffRole.MERCHANT), jobForm());
        verify(guard).check("招换工", "", "前台接待", "开朗", "");

        KgCommunityGuard blocking = mock(KgCommunityGuard.class);
        when(blocking.check(any())).thenThrow(new ServiceException("内容包含不允许发布的信息，请修改后再发", 422));
        ReflectionTestUtils.setField(s, "guard", blocking);
        int before = mockingDetails(jdbc).getInvocations().size();
        assertEquals(422, code(() -> s.create(ctx(STORE_X, MERCHANT, StaffRole.MERCHANT), jobForm())));
        assertTrue(mockingDetails(jdbc).getInvocations().stream().skip(before).noneMatch(i -> "insert".equals(i.getMethod().getName())), "没过审核就不写库");

        ReflectionTestUtils.setField(s, "guard", guard);
        clearInvocations(guard);
        s.create(ctx(STORE_X, MANAGER, StaffRole.MANAGER), jobForm());
        verifyNoInteractions(guard);
    }

    // ------------------------------------------------------------------ 工作地点、上架后换图

    @Test
    void workplaceIsRequiredWhenTheStoreHasNoAddress()
    {
        KgJdbc jdbc = mock(KgJdbc.class);
        KgStaffJobService s = jobService(jdbc, "");
        assertEquals(422, code(() -> s.create(ctx(STORE_X, MERCHANT, StaffRole.MERCHANT), jobForm())));
        verify(jdbc, never()).insert(anyString(), anyMap());

        Map<String, Object> filled = jobForm();
        filled.put("address", "大理市喜洲镇");
        assertEquals(77L, s.create(ctx(STORE_X, MERCHANT, StaffRole.MERCHANT), filled).get("jobId"));
    }

    @Test
    void merchantCannotSwapPhotosOnceTheJobIsLive()
    {
        KgJdbc jdbc = mock(KgJdbc.class);
        KgStaffJobService s = jobService(jdbc, "喜洲");
        Map<String, Object> in = new HashMap<String, Object>(p("images", Collections.singletonList(PHOTO)));
        for (String status : Arrays.asList("published", "paused"))
        {
            when(jdbc.must(contains("from kg_job"), anyMap(), anyString())).thenReturn(p("jobId", 77L, "storeId", STORE_X, "title", "招换工", "jobType", "volunteer", "status", status));
            assertEquals(403, code(() -> s.images(ctx(STORE_X, MERCHANT, StaffRole.MERCHANT), 77L, in)), status);
            verify(jdbc, never()).updateRow(anyString(), anyString(), any(), anyMap());
        }
        // 换工主理人换得了；商家自己的草稿也换得了（上架前换工主理人会看到）
        assertDoesNotThrow(() -> s.images(ctx(STORE_X, MANAGER, StaffRole.MANAGER), 77L, in));
        when(jdbc.must(contains("from kg_job"), anyMap(), anyString())).thenReturn(p("jobId", 77L, "storeId", STORE_X, "title", "招换工", "jobType", "volunteer", "status", "draft"));
        assertDoesNotThrow(() -> s.images(ctx(STORE_X, MERCHANT, StaffRole.MERCHANT), 77L, in));
    }

    // ------------------------------------------------------------------ 自己审自己

    @Test
    void nobodyHandlesTheirOwnRequest()
    {
        StaffSupport.Ctx asManager = ctx(HOME_STORE, MERCHANT, StaffRole.MANAGER);
        request("online_task", "pending_verify");
        assertEquals(403, code(() -> service.intakeDetail(asManager, 500L)));
        assertEquals(403, code(() -> service.intakeNote(asManager, 500L, "备注")));
        assertEquals(403, code(() -> service.intakeForward(asManager, 500L, null, null)));
        assertEquals(403, code(() -> service.intakeReturn(asManager, 500L, "不行")));

        StaffSupport.Ctx asService = ctx(HOME_STORE, MERCHANT, StaffRole.MERCHANT_SERVICE);
        request("online_task", "forwarded");
        assertEquals(403, code(() -> service.serviceDetail(asService, 500L)));
        assertEquals(403, code(() -> service.serviceAccept(asService, 500L)));
        Map<String, Object> mine = request("online_task", "accepted");
        mine.put("serviceUserId", MERCHANT);
        mine.put("jobId", 88L);
        assertEquals(403, code(() -> service.servicePublish(asService, 500L)));
        assertEquals(403, code(() -> service.serviceDone(asService, 500L)));
        verify(db, never()).update(contains("update kg_merchant_request set status"), anyMap());
        verify(jobs, never()).publishMerchantDraft(any(), anyString());
    }

    @Test
    void listsAndBadgesLeaveOutOwnRequests()
    {
        StaffSupport.Ctx c = ctx(HOME_STORE, MANAGER, StaffRole.MANAGER);
        service.intakeList(c, null, null, 1, 10);
        service.dispatchedList(c, null, 1, 10);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db, times(2)).page(sql.capture(), anyMap(), any(), any());
        for (String s : sql.getAllValues())
        {
            assertTrue(s.contains("r.merchant_user_id <> :me"), s);
        }
        service.hubCounts(c);
        verify(db, atLeastOnce()).count(contains("r.merchant_user_id <> :me"), argThat(m -> Long.valueOf(MANAGER).equals(m.get("me"))));
    }

    // ------------------------------------------------------------------ 被撤销授权的人

    @Test
    void revokedServiceCannotBeForwardedToOrAcceptOrOpenThatStore()
    {
        onGrantRow(SERVICE, "1", StaffRole.MERCHANT_SERVICE);
        request("online_task", "pending_verify");
        assertEquals(403, code(() -> service.intakeForward(ctx(HOME_STORE, MANAGER, StaffRole.MANAGER), 500L, SERVICE, null)), "指定他也不行");
        verify(db, never()).update(contains("update kg_merchant_request set status"), anyMap());

        // 没指定、本城只有他：等于本城没有人，转平台，不能说「已转给」
        Map<String, Object> out = service.intakeForward(ctx(HOME_STORE, MANAGER, StaffRole.MANAGER), 500L, null, null);
        assertEquals(KgMerchantRequestService.UNASSIGNED, out.get("requestStatus"));
        verify(support, never()).notifyStaff(eq(SERVICE), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());

        StaffSupport.Ctx s = ctx(HOME_STORE, SERVICE, StaffRole.MERCHANT_SERVICE);
        request("online_task", "forwarded");
        assertEquals(403, code(() -> service.serviceDetail(s, 500L)));
        assertEquals(403, code(() -> service.serviceAccept(s, 500L)));
        verify(db, never()).update(contains("update kg_merchant_request set status"), argThat(m -> KgMerchantRequestService.ACCEPTED.equals(m.get("to"))));
    }

    @Test
    void otherRoleAtThatStoreAlsoBlocksAndServiceListHidesThem()
    {
        onGrantRow(SERVICE, "0", StaffRole.KEEPER);
        request("online_task", "forwarded");
        assertEquals(403, code(() -> service.serviceAccept(ctx(HOME_STORE, SERVICE, StaffRole.MERCHANT_SERVICE), 500L)));
        service.serviceList(ctx(HOME_STORE, SERVICE, StaffRole.MERCHANT_SERVICE), null, null, 1, 10);
        // 另有角色的不出现；兼任商家服务人的小院主理人不算另有角色
        verify(db).page(contains("(bg.role_code = 'merchant_service' or (bg.role_code = 'keeper' and bg.also_service = '1'))"), anyMap(), any(), any());
    }

    @Test
    void goodServiceStillAcceptsAndGetsTheGrant()
    {
        request("online_task", "forwarded");
        Map<String, Object> out = service.serviceAccept(ctx(HOME_STORE, SERVICE, StaffRole.MERCHANT_SERVICE), 500L);
        assertEquals(KgMerchantRequestService.ACCEPTED, out.get("requestStatus"));
        verify(db).insert(eq("kg_staff_grant"), argThat(g -> Long.valueOf(SERVICE).equals(g.get("app_user_id")) && Long.valueOf(STORE_X).equals(g.get("store_id"))));
    }

    // ------------------------------------------------------------------ 城市负责人看不到完整联系方式

    @Test
    void cityLeadSeesContactsMaskedInFreeText()
    {
        Map<String, Object> r = request("online_task", "pending_verify");
        r.put("content", "联系我 13812345678");
        r.put("verifyNote", "微信同号 13912345678");
        r.put("title", "加 13712345678");
        r.put("jobId", 88L);
        when(db.one(contains("from kg_job j"), anyMap())).thenReturn(new HashMap<String, Object>(p("jobStatus", "draft", "content", "打 13612345678", "requirement", "问 13512345678",
                "deliverables", "发 13412345678", "criteria", "找 13312345678")));
        Map<String, Object> d = service.cityDetail(ctx(HOME_STORE, 1003L, StaffRole.CITY_LEAD), 500L);
        String all = d.toString();
        for (String phone : Arrays.asList("13812345678", "13912345678", "13712345678", "13612345678", "13512345678", "13412345678", "13312345678"))
        {
            assertFalse(all.contains(phone), phone + " 不能原样露出：" + all);
        }

        // 换工主理人是要联系商家的，照旧看完整
        Map<String, Object> full = service.intakeDetail(ctx(HOME_STORE, MANAGER, StaffRole.MANAGER), 500L);
        assertEquals("联系我 13812345678", full.get("content"));
        assertEquals("13912345678", full.get("contactPhone"));
    }

    // ------------------------------------------------------------------ 状态文字跟着岗位

    @Test
    void publishedRequestSaysPausedOrClosedWhenTheJobIs()
    {
        assertEquals("已暂停", KgMerchantRequestService.statusLabel("published", "paused"));
        assertEquals("已关闭", KgMerchantRequestService.statusLabel("published", "closed"));
        assertEquals("已上架", KgMerchantRequestService.statusLabel("published", "published"));
        assertEquals("已上架", KgMerchantRequestService.statusLabel("published", null));
        assertEquals("换工主理人完善中", KgMerchantRequestService.statusLabel("dispatched", "closed"), "只看已上架的");

        Map<String, Object> row = new HashMap<String, Object>(p("requestId", 500L, "requestType", "volunteer", "status", "published", "jobStatus", "closed", "title", "招换工"));
        when(db.page(anyString(), anyMap(), any(), any())).thenReturn(new HashMap<String, Object>(p("rows", new ArrayList<Object>(Collections.singletonList(row)), "total", 1L)));
        Map<String, Object> page = service.cityList(ctx(HOME_STORE, 1003L, StaffRole.CITY_LEAD), null, null, 1, 10);
        Map<?, ?> first = (Map<?, ?>) ((List<?>) page.get("rows")).get(0);
        assertEquals("已关闭", first.get("statusLabel"));
        assertEquals("closed", first.get("jobStatus"));
        verify(db).page(contains("left join kg_job j on j.job_id = r.job_id"), anyMap(), any(), any());
    }

    // ------------------------------------------------------------------ 退回

    @Test
    void storeNameOnlyRequestCannotBeReturned()
    {
        Map<String, Object> r = request("managed", "pending_verify");
        r.remove("merchantUserId");
        r.remove("storeId");
        StaffSupport.Ctx c = ctx(HOME_STORE, MANAGER, StaffRole.MANAGER);
        assertEquals(409, code(() -> service.intakeReturn(c, 500L, "信息不全")));
        verify(db, never()).update(contains("update kg_merchant_request set status"), anyMap());
        assertEquals(Boolean.FALSE, service.intakeDetail(c, 500L).get("canReturn"));
        r.put("merchantUserId", MERCHANT);
        assertEquals(Boolean.TRUE, service.intakeDetail(c, 500L).get("canReturn"));
    }

    @Test
    void resubmitClearsTheVerifierSoItMustBeVerifiedAgain()
    {
        Map<String, Object> r = request("managed", "returned");
        r.put("verifierUserId", MANAGER);
        Map<String, Object> b = new HashMap<String, Object>(p("requestType", "managed", "content", "改过的", "contactName", "阿杰", "contactPhone", "13912345678"));
        service.merchantResubmit(ctx(STORE_X, MERCHANT, StaffRole.MERCHANT), 500L, b);
        Map<String, Object> ps = movedColumns(KgMerchantRequestService.PENDING_VERIFY);
        assertTrue(ps.containsKey("x_verifier_user_id"));
        assertNull(ps.get("x_verifier_user_id"));
    }

    // ------------------------------------------------------------------ 后台：改派、关闭、标记完成

    @Test
    void redispatchMovesAStuckAcceptedRequestToOtherServiceAndClearsTheOldOne()
    {
        Map<String, Object> r = request("online_task", "accepted");
        r.put("verifierUserId", MANAGER);
        r.put("serviceUserId", 9999L);
        Map<String, Object> out = service.redispatch(500L, "boss");
        assertEquals(KgMerchantRequestService.FORWARDED, out.get("requestStatus"));
        Map<String, Object> ps = movedColumns(KgMerchantRequestService.FORWARDED);
        assertEquals(KgMerchantRequestService.ACCEPTED, ps.get("from"));
        assertTrue(ps.containsKey("x_service_user_id"));
        assertNull(ps.get("x_service_user_id"));
        verify(support).notifyStaff(eq(SERVICE), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
        verify(support, never()).notifyStaff(eq(MERCHANT), anyString(), eq("需求已核实"), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void redispatchPicksUpANewManagerForADispatchedJob()
    {
        request("volunteer", "dispatched").put("jobId", 77L);
        Map<String, Object> out = service.redispatch(500L, "boss");
        assertEquals(KgMerchantRequestService.DISPATCHED, out.get("requestStatus"));
        assertEquals(KgMerchantRequestService.DISPATCHED, movedColumns(KgMerchantRequestService.DISPATCHED).get("from"));
        verify(db).insert(eq("kg_staff_grant"), argThat(g -> Long.valueOf(MANAGER).equals(g.get("app_user_id")) && StaffRole.MANAGER.equals(g.get("role_code"))));
    }

    @Test
    void redispatchWithNobodyDropsBackToThePlatformQueue()
    {
        staffByRole.remove(StaffRole.MANAGER);
        request("volunteer", "dispatched");
        assertEquals(KgMerchantRequestService.UNASSIGNED, service.redispatch(500L, "boss").get("requestStatus"));

        request("volunteer", "unassigned");
        assertEquals(409, code(() -> service.redispatch(500L, "boss")), "本来就等平台的，提示去开通，状态不变");
        for (String done : Arrays.asList("done", "closed", "published", "returned"))
        {
            request("online_task", done);
            assertEquals(409, code(() -> service.redispatch(500L, "boss")), done);
        }
    }

    @Test
    void adminCloseClosesTheDraftAndTellsTheMerchant()
    {
        request("volunteer", "dispatched").put("jobId", 77L);
        when(db.one(contains("select status from kg_job"), anyMap())).thenReturn(p("status", "draft"));
        assertEquals(422, code(() -> service.adminClose(500L, "  ", "boss")));
        Map<String, Object> out = service.adminClose(500L, "商家不做了", "boss");
        assertEquals(KgMerchantRequestService.CLOSED, out.get("requestStatus"));
        verify(jobs).closeMerchantDraft(77L, "boss", "商家不做了");
        assertEquals("商家不做了", movedColumns(KgMerchantRequestService.CLOSED).get("x_return_reason"));
        verify(support).notifyStaff(eq(MERCHANT), anyString(), eq("需求已关闭"), contains("商家不做了"), anyString(), anyLong(), anyString());
    }

    @Test
    void adminCloseRefusesWhileTheJobIsLiveOrTheRequestIsOver()
    {
        request("online_task", "accepted").put("jobId", 88L);
        when(db.one(contains("select status from kg_job"), anyMap())).thenReturn(p("status", "published"));
        assertEquals(409, code(() -> service.adminClose(500L, "不做了", "boss")));
        verify(jobs, never()).closeMerchantDraft(any(), anyString(), anyString());
        when(db.one(contains("select status from kg_job"), anyMap())).thenReturn(p("status", "closed"));
        assertEquals(KgMerchantRequestService.CLOSED, service.adminClose(500L, "不做了", "boss").get("requestStatus"), "岗位已关闭就能关");
        for (String over : Arrays.asList("done", "closed", "published"))
        {
            request("managed", over);
            assertEquals(409, code(() -> service.adminClose(500L, "不做了", "boss")), over);
        }
    }

    @Test
    void adminDoneOnlyForManagedAndCloudChain()
    {
        request("managed", "unassigned");
        assertEquals(KgMerchantRequestService.DONE, service.adminDone(500L, "boss").get("requestStatus"));
        verify(support).notifyStaff(eq(MERCHANT), anyString(), eq("需求已完成"), anyString(), anyString(), anyLong(), anyString());
        request("online_task", "accepted");
        assertEquals(409, code(() -> service.adminDone(500L, "boss")));
        request("cloud_chain", "done");
        assertEquals(409, code(() -> service.adminDone(500L, "boss")));
    }

    @Test
    void adminEndpointsPassTheReason()
    {
        KgMerchantRequestService requests = mock(KgMerchantRequestService.class);
        when(requests.adminClose(any(), any(), any())).thenReturn(new HashMap<String, Object>(p("message", "已关闭并通知商家")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(adminAuth());
        try
        {
            new KgMerchantRequestAdminController(requests).close(500L, new HashMap<String, Object>(p("reason", "不做了")));
            verify(requests).adminClose(500L, "不做了", "boss");
        }
        finally
        {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    private static org.springframework.security.authentication.UsernamePasswordAuthenticationToken adminAuth()
    {
        com.ruoyi.common.core.domain.entity.SysUser user = new com.ruoyi.common.core.domain.entity.SysUser();
        user.setUserId(1L);
        user.setUserName("boss");
        com.ruoyi.common.core.domain.model.LoginUser login = new com.ruoyi.common.core.domain.model.LoginUser();
        login.setUser(user);
        return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(login, null, Collections.emptyList());
    }

    @Test
    void adminEntityShowsTheActionsOnTheRightRows()
    {
        ExtEntity e = new KgMerchantRequestModule().entities().get(0);
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<String, Map<String, Object>>();
        for (Map<String, Object> a : e.actions)
        {
            byKey.put(String.valueOf(a.get("key")), a);
        }
        assertEquals(Arrays.asList("redispatch", "closeRequest", "markDone"), new ArrayList<String>(byKey.keySet()));
        @SuppressWarnings("unchecked")
        Map<String, List<String>> redispatchIf = (Map<String, List<String>>) byKey.get("redispatch").get("showIf");
        assertEquals(Arrays.asList("unassigned", "dispatched", "pending_verify", "forwarded", "accepted"), redispatchIf.get("status"));
        assertEquals(Boolean.TRUE, byKey.get("closeRequest").get("prompt"), "关闭要填原因");
        @SuppressWarnings("unchecked")
        Map<String, List<String>> doneIf = (Map<String, List<String>>) byKey.get("markDone").get("showIf");
        assertEquals(Arrays.asList("managed", "cloud_chain"), doneIf.get("requestType"));
    }

    @Test
    void adminCannotEditTitleOrContentOnceThereIsAJob()
    {
        KgMerchantRequestModule module = new KgMerchantRequestModule();
        KgJdbc jdbc = mock(KgJdbc.class);
        ReflectionTestUtils.setField(module, "db", jdbc);
        ExtEntity e = module.entities().get(0);
        when(jdbc.one(contains("from kg_merchant_request"), anyMap())).thenReturn(p("jobId", 77L, "title", "招换工", "content", "第一行\r\n第二行"));
        Map<String, Object> changed = new HashMap<String, Object>(p("_pk", 500L, "title", "老板改的", "content", "第一行\n第二行"));
        assertEquals(422, code(() -> e.validator.accept(changed, false)));
        Map<String, Object> same = new HashMap<String, Object>(p("_pk", 500L, "title", "招换工", "content", "第一行\n第二行", "verify_note", "备注能改"));
        assertDoesNotThrow(() -> e.validator.accept(same, false));

        when(jdbc.one(contains("from kg_merchant_request"), anyMap())).thenReturn(p("title", "代运营", "content", "说明"));
        Map<String, Object> noJob = new HashMap<String, Object>(p("_pk", 500L, "title", "老板改的", "content", "改过"));
        assertDoesNotThrow(() -> e.validator.accept(noJob, false), "代运营、抖音卖房没有岗位，照旧能改");
    }

    // ------------------------------------------------------------------ 派单开关（新版小程序覆盖前）

    @Test
    void switchOffKeepsEverythingInTheOwnersQueue()
    {
        when(configs.selectConfigByKey(KgMerchantRequestService.ROUTING_KEY)).thenReturn("off");
        Map<String, Object> job = service.dispatchJob(ctx(STORE_X, MERCHANT, StaffRole.MERCHANT), 77L, "volunteer", "招换工", "内容");
        assertEquals(KgMerchantRequestService.UNASSIGNED, job.get("requestStatus"));
        assertEquals("已提交，平台会尽快安排", job.get("message"), "本城其实有人，不能说没有");
        verify(db, never()).insert(eq("kg_staff_grant"), anyMap());

        Map<String, Object> task = service.onlineTaskCreated(ctx(STORE_X, MERCHANT, StaffRole.MERCHANT), 88L, "写推文", "内容");
        assertEquals(KgMerchantRequestService.UNASSIGNED, task.get("requestStatus"));
        verify(support, never()).notifyStaff(eq(MANAGER), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());

        request("volunteer", "unassigned");
        assertEquals(409, code(() -> service.redispatch(500L, "boss")), "新版没发布时后台也不能派出去");
    }

    @Test
    void switchOnOrMissingRoutesAsNormal()
    {
        for (String value : Arrays.asList("on", "", "随便写的"))
        {
            when(configs.selectConfigByKey(KgMerchantRequestService.ROUTING_KEY)).thenReturn(value);
            assertTrue(service.staffRouting(), value);
        }
        when(configs.selectConfigByKey(KgMerchantRequestService.ROUTING_KEY)).thenThrow(new RuntimeException("redis down"));
        assertTrue(service.staffRouting(), "参数读不到不能把流程弄断");
        ReflectionTestUtils.setField(service, "configs", null);
        assertTrue(service.staffRouting());
    }

    @Test
    void beforeTheRoleSwitchNoValueOfTheSwitchRoutes()
    {
        RolePhase.set(RolePhase.LEGACY);
        for (String value : Arrays.asList("on", "off", "", "随便写的"))
        {
            when(configs.selectConfigByKey(KgMerchantRequestService.ROUTING_KEY)).thenReturn(value);
            assertFalse(service.staffRouting(), "阶段锁：切换之前开关填什么都不派 " + value);
        }
        Map<String, Object> job = service.dispatchJob(ctx(STORE_X, MERCHANT, StaffRole.MERCHANT), 77L, "volunteer", "招换工", "内容");
        assertEquals(KgMerchantRequestService.UNASSIGNED, job.get("requestStatus"));
        verify(db, never()).insert(eq("kg_staff_grant"), anyMap());
        request("volunteer", "unassigned");
        assertEquals(409, code(() -> service.redispatch(500L, "boss")));
    }

    @Test
    void migrationWritesTheSwitchOffAndRollbackRemovesIt() throws Exception
    {
        Path dir = Paths.get("../sql/kangaroo");
        String up = new String(Files.readAllBytes(dir.resolve("V63__merchant_roles.sql")), StandardCharsets.UTF_8);
        String down = new String(Files.readAllBytes(dir.resolve("V63__rollback.sql")), StandardCharsets.UTF_8);
        assertTrue(up.contains("'kangaroo.merchant.staffRouting', 'off'"), "生产先关着，新版发布后再开");
        assertTrue(down.contains("delete from sys_config where config_key = 'kangaroo.merchant.staffRouting'"));
    }
}
