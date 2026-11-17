package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.KgCityService;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppTokenService;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.security.StaffGrantView;
import com.ruoyi.kangaroo.service.IKgJobService;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;

/**
 * 商家需求（V63）：派单、核实、转发、接手、城市隔离、角色把关。真实 service + Mock JDBC；SQL 另由本地验收库实跑。
 */
class KgMerchantRequestServiceTest
{
    private static final long DALI_STORE = 10L;
    private static final long LIJIANG_STORE = 20L;
    private static final long MERCHANT_STORE = 30L;
    private static final long MERCHANT = 1000L;
    private static final long DALI_MANAGER = 1001L;
    private static final long LIJIANG_MANAGER = 2001L;
    private static final long DALI_SERVICE = 1002L;
    private static final long LIJIANG_SERVICE = 2002L;
    private static final long DALI_LEAD = 1003L;

    private final KgJdbc db = mock(KgJdbc.class);
    private final StaffSupport support = mock(StaffSupport.class);
    private final KgCityService cities = mock(KgCityService.class);
    private final IKgStaffGrantService grants = mock(IKgStaffGrantService.class);
    private final AppTokenService tokens = mock(AppTokenService.class);
    private final IKgJobService jobs = mock(IKgJobService.class);
    private final KgStaffJobService staffJobs = mock(KgStaffJobService.class);
    private final KgOnlineTaskService onlineTasks = mock(KgOnlineTaskService.class);
    private final KgMerchantRequestService service = new KgMerchantRequestService();

    /** 每个角色在职的人：role -> [{appUserId, storeId, city, nickname}] */
    private final Map<String, List<Map<String, Object>>> staffByRole = new HashMap<String, List<Map<String, Object>>>();

