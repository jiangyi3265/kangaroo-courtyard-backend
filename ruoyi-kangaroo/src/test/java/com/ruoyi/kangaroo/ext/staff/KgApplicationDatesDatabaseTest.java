package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.service.IKgMessageService;

/** 只运行在本任务自建、独立端口的空库；不访问或清理任何现有业务库。 */
class KgApplicationDatesDatabaseTest {
    static DriverManagerDataSource ds;
    static JdbcTemplate sql;
    static KgJdbc db;
    KgApplicationDatesService service;
    IKgMessageService messages;
    StaffSupport support;
    boolean previousPhase;
    final LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    final String start = today.plusDays(4).toString(), end = today.plusDays(33).toString();
    @BeforeAll static void init() throws Exception {
        String url = System.getenv("KG_DATES_TEST_URL");
        Assumptions.assumeTrue(url != null && url.equals("jdbc:mysql://127.0.0.1:33319/kg_dates_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"));
        DriverManagerDataSource root = new DriverManagerDataSource(url.replace("/kg_dates_test?","/?"),"root","");
        new JdbcTemplate(root).execute("create database if not exists kg_dates_test character set utf8mb4 collate utf8mb4_unicode_ci");
        ds = new DriverManagerDataSource(url,"root",""); sql = new JdbcTemplate(ds); db = new KgJdbc(ds);
        sql.execute("create table if not exists kg_app_user(user_id bigint primary key,status char(1) default '0',del_flag char(1) default '0')");
        sql.execute("create table if not exists kg_store(store_id bigint primary key,status char(1) default '0',del_flag char(1) default '0')");
        sql.execute("create table if not exists kg_staff_grant(app_user_id bigint,store_id bigint,role_code varchar(32),status char(1) default '0',del_flag char(1) default '0')");
        sql.execute("create table if not exists kg_job(job_id bigint primary key,job_type varchar(20),start_date date,min_days int)");
        sql.execute("create table if not exists kg_application(app_id bigint primary key,app_user_id bigint,store_id bigint,job_id bigint,status varchar(30),arrival_date date,stay_days int,del_flag char(1) default '0',update_time datetime)");
        String migration = new String(Files.readAllBytes(Paths.get("../sql/kangaroo/V93__application_dates.sql")),StandardCharsets.UTF_8);
        Matcher ddl = Pattern.compile("(?s)create table if not exists kg_application_date_plan.*?;").matcher(migration);
        assertTrue(ddl.find()); sql.execute(ddl.group());
    }
    @BeforeEach void setup() {
        previousPhase = RolePhase.isNew(); RolePhase.set("new");
        for (String table : Arrays.asList("kg_application_date_plan","kg_application","kg_job","kg_staff_grant","kg_store","kg_app_user")) sql.execute("delete from "+table);
        sql.execute("insert into kg_app_user(user_id) values (7),(8),(9),(10),(11)");
        sql.execute("insert into kg_store(store_id) values (1),(2)");
        sql.execute("insert into kg_staff_grant(app_user_id,store_id,role_code) values (8,1,'manager'),(9,2,'manager'),(10,1,'staff'),(11,1,'online_staff')");
        db.update("insert into kg_job(job_id,job_type,start_date,min_days) values(3,'volunteer',:s,7)",p("s",today.toString()));
        db.update("insert into kg_application(app_id,app_user_id,store_id,job_id,status,arrival_date,stay_days) values(81,7,1,3,'store_confirmed',:s,30)",p("s",today.plusDays(2).toString()));
        messages = mock(IKgMessageService.class); support = mock(StaffSupport.class);
        KgApplicationDatesService target = new KgApplicationDatesService();
        ReflectionTestUtils.setField(target,"db",db); ReflectionTestUtils.setField(target,"messages",messages); ReflectionTestUtils.setField(target,"support",support);
        ProxyFactory proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));
        service = (KgApplicationDatesService)proxy.getProxy();
    }
    @AfterEach void restorePhase() { RolePhase.set(previousPhase ? "new" : "legacy"); }
    AppLoginUser actor(long id) { AppLoginUser a = new AppLoginUser(); a.setUserId(id); a.setNickname("隔离验收"); return a; }
    Map<String,Object> plan(long revision) { return p("revision",revision,"startDate",start,"endDate",end); }
    Map<String,Object> app() { return db.one("select arrival_date,stay_days,status from kg_application where app_id=81",p()); }
    @Test void emptyHistoryIsNotFalselyConfirmedAndReadsDoNotWrite() {
        Map<String,Object> d = service.detail(actor(7),81L,false);
        assertEquals("not_set",d.get("state")); assertEquals(false,d.get("canConfirm"));
        assertEquals(today.plusDays(31).toString(),d.get("applicationEnd"));
        assertEquals(true,service.detail(actor(8),81L,true).get("canEdit"));
        assertEquals(0,db.count("select count(*) from kg_application_date_plan",p())); verifyNoInteractions(messages,support);
    }
    @Test void proposalWaitsForOwnerAndConfirmationUpdatesInclusiveDatesAndNotifies() {
        Map<String,Object> before = app();
        Map<String,Object> pending = service.propose(actor(8),81L,plan(0));
        assertEquals("pending",pending.get("state")); assertEquals(1L,pending.get("revision")); assertEquals(before,app());
        Map<String,Object> confirmed = service.confirm(actor(7),81L,p("revision",1));
        assertEquals("confirmed",confirmed.get("state")); assertNotNull(confirmed.get("confirmedTime"));
        assertEquals(start,app().get("arrivalDate")); assertEquals(30,((Number)app().get("stayDays")).intValue()); assertEquals("store_confirmed",app().get("status"));
        verify(messages,times(2)).send(eq(7L),eq("user"),eq("apply"),anyString(),anyString(),eq("application"),eq(81L),anyString());
        verify(support).notifyStore(eq(1L),eq("apply"),anyString(),anyString(),eq("application"),eq(81L),anyString());
    }
    @Test void revisedDatesRequireFreshConfirmationAndKeepOldHistoryAndOfficialDates() {
        service.propose(actor(8),81L,plan(0)); service.confirm(actor(7),81L,p("revision",1));
        Map<String,Object> next = plan(1); next.put("endDate",today.plusDays(40).toString());
        assertEquals("pending",service.propose(actor(8),81L,next).get("state"));
        assertEquals(409,assertThrows(ServiceException.class,()->service.confirm(actor(7),81L,p("revision",1))).getCode());
        assertEquals(30,((Number)app().get("stayDays")).intValue());
        assertEquals(1,db.count("select count(*) from kg_application_date_plan where confirmed_time is not null",p()));
        service.confirm(actor(7),81L,p("revision",2)); assertEquals(37,((Number)app().get("stayDays")).intValue());
        assertEquals(2,db.count("select count(*) from kg_application_date_plan where confirmed_time is not null",p()));
    }
    @Test void duplicateRequestsAreIdempotentIncludingRetryAfterConfirmation() {
        service.propose(actor(8),81L,plan(0)); service.propose(actor(8),81L,plan(0));
        service.confirm(actor(7),81L,p("revision",1)); service.confirm(actor(7),81L,p("revision",1));
        assertEquals("confirmed",service.propose(actor(8),81L,plan(0)).get("state"));
        assertEquals(1,db.count("select count(*) from kg_application_date_plan",p()));
        verify(support,times(1)).notifyStore(anyLong(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString());
    }
    @Test void otherUsersStoresRetiredAndRevokedRolesCannotWrite() {
        assertEquals(403,assertThrows(ServiceException.class,()->service.detail(actor(9),81L,false)).getCode());
        for (long id : new long[]{9,10,11}) assertEquals(403,assertThrows(ServiceException.class,()->service.propose(actor(id),81L,plan(0))).getCode());
        assertEquals(403,assertThrows(ServiceException.class,()->service.detail(actor(10),81L,true)).getCode());
        sql.execute("update kg_staff_grant set status='1' where app_user_id=8");
        assertEquals(403,assertThrows(ServiceException.class,()->service.propose(actor(8),81L,plan(0))).getCode());
        verifyNoInteractions(messages,support);
    }
    @Test void selfDealingAndOnlineAndClosedApplicationsAreRejected() {
        sql.execute("insert into kg_staff_grant(app_user_id,store_id,role_code) values (7,1,'manager')");
        assertEquals(403,assertThrows(ServiceException.class,()->service.propose(actor(7),81L,plan(0))).getCode());
        for (String state : Arrays.asList("submitted","withdrawn","terminated","onboarded","completed")) {
            db.update("update kg_application set status=:s where app_id=81",p("s",state));
            assertEquals(409,assertThrows(ServiceException.class,()->service.propose(actor(8),81L,plan(0))).getCode());
        }
        sql.execute("update kg_job set job_type='online'"); assertEquals(422,assertThrows(ServiceException.class,()->service.detail(actor(7),81L,false)).getCode());
    }
    @Test void malformedExpiredReversedTooShortAndTooLongDatesAreRejected() {
        for (Map<String,Object> bad : Arrays.asList(p("revision",0,"startDate","2030-02-30","endDate",end),p("revision",0,"startDate",today.minusDays(1).toString(),"endDate",end),p("revision",0,"startDate",end,"endDate",start),p("revision",0,"startDate",start,"endDate",today.plusDays(5).toString()),p("revision",0,"startDate",start,"endDate",today.plusDays(370).toString())))
            assertThrows(ServiceException.class,()->service.propose(actor(8),81L,bad));
        assertThrows(ServiceException.class,()->service.propose(actor(8),81L,p("startDate",start,"endDate",end)));
        assertEquals(0,db.count("select count(*) from kg_application_date_plan",p()));
    }
    @Test void changedJobConstraintsCannotBeBypassedByOldUserPage() {
        service.propose(actor(8),81L,plan(0)); sql.execute("update kg_job set min_days=60");
        assertEquals(422,assertThrows(ServiceException.class,()->service.confirm(actor(7),81L,p("revision",1))).getCode());
        assertEquals("pending",service.detail(actor(7),81L,false).get("state"));
    }
    @Test void failedNotificationRollsBackProposalAndConfirmation() {
        doThrow(new RuntimeException("fixture message failure")).when(messages).send(anyLong(),anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString());
        assertThrows(RuntimeException.class,()->service.propose(actor(8),81L,plan(0)));
        assertEquals(0,db.count("select count(*) from kg_application_date_plan",p())); reset(messages);
        service.propose(actor(8),81L,plan(0)); Map<String,Object> previous = app();
        doThrow(new RuntimeException("fixture notify failure")).when(support).notifyStore(anyLong(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString());
        assertThrows(RuntimeException.class,()->service.confirm(actor(7),81L,p("revision",1)));
        assertEquals(previous,app()); assertEquals("pending",service.detail(actor(7),81L,false).get("state"));
    }
    @Test void concurrentMerchantChangesCannotOverwriteEachOther() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<Integer>> work = new ArrayList<>();
            for (int i=0;i<2;i++) { final int offset=i; work.add(pool.submit(()-> { startGate.await(); Map<String,Object> body=plan(0); body.put("endDate",today.plusDays(33+offset).toString()); try { service.propose(actor(8),81L,body); return 200; } catch(ServiceException e) { return e.getCode(); } })); }
            startGate.countDown(); List<Integer> result=Arrays.asList(work.get(0).get(10,TimeUnit.SECONDS),work.get(1).get(10,TimeUnit.SECONDS));
            assertTrue(result.contains(200)); assertTrue(result.contains(409)); assertEquals(1,db.count("select count(*) from kg_application_date_plan",p()));
        } finally { pool.shutdownNow(); }
    }
}
