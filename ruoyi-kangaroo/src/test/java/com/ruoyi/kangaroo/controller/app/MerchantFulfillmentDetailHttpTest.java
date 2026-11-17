package com.ruoyi.kangaroo.controller.app;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.ruoyi.framework.web.exception.GlobalExceptionHandler;
import com.ruoyi.kangaroo.domain.KgApplication;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.KgTalentService;
import com.ruoyi.kangaroo.ext.staff.RolePhase;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.ext.user.KgPointsService;
import com.ruoyi.kangaroo.mapper.KgApplicationMapper;
import com.ruoyi.kangaroo.security.*;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;
import com.ruoyi.kangaroo.service.impl.KgApplicationServiceImpl;

/** Actual HTTP handler + interceptor + ownership service; no network/database/business writes. */
class MerchantFulfillmentDetailHttpTest {
    private final KgJdbc db = mock(KgJdbc.class);
    private final AppLoginUser user = new AppLoginUser();
    private final KgApplication app = new KgApplication();
    private final KgTalentService talents = mock(KgTalentService.class);
    private final KgPointsService points = mock(KgPointsService.class);
    private MockMvc mvc;

    @BeforeEach void setup() {
        RolePhase.set(RolePhase.NEW);
        user.setUserId(700L); grant(20L,"merchant");
        app.setAppId(90L); app.setStoreId(10L); app.setJobId(91L); app.setAppUserId(800L);
        app.setJobType("volunteer"); app.setStatus("onboarded"); app.setRealName("接待候选人"); app.setPhone("13900000000");
        KgApplicationMapper mapper = mock(KgApplicationMapper.class);
        when(mapper.selectKgApplicationByAppId(90L)).thenReturn(app);
        IKgStaffGrantService grants = mock(IKgStaffGrantService.class);
        when(grants.loadActiveGrantViews(700L)).thenAnswer(i -> user.getStaffGrants());
        KgApplicationServiceImpl applications = new KgApplicationServiceImpl();
        ReflectionTestUtils.setField(applications,"kgApplicationMapper",mapper);
        ReflectionTestUtils.setField(applications,"kgStaffGrantService",grants);
        StaffSupport support = new StaffSupport();
        ReflectionTestUtils.setField(support,"db",db);
        AppStaffController controller = new AppStaffController();
        ReflectionTestUtils.setField(controller,"kgApplicationService",applications);
        ReflectionTestUtils.setField(controller,"staffSupport",support);
        ReflectionTestUtils.setField(controller,"db",db);
        ReflectionTestUtils.setField(controller,"talents",talents);
        ReflectionTestUtils.setField(controller,"points",points);
        AppTokenService tokens = mock(AppTokenService.class);
        when(tokens.getLoginUser(any())).thenReturn(user);
        AppAuthInterceptor interceptor = new AppAuthInterceptor();
        ReflectionTestUtils.setField(interceptor,"appTokenService",tokens);
        ReflectionTestUtils.setField(interceptor,"identities",mock(AppIdentityLoader.class));
        mvc = MockMvcBuilders.standaloneSetup(controller).addInterceptors(interceptor)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(db.one(contains("select recruit_store_id from kg_job"),anyMap())).thenReturn(p("recruitStoreId",20L));
        when(db.one(contains("from kg_app_arrival"),anyMap())).thenReturn(p("arriveDate","2026-11-12","note","已确认到店"));
    }

    private void grant(long store,String role) {
        StaffGrantView g = new StaffGrantView(); g.setStoreId(store); g.setRoleCode(role); g.setActive(true);
        user.setStaffGrants(Arrays.asList(g));
    }

    @AfterEach void clear() { AppUserContext.remove(); RolePhase.set(RolePhase.LEGACY); }

    @Test void actualStoreMerchantCanReadFulfillmentWithoutRecruitmentPowers() throws Exception {
        mvc.perform(get("/app/staff/applications/90")).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.storeId").value(10))
                .andExpect(jsonPath("$.data.fulfillmentStoreId").value(20))
                .andExpect(jsonPath("$.data.arrival.note").value("已确认到店"))
                .andExpect(jsonPath("$.data.canViewRecruitmentDetails").value(false))
                .andExpect(jsonPath("$.data.canManage").value(false))
                .andExpect(jsonPath("$.data.canAudit").value(false))
                .andExpect(jsonPath("$.data.canForward").value(false))
                .andExpect(jsonPath("$.data.canTag").value(false))
                .andExpect(jsonPath("$.data.canReviewVolunteerContract").value(false));
        verifyNoInteractions(talents);
        verify(points).staffExtras(90L,"volunteer",false);
        assertNull(AppUserContext.get());
    }

    @Test void actualStoreCannotModifyManagementStoreTags() throws Exception {
        mvc.perform(post("/app/staff/applications/90/tags").contentType(MediaType.APPLICATION_JSON)
                .content("{\"tag\":\"should-not-save\",\"action\":\"add\"}"))
                .andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(talents);
        verify(db,never()).insert(anyString(),anyMap());
        verify(db,never()).update(anyString(),anyMap());
    }

    @Test void unrelatedStoreAndDelegatedOnlineApplicationAreDenied() throws Exception {
        grant(30L,"merchant");
        mvc.perform(get("/app/staff/applications/90")).andExpect(jsonPath("$.code").value(403));
        grant(20L,"merchant"); app.setJobType("online");
        mvc.perform(get("/app/staff/applications/90")).andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(points,talents);
    }

    @Test void managementStoreRetainsExistingMerchantCapabilities() throws Exception {
        grant(10L,"merchant");
        mvc.perform(get("/app/staff/applications/90")).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.canViewRecruitmentDetails").value(true))
                .andExpect(jsonPath("$.data.canManage").value(true))
                .andExpect(jsonPath("$.data.canForward").value(true))
                .andExpect(jsonPath("$.data.canReviewVolunteerContract").value(true))
                .andExpect(jsonPath("$.data.canAudit").value(false));
        verify(talents).storeTags(10L,800L);
        verify(points).staffExtras(90L,"volunteer",true);
    }

    @Test void unDelegatedApplicationCannotUseOtherStoreFallback() throws Exception {
        when(db.one(contains("select recruit_store_id from kg_job"),anyMap())).thenReturn(p("recruitStoreId",null));
        mvc.perform(get("/app/staff/applications/90")).andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(points,talents);
    }

    @Test void ownApplicationStillHasNoRecruitmentOrContractApproval() throws Exception {
        grant(10L,"merchant"); app.setAppUserId(user.getUserId());
        mvc.perform(get("/app/staff/applications/90")).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.canManage").value(false))
                .andExpect(jsonPath("$.data.canAudit").value(false))
                .andExpect(jsonPath("$.data.canTag").value(false))
                .andExpect(jsonPath("$.data.canReviewVolunteerContract").value(false));
    }
}
