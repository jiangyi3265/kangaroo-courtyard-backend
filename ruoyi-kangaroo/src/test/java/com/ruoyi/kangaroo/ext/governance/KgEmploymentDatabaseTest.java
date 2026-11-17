package com.ruoyi.kangaroo.ext.governance;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.governance.KgGovernanceExecutionTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.KgEmploymentService;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.StaffGrantView;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;

class KgEmploymentDatabaseTest {
    private final KgGovernanceExecutionTest fixture = new KgGovernanceExecutionTest();
    private KgEmploymentService employment;
    private long application;
    private LocalDateTime onboarded;

    @BeforeAll static void initialize() { KgGovernanceExecutionTest.setup(); }

    @BeforeEach void prepare() {
        fixture.reset();
        application = fixture.app(1L);
        onboarded = KgBadgeRules.quarterStart(Governance.now().toLocalDate()).minusMonths(3).atStartOfDay();
        db.update("update kg_application a join kg_job j on j.job_id=a.job_id "
                + "set a.volunteer_done_time=null,a.onboard_time=:t,j.job_type='fulltime' where a.app_id=:a",p("a",application,"t",onboarded));
        fixture.grant(4L,1L,"manager");
        fixture.grant(5L,3L,"manager");
        IKgStaffGrantService grants = mock(IKgStaffGrantService.class);
        when(grants.loadActiveGrantViews(anyLong())).thenAnswer(invocation -> {
            List<StaffGrantView> result = new ArrayList<>();
            for (Map<String,Object> row : db.list("select store_id,role_code from kg_staff_grant where app_user_id=:u and status='0' and del_flag='0'",p("u",invocation.getArgument(0)))) {
                StaffGrantView grant = new StaffGrantView();
                grant.setStoreId(KgJdbc.asLong(row.get("storeId")));
                grant.setRoleCode(String.valueOf(row.get("roleCode")));
                grant.setActive(true);
                result.add(grant);
            }
            return result;
        });
        employment = new KgEmploymentService();
        field(employment,"db",db);
        field(employment,"grants",grants);
    }

    @AfterEach void clean() {
        db.update("delete from kg_idempotency where biz_type='employment'",p());
        db.update("delete from kg_application_log where app_id=:a",p("a",application));
        for (String table : new String[]{"kg_badge_quarter_review","kg_badge_content_check","kg_badge_violation","kg_badge_integrity"})
            db.update("delete from "+table,p());
    }

    AppLoginUser actor(long uid) {
        AppLoginUser user = new AppLoginUser();
        user.setUserId(uid);
        user.setNickname("隔离门店负责人");
        return user;
    }

    Map<String,Object> body(Object departure,Object expected) {
        return p("offboardTime",departure,"expectedOffboardTime",expected,"note","已联系工作人员核验实际在岗情况","requestKey",UUID.randomUUID().toString());
    }

    Map<String,Object> save(long uid,Map<String,Object> body) {
        return in(()->employment.save(actor(uid),application,body));
    }

    @Test void actualRecruitingShopCanDepartCorrectAndRetryWithoutReapplyingAnOldWrite() {
        assertEquals(Boolean.TRUE,employment.detail(actor(2),application).get("canManage"));
        Map<String,Object> departure = body(Governance.now().minusDays(1).withNano(0),null);
        Map<String,Object> departed = save(2,departure);
        assertEquals("offboarded",departed.get("state"));
        save(2,departure);
        assertEquals(1,db.count("select count(*) from kg_application_log where app_id=:a",p("a",application)));
        assertEquals("onboarded",db.one("select status from kg_application where app_id=:a",p("a",application)).get("status"));
        assertNull(db.one("select volunteer_done_time from kg_application where app_id=:a",p("a",application)).get("volunteerDoneTime"));
        assertEquals("active",save(2,body(null,departed.get("offboardTime"))).get("state"));
        assertEquals("active",save(2,departure).get("state"),"旧请求重试不能覆盖后续纠错");
        assertEquals(2,db.count("select count(*) from kg_application_log where app_id=:a",p("a",application)));
    }

