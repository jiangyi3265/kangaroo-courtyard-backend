package com.ruoyi.kangaroo.controller.app;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.ruoyi.framework.web.exception.GlobalExceptionHandler;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.KgInsuranceService;
import com.ruoyi.kangaroo.ext.staff.RolePhase;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.security.AppAuthInterceptor;
import com.ruoyi.kangaroo.security.AppIdentityLoader;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppTokenService;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.security.StaffGrantView;

/** HTTP dispatch and the real auth/context/service chain; JDBC and notifications are isolated doubles. */
class MerchantInsuranceHttpTest {
    private static final long MANAGEMENT_STORE = 10L, ACTUAL_STORE = 20L, OTHER_STORE = 30L;
    private final KgJdbc db = mock(KgJdbc.class);
    private final AppTokenService tokens = mock(AppTokenService.class);
    private final AppLoginUser user = new AppLoginUser();
    private MockMvc mvc;
    private Map<String,Object> application;

    @BeforeEach void setup() {
        RolePhase.set(RolePhase.NEW);
        user.setUserId(700L); user.setNickname("测试商家");
        grant(ACTUAL_STORE, "merchant");
        when(tokens.getLoginUser(any())).thenReturn(user);
        StaffSupport support = spy(new StaffSupport());
        ReflectionTestUtils.setField(support, "db", db);
        doNothing().when(support).notifyUser(anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
        doReturn(1).when(support).notifyStore(anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
        doNothing().when(support).op(any(), anyString(), anyString(), anyString(), nullable(String.class), anyBoolean(), nullable(String.class));
        KgInsuranceService insurance = new KgInsuranceService();
        ReflectionTestUtils.setField(insurance, "db", db);
        ReflectionTestUtils.setField(insurance, "support", support);
        AppStaffOpsController controller = new AppStaffOpsController();
        ReflectionTestUtils.setField(controller, "db", db);
        ReflectionTestUtils.setField(controller, "support", support);
        ReflectionTestUtils.setField(controller, "insurance", insurance);
        AppAuthInterceptor interceptor = new AppAuthInterceptor();
        ReflectionTestUtils.setField(interceptor, "appTokenService", tokens);
        ReflectionTestUtils.setField(interceptor, "identities", mock(AppIdentityLoader.class));
        mvc = MockMvcBuilders.standaloneSetup(controller).addInterceptors(interceptor)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        application = new LinkedHashMap<>(p("appId", 90L, "appUserId", 800L, "storeId", ACTUAL_STORE,
                "realName", "候选人", "status", "onboarded", "jobType", "volunteer", "jobTitle", "换工", "age", 22));
        when(db.must(contains("coalesce(j.recruit_store_id,a.store_id) store_id from kg_application"), anyMap(), anyString()))
                .thenAnswer(i -> p("storeId", application.get("storeId")));
        when(db.must(contains("select a.app_id, a.app_user_id"), anyMap(), anyString())).thenAnswer(i -> application);
        when(db.one(contains("select status from kg_application"), anyMap()))
                .thenAnswer(i -> p("status", application.get("status")));
        when(db.must(contains("from kg_insurance_plan"), anyMap(), anyString()))
                .thenReturn(p("planId", 1L, "planName", "保障方案", "days", 30, "price", 3000L));
        when(db.list(anyString(), anyMap())).thenReturn(new ArrayList<>());
        when(db.list(contains("from kg_insurance_plan"), anyMap()))
                .thenAnswer(i -> new ArrayList<>(Arrays.asList(p("planId",1L,"planName","保障方案","days",30,"price",3000L))));
        when(db.insert(eq("kg_insurance_apply"), anyMap())).thenReturn(901L);
    }

    private void grant(long store, String role) {
        StaffGrantView g = new StaffGrantView(); g.setStoreId(store); g.setRoleCode(role); g.setActive(true);
        g.setStoreName("授权门店"); user.setStaffGrants(Arrays.asList(g));
    }

    private String body() {
        return "{\"appId\":90,\"planId\":1,\"startDate\":\"" + StaffSupport.fmt(new Date(), "yyyy-MM-dd") + "\"}";
    }

    @AfterEach void cleanup() { AppUserContext.remove(); RolePhase.set(RolePhase.LEGACY); }

    @Test void actualStoreMerchantCanLoadCandidatePlansAndActualScope() throws Exception {
        mvc.perform(get("/app/staff/insurance/app/90")).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.storeId").value(ACTUAL_STORE))
                .andExpect(jsonPath("$.data.candidate.name").value("候选人"))
                .andExpect(jsonPath("$.data.plans[0].planId").value(1));
        assertNull(AppUserContext.get(), "HTTP completion must clear the scoped identity");
    }

    @Test void merchantCanSubmitSingleApplicationThroughRealHttpAuth() throws Exception {
        mvc.perform(post("/app/staff/insurance/apply").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.applyId").value(901));
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String,Object>> saved = ArgumentCaptor.forClass(Map.class);
        verify(db).insert(eq("kg_insurance_apply"), saved.capture());
        assertEquals(ACTUAL_STORE, saved.getValue().get("store_id"));
        assertEquals("pending", saved.getValue().get("status"));
        assertEquals(700L, saved.getValue().get("applied_by"));
        assertFalse(saved.getValue().containsKey("policy_no"));
    }

    @Test void normalNonDelegatedMerchantApplicationAlsoWorks() throws Exception {
        application.put("storeId", MANAGEMENT_STORE); grant(MANAGEMENT_STORE, "merchant");
        mvc.perform(post("/app/staff/insurance/apply").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(jsonPath("$.code").value(200));
        verify(db).insert(eq("kg_insurance_apply"), argThat(m -> Long.valueOf(MANAGEMENT_STORE).equals(m.get("store_id"))));
    }

    @Test void managementOnlyAndUnrelatedMerchantsCannotBorrowActualStore() throws Exception {
        for (long store : new long[]{MANAGEMENT_STORE, OTHER_STORE}) {
            grant(store, "merchant");
            mvc.perform(get("/app/staff/insurance/app/90")).andExpect(jsonPath("$.code").value(403));
            mvc.perform(post("/app/staff/insurance/apply").contentType(MediaType.APPLICATION_JSON).content(body()))
                    .andExpect(jsonPath("$.code").value(403));
        }
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test void merchantCannotIssuePoliciesEvenWhenStoreMatches() throws Exception {
        mvc.perform(put("/app/staff/insurance/applies/901/policy").contentType(MediaType.APPLICATION_JSON)
                .content("{\"policyNo\":\"cannot-issue\"}")).andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(db);
        assertNull(AppUserContext.get());
    }

    @Test void unsignedAndOnlineOnlyAccountsCannotReachInsurance() throws Exception {
        when(tokens.getLoginUser(any())).thenReturn(null);
        mvc.perform(get("/app/staff/insurance/app/90")).andExpect(jsonPath("$.code").value(401));
        when(tokens.getLoginUser(any())).thenReturn(user); grant(ACTUAL_STORE, "online_staff");
        mvc.perform(post("/app/staff/insurance/apply").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(db);
    }

    @Test void selfInsuranceAndOnlineTaskRemainForbidden() throws Exception {
        application.put("appUserId", user.getUserId());
        mvc.perform(post("/app/staff/insurance/apply").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(jsonPath("$.code").value(403));
        application.put("appUserId", 800L); application.put("jobType", "online");
        mvc.perform(post("/app/staff/insurance/apply").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(jsonPath("$.code").value(409));
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test void overviewAndWaitingListStayScopedToActualStore() throws Exception {
        mvc.perform(get("/app/staff/insurance").param("storeId", "20").param("tab", "toBuy"))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(get("/app/staff/insurance/to-buy").param("storeId", "20"))
                .andExpect(jsonPath("$.code").value(200));
        verify(db).list(contains("s.store_id = coalesce(j.recruit_store_id,a.store_id)"), argThat(m -> Long.valueOf(ACTUAL_STORE).equals(m.get("s"))));
        mvc.perform(get("/app/staff/insurance").param("storeId", "10")).andExpect(jsonPath("$.code").value(403));
        mvc.perform(get("/app/staff/insurance/to-buy").param("storeId", "30")).andExpect(jsonPath("$.code").value(403));
    }
}
