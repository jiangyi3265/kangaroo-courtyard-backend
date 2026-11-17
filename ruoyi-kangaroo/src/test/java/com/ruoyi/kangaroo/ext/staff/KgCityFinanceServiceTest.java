package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.controller.app.AppStaffCityFinanceController;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgActivityService;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.security.StaffGrantView;

/** 城市负责人的财务：只在切换后、只限有授权的门店；别的角色、别家门店都不行。 */
class KgCityFinanceServiceTest
{
    private static final long LEAD_STORE = 10L;
    private static final long OTHER_STORE = 20L;
    private final KgJdbc db = mock(KgJdbc.class);
    private final KgActivityService activities = mock(KgActivityService.class);
    private final KgMerchantRequestService requests = mock(KgMerchantRequestService.class);
    private final StaffSupport support = new StaffSupport();
    private final KgCityFinanceService finance = new KgCityFinanceService();

    @BeforeEach
    void setup()
    {
        RolePhase.set(RolePhase.NEW);
        ReflectionTestUtils.setField(support, "db", db);
        ReflectionTestUtils.setField(finance, "db", db);
        ReflectionTestUtils.setField(finance, "support", support);
        ReflectionTestUtils.setField(finance, "activities", activities);
        ReflectionTestUtils.setField(finance, "requests", requests);
        when(db.one(anyString(), anyMap())).thenReturn(null);
        when(activities.approveRefund(anyLong(), anyString())).thenReturn("已同意");
        when(activities.rejectRefund(anyLong(), anyString(), anyString())).thenReturn("已驳回");
        when(activities.transition(anyMap(), anyString(), anyString(), anyString(), anyBoolean())).thenReturn("已取消");
    }

    @AfterEach
    void clear()
    {
        RolePhase.set(RolePhase.LEGACY);
        AppUserContext.remove();
    }

    private void login(long uid, String role)
    {
        AppLoginUser u = new AppLoginUser();
        u.setUserId(uid);
        u.setNickname("甲");
        StaffGrantView lead = new StaffGrantView();
        lead.setStoreId(LEAD_STORE);
        lead.setStoreName("授权店");
        lead.setRoleCode(role);
        lead.setActive(true);
        StaffGrantView other = new StaffGrantView();
        other.setStoreId(OTHER_STORE);
        other.setStoreName("别的店");
        other.setRoleCode(StaffRole.KEEPER);
        other.setActive(true);
        u.setStaffGrants(new ArrayList<StaffGrantView>(Arrays.asList(lead, other)));
        AppUserContext.set(u);
    }

    private void activityAt(long store)
    {
        when(db.one(contains("select store_id from kg_activity"), anyMap())).thenReturn(p("storeId", store));
    }

    private void signup(long owner)
    {
        when(db.one(contains("from kg_activity_signup"), anyMap())).thenReturn(p("activityId", 5L, "appUserId", owner));
    }

    private static int code(Runnable r)
    {
        return assertThrows(ServiceException.class, r::run).getCode();
    }

    @Test
    void refusedBeforeTheSwitchEvenForAnExistingCityLead()
    {
        RolePhase.set(RolePhase.LEGACY);
        login(1L, StaffRole.CITY_LEAD);
        activityAt(LEAD_STORE);
        signup(2L);
        assertEquals(403, code(() -> finance.refunds(null, 1, 10)));
        assertEquals(403, code(() -> finance.cancellable(null, 1, 10)));
        assertEquals(403, code(() -> finance.ctxOfSignup(9L, "x", "x")));
        assertEquals(403, code(() -> finance.ctxOfActivity(5L, "x", "x")));
        assertEquals(403, code(() -> finance.summary(null)));
        verifyNoInteractions(activities);
    }

    @Test
    void onlyCityLeadOfThatStore()
    {
        login(1L, StaffRole.CITY_LEAD);
        activityAt(OTHER_STORE);
        signup(2L);
        assertEquals(403, code(() -> finance.ctxOfSignup(9L, "x", "x")), "别家门店是小院主理人，借不了");
        activityAt(LEAD_STORE);
        assertEquals(StaffRole.CITY_LEAD, finance.ctxOfSignup(9L, "x", "x").role);
        for (String role : Arrays.asList(StaffRole.MANAGER, StaffRole.KEEPER, StaffRole.MERCHANT))
        {
            login(1L, role);
            assertEquals(403, code(() -> finance.ctxOfSignup(9L, "x", "x")), role);
            assertEquals(403, code(() -> finance.ctxOfActivity(5L, "x", "x")), role);
            assertEquals(403, code(() -> finance.refunds(null, 1, 10)), role);
        }
    }

    @Test
    void cannotApproveOwnSignup()
    {
        login(1L, StaffRole.CITY_LEAD);
        activityAt(LEAD_STORE);
        signup(1L);
        assertEquals(403, code(() -> finance.ctxOfSignup(9L, "x", "x")));
    }

