package com.ruoyi.kangaroo.ext.governance;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.governance.KgGovernanceExecutionTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
import com.ruoyi.kangaroo.ext.user.KgMembershipRewards;

/** Runs on the same isolated MySQL fixture as governance, with real aggregate SQL and award persistence. */
class KgBadgeCompoundDatabaseTest {
    private final KgGovernanceExecutionTest fixture = new KgGovernanceExecutionTest();
    private KgBadgeMetrics actualMetrics;
    private KgMembershipRewards actualRewards;
    private long nextUser;

    @BeforeAll static void initialize() { KgGovernanceExecutionTest.setup(); }

    @BeforeEach void prepare() {
        fixture.reset();
        nextUser = 1000;
        actualMetrics = (KgBadgeMetrics) ReflectionTestUtils.getField(badges,"metrics");
        actualRewards = (KgMembershipRewards) ReflectionTestUtils.getField(badges,"rewards");
    }

    @AfterEach void clean() {
        field(badges,"metrics",actualMetrics);
        field(badges,"rewards",actualRewards);
        for (String table : new String[]{"kg_badge_quarter_review","kg_badge_content_check","kg_badge_violation","kg_badge_integrity"})
            db.update("delete from " + table,p());
    }

    LocalDate quarter() { return KgBadgeRules.quarterStart(Governance.now().toLocalDate()); }

    long user(Long inviter) {
        long user = nextUser++;
        db.insert("kg_app_user",p("user_id",user,"openid","badge-compound-"+user,"nickname","徽章隔离测试",
                "invited_by",inviter,"invited_time",quarter().minusYears(2).atStartOfDay(),
                "register_time",quarter().minusYears(2).atStartOfDay()));
        return user;
    }

    long completion(long user, LocalDateTime completed) {
        long application = fixture.app(user);
        db.update("update kg_application set volunteer_done_time=:t,volunteer_done_by='staff:test' where app_id=:a",p("t",completed,"a",application));
        return application;
    }

    long stay(long user, LocalDateTime start, LocalDateTime end) {
        return db.insert("kg_stay_order",p("order_no",UUID.randomUUID().toString().replace("-",""),"stay_id",1L,
                "store_id",1L,"room_type_id",1L,"app_user_id",user,"check_in",start.toLocalDate(),"check_out",end.toLocalDate(),
                "verify_time",start,"checked_out_time",end,"nights",10,"guest_name","隔离测试","guest_phone","TEST","pay_fen",1,"status","completed"));
    }

    Map<String,Object> referral(long user) { return actualMetrics.referral(user,quarter(),Governance.now()); }

    @Test void twentyRegisteredAndFiveCompletedEarnTheAlternateBaseRoute() {
        for (int i=0;i<20;i++) {
            long invited = user(1L);
            if (i<5) completion(invited,Governance.now().minusDays(1));
        }
        run(()->badges.sync(1L));
        assertEquals(1,active("referrer"));
        assertEquals(0,active("referrer_gold"));
        Map<String,Object> metrics = referral(1);
        assertEquals(20,Governance.num(metrics.get("registeredPeople")));
        assertEquals(5,Governance.num(metrics.get("experiencedPeople")));
    }

    @Test void firstActualExperienceControlsTheQuarterAndOnePersonCountsOnce() {
        long invited = user(1L);
        completion(invited,quarter().minusDays(1).atTime(12,0));
        completion(invited,quarter().atStartOfDay());
        stay(invited,quarter().minusDays(1).atStartOfDay(),quarter().atStartOfDay());
        Map<String,Object> metrics = referral(1);
        assertEquals(1,Governance.num(metrics.get("experiencedPeople")));
        assertEquals(0,Governance.num(metrics.get("quarterExperiencedPeople")));
        long stayOnly = user(1L);
        stay(stayOnly,quarter().minusDays(1).atStartOfDay(),quarter().atStartOfDay());
        metrics = referral(1);
        assertEquals(2,Governance.num(metrics.get("experiencedPeople")));
        assertEquals(1,Governance.num(metrics.get("quarterExperiencedPeople")));
    }