    @Test void managementShopMayReadButOnlyActualRecruitingShopMayWrite() {
        assertEquals(Boolean.FALSE,employment.detail(actor(4),application).get("canManage"));
        ServiceException wrongShop = assertThrows(ServiceException.class,()->save(4,body(Governance.now().minusDays(1),null)));
        assertEquals(Integer.valueOf(403),wrongShop.getCode());
        assertThrows(ServiceException.class,()->employment.detail(actor(5),application));
        db.update("update kg_staff_grant set status='1' where app_user_id=2",p());
        assertThrows(ServiceException.class,()->save(2,body(Governance.now().minusDays(1),null)),"即时失效授权不能继续操作");
    }

    @Test void selfServiceAndUnconfirmedOrCompletedEmploymentCannotBecomeActive() {
        fixture.grant(1L,2L,"merchant");
        assertEquals(Boolean.FALSE,employment.detail(actor(1),application).get("canManage"));
        assertThrows(ServiceException.class,()->save(1,body(Governance.now().minusDays(1),null)));
        db.update("update kg_application set onboard_time=null where app_id=:a",p("a",application));
        assertEquals("unconfirmed",employment.detail(actor(2),application).get("state"));
        assertThrows(ServiceException.class,()->save(2,body(null,null)));
        db.update("update kg_application set onboard_time=:t,volunteer_done_time=now() where app_id=:a",p("a",application,"t",onboarded));
        assertEquals("completed",employment.detail(actor(2),application).get("state"));
        assertThrows(ServiceException.class,()->save(2,body(null,null)));
    }

    @Test void datesAndConflictingVersionsAreRejectedWithoutOverwritingFacts() {
        assertThrows(ServiceException.class,()->save(2,body(onboarded.minusSeconds(1),null)));
        assertThrows(ServiceException.class,()->save(2,body(Governance.now().plusDays(1),null)));
        assertEquals(0,db.count("select count(*) from kg_idempotency where biz_type='employment'",p()));
        Map<String,Object> saved = save(2,body(Governance.now().minusDays(1).withNano(0),null));
        assertThrows(ServiceException.class,()->save(2,body(null,null)));
        assertEquals(saved.get("offboardTime"),employment.detail(actor(2),application).get("offboardTime"));
        assertEquals(1,db.count("select count(*) from kg_idempotency where biz_type='employment'",p()));
    }

    @Test void departureRemovesCurrentActivityAndPreservesThePreviousQuarter() {
        LocalDate quarter = KgBadgeRules.quarterStart(Governance.now().toLocalDate());
        db.update("update kg_app_user set invited_by=3,invited_time=:t where user_id=1",p("t",quarter.minusYears(1).atStartOfDay()));
        KgBadgeMetrics metrics = (KgBadgeMetrics) ReflectionTestUtils.getField(badges,"metrics");
        assertEquals(1,Governance.num(metrics.referral(3L,quarter,Governance.now()).get("activeReferredWorkers")));
        assertEquals(0,Governance.num(metrics.travel(1L).get("completedExperiences")));
        save(2,body(quarter.atStartOfDay(),null));
        assertEquals(0,Governance.num(metrics.referral(3L,quarter,Governance.now()).get("activeReferredWorkers")));
        assertEquals(1,Governance.num(metrics.referral(3L,quarter.minusMonths(3),quarter.atStartOfDay()).get("activeReferredWorkers")));
        run(()->badges.sync(1L));
        run(()->badges.sync(1L));
        assertEquals(1,Governance.num(metrics.travel(1L).get("completedExperiences")),"正常离岗全职经历只计一次完成");
        assertEquals(java.time.temporal.ChronoUnit.DAYS.between(onboarded.toLocalDate(),quarter)+1,
                Governance.num(metrics.travel(1L).get("travelDays")),"全职实际工作计算至离岗当天并与其它旅行日期去重");
    }