    @Test
    void approveRejectGoThroughTheExistingRefundPathAndLeaveALog()
    {
        login(1L, StaffRole.CITY_LEAD);
        activityAt(LEAD_STORE);
        signup(2L);
        StaffSupport.Ctx c = finance.ctxOfSignup(9L, "x", "x");
        assertEquals("已同意", finance.approve(c, 9L));
        verify(activities).approveRefund(9L, "staff:1");
        assertEquals("已驳回", finance.reject(c, 9L, "不符合"));
        verify(activities).rejectRefund(9L, "不符合", "staff:1");
        verify(db, times(2)).insert(eq("kg_staff_op_log"), argThat(m -> "fund".equals(m.get("category")) && StaffRole.CITY_LEAD.equals(m.get("role_code"))));
    }

    @Test
    void cancelOnlyPaidOrSignedActivitiesAndRequiresAReason()
    {
        login(1L, StaffRole.CITY_LEAD);
        activityAt(LEAD_STORE);
        StaffSupport.Ctx c = finance.ctxOfActivity(5L, "x", "x");
        when(db.must(contains("select * from kg_activity"), anyMap(), anyString())).thenReturn(new HashMap<String, Object>(p("activityId", 5L, "storeId", LEAD_STORE, "feeFen", 0L, "signed", 0)));
        assertEquals(409, code(() -> finance.cancel(c, 5L, "原因")), "不收费也没人报名的不归他");
        when(db.must(contains("select * from kg_activity"), anyMap(), anyString())).thenReturn(new HashMap<String, Object>(p("activityId", 5L, "storeId", LEAD_STORE, "feeFen", 100L, "signed", 0)));
        assertEquals(422, code(() -> finance.cancel(c, 5L, " ")));
        assertEquals("已取消", finance.cancel(c, 5L, "下雨"));
        verify(activities, never()).ownStoreActivity(any(), anyLong());
        verify(activities).transition(anyMap(), eq("cancel"), eq("下雨"), eq("staff:1"), eq(true));
    }

    @Test
    @SuppressWarnings("unchecked")
    void refundListIsLimitedToLeadStoresAndMasksContacts()
    {
        login(1L, StaffRole.CITY_LEAD);
        Map<String, Object> page = new HashMap<String, Object>();
        Map<String, Object> row = new HashMap<String, Object>(p("name", "张三丰", "phone", "13812345678", "refundFen", 1050L, "appUserId", 1L));
        page.put("rows", new ArrayList<Map<String, Object>>(Arrays.asList(row)));
        ArgumentCaptor<Map<String, Object>> ps = ArgumentCaptor.forClass(Map.class);
        when(db.page(contains("refund_pending"), ps.capture(), any(), any())).thenReturn(page);
        Map<String, Object> out = finance.refunds(null, 1, 10);
        assertEquals(Arrays.asList(LEAD_STORE), ps.getValue().get("ids"), "别的店不混进来");
        Map<String, Object> r = ((List<Map<String, Object>>) out.get("rows")).get(0);
        assertNotEquals("张三丰", r.get("name"));
        assertNotEquals("13812345678", r.get("phone"));
        assertEquals("10.5", r.get("refundYuan"));
        assertEquals(Boolean.TRUE, r.get("mine"));
        assertFalse(r.containsKey("appUserId"));
        assertEquals(403, code(() -> finance.refunds(OTHER_STORE, 1, 10)), "传了别家门店");
    }

    @Test
    void summaryIsOnlyNumbersForTheNormalisedCity()
    {
        login(1L, StaffRole.CITY_LEAD);
        StaffSupport.Ctx c = support.ctx(LEAD_STORE);
        when(requests.cityCtx(LEAD_STORE)).thenReturn(c);
        when(requests.townOf(c)).thenReturn("大理");
        when(requests.normalCity("大理市")).thenReturn("大理");
        when(requests.normalCity("丽江")).thenReturn("丽江");
        when(db.list(contains("from kg_store"), anyMap())).thenReturn(new ArrayList<Map<String, Object>>(Arrays.asList(p("storeId", 10L, "city", "大理市"), p("storeId", 30L, "city", "丽江"))));
        when(db.count(contains("kg_application"), anyMap())).thenReturn(4L);
        when(db.count(contains("kg_job"), anyMap())).thenReturn(5L);
        when(db.count(contains("kg_stay_order"), anyMap())).thenReturn(6L);
        Map<String, Object> m = finance.summary(LEAD_STORE);
        assertEquals("大理", m.get("city"));
        assertEquals(1, m.get("storeCount"));
        assertEquals(4L, m.get("todayApplications"));
        assertEquals(5L, m.get("openJobs"));
        assertEquals(6L, m.get("inHouseGuests"));
    }

    @Test
    void everyEndpointIsOpenToCityLeadOnlyAfterTheSwitch()
    {
        int seen = 0;
        for (Method m : AppStaffCityFinanceController.class.getDeclaredMethods())
        {
            if (m.getAnnotation(GetMapping.class) == null && m.getAnnotation(PostMapping.class) == null)
            {
                continue;
            }
            seen++;
            AppAuth a = m.getAnnotation(AppAuth.class);
            assertNotNull(a, m.getName());
            assertTrue(a.staff() && a.cityLeadAfterSwitch(), m.getName());
            assertFalse(a.cityLead() || a.merchant() || a.merchantService() || a.onlineStaff(), m.getName());
        }
        assertEquals(6, seen);
    }
}