    @Test void migratedUnknownCompletionDoesNotInventAQuarterConversion() {
        long invited = user(1L);
        long application = completion(invited,Governance.now().minusHours(1));
        db.update("update kg_application set volunteer_done_by='migration' where app_id=:a",p("a",application));
        stay(invited,quarter().minusDays(1).atStartOfDay(),quarter().atStartOfDay());
        Map<String,Object> metrics = referral(1);
        assertEquals(1,Governance.num(metrics.get("experiencedPeople")));
        assertEquals(0,Governance.num(metrics.get("quarterExperiencedPeople")));
        assertEquals(1,Governance.num(metrics.get("undatedExperiences")));
    }

    @Test void tenthPlaceTiesShareTheSameRank() {
        LocalDateTime completed = quarter().atStartOfDay();
        for (int i=0;i<9;i++) {
            long inviter = user(null);
            for (int n=0;n<3;n++) completion(user(inviter),completed);
        }
        long peer = user(null);
        for (int n=0;n<2;n++) {
            completion(user(1L),completed);
            completion(user(peer),completed);
        }
        assertEquals(10,Governance.num(referral(1).get("quarterRank")));
        assertEquals(10,Governance.num(referral(peer).get("quarterRank")));
    }

    @Test void activeWorkersRequireConfirmedOnboardingAndAreDistinctPeople() {
        long invited = user(1L);
        for (int i=0;i<2;i++) {
            long application = completion(invited,Governance.now().minusDays(1));
            db.update("update kg_application a join kg_job j on j.job_id=a.job_id "
                    + "set j.job_type='fulltime',a.volunteer_done_time=null,a.onboard_time=:t where a.app_id=:a",
                    p("a",application,"t",Governance.now().minusDays(2)));
        }
        assertEquals(1,Governance.num(referral(1).get("activeReferredWorkers")));
        assertEquals(0,Governance.num(referral(1).get("experiencedPeople")),"全职在岗不代替首次换工、兼职或住宿体验");
        db.update("update kg_application set status='completed' where app_user_id=:u",p("u",invited));
        assertEquals(0,Governance.num(referral(1).get("activeReferredWorkers")));
    }

    @Test void jurorCountsClosedValidVotesAndPersonalAdoptionOnly() {
        for (int i=0;i<10;i++) vote(i,"closed","both","1",i<7?"1":null);
        vote(20,"open","both","1","1");
        vote(21,"closed","both","0","1");
        Map<String,Object> metrics = actualMetrics.jury(1L);
        assertEquals(10,Governance.num(metrics.get("participations")));
        assertEquals(7,Governance.num(metrics.get("adoptedVotes")));
        assertTrue(badges.goldJurorEligible(1L));
        vote(22,"closed",null,"0",null);
        assertEquals(1,Governance.num(actualMetrics.jury(1L).get("absenceCount")));
        assertFalse(badges.goldJurorEligible(1L));
    }

    @Test void anUnrelatedExecutedMemberCaseDoesNotInvalidateTheGoldJurorFormula() {
        for (int i=0;i<10;i++) vote(i,"closed","both","1",i<7?"1":null);
        db.insert("kg_member_case",p("app_user_id",1L,"severity",1,"facts","已核定的普通会员评价案件，与本次评审无关",
                "status","executed","created_by","admin:test","appeal_deadline",Governance.now().minusDays(1)));
        assertTrue(badges.goldJurorEligible(1L));
        run(()->badges.sync(1L));
        assertEquals(1,active("juror_gold"));
    }

    @Test void historicalContentWithoutDateCannotCrashReconciliationOrInventRecorderMonths() {
        for (int i=0;i<5;i++) db.insert("kg_post",p("app_user_id",1L,"content","历史真实动态未记录发布日期","status","online","create_time",null));
        assertDoesNotThrow(()->run(()->badges.sync(1L)));
        assertEquals(0,active("recorder"));
    }