    @Test void rosterUsesTheActualShopRetainsDeparturesAndPaginatesWithoutLeakage() {
        save(2,body(Governance.now().minusDays(1).withNano(0),null));
        for (int i=0;i<11;i++) {
            long next = fixture.app(1L);
            db.update("update kg_application set volunteer_done_time=null,onboard_time=:t where app_id=:a",p("a",next,"t",onboarded));
        }
        Map<String,Object> first = employment.list(actor(2),2L,1);
        Map<String,Object> second = employment.list(actor(2),2L,2);
        assertEquals(12,Governance.num(first.get("total")));
        assertEquals(10,((List<?>) first.get("rows")).size());
        assertEquals(2,((List<?>) second.get("rows")).size());
        assertEquals(Boolean.TRUE,first.get("hasMore"));
        assertEquals(Boolean.FALSE,second.get("hasMore"));
        @SuppressWarnings("unchecked") List<Map<String,Object>> rows = (List<Map<String,Object>>) second.get("rows");
        assertTrue(rows.stream().anyMatch(row -> "offboarded".equals(row.get("state"))));
        assertEquals(0,Governance.num(employment.list(actor(4),1L,1).get("total")),"归属小院列表不冒充实际招聘店人员");
        assertThrows(ServiceException.class,()->employment.list(actor(5),2L,1));
        assertThrows(ServiceException.class,()->employment.list(actor(2),3L,1));
    }

    @Test void futureOrUnconfirmedFulltimeDepartureCannotCreateACompletedExperience() {
        KgBadgeMetrics metrics = (KgBadgeMetrics) ReflectionTestUtils.getField(badges,"metrics");
        db.update("update kg_application set offboard_time=:t where app_id=:a",p("a",application,"t",Governance.now().plusDays(1)));
        assertEquals(0,Governance.num(metrics.travel(1L).get("completedExperiences")));
        db.update("update kg_application set onboard_time=null,offboard_time=:t where app_id=:a",p("a",application,"t",Governance.now().minusDays(1)));
        assertEquals(0,Governance.num(metrics.travel(1L).get("completedExperiences")));
    }

    @Test void permissionRevokedAfterTheInitialReadCannotSurviveTheLockWaitOrSessionCache() throws Exception {
        IKgStaffGrantService grants = (IKgStaffGrantService) ReflectionTestUtils.getField(employment,"grants");
        List<StaffGrantView> earlierGrant = grants.loadActiveGrantViews(2L);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch initiallyAuthorized = new CountDownLatch(1);
        when(grants.loadActiveGrantViews(2L)).thenAnswer(invocation -> {
            initiallyAuthorized.countDown();
            return earlierGrant; // Reproduce the MyBatis transaction-local cached result.
        });
        ProxyFactory factory = new ProxyFactory(employment);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(tx.getTransactionManager(),new AnnotationTransactionAttributeSource()));
        KgEmploymentService proxied = (KgEmploymentService) factory.getProxy();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = pool.submit(()->run(()->{
                db.must("select app_id from kg_application where app_id=:a for update",p("a",application),"missing application");
                locked.countDown();
                try { assertTrue(initiallyAuthorized.await(10,TimeUnit.SECONDS)); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new RuntimeException(exception); }
                db.update("update kg_staff_grant set status='1' where app_user_id=2 and store_id=2",p());
            }));
            assertTrue(locked.await(10,TimeUnit.SECONDS));
            Future<ServiceException> request = pool.submit(()->assertThrows(ServiceException.class,
                    ()->proxied.save(actor(2),application,body(Governance.now().minusDays(1),null))));
            writer.get(20,TimeUnit.SECONDS);
            assertEquals(Integer.valueOf(403),request.get(20,TimeUnit.SECONDS).getCode());
            assertNull(db.one("select offboard_time from kg_application where app_id=:a",p("a",application)).get("offboardTime"));
            assertEquals(0,db.count("select count(*) from kg_application_log where app_id=:a",p("a",application)));
            assertEquals(0,db.count("select count(*) from kg_idempotency where biz_type='employment'",p()));
        } finally {
            initiallyAuthorized.countDown();
            pool.shutdownNow();
        }
    }

    @Test void disabledAccountsOrStoresCannotWriteWithAnEarlierGrantSnapshot() {
        db.update("update kg_app_user set status='1' where user_id=2",p());
        assertThrows(ServiceException.class,()->save(2,body(Governance.now().minusDays(1),null)));
        db.update("update kg_app_user set status='0' where user_id=2",p());
        db.update("update kg_store set del_flag='2' where store_id=2",p());
        assertThrows(ServiceException.class,()->save(2,body(Governance.now().minusDays(1),null)));
        assertNull(db.one("select offboard_time from kg_application where app_id=:a",p("a",application)).get("offboardTime"));
    }
}