    @BeforeEach
    void setup()
    {
        RolePhase.set(RolePhase.NEW);
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "merchantAgreements", mock(KgMerchantAgreements.class));
        ReflectionTestUtils.setField(service, "support", support);
        ReflectionTestUtils.setField(service, "cities", cities);
        ReflectionTestUtils.setField(service, "grants", grants);
        ReflectionTestUtils.setField(service, "tokens", tokens);
        ReflectionTestUtils.setField(service, "jobs", jobs);
        ReflectionTestUtils.setField(service, "staffJobs", staffJobs);
        ReflectionTestUtils.setField(service, "onlineTasks", onlineTasks);
        when(cities.jobCity(anyString())).thenAnswer(i -> {
            String n = i.getArgument(0);
            return n.endsWith("市") ? n.substring(0, n.length() - 1) : n;
        });
        staff(StaffRole.MANAGER, row(DALI_MANAGER, DALI_STORE, "大理市"), row(LIJIANG_MANAGER, LIJIANG_STORE, "丽江"));
        staff(StaffRole.MERCHANT_SERVICE, row(DALI_SERVICE, DALI_STORE, "大理"), row(LIJIANG_SERVICE, LIJIANG_STORE, "丽江"));
        staff(StaffRole.CITY_LEAD, row(DALI_LEAD, DALI_STORE, "大理"));
        when(db.list(contains("g.role_code = :r"), anyMap())).thenAnswer(i -> {
            Map<String, Object> ps = i.getArgument(1);
            List<Map<String, Object>> l = staffByRole.get(ps.get("r"));
            return l == null ? new ArrayList<Map<String, Object>>() : l;
        });
        when(db.one(contains("select city from kg_store"), anyMap())).thenAnswer(i -> {
            Map<String, Object> ps = i.getArgument(1);
            return p("city", cityOf((Long) ps.get("s")));
        });
        when(db.must(contains("select store_id, store_name, city from kg_store"), anyMap(), anyString())).thenAnswer(i -> {
            Map<String, Object> ps = i.getArgument(1);
            return p("storeId", ps.get("s"), "storeName", "喜洲小院", "city", cityOf((Long) ps.get("s")));
        });
        when(db.one(contains("from kg_merchant_apply"), anyMap())).thenReturn(p("contactName", "阿杰", "phone", "13912345678"));
        when(db.one(contains("from kg_staff_grant where app_user_id"), anyMap())).thenReturn(null);
        when(db.update(contains("update kg_merchant_request set status"), anyMap())).thenReturn(1);
        when(db.insert(eq("kg_merchant_request"), anyMap())).thenReturn(500L);
        when(db.page(anyString(), anyMap(), any(), any())).thenReturn(new HashMap<String, Object>(p("rows", new ArrayList<Object>(), "total", 0L)));
        when(support.ctx(anyLong())).thenAnswer(i -> ctx(i.getArgument(0), currentUser, currentRole));
    }

    @AfterEach
    void clear()
    {
        RolePhase.set(RolePhase.LEGACY);
        AppUserContext.remove();
    }

    private long currentUser = DALI_MANAGER;
    private String currentRole = StaffRole.MANAGER;

    private static String cityOf(Long store)
    {
        if (store != null && store == LIJIANG_STORE) return "丽江";
        if (store != null && store == 40L) return "";
        return "大理市";
    }

    private void staff(String role, Map<String, Object>... rows)
    {
        staffByRole.put(role, new ArrayList<Map<String, Object>>(Arrays.asList(rows)));
    }

    private static Map<String, Object> row(long uid, long store, String city)
    {
        return p("appUserId", uid, "storeId", store, "city", city, "nickname", "用户" + uid);
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

    private Map<String, Object> request(String type, String status, String city)
    {
        Map<String, Object> r = new HashMap<String, Object>(p("requestId", 500L, "requestType", type, "status", status, "city", city, "storeId", MERCHANT_STORE,
                "storeName", "喜洲小院", "merchantUserId", MERCHANT, "title", "写一篇推文", "contactPhone", "13912345678", "contactName", "阿杰", "content", "说明"));
        return r;
    }

    private void onRequest(Map<String, Object> r)
    {
        when(db.one(contains("from kg_merchant_request where request_id"), anyMap())).thenReturn(r);
        when(db.one(contains("from kg_merchant_request where job_id"), anyMap())).thenReturn(r);
    }

    private int code(org.junit.jupiter.api.function.Executable e)
    {
        return assertThrows(ServiceException.class, e).getCode();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> insertedRequest()
    {
        ArgumentCaptor<Map<String, Object>> row = ArgumentCaptor.forClass(Map.class);
        verify(db).insert(eq("kg_merchant_request"), row.capture());
        return row.getValue();
    }

    // ------------------------------------------------------------------ 派单

    @SuppressWarnings("unchecked")
    @Test
    void merchantJobGoesToManagersOfTheSameCityOnly()
    {
        Map<String, Object> out = service.dispatchJob(ctx(MERCHANT_STORE, MERCHANT, StaffRole.MERCHANT), 77L, "volunteer", "招换工", "内容");

        Map<String, Object> r = insertedRequest();
        assertEquals("volunteer", r.get("request_type"));
        assertEquals("大理", r.get("city"), "城市归一后再存");
        assertEquals(KgMerchantRequestService.DISPATCHED, r.get("status"));
        assertEquals(77L, r.get("job_id"));
        assertEquals("13912345678", r.get("contact_phone"), "联系方式取入驻时填的");
        assertEquals(KgMerchantRequestService.DISPATCHED, out.get("requestStatus"));
        assertFalse(String.valueOf(out.get("statusLabel")).matches(".*[a-z].*"), "老版本小程序也读这个，要中文");

        ArgumentCaptor<Map<String, Object>> grant = ArgumentCaptor.forClass(Map.class);
        verify(db).insert(eq("kg_staff_grant"), grant.capture());
        assertEquals(DALI_MANAGER, grant.getValue().get("app_user_id"), "只给本城的换工主理人补授权，丽江的不能动");
        assertEquals(MERCHANT_STORE, grant.getValue().get("store_id"));
        assertEquals(StaffRole.MANAGER, grant.getValue().get("role_code"));
        assertEquals(KgMerchantRequestService.GRANTED_BY, grant.getValue().get("granted_by"));
        verify(tokens).refreshGrants(eq(DALI_MANAGER), any());
        verify(support).notifyStaff(eq(DALI_MANAGER), anyString(), anyString(), anyString(), eq("merchant_request"), eq(500L), eq("/pages/staff/jobs?storeId=" + MERCHANT_STORE));
        verify(support, never()).notifyStaff(eq(LIJIANG_MANAGER), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
        verify(support).notifyStaff(eq(DALI_LEAD), anyString(), anyString(), anyString(), eq("merchant_request"), eq(500L), anyString());
    }

    @Test
    void merchantIsNotDispatchedToHimself()
    {
        staff(StaffRole.MANAGER, row(MERCHANT, DALI_STORE, "大理"));
        Map<String, Object> out = service.dispatchJob(ctx(MERCHANT_STORE, MERCHANT, StaffRole.MERCHANT), 77L, "parttime", "招兼职", "内容");
        assertEquals(KgMerchantRequestService.UNASSIGNED, out.get("requestStatus"));
        verify(db, never()).insert(eq("kg_staff_grant"), anyMap());
    }

    @Test
    void noLocalManagerWaitsForTheOwner()
    {
        staff(StaffRole.MANAGER, row(LIJIANG_MANAGER, LIJIANG_STORE, "丽江"));
        Map<String, Object> out = service.dispatchJob(ctx(MERCHANT_STORE, MERCHANT, StaffRole.MERCHANT), 77L, "fulltime", "招全职", "内容");
        assertEquals(KgMerchantRequestService.UNASSIGNED, out.get("requestStatus"));
        assertEquals(KgMerchantRequestService.UNASSIGNED, insertedRequest().get("status"));
        verify(db, never()).insert(eq("kg_staff_grant"), anyMap());
        verify(support, never()).notifyStaff(eq(LIJIANG_MANAGER), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
        verify(support).notifyStaff(eq(DALI_LEAD), anyString(), anyString(), contains("转平台"), anyString(), anyLong(), anyString());
    }

    @Test
    void storeWithoutCityNeverMatchesAnyone()
    {
        Map<String, Object> out = service.dispatchJob(ctx(40L, MERCHANT, StaffRole.MERCHANT), 77L, "volunteer", "招换工", "内容");
        assertEquals(KgMerchantRequestService.UNASSIGNED, out.get("requestStatus"));
    }

    @Test
    void revokedGrantIsNotRevivedAndDoesNotCountAsDispatched()
    {
        when(db.one(contains("from kg_staff_grant where app_user_id"), anyMap())).thenReturn(p("status", "1", "delFlag", "0", "roleCode", "manager"));
        Map<String, Object> out = service.dispatchJob(ctx(MERCHANT_STORE, MERCHANT, StaffRole.MERCHANT), 77L, "volunteer", "招换工", "内容");
        verify(db, never()).insert(eq("kg_staff_grant"), anyMap());
        verify(db, never()).update(contains("kg_staff_grant"), anyMap());
        assertEquals(KgMerchantRequestService.UNASSIGNED, out.get("requestStatus"), "唯一的换工主理人被撤销过：派不出去，转老板");
    }

    @Test
    void existingActiveManagerGrantIsLeftAlone()
    {
        when(db.one(contains("from kg_staff_grant where app_user_id"), anyMap())).thenReturn(p("status", "0", "delFlag", "0", "roleCode", "manager"));
        Map<String, Object> out = service.dispatchJob(ctx(MERCHANT_STORE, MERCHANT, StaffRole.MERCHANT), 77L, "volunteer", "招换工", "内容");
        verify(db, never()).insert(eq("kg_staff_grant"), anyMap());
        assertEquals(KgMerchantRequestService.DISPATCHED, out.get("requestStatus"));
    }

    @Test
    void onlineTaskGoesToVerifyOrToTheOwner()
    {
        Map<String, Object> out = service.onlineTaskCreated(ctx(MERCHANT_STORE, MERCHANT, StaffRole.MERCHANT), 88L, "写推文", "内容");
        assertEquals(KgMerchantRequestService.PENDING_VERIFY, out.get("requestStatus"));
        assertEquals("online_task", insertedRequest().get("request_type"));
        verify(db, never()).insert(eq("kg_staff_grant"), anyMap());
        verify(support).notifyStaff(eq(DALI_MANAGER), anyString(), anyString(), anyString(), eq("merchant_request"), eq(500L),
                eq("/pages/staff/intake?storeId=" + DALI_STORE + "&requestId=500"));

        staff(StaffRole.MANAGER);
        assertEquals(KgMerchantRequestService.UNASSIGNED, service.onlineTaskCreated(ctx(MERCHANT_STORE, MERCHANT, StaffRole.MERCHANT), 89L, "写推文", "内容").get("requestStatus"));
    }

    // ------------------------------------------------------------------ 城市隔离

    @Test
    void intakeListIsFilteredByTheCurrentStoresCity()
    {
        service.intakeList(ctx(LIJIANG_STORE, LIJIANG_MANAGER, StaffRole.MANAGER), null, null, 1, 10);
        ArgumentCaptor<Map<String, Object>> ps = ArgumentCaptor.forClass(Map.class);
        verify(db).page(contains("r.city = :town"), ps.capture(), any(), any());
        assertEquals("丽江", ps.getValue().get("town"));
        assertEquals(KgMerchantRequestService.PENDING_VERIFY, ps.getValue().get("st"), "默认只列待核实");
    }

    @Test
    void storeWithoutCityCannotListAnything()
    {
        assertEquals(422, code(() -> service.intakeList(ctx(40L, DALI_MANAGER, StaffRole.MANAGER), null, null, 1, 10)));
        verify(db, never()).page(anyString(), anyMap(), any(), any());
    }

    @Test
    void managerOfAnotherCityCannotSeeOrActOnARequest()
    {
        onRequest(request("online_task", KgMerchantRequestService.PENDING_VERIFY, "大理"));
        StaffSupport.Ctx lijiang = ctx(LIJIANG_STORE, LIJIANG_MANAGER, StaffRole.MANAGER);
        assertEquals(403, code(() -> service.intakeDetail(lijiang, 500L)));
        assertEquals(403, code(() -> service.intakeNote(lijiang, 500L, "备注")));
        assertEquals(403, code(() -> service.intakeForward(lijiang, 500L, null, null)));
        assertEquals(403, code(() -> service.intakeReturn(lijiang, 500L, "不行")));
        verify(db, never()).update(anyString(), anyMap());
    }

    @Test
    void serviceOfAnotherCityCannotSeeOrAccept()
    {
        onRequest(request("managed", KgMerchantRequestService.FORWARDED, "大理"));
        StaffSupport.Ctx lijiang = ctx(LIJIANG_STORE, LIJIANG_SERVICE, StaffRole.MERCHANT_SERVICE);
        assertEquals(403, code(() -> service.serviceDetail(lijiang, 500L)));
        assertEquals(403, code(() -> service.serviceAccept(lijiang, 500L)));
        verify(db, never()).update(anyString(), anyMap());
    }

    @Test
    void cityLeadSeesOnlyHisCityAndPhoneIsMasked()
    {
        onRequest(request("volunteer", KgMerchantRequestService.DISPATCHED, "大理"));
        assertEquals(403, code(() -> service.cityDetail(ctx(LIJIANG_STORE, 2003L, StaffRole.CITY_LEAD), 500L)));
        Map<String, Object> d = service.cityDetail(ctx(DALI_STORE, DALI_LEAD, StaffRole.CITY_LEAD), 500L);
        assertEquals("139****5678", d.get("contactPhone"));
    }

    @Test
    void forwardOnlyToServiceInTheSameCity()
    {
        onRequest(request("online_task", KgMerchantRequestService.PENDING_VERIFY, "大理"));
        assertEquals(422, code(() -> service.intakeForward(ctx(DALI_STORE, DALI_MANAGER, StaffRole.MANAGER), 500L, LIJIANG_SERVICE, null)));
        verify(db, never()).update(anyString(), anyMap());
    }

    @Test
    void submitForAStoreInAnotherCityIsRefused()
    {
        Map<String, Object> b = form();
        b.put("requestType", "managed");
        b.put("merchantStoreId", LIJIANG_STORE);
        assertEquals(403, code(() -> service.intakeSubmit(ctx(DALI_STORE, DALI_MANAGER, StaffRole.MANAGER), b)));
        verify(db, never()).insert(eq("kg_merchant_request"), anyMap());
    }

    @Test
    void merchantCannotSeeAnotherStoresRequest()
    {
        onRequest(request("managed", KgMerchantRequestService.RETURNED, "大理"));
        StaffSupport.Ctx other = ctx(31L, 1999L, StaffRole.MERCHANT);
        assertEquals(403, code(() -> service.merchantDetail(other, 500L)));
        assertEquals(403, code(() -> service.merchantResubmit(other, 500L, form())));
    }

    // ------------------------------------------------------------------ 角色把关

    private void login(long uid, String role, long store)
    {
        currentUser = uid;
        currentRole = role;
        AppLoginUser me = new AppLoginUser();
        me.setUserId(uid);
        StaffGrantView g = new StaffGrantView();
        g.setStoreId(store);
        g.setRoleCode(role);
        g.setActive(true);
        me.setStaffGrants(Collections.singletonList(g));
        AppUserContext.set(me);
    }

    @Test
    void wrongRoleCannotUseAnotherRolesEntrance()
    {
        // 换工主理人也有 intake·编辑，只看矩阵的话他能调商家服务人的接口
        login(DALI_MANAGER, StaffRole.MANAGER, DALI_STORE);
        assertEquals(403, code(() -> service.serviceCtx(null, StaffPerms.EDIT)));
        assertEquals(403, code(() -> service.serviceCtx(DALI_STORE, StaffPerms.EDIT)));
        assertEquals(403, code(() -> service.cityCtx(DALI_STORE)));
        assertEquals(403, code(() -> service.merchantCtx(DALI_STORE)));

        login(DALI_SERVICE, StaffRole.MERCHANT_SERVICE, DALI_STORE);
        assertEquals(403, code(() -> service.managerCtx(DALI_STORE, StaffPerms.VIEW)));
        assertEquals(403, code(() -> service.managerCtx(null, StaffPerms.VIEW)));

        // 城市负责人切换后能顶换工主理人和商家服务人（见 leadStandsIn*），商家专用的入口不行
        login(DALI_LEAD, StaffRole.CITY_LEAD, DALI_STORE);
        assertEquals(403, code(() -> service.merchantCtx(DALI_STORE)));

        login(MERCHANT, StaffRole.MERCHANT, MERCHANT_STORE);
        assertEquals(403, code(() -> service.managerCtx(MERCHANT_STORE, StaffPerms.VIEW)));
        assertEquals(403, code(() -> service.cityCtx(MERCHANT_STORE)));
    }

    @Test
    void noStoreParameterPicksTheFirstStoreOfThatRole()
    {
        login(DALI_SERVICE, StaffRole.MERCHANT_SERVICE, DALI_STORE);
        assertEquals(DALI_STORE, service.serviceCtx(null, StaffPerms.VIEW).storeId);
    }

    @Test
    void onlyTheStoresOwnManagerHandlesItsDispatchedJob()
    {
        when(db.one(contains("select store_id from kg_job"), anyMap())).thenReturn(p("storeId", MERCHANT_STORE));
        currentRole = StaffRole.KEEPER;
        assertEquals(403, code(() -> service.jobManagerCtx(77L, StaffPerms.AUDIT, "x", "y")));
        currentRole = StaffRole.MERCHANT;
        assertEquals(403, code(() -> service.jobManagerCtx(77L, StaffPerms.AUDIT, "x", "y")));
        currentRole = StaffRole.MANAGER;
        assertEquals(MERCHANT_STORE, service.jobManagerCtx(77L, StaffPerms.AUDIT, "x", "y").storeId);
    }

    // ------------------------------------------------------------------ 核实、转发

    @Test
    void forwardGrantsTheStoreAndTellsEveryoneInTheCity()
    {
        staff(StaffRole.MERCHANT_SERVICE, row(DALI_SERVICE, DALI_STORE, "大理"), row(1012L, DALI_STORE, "大理"), row(LIJIANG_SERVICE, LIJIANG_STORE, "丽江"));
        onRequest(request("online_task", KgMerchantRequestService.PENDING_VERIFY, "大理"));

        Map<String, Object> out = service.intakeForward(ctx(DALI_STORE, DALI_MANAGER, StaffRole.MANAGER), 500L, null, "核实过了");

        assertEquals(KgMerchantRequestService.FORWARDED, out.get("requestStatus"));
        ArgumentCaptor<Map<String, Object>> ps = ArgumentCaptor.forClass(Map.class);
        verify(db).update(contains("update kg_merchant_request set status"), ps.capture());
        assertEquals(KgMerchantRequestService.PENDING_VERIFY, ps.getValue().get("from"));
        assertEquals(KgMerchantRequestService.FORWARDED, ps.getValue().get("to"));
        assertEquals(DALI_MANAGER, ps.getValue().get("x_verifier_user_id"));
        assertEquals("核实过了", ps.getValue().get("x_verify_note"));
        verify(db, times(2)).insert(eq("kg_staff_grant"), argThat(g -> StaffRole.MERCHANT_SERVICE.equals(g.get("role_code")) && Long.valueOf(MERCHANT_STORE).equals(g.get("store_id"))));
        verify(db, never()).insert(eq("kg_staff_grant"), argThat(g -> Long.valueOf(LIJIANG_SERVICE).equals(g.get("app_user_id"))));
        verify(support).notifyStaff(eq(DALI_SERVICE), anyString(), anyString(), anyString(), eq("merchant_request"), eq(500L), anyString());
        verify(support).notifyStaff(eq(1012L), anyString(), anyString(), anyString(), eq("merchant_request"), eq(500L), anyString());
        verify(support, never()).notifyStaff(eq(LIJIANG_SERVICE), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
        verify(support).notifyStaff(eq(MERCHANT), anyString(), anyString(), anyString(), eq("merchant_request"), eq(500L), anyString());
    }

    @Test
    void forwardToAChosenPersonGrantsOnlyHimButTellsAll()
    {
        staff(StaffRole.MERCHANT_SERVICE, row(DALI_SERVICE, DALI_STORE, "大理"), row(1012L, DALI_STORE, "大理"));
        onRequest(request("managed", KgMerchantRequestService.PENDING_VERIFY, "大理"));
        service.intakeForward(ctx(DALI_STORE, DALI_MANAGER, StaffRole.MANAGER), 500L, 1012L, null);
        verify(db, times(1)).insert(eq("kg_staff_grant"), anyMap());
        verify(db).insert(eq("kg_staff_grant"), argThat(g -> Long.valueOf(1012L).equals(g.get("app_user_id"))));
        verify(support).notifyStaff(eq(DALI_SERVICE), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void noServiceInTheCityGoesToTheOwnerQueueAndKeepsTheVerification()
    {
        staff(StaffRole.MERCHANT_SERVICE, row(LIJIANG_SERVICE, LIJIANG_STORE, "丽江"));
        onRequest(request("online_task", KgMerchantRequestService.PENDING_VERIFY, "大理"));
        Map<String, Object> out = service.intakeForward(ctx(DALI_STORE, DALI_MANAGER, StaffRole.MANAGER), 500L, null, null);
        assertEquals(KgMerchantRequestService.UNASSIGNED, out.get("requestStatus"));
        ArgumentCaptor<Map<String, Object>> ps = ArgumentCaptor.forClass(Map.class);
        verify(db).update(contains("update kg_merchant_request set status"), ps.capture());
        assertEquals(DALI_MANAGER, ps.getValue().get("x_verifier_user_id"));
        verify(db, never()).insert(eq("kg_staff_grant"), anyMap());
    }

    @Test
    void secondClickLosesTheRaceWith409()
    {
        onRequest(request("managed", KgMerchantRequestService.PENDING_VERIFY, "大理"));
        when(db.update(contains("update kg_merchant_request set status"), anyMap())).thenReturn(0);
        assertEquals(409, code(() -> service.intakeForward(ctx(DALI_STORE, DALI_MANAGER, StaffRole.MANAGER), 500L, null, null)));
    }

    @Test
    void intakeOnlyActsOnPendingVerifyAndOnlyOnIntakeTypes()
    {
        StaffSupport.Ctx c = ctx(DALI_STORE, DALI_MANAGER, StaffRole.MANAGER);
        onRequest(request("online_task", KgMerchantRequestService.FORWARDED, "大理"));
        assertEquals(409, code(() -> service.intakeForward(c, 500L, null, null)));
        assertEquals(409, code(() -> service.intakeReturn(c, 500L, "原因")));
        onRequest(request("volunteer", KgMerchantRequestService.DISPATCHED, "大理"));
        assertEquals(409, code(() -> service.intakeForward(c, 500L, null, null)));
        assertEquals(409, code(() -> service.intakeDetail(c, 500L)));
    }

    @Test
    void returnNeedsAReasonAndTellsTheMerchant()
    {
        StaffSupport.Ctx c = ctx(DALI_STORE, DALI_MANAGER, StaffRole.MANAGER);
        onRequest(request("managed", KgMerchantRequestService.PENDING_VERIFY, "大理"));
        assertEquals(422, code(() -> service.intakeReturn(c, 500L, "  ")));
        assertEquals(KgMerchantRequestService.RETURNED, service.intakeReturn(c, 500L, "电话打不通").get("requestStatus"));
        verify(support).notifyStaff(eq(MERCHANT), anyString(), anyString(), contains("电话打不通"), eq("merchant_request"), eq(500L), anyString());
    }

    @Test
    void managerSubmitsForAMerchantByName()
    {
        Map<String, Object> b = form();
        b.put("requestType", "cloud_chain");
        b.put("storeName", "还没开通的小店");
        Map<String, Object> out = service.intakeSubmit(ctx(DALI_STORE, DALI_MANAGER, StaffRole.MANAGER), b);
        assertEquals(KgMerchantRequestService.PENDING_VERIFY, out.get("requestStatus"));
        Map<String, Object> r = insertedRequest();
        assertEquals("manager", r.get("source"));
        assertEquals("大理", r.get("city"));
        assertNull(r.get("store_id"));
        assertEquals("还没开通的小店", r.get("store_name"));
    }

    @Test
    void managerSubmitNeedsAStoreAndAValidType()
    {
        StaffSupport.Ctx c = ctx(DALI_STORE, DALI_MANAGER, StaffRole.MANAGER);
        Map<String, Object> b = form();
        b.put("requestType", "managed");
        assertEquals(422, code(() -> service.intakeSubmit(c, b)), "没选店也没填店名");
        Map<String, Object> job = form();
        job.put("requestType", "online_task");
        job.put("storeName", "x");
        assertEquals(422, code(() -> service.intakeSubmit(c, job)), "只代提交代运营、抖音卖房");
    }

    // ------------------------------------------------------------------ 商家服务人

    @Test
    void serviceAcceptsThenOnlyThatPersonContinues()
    {
        Map<String, Object> r = request("online_task", KgMerchantRequestService.FORWARDED, "大理");
        r.put("jobId", 88L);
        onRequest(r);
        StaffSupport.Ctx me = ctx(DALI_STORE, DALI_SERVICE, StaffRole.MERCHANT_SERVICE);
        assertEquals(409, code(() -> service.servicePublish(me, 500L)), "没接手不能上架");

        assertEquals(KgMerchantRequestService.ACCEPTED, service.serviceAccept(me, 500L).get("requestStatus"));
        verify(db).insert(eq("kg_staff_grant"), argThat(g -> Long.valueOf(DALI_SERVICE).equals(g.get("app_user_id")) && StaffRole.MERCHANT_SERVICE.equals(g.get("role_code"))));

        r.put("status", KgMerchantRequestService.ACCEPTED);
        r.put("serviceUserId", DALI_SERVICE);
        StaffSupport.Ctx other = ctx(DALI_STORE, 1012L, StaffRole.MERCHANT_SERVICE);
        assertEquals(403, code(() -> service.servicePublish(other, 500L)));
        assertEquals(403, code(() -> service.serviceDone(other, 500L)));

        service.servicePublish(me, 500L);
        verify(jobs).publishMerchantDraft(88L, "staff:" + DALI_SERVICE);
    }

    @Test
    void serviceCannotTouchWhatWasNotForwarded()
    {
        StaffSupport.Ctx me = ctx(DALI_STORE, DALI_SERVICE, StaffRole.MERCHANT_SERVICE);
        for (String st : Arrays.asList(KgMerchantRequestService.PENDING_VERIFY, KgMerchantRequestService.RETURNED, KgMerchantRequestService.UNASSIGNED))
        {
            onRequest(request("online_task", st, "大理"));
            assertEquals(403, code(() -> service.serviceDetail(me, 500L)), st);
            assertEquals(403, code(() -> service.serviceAccept(me, 500L)), st);
        }
        onRequest(request("volunteer", KgMerchantRequestService.DISPATCHED, "大理"));
        assertEquals(409, code(() -> service.serviceDetail(me, 500L)), "招募类不归商家服务人");
    }

    @Test
    void doneRequiresTheOnlineTaskToBeUp()
    {
        Map<String, Object> r = request("online_task", KgMerchantRequestService.ACCEPTED, "大理");
        r.put("jobId", 88L);
        r.put("serviceUserId", DALI_SERVICE);
        onRequest(r);
        StaffSupport.Ctx me = ctx(DALI_STORE, DALI_SERVICE, StaffRole.MERCHANT_SERVICE);
        when(db.one(contains("select status from kg_job"), anyMap())).thenReturn(p("status", "draft"));
        assertEquals(409, code(() -> service.serviceDone(me, 500L)));
        when(db.one(contains("select status from kg_job"), anyMap())).thenReturn(p("status", "published"));
        assertEquals(KgMerchantRequestService.DONE, service.serviceDone(me, 500L).get("requestStatus"));
    }

    @Test
    void managedRequestIsDoneWithoutAJob()
    {
        Map<String, Object> r = request("managed", KgMerchantRequestService.ACCEPTED, "大理");
        r.put("serviceUserId", DALI_SERVICE);
        onRequest(r);
        StaffSupport.Ctx me = ctx(DALI_STORE, DALI_SERVICE, StaffRole.MERCHANT_SERVICE);
        assertEquals(409, code(() -> service.servicePublish(me, 500L)), "代运营没有东西要上架");
        assertEquals(KgMerchantRequestService.DONE, service.serviceDone(me, 500L).get("requestStatus"));
    }

    // ------------------------------------------------------------------ 换工主理人处理派单岗位

    @Test
    void publishGoesThroughTheJobServiceAndRecordsIt()
    {
        onRequest(request("volunteer", KgMerchantRequestService.DISPATCHED, "大理"));
        StaffSupport.Ctx c = ctx(MERCHANT_STORE, DALI_MANAGER, StaffRole.MANAGER);
        Map<String, Object> out = service.dispatchedPublish(c, 77L);
        verify(jobs).publishMerchantDraft(77L, "staff:" + DALI_MANAGER);
        assertEquals(KgMerchantRequestService.PUBLISHED, out.get("requestStatus"));
        verify(support).notifyStaff(eq(MERCHANT), anyString(), eq("岗位已上架"), anyString(), eq("merchant_request"), eq(500L), anyString());
    }

    @Test
    void ordinaryDraftCannotBePublishedFromTheApp()
    {
        when(db.one(contains("from kg_merchant_request where job_id"), anyMap())).thenReturn(null);
        StaffSupport.Ctx c = ctx(DALI_STORE, DALI_MANAGER, StaffRole.MANAGER);
        assertEquals(403, code(() -> service.dispatchedPublish(c, 77L)));
        assertEquals(403, code(() -> service.dispatchedEdit(c, 77L, form())));
        assertEquals(403, code(() -> service.dispatchedClose(c, 77L, "不行")));
        verify(jobs, never()).publishMerchantDraft(anyLong(), anyString());
    }

    @Test
    void handledDraftAndOnlineRequestsAreRefused()
    {
        StaffSupport.Ctx c = ctx(MERCHANT_STORE, DALI_MANAGER, StaffRole.MANAGER);
        onRequest(request("volunteer", KgMerchantRequestService.PUBLISHED, "大理"));
        assertEquals(409, code(() -> service.dispatchedPublish(c, 77L)));
        onRequest(request("online_task", KgMerchantRequestService.PENDING_VERIFY, "大理"));
        assertEquals(403, code(() -> service.dispatchedPublish(c, 77L)), "线上任务不能从岗位这边上架");
    }

    @Test
    void closeNeedsAReasonAndTellsTheMerchant()
    {
        onRequest(request("parttime", KgMerchantRequestService.DISPATCHED, "大理"));
        StaffSupport.Ctx c = ctx(MERCHANT_STORE, DALI_MANAGER, StaffRole.MANAGER);
        assertEquals(422, code(() -> service.dispatchedClose(c, 77L, " ")));
        verify(jobs, never()).closeMerchantDraft(anyLong(), anyString(), anyString());
        assertEquals(KgMerchantRequestService.CLOSED, service.dispatchedClose(c, 77L, "工资没写清楚").get("requestStatus"));
        verify(jobs).closeMerchantDraft(77L, "staff:" + DALI_MANAGER, "工资没写清楚");
        verify(support).notifyStaff(eq(MERCHANT), anyString(), anyString(), contains("工资没写清楚"), anyString(), anyLong(), anyString());
    }

    @Test
    void editGoesThroughTheSameValidationAndKeepsTheTitleInStep()
    {
        onRequest(request("volunteer", KgMerchantRequestService.DISPATCHED, "大理"));
        StaffSupport.Ctx c = ctx(MERCHANT_STORE, DALI_MANAGER, StaffRole.MANAGER);
        when(staffJobs.updateMerchantDraft(eq(c), eq(77L), anyMap())).thenReturn(new HashMap<String, Object>(p("jobId", 77L, "title", "新标题")));
        service.dispatchedEdit(c, 77L, form());
        verify(db).update(contains("set title = :t"), argThat(ps -> "新标题".equals(ps.get("t"))));
    }

    // ------------------------------------------------------------------ 商家

    private static Map<String, Object> form()
    {
        Map<String, Object> b = new HashMap<String, Object>();
        b.put("content", "想让袋鼠帮我运营");
        b.put("contactName", "阿杰");
        b.put("contactPhone", "139 1234-5678");
        return b;
    }

    @Test
    void merchantSubmitsManagedAndCloudChainOnly()
    {
        StaffSupport.Ctx c = ctx(MERCHANT_STORE, MERCHANT, StaffRole.MERCHANT);
        Map<String, Object> b = form();
        b.put("requestType", "managed");
        assertEquals(KgMerchantRequestService.PENDING_VERIFY, service.merchantSubmit(c, b).get("requestStatus"));
        Map<String, Object> r = insertedRequest();
        assertEquals("13912345678", r.get("contact_phone"));
        assertEquals("代运营 · 喜洲小院", r.get("title"));
        assertEquals(MERCHANT, r.get("merchant_user_id"));
        assertEquals(MERCHANT_STORE, r.get("store_id"));

        Map<String, Object> bad = form();
        bad.put("requestType", "volunteer");
        assertEquals(422, code(() -> service.merchantSubmit(c, bad)));
    }

    @Test void managedAgreementGuardPreventsInitialAndReturnedRequestWrites() {
        StaffSupport.Ctx c = ctx(MERCHANT_STORE, MERCHANT, StaffRole.MERCHANT);
        KgMerchantAgreements agreements = mock(KgMerchantAgreements.class);
        ReflectionTestUtils.setField(service, "merchantAgreements", agreements);
        doThrow(new ServiceException("先确认协议", 428)).when(agreements).requireAccepted(c, "managed");
        Map<String,Object> b = form(); b.put("requestType", "managed");
        assertEquals(428, code(() -> service.merchantSubmit(c, b)));
        verify(db, never()).insert(eq("kg_merchant_request"), anyMap());
        onRequest(request("managed", KgMerchantRequestService.RETURNED, "大理"));
        assertEquals(428, code(() -> service.merchantResubmit(c, 500L, b)));
        verify(db, never()).update(contains("update kg_merchant_request set status"), anyMap());
    }

    @Test
    void formIsValidated()
    {
        StaffSupport.Ctx c = ctx(MERCHANT_STORE, MERCHANT, StaffRole.MERCHANT);
        Map<String, Object> b = form(); b.put("requestType", "managed"); b.put("content", " ");
        assertEquals(422, code(() -> service.merchantSubmit(c, b)));
        Map<String, Object> b2 = form(); b2.put("requestType", "managed"); b2.put("contactPhone", "123");
        assertEquals(422, code(() -> service.merchantSubmit(c, b2)));
        Map<String, Object> b3 = form(); b3.put("requestType", "managed"); b3.put("images", Arrays.asList("https://evil.example/a.png"));
        assertEquals(422, code(() -> service.merchantSubmit(c, b3)), "只收本平台图片");
        Map<String, Object> b4 = form(); b4.put("requestType", "managed"); b4.put("contactName", "");
        assertEquals(422, code(() -> service.merchantSubmit(c, b4)));
        verify(db, never()).insert(eq("kg_merchant_request"), anyMap());
    }

    @Test
    void onlyAReturnedRequestCanBeResubmitted()
    {
        StaffSupport.Ctx c = ctx(MERCHANT_STORE, MERCHANT, StaffRole.MERCHANT);
        onRequest(request("managed", KgMerchantRequestService.PENDING_VERIFY, "大理"));
        assertEquals(409, code(() -> service.merchantResubmit(c, 500L, form())));

        onRequest(request("managed", KgMerchantRequestService.RETURNED, "大理"));
        assertEquals(KgMerchantRequestService.PENDING_VERIFY, service.merchantResubmit(c, 500L, form()).get("requestStatus"));
        verify(db).update(contains("update kg_merchant_request set status"), argThat(ps -> "".equals(ps.get("x_return_reason")) && KgMerchantRequestService.RETURNED.equals(ps.get("from"))));
    }

    @Test
    void returnedOnlineTaskIsEditedThroughTheOnlineService()
    {
        StaffSupport.Ctx c = ctx(MERCHANT_STORE, MERCHANT, StaffRole.MERCHANT);
        Map<String, Object> r = request("online_task", KgMerchantRequestService.RETURNED, "大理");
        r.put("jobId", 88L);
        onRequest(r);
        Map<String, Object> b = new HashMap<String, Object>(p("title", "改过的", "deliverables", "一篇文章"));
        service.merchantResubmit(c, 500L, b);
        verify(onlineTasks).updateDraftTask(c, 88L, b);
    }

    @Test
    void merchantViewHidesInternalNotes()
    {
        Map<String, Object> r = request("managed", KgMerchantRequestService.FORWARDED, "大理");
        r.put("verifyNote", "这家店老板不好说话");
        onRequest(r);
        Map<String, Object> d = service.merchantDetail(ctx(MERCHANT_STORE, MERCHANT, StaffRole.MERCHANT), 500L);
        assertFalse(d.containsKey("verifyNote"));
        assertFalse(d.containsKey("serviceUserId"));
        assertEquals("已转商家服务人", d.get("statusLabel"));
    }

    @Test
    void merchantDetailCarriesOwnJobForResubmit()
    {
        Map<String, Object> r = request("online_task", KgMerchantRequestService.RETURNED, "大理");
        r.put("jobId", 88L);
        onRequest(r);
        when(db.one(contains("from kg_job j left join kg_online_task"), anyMap())).thenReturn(p("jobStatus", "draft", "deliverables", "推文一篇", "commissionFen", 5000L));
        Map<String, Object> d = service.merchantDetail(ctx(MERCHANT_STORE, MERCHANT, StaffRole.MERCHANT), 500L);
        assertEquals(true, d.get("canEdit"));
        @SuppressWarnings("unchecked")
        Map<String, Object> job = (Map<String, Object>) d.get("job");
        assertEquals("推文一篇", job.get("deliverables"));
        verify(db).one(contains("from kg_job j left join kg_online_task"), argThat(ps -> Long.valueOf(88L).equals(ps.get("j"))));
    }

    // ------------------------------------------------------------------ 工作台

    @Test
    void hubCountsAreByCityAndRole()
    {
        when(db.count(contains("from kg_merchant_request"), anyMap())).thenReturn(3L);
        Map<String, Object> m = service.hubCounts(ctx(DALI_STORE, DALI_MANAGER, StaffRole.MANAGER));
        assertEquals(3L, m.get("intakePending"));
        assertEquals(3L, m.get("merchantJobsPending"));
        verify(db, atLeastOnce()).count(contains("city = :c"), argThat(ps -> "大理".equals(ps.get("c"))));
        assertEquals(3L, service.hubCounts(ctx(DALI_STORE, DALI_SERVICE, StaffRole.MERCHANT_SERVICE)).get("servicePending"));
        assertEquals(3L, service.hubCounts(ctx(DALI_STORE, DALI_LEAD, StaffRole.CITY_LEAD)).get("cityUnassigned"));
        assertTrue(service.hubCounts(ctx(DALI_STORE, 9L, StaffRole.KEEPER)).isEmpty());
        assertTrue(service.hubCounts(ctx(MERCHANT_STORE, MERCHANT, StaffRole.MERCHANT)).isEmpty());
        assertEquals(0L, service.hubCounts(ctx(40L, DALI_MANAGER, StaffRole.MANAGER)).get("intakePending"), "门店没城市不能数出所有没城市的需求");
    }

    // ------------------------------------------------------------------ 兼任（小院主理人 + 商家服务人，kg_staff_grant.also_service）

    private static final long LUNA = 1005L;

    private static StaffGrantView grantView(long store, String role, boolean also)
    {
        StaffGrantView g = new StaffGrantView();
        g.setStoreId(store);
        g.setRoleCode(role);
        g.setActive(true);
        g.setAlsoService(also);
        return g;
    }

    /** 登录态里只有这一条授权；support.ctx 按它返回小院主理人的 Ctx（和真的一样，角色取自授权） */
    private StaffSupport.Ctx loginKeeper(long uid, long store, boolean also)
    {
        AppLoginUser me = new AppLoginUser();
        me.setUserId(uid);
        me.setStaffGrants(Collections.singletonList(grantView(store, StaffRole.KEEPER, also)));
        AppUserContext.set(me);
        StaffSupport.Ctx c = new StaffSupport.Ctx();
        c.me = me;
        c.storeId = store;
        c.storeName = "门店" + store;
        c.role = StaffRole.KEEPER;
        when(support.ctx(anyLong())).thenReturn(c);
        return c;
    }

    private void onGrantRow(long uid, Map<String, Object> row)
    {
        when(db.one(contains("from kg_staff_grant where app_user_id"), argThat(m -> m != null && Long.valueOf(uid).equals(m.get("u"))))).thenReturn(row);
    }

    @Test
    void doubleRolePassesServiceCtxOnANewCtxAndTheCachedOneStaysKeeper()
    {
        StaffSupport.Ctx cached = loginKeeper(LUNA, DALI_STORE, true);
        StaffSupport.Ctx c = service.serviceCtx(DALI_STORE, StaffPerms.EDIT);
        assertEquals(StaffRole.MERCHANT_SERVICE, c.role);
        assertEquals(DALI_STORE, c.storeId);
        assertSame(cached.me, c.me);
        assertNotSame(cached, c, "别改缓存对象");
        assertEquals(StaffRole.KEEPER, cached.role);
        // 权限矩阵按商家服务人判：矩阵本身不动，小院主理人那一行 intake 还是没有
        assertTrue(StaffPerms.allows(c.role, "intake", StaffPerms.EDIT));
        assertFalse(StaffPerms.allows(cached.role, "intake", StaffPerms.VIEW));
        verify(support).need(argThat(x -> StaffRole.MERCHANT_SERVICE.equals(x.role)), eq("intake"), eq(StaffPerms.EDIT), anyString(), anyString());
        // 不传门店时找到他兼任的这家
        assertEquals(DALI_STORE, service.serviceCtx(null, StaffPerms.VIEW).storeId);
        assertEquals(StaffRole.MERCHANT_SERVICE, service.serviceCtx(null, StaffPerms.VIEW).role);
    }

    @Test
    void plainKeeperAndBadDataStayForbidden()
    {
        loginKeeper(LUNA, DALI_STORE, false);
        assertEquals(403, code(() -> service.serviceCtx(DALI_STORE, StaffPerms.VIEW)));
        assertEquals(403, code(() -> service.serviceCtx(null, StaffPerms.VIEW)));

        // 开关只对小院主理人有效：别的角色上就算脏数据是 true 也不算
        for (String role : Arrays.asList(StaffRole.STAFF, StaffRole.MANAGER, StaffRole.ONLINE_STAFF, StaffRole.MERCHANT))
        {
            StaffSupport.Ctx c = loginKeeper(LUNA, DALI_STORE, true);
            c.role = role;
            c.me.setStaffGrants(Collections.singletonList(grantView(DALI_STORE, role, true)));
            assertEquals(403, code(() -> service.serviceCtx(DALI_STORE, StaffPerms.VIEW)), role);
            assertFalse(KgMerchantRequestService.alsoService(c), role);
        }

        // 只在 A 店开了，人在 B 店：不算
        StaffSupport.Ctx atB = loginKeeper(LUNA, DALI_STORE, false);
        atB.me.setStaffGrants(Arrays.asList(grantView(DALI_STORE, StaffRole.KEEPER, false), grantView(MERCHANT_STORE, StaffRole.KEEPER, true)));
        assertEquals(403, code(() -> service.serviceCtx(DALI_STORE, StaffPerms.VIEW)));
    }

    @Test
    void localServiceStaffIncludeDoubleRoleButOtherRolesQueriesAreUnchanged()
    {
        service.intakeOptions(ctx(DALI_STORE, DALI_MANAGER, StaffRole.MANAGER));
        verify(db).list(contains("(g.role_code = :r or (g.role_code = 'keeper' and g.also_service = '1'))"), argThat(m -> StaffRole.MERCHANT_SERVICE.equals(m.get("r"))));

        service.dispatchJob(ctx(MERCHANT_STORE, MERCHANT, StaffRole.MERCHANT), 77L, "volunteer", "招换工", "内容");
        verify(db, atLeastOnce()).list(argThat(sql -> sql != null && sql.contains("g.role_code = :r") && !sql.contains("also_service")), argThat(m -> StaffRole.MANAGER.equals(m.get("r"))));
        verify(db, atLeastOnce()).list(argThat(sql -> sql != null && sql.contains("g.role_code = :r") && !sql.contains("also_service")), argThat(m -> StaffRole.CITY_LEAD.equals(m.get("r"))));
    }

    @Test
    void doubleRoleAtTheMerchantStoreIsNotBlockedAndNoSecondGrantIsInserted()
    {
        onGrantRow(LUNA, p("status", "0", "delFlag", "0", "roleCode", StaffRole.KEEPER, "alsoService", "1"));
        Map<String, Object> r = request("online_task", KgMerchantRequestService.FORWARDED, "大理");
        onRequest(r);
        StaffSupport.Ctx me = ctx(DALI_STORE, LUNA, StaffRole.MERCHANT_SERVICE);
        assertNotNull(service.serviceDetail(me, 500L));
        assertEquals(KgMerchantRequestService.ACCEPTED, service.serviceAccept(me, 500L).get("requestStatus"));
        verify(db, never()).insert(eq("kg_staff_grant"), anyMap());
        verify(tokens, never()).refreshGrants(anyLong(), any());
    }

    @Test
    void doubleRoleWithoutARowAtTheMerchantStoreGetsTheUsualServiceGrant()
    {
        Map<String, Object> r = request("online_task", KgMerchantRequestService.FORWARDED, "大理");
        onRequest(r);
        service.serviceAccept(ctx(DALI_STORE, LUNA, StaffRole.MERCHANT_SERVICE), 500L);
        verify(db).insert(eq("kg_staff_grant"), argThat(g -> Long.valueOf(LUNA).equals(g.get("app_user_id")) && StaffRole.MERCHANT_SERVICE.equals(g.get("role_code")) && Long.valueOf(MERCHANT_STORE).equals(g.get("store_id"))));
    }

    @Test
    void plainOrRevokedKeeperAtTheMerchantStoreIsStillBlocked()
    {
        Map<String, Object> r = request("online_task", KgMerchantRequestService.FORWARDED, "大理");
        onRequest(r);
        StaffSupport.Ctx me = ctx(DALI_STORE, LUNA, StaffRole.MERCHANT_SERVICE);
        onGrantRow(LUNA, p("status", "0", "delFlag", "0", "roleCode", StaffRole.KEEPER, "alsoService", "0"));
        assertEquals(403, code(() -> service.serviceAccept(me, 500L)), "没开兼任：这家店另有角色");
        onGrantRow(LUNA, p("status", "1", "delFlag", "0", "roleCode", StaffRole.KEEPER, "alsoService", "1"));
        assertEquals(403, code(() -> service.serviceAccept(me, 500L)), "撤销过的不算");
        onGrantRow(LUNA, p("status", "0", "delFlag", "2", "roleCode", StaffRole.KEEPER, "alsoService", "1"));
        assertEquals(403, code(() -> service.serviceAccept(me, 500L)), "删除过的不算");
        verify(db, never()).insert(eq("kg_staff_grant"), anyMap());
    }

    @Test
    void forwardCanTargetADoubleRoleWhoIsKeeperAtTheMerchantStore()
    {
        staff(StaffRole.MERCHANT_SERVICE, row(LUNA, DALI_STORE, "大理"));
        onGrantRow(LUNA, p("status", "0", "delFlag", "0", "roleCode", StaffRole.KEEPER, "alsoService", "1"));
        onRequest(request("online_task", KgMerchantRequestService.PENDING_VERIFY, "大理"));
        Map<String, Object> out = service.intakeForward(ctx(DALI_STORE, DALI_MANAGER, StaffRole.MANAGER), 500L, LUNA, null);
        assertEquals(KgMerchantRequestService.FORWARDED, out.get("requestStatus"));
        verify(db, never()).insert(eq("kg_staff_grant"), anyMap());
        verify(support).notifyStaff(eq(LUNA), anyString(), anyString(), anyString(), eq("merchant_request"), eq(500L), anyString());
    }

    @Test
    void serviceListAndBadgeTreatDoubleRoleAsNotBlocked()
    {
        service.serviceList(ctx(DALI_STORE, LUNA, StaffRole.MERCHANT_SERVICE), null, null, 1, 10);
        verify(db).page(contains("bg.role_code = 'merchant_service' or (bg.role_code = 'keeper' and bg.also_service = '1')"), anyMap(), any(), any());

        when(db.count(contains("from kg_merchant_request"), anyMap())).thenReturn(2L);
        StaffSupport.Ctx dual = loginKeeper(LUNA, DALI_STORE, true);
        assertEquals(2L, service.hubCounts(dual).get("servicePending"));
        verify(db).count(contains("bg.also_service = '1'"), argThat(m -> Long.valueOf(LUNA).equals(m.get("me")) && "大理".equals(m.get("c"))));
        assertFalse(service.hubCounts(dual).containsKey("intakePending"));

        StaffSupport.Ctx plain = loginKeeper(LUNA, DALI_STORE, false);
        assertTrue(service.hubCounts(plain).isEmpty(), "普通小院主理人不多查一次库");
    }

    /** 主人 2026-11-03：工作台「线上任务管理」按列表上的 mine 决定哪条给「管理任务」；只有商家服务人的列表带，别的列表不变 */
    @Test
    void serviceListRowsSayWhetherIAcceptedThem()
    {
        List<Object> rows = new ArrayList<Object>();
        Map<String, Object> mine = request("online_task", KgMerchantRequestService.ACCEPTED, "大理");
        mine.put("serviceUserId", DALI_SERVICE);
        Map<String, Object> other = request("online_task", KgMerchantRequestService.ACCEPTED, "大理");
        other.put("serviceUserId", 9999L);
        Map<String, Object> waiting = request("managed", KgMerchantRequestService.FORWARDED, "大理");
        rows.add(mine);
        rows.add(other);
        rows.add(waiting);
        when(db.page(anyString(), anyMap(), any(), any())).thenReturn(new HashMap<String, Object>(p("rows", rows, "total", 3L)));
        Map<String, Object> page = service.serviceList(ctx(DALI_STORE, DALI_SERVICE, StaffRole.MERCHANT_SERVICE), null, "online_task", 1, 10);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> out = (List<Map<String, Object>>) page.get("rows");
        assertEquals(Arrays.asList(true, false, false), Arrays.asList(out.get(0).get("mine"), out.get(1).get("mine"), out.get(2).get("mine")));
        verify(db).page(contains("r.request_type = :t"), argThat(m -> "online_task".equals(m.get("t"))), any(), any());

        when(db.page(anyString(), anyMap(), any(), any())).thenReturn(new HashMap<String, Object>(p("rows", new ArrayList<Object>(Arrays.asList(request("online_task", KgMerchantRequestService.PENDING_VERIFY, "大理"))), "total", 1L)));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> intake = (List<Map<String, Object>>) service.intakeList(ctx(DALI_STORE, DALI_MANAGER, StaffRole.MANAGER), null, "online_task", 1, 10).get("rows");
        assertFalse(intake.get(0).containsKey("mine"), "换工主理人的列表不带");
    }

    // ------------------------------------------------------------------ 老板

    @Test
    void redispatchFollowsTheNormalPathWhenSomeoneIsThere()
    {
        Map<String, Object> job = request("volunteer", KgMerchantRequestService.UNASSIGNED, "大理");
        onRequest(job);
        assertEquals(KgMerchantRequestService.DISPATCHED, service.redispatch(500L, "admin").get("requestStatus"));
        verify(db).insert(eq("kg_staff_grant"), argThat(g -> Long.valueOf(DALI_MANAGER).equals(g.get("app_user_id"))));
        verify(support).notifyStaff(eq(DALI_MANAGER), anyString(), anyString(), anyString(), eq("merchant_request"), eq(500L), anyString());
    }

    @Test
    void redispatchOfAVerifiedRequestGoesToService()
    {
        Map<String, Object> r = request("online_task", KgMerchantRequestService.UNASSIGNED, "大理");
        r.put("verifierUserId", DALI_MANAGER);
        onRequest(r);
        assertEquals(KgMerchantRequestService.FORWARDED, service.redispatch(500L, "admin").get("requestStatus"));
        verify(support).notifyStaff(eq(DALI_SERVICE), anyString(), anyString(), anyString(), eq("merchant_request"), eq(500L), anyString());
    }

    @Test
    void redispatchOfAnUnverifiedRequestGoesToVerify()
    {
        onRequest(request("managed", KgMerchantRequestService.UNASSIGNED, "大理"));
        assertEquals(KgMerchantRequestService.PENDING_VERIFY, service.redispatch(500L, "admin").get("requestStatus"));
    }

    @Test
    void redispatchStillWithNobodySays409AndChangesNothing()
    {
        staff(StaffRole.MANAGER);
        onRequest(request("volunteer", KgMerchantRequestService.UNASSIGNED, "大理"));
        assertEquals(409, code(() -> service.redispatch(500L, "admin")));
        verify(db, never()).update(anyString(), anyMap());
    }

    @Test
    void redispatchIsRefusedOnceTheRequestIsOver()
    {
        onRequest(request("volunteer", KgMerchantRequestService.PUBLISHED, "大理"));
        assertEquals(409, code(() -> service.redispatch(500L, "admin")));
    }

    // ------------------------------------------------------------------ 同一个人既是商家又是换工主理人（2026-10-31 主人反馈）：不派给自己

    @Test
    void merchantWhoIsTheOnlyLocalManagerIsToldThereIsNoOtherManager()
    {
        staff(StaffRole.MANAGER, row(MERCHANT, DALI_STORE, "大理"));
        Map<String, Object> out = service.dispatchJob(ctx(MERCHANT_STORE, MERCHANT, StaffRole.MERCHANT), 77L, "volunteer", "招换工", "内容");
        assertEquals(KgMerchantRequestService.UNASSIGNED, out.get("requestStatus"));
        assertEquals("已提交，本城没有其他换工主理人，平台会尽快安排", out.get("message"), "他自己就是换工主理人，不能说本城没有");
        verify(support).notifyStaff(eq(DALI_LEAD), anyString(), anyString(), contains("本城没有其他换工主理人"), anyString(), anyLong(), anyString());
        verify(support, never()).notifyStaff(eq(MERCHANT), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void merchantWhoIsAlsoAManagerGoesToTheOtherManagerOnly()
    {
        staff(StaffRole.MANAGER, row(MERCHANT, DALI_STORE, "大理"), row(DALI_MANAGER, DALI_STORE, "大理"));
        Map<String, Object> out = service.dispatchJob(ctx(MERCHANT_STORE, MERCHANT, StaffRole.MERCHANT), 77L, "volunteer", "招换工", "内容");
        assertEquals(KgMerchantRequestService.DISPATCHED, out.get("requestStatus"));
        verify(support).notifyStaff(eq(DALI_MANAGER), anyString(), anyString(), anyString(), eq("merchant_request"), eq(500L), anyString());
        verify(support, never()).notifyStaff(eq(MERCHANT), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void ordinaryCityWithoutAManagerKeepsTheOldWords()
    {
        staff(StaffRole.MANAGER, row(LIJIANG_MANAGER, LIJIANG_STORE, "丽江"));
        Map<String, Object> out = service.dispatchJob(ctx(MERCHANT_STORE, MERCHANT, StaffRole.MERCHANT), 77L, "volunteer", "招换工", "内容");
        assertEquals("已提交，本城暂时没有换工主理人，平台会尽快安排", out.get("message"));
        verify(support).notifyStaff(eq(DALI_LEAD), anyString(), anyString(), contains("本城还没有换工主理人"), anyString(), anyLong(), anyString());
    }

    @Test
    void intakeSubmissionFromTheOnlyManagerSaysTheSame()
    {
        staff(StaffRole.MANAGER, row(MERCHANT, DALI_STORE, "大理"));
        Map<String, Object> b = form();
        b.put("requestType", "cloud_chain");
        Map<String, Object> out = service.merchantSubmit(ctx(MERCHANT_STORE, MERCHANT, StaffRole.MERCHANT), b);
        assertEquals(KgMerchantRequestService.UNASSIGNED, out.get("requestStatus"));
        assertEquals("已提交，本城没有其他换工主理人，平台会尽快安排", out.get("message"));
        assertTrue(String.valueOf(insertedRequest().get("title")).startsWith("抖音卖房 · "));
    }

    @Test
    void redispatchSaysItCannotGoToTheMerchantHimself()
    {
        staff(StaffRole.MANAGER, row(MERCHANT, DALI_STORE, "大理"));
        onRequest(request("volunteer", KgMerchantRequestService.UNASSIGNED, "大理"));
        ServiceException e = assertThrows(ServiceException.class, () -> service.redispatch(500L, "admin"));
        assertEquals(409, e.getCode());
        assertTrue(e.getMessage().contains("商家本人不能派给自己"), e.getMessage());
        staff(StaffRole.MANAGER);
        e = assertThrows(ServiceException.class, () -> service.redispatch(500L, "admin"));
        assertFalse(e.getMessage().contains("商家本人"), e.getMessage());
    }

    @Test
    void offRoutingSaysNothingAboutManagersAtAll()
    {
        RolePhase.set(RolePhase.LEGACY);
        staff(StaffRole.MANAGER, row(MERCHANT, DALI_STORE, "大理"), row(DALI_MANAGER, DALI_STORE, "大理"));
        Map<String, Object> out = service.dispatchJob(ctx(MERCHANT_STORE, MERCHANT, StaffRole.MERCHANT), 77L, "volunteer", "招换工", "内容");
        assertEquals(KgMerchantRequestService.UNASSIGNED, out.get("requestStatus"), "老规则阶段一律等平台");
        assertEquals("已提交，平台会尽快安排", out.get("message"));
        verify(support, never()).notifyStaff(eq(DALI_MANAGER), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
        verify(db, never()).insert(eq("kg_staff_grant"), anyMap());
    }

    // ------------------------------------------------------------------ 完善页回填（V66）

    @Test
    @SuppressWarnings("unchecked")
    void dispatchedJobCarriesTheNewFieldsAsStored()
    {
        onRequest(request("volunteer", KgMerchantRequestService.DISPATCHED, "大理"));
        Map<String, Object> job = new HashMap<String, Object>(p("jobId", 77L, "jobType", "volunteer", "boardType", "all", "images", "", "mealAllowance", "15", "mealAllowanceUnit", "day",
                "petStatus", "yes", "petImages", "[\"/profile/upload/a.jpg\"]", "lodgingImages", null, "lodgingVideo", null, "lodgingVideoCover", null));
        when(db.must(contains("from kg_job where job_id"), anyMap(), anyString())).thenReturn(job);
        Map<String, Object> out = service.dispatchedJob(ctx(MERCHANT_STORE, DALI_MANAGER, StaffRole.MANAGER), 77L);
        Map<String, Object> j = (Map<String, Object>) out.get("job");
        assertEquals("15", j.get("mealAllowance"), "完善页给原值，包吃包住也不收口");
        assertEquals(Arrays.asList("/profile/upload/a.jpg"), j.get("petImages"));
        assertEquals(new ArrayList<String>(), j.get("lodgingImages"));
        assertEquals("", j.get("lodgingVideo"));
        assertEquals("volunteer", j.get("jobType"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db).must(sql.capture(), anyMap(), anyString());
        for (String col : Arrays.asList("meal_allowance", "meal_allowance_unit", "pet_status", "pet_images", "lodging_images", "lodging_video", "lodging_video_cover"))
        {
            assertTrue(sql.getValue().contains(col), col);
        }
    }

    @Test
    void everyStatusHasAChineseLabel()
    {
        for (String s : Arrays.asList("dispatched", "published", "closed", "unassigned", "pending_verify", "forwarded", "accepted", "done", "returned"))
        {
            assertFalse(KgMerchantRequestService.statusLabel(s).matches(".*[a-zA-Z].*"), s);
        }
    }

    // ------------------------------------------------------------------ 城市负责人全部权限（2026-11-01）：顶上

    @Test
    void leadStandsInForManagerAndService()
    {
        login(DALI_LEAD, StaffRole.CITY_LEAD, DALI_STORE);
        currentUser = DALI_LEAD;
        currentRole = StaffRole.CITY_LEAD;
        StaffSupport.Ctx m = service.managerCtx(DALI_STORE, StaffPerms.AUDIT);
        assertEquals(StaffRole.MANAGER, m.role);
        assertTrue(m.cityLeadActing(), "操作日志要记城市负责人");
        StaffSupport.Ctx s = service.serviceCtx(DALI_STORE, StaffPerms.EDIT);
        assertEquals(StaffRole.MERCHANT_SERVICE, s.role);
        assertTrue(s.cityLeadActing());
    }

    @Test
    void leadNeverStandsInBeforeTheSwitch()
    {
        RolePhase.set(RolePhase.LEGACY);
        login(DALI_LEAD, StaffRole.CITY_LEAD, DALI_STORE);
        currentUser = DALI_LEAD;
        currentRole = StaffRole.CITY_LEAD;
        assertEquals(403, code(() -> service.managerCtx(DALI_STORE, StaffPerms.VIEW)));
        assertEquals(403, code(() -> service.serviceCtx(DALI_STORE, StaffPerms.VIEW)));
    }

    @Test
    void leadCannotHandleTheRequestHeSubmittedHimself()
    {
        Map<String, Object> mine = request("online_task", KgMerchantRequestService.PENDING_VERIFY, "大理");
        mine.put("merchantUserId", DALI_LEAD);
        onRequest(mine);
        login(DALI_LEAD, StaffRole.CITY_LEAD, DALI_STORE);
        currentUser = DALI_LEAD;
        currentRole = StaffRole.CITY_LEAD;
        StaffSupport.Ctx acting = service.managerCtx(DALI_STORE, StaffPerms.VIEW);
        assertEquals(403, code(() -> service.intakeDetail(acting, 500L)), "自己提交的需求顶上也不能处理");
    }

    @Test
    void leadInAnotherCityCannotSeeThisCitysRequest()
    {
        onRequest(request("online_task", KgMerchantRequestService.PENDING_VERIFY, "大理"));
        StaffSupport.Ctx acting = ctx(LIJIANG_STORE, DALI_LEAD, StaffRole.MANAGER);
        acting.actingFrom = StaffRole.CITY_LEAD;
        assertThrows(ServiceException.class, () -> service.intakeDetail(acting, 500L));
    }

    @Test
    void standingInNeverCreatesAGrant()
    {
        when(db.one(contains("select store_id from kg_job"), anyMap())).thenReturn(p("storeId", MERCHANT_STORE));
        currentUser = DALI_LEAD;
        currentRole = StaffRole.CITY_LEAD;
        assertEquals(StaffRole.MANAGER, service.jobManagerCtx(77L, StaffPerms.VIEW, "x", "y").role);
        verify(db, never()).insert(eq("kg_staff_grant"), anyMap());
    }
}