    @Test void reconciliationWaitingForCreditCorrectionReadsTheCommittedIntegrityState() throws Exception {
        for (int i=0;i<10;i++) completion(user(1L),Governance.now().minusDays(1));
        db.insert("kg_badge_integrity",p("app_user_id",1L,"credit_score",100));
        run(()->badges.sync(1L));
        assertEquals(1,active("referrer"));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch readerStarted = new CountDownLatch(1);
        KgMembershipRewards rewards = mock(KgMembershipRewards.class);
        doAnswer(invocation -> {
            // This read used to establish an RR snapshot before sync obtained the account lock.
            db.count("select count(*) from kg_member_execution",p());
            readerStarted.countDown();
            return null;
        }).when(rewards).sync(1L);
        field(badges,"rewards",rewards);
        ProxyFactory factory = new ProxyFactory(badges);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(tx.getTransactionManager(),new AnnotationTransactionAttributeSource()));
        KgBadgeService proxied = (KgBadgeService) factory.getProxy();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = pool.submit(()->run(()->{
                db.must("select user_id from kg_app_user where user_id=1 for update",p(),"missing user");
                locked.countDown();
                try { assertTrue(readerStarted.await(10,TimeUnit.SECONDS)); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new RuntimeException(exception); }
                db.update("update kg_badge_integrity set credit_score=79 where app_user_id=1",p());
                db.update("update kg_badge_award set status='inactive',worn='0' where app_user_id=1 and badge_code='referrer'",p());
            }));
            assertTrue(locked.await(10,TimeUnit.SECONDS));
            Future<?> reader = pool.submit(()->proxied.sync(1L));
            writer.get(20,TimeUnit.SECONDS);
            reader.get(20,TimeUnit.SECONDS);
            assertEquals(0,active("referrer"),"等候锁之后必须读取已提交扣分，不能复活刚撤销的徽章");
        } finally {
            readerStarted.countDown();
            pool.shutdownNow();
        }
    }

    void vote(int sequence,String status,String choice,String valid,String adopted) {
        long round = db.insert("kg_mediation_round",p("case_id",900000+sequence,"anonymous_narrative","隔离证据",
                "anonymous_claims","隔离诉求","deadline",Governance.now().minusDays(1),"status",status));
        db.insert("kg_mediation_vote",p("round_id",round,"app_user_id",1L,"juror_kind","user","choice",choice,
                "valid",valid,"city_adopted",adopted));
    }

    @Test void travelUsesCompletedTypesAndVerifiedDaysWithOverlapDeduplication() {
        LocalDate start = quarter().minusMonths(3);
        long application = completion(1L,start.plusDays(9).atTime(18,0));
        db.update("update kg_application set onboard_time=:t,arrival_date=:d,volunteer_days=10 where app_id=:a",
                p("a",application,"t",start.atTime(8,0),"d",start));
        stay(1,start.plusDays(5).atTime(14,0),start.plusDays(15).atTime(10,0));
        long fulltime = completion(1L,start.plusDays(20).atTime(12,0));
        db.update("update kg_application a join kg_job j on j.job_id=a.job_id "
                + "set j.job_type='fulltime',a.status='completed',a.volunteer_done_time=null where a.app_id=:a",p("a",fulltime));
        Map<String,Object> metrics = actualMetrics.travel(1L);
        assertEquals(3,Governance.num(metrics.get("completedExperiences")));
        assertEquals(15,Governance.num(metrics.get("travelDays")));
        assertEquals(1,Governance.num(metrics.get("experiencesWithoutVerifiedDays")));
    }

    long active(String code) {
        return db.count("select count(*) from kg_badge_award where app_user_id=1 and badge_code=:c and status='active'",p("c",code));
    }

    Map<String,Object> mockReferral(long experienced) {
        return p("accountAvailable",true,"creditScore",100,"experiencedPeople",experienced,"registeredPeople",experienced,
                "quarterExperiencedPeople",0,"quarterRank",0,"activeReferredWorkers",0,"referralFraud",false,"hasViolation",false);
    }

    void useMetrics(Map<String,Object> referral) {
        KgBadgeMetrics controlled = mock(KgBadgeMetrics.class);
        when(controlled.referral(eq(1L),any(LocalDate.class),any(LocalDateTime.class))).thenAnswer(invocation -> new java.util.HashMap<>(referral));
        when(controlled.jury(1L)).thenReturn(p("accountAvailable",true));
        when(controlled.travel(1L)).thenReturn(p("accountAvailable",true,"creditScore",100));
        field(badges,"metrics",controlled);
    }

    void goldSince(LocalDate acquiredQuarter) {
        db.insert("kg_badge_award",p("app_user_id",1L,"badge_code","referrer_gold","source_key","automatic",
                "source_note","历史合法授予记录","awarded_by","system","rule_version",88,"awarded_at",acquiredQuarter.plusDays(1).atStartOfDay()));
    }

    @Test void acquisitionQuarterIsExcludedAndOneFailedQuarterRetainsGold() {
        useMetrics(mockReferral(10));
        goldSince(quarter().minusMonths(6));
        run(()->badges.sync(1L));
        run(()->badges.sync(1L));
        assertEquals(1,active("referrer_gold"));
        assertEquals(1,db.count("select count(*) from kg_badge_quarter_review where app_user_id=1",p()));
    }

    @Test void twoClosedFailedQuartersDowngradeExactlyOnce() {
        useMetrics(mockReferral(10));
        goldSince(quarter().minusMonths(9));
        run(()->badges.sync(1L));
        run(()->badges.sync(1L));
        assertEquals(0,active("referrer_gold"));
        assertEquals(1,active("referrer"));
        assertEquals(2,db.count("select count(*) from kg_badge_quarter_review where app_user_id=1",p()));
        assertEquals(1,db.count("select count(*) from kg_badge_log where app_user_id=1 and action='gold_downgrade'",p()));
    }

    @Test void acquiredThisQuarterCannotAccumulateHistoricalFailures() {
        useMetrics(mockReferral(0));
        goldSince(quarter());
        run(()->badges.sync(1L));
        assertEquals(1,active("referrer_gold"));
        assertEquals(0,db.count("select count(*) from kg_badge_quarter_review",p()));
    }

    @Test void lifetimeFiftyPassesEveryClosedQuarterWithoutNewConversions() {
        useMetrics(mockReferral(50));
        goldSince(quarter().minusMonths(9));
        run(()->badges.sync(1L));
        assertEquals(1,active("referrer_gold"));
        assertEquals(2,db.count("select count(*) from kg_badge_quarter_review where qualified='1'",p()));
    }

    @Test void legacyScalarGoldMustQualifyBeforeReceivingNewQuarterGrace() {
        useMetrics(mockReferral(1));
        goldSince(quarter().minusMonths(9));
        db.update("update kg_badge_award set rule_version=0 where app_user_id=1",p());
        run(()->badges.sync(1L));
        assertEquals(0,active("referrer_gold"));
        assertEquals(0,active("referrer"));
        assertEquals(0,db.count("select count(*) from kg_badge_quarter_review",p()));
        assertEquals(0,db.count("select count(*) from kg_badge_log where action='gold_downgrade'",p()));
    }

    @Test void qualifiedLegacyGoldBeginsQuarterAssessmentUnderTheNewRuleVersion() {
        useMetrics(mockReferral(50));
        goldSince(quarter().minusMonths(9));
        db.update("update kg_badge_award set rule_version=0 where app_user_id=1",p());
        run(()->badges.sync(1L));
        assertEquals(1,active("referrer_gold"));
        assertEquals(88,db.count("select rule_version from kg_badge_award where app_user_id=1 and badge_code='referrer_gold'",p()));
        assertEquals(0,db.count("select count(*) from kg_badge_quarter_review",p()));
    }

    @Test void tenActiveFulltimeRouteDowngradesToBaseWithoutFirstExperienceQuota() {
        Map<String,Object> current = mockReferral(0);
        current.put("activeReferredWorkers",10);
        useMetrics(current);
        run(()->badges.sync(1L));
        assertEquals(1,active("referrer_gold"));
        assertEquals(0,active("referrer"));
        db.update("update kg_badge_award set awarded_at=:t where app_user_id=1 and badge_code='referrer_gold'",
                p("t",quarter().minusMonths(9).plusDays(1).atStartOfDay()));
        current.put("activeReferredWorkers",0);
        run(()->badges.sync(1L));
        assertEquals(0,active("referrer_gold"));
        assertEquals(1,active("referrer"));
        // The actual metric reader carries the persisted downgrade basis on later requests.
        field(badges,"metrics",actualMetrics);
        run(()->badges.sync(1L));
        assertEquals(1,active("referrer"));
        db.insert("kg_badge_integrity",p("app_user_id",1L,"credit_score",79));
        run(()->badges.sync(1L));
        assertEquals(0,active("referrer"),"降级不绕过80分底线");
    }
}
