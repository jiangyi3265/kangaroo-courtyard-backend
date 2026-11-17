package com.ruoyi.kangaroo.ext.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import com.ruoyi.kangaroo.config.KangarooProperties;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgActivityService;
import com.ruoyi.kangaroo.ext.community.KgChatService;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** Real isolated MySQL and Spring transactions. No payment channel/network calls. */
class PayCreationIT
{
    private String database; private JdbcTemplate root,sql; private KgPayService pay; private TransactionTemplate tx;
    private volatile CyclicBarrier insertGate;
    private KgActivityService activity; private KgChatService chats; private StaffSupport support;
    private DataSource source(String db) {
        String password=System.getenv("KG_TEST_DB_PASSWORD");assertNotNull(password);
        return new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/"+db+"?serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false","root",password);
    }
    @BeforeEach void setup() {
        database="kg_pay_test_"+UUID.randomUUID().toString().replace("-","").substring(0,8);
        root=new JdbcTemplate(source("dkxy"));root.execute("create database "+database+" character set utf8mb4");
        DataSource ds=source(database);sql=new JdbcTemplate(ds);
        sql.execute("create table kg_pay_order like dkxy.kg_pay_order");sql.execute("create table kg_activity_signup like dkxy.kg_activity_signup");sql.execute("create table fixture_business(id bigint primary key) engine=InnoDB");
        KgJdbc db=new KgJdbc(ds) {
            @Override public long insert(String table,Map<String,Object> values) {
                if("kg_pay_order".equals(table)&&insertGate!=null)try{insertGate.await(10,TimeUnit.SECONDS);}catch(Exception e){throw new IllegalStateException(e);}
                return super.insert(table,values);
            }
        };
        sql.execute("create table kg_activity like dkxy.kg_activity");
        chats=mock(KgChatService.class);support=mock(StaffSupport.class);activity=new KgActivityService();
        ReflectionTestUtils.setField(activity,"db",db);ReflectionTestUtils.setField(activity,"chats",chats);ReflectionTestUtils.setField(activity,"support",support);
        KangarooProperties props=mock(KangarooProperties.class);when(props.getPayMode()).thenReturn("mock");
        KgPayService target=new KgPayService();ReflectionTestUtils.setField(target,"db",db);ReflectionTestUtils.setField(target,"properties",props);
        DataSourceTransactionManager manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);
        ProxyFactory f=new ProxyFactory(target);f.setProxyTargetClass(true);f.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));pay=(KgPayService)f.getProxy();
        ReflectionTestUtils.setField(activity,"pay",pay);ReflectionTestUtils.setField(target,"listeners",Collections.singletonList(activity));
        assertEquals("REPEATABLE-READ",sql.queryForObject("select @@transaction_isolation",String.class));
    }
    @AfterEach void cleanup(){if(root!=null&&database!=null&&database.matches("kg_pay_test_[a-f0-9]{8}"))root.execute("drop database "+database);}
    @Test void concurrentNewBusinessesBothPersistAtInsertBarrier() throws Exception {
        insertGate=new CyclicBarrier(2);ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> work=new ArrayList<>();
            for(long id:new long[]{1501,1502})work.add(pool.submit(()->tx.execute(s->{sql.update("insert into fixture_business values(?)",id);return pay.createForNewBusiness("service_order",id,1,1L,1000,"isolated");})));
            for(Future<?> future:work)future.get(20,TimeUnit.SECONDS);
        }finally{pool.shutdownNow();}
        assertEquals(2,sql.queryForObject("select count(*) from kg_pay_order where status='pending' and amount=1000",Integer.class));
        assertEquals(2,sql.queryForObject("select count(*) from fixture_business",Integer.class));
    }
    @Test void laterBusinessFailureRollsBackPaymentToo() {
        assertThrows(IllegalStateException.class,()->tx.execute(s->{sql.update("insert into fixture_business values(1)");pay.createForNewBusiness("stay_order",1,1,1L,1000,"isolated");throw new IllegalStateException("business rollback");}));
        assertEquals(0,sql.queryForObject("select count(*) from kg_pay_order",Integer.class));assertEquals(0,sql.queryForObject("select count(*) from fixture_business",Integer.class));
    }
    @Test void existingBusinessStillReusesPendingAndRejectsPaid() {
        Map<String,Object> initial=pay.createForNewBusiness("stay_order",1,1,1L,1000,"isolated");
        assertEquals(initial.get("payNo"),pay.create("stay_order",1,1,1L,1000,"duplicate").get("payNo"));
        sql.update("update kg_pay_order set status='paid'");
        assertThrows(com.ruoyi.common.exception.ServiceException.class,()->pay.create("stay_order",1,1,1L,1000,"duplicate"));
        assertEquals(1,sql.queryForObject("select count(*) from kg_pay_order",Integer.class));
    }
    private void signup(long id) {
        sql.update("insert into kg_activity_signup(signup_id,signup_no,activity_id,app_user_id,status,fee_fen) values(?,?,?,?, 'pending_payment',1000)",id,"test-"+id,id,7);
    }
    @Test void separateReusedSignupsDoNotGapLockEachOther() throws Exception {
        signup(1);signup(2);insertGate=new CyclicBarrier(2);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<?> a=pool.submit(()->pay.createForSignupCycle(1,7,1L,1000,"reused"));
            Future<?> b=pool.submit(()->pay.createForSignupCycle(2,7,1L,1000,"promoted"));
            a.get(20,TimeUnit.SECONDS);b.get(20,TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
        assertEquals(2,sql.queryForObject("select count(*) from kg_activity_signup s join kg_pay_order p on p.pay_no=s.pay_no where p.biz_id=s.signup_id and p.status='pending'",Integer.class));
    }
    @Test void sameCycleConcurrentRetriesShareOnePayment() throws Exception {
        signup(1);ExecutorService pool=Executors.newFixedThreadPool(2);CyclicBarrier start=new CyclicBarrier(2);
        try {
            Callable<Object> task=()->{start.await(5,TimeUnit.SECONDS);return pay.createForSignupCycle(1,7,1L,1000,"retry").get("payNo");};
            Future<Object> a=pool.submit(task),b=pool.submit(task);assertEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
        }finally{pool.shutdownNow();}
        assertEquals(1,sql.queryForObject("select count(*) from kg_pay_order",Integer.class));
    }
    @Test void nextCycleIgnoresPreviouslyPaidHistory() {
        signup(1);Object old=pay.createForSignupCycle(1,7,1L,1000,"first").get("payNo");
        sql.update("update kg_pay_order set status='refunded' where pay_no=?",old);
        sql.update("update kg_activity_signup set pay_no=null, status='pending_payment' where signup_id=1");
        Object current=pay.createForSignupCycle(1,7,1L,1000,"next").get("payNo");
        assertNotEquals(old,current);assertEquals(current,sql.queryForObject("select pay_no from kg_activity_signup where signup_id=1",String.class));
        assertEquals(2,sql.queryForObject("select count(*) from kg_pay_order",Integer.class));
    }
    @Test void rejectedOwnerAmountAndPaidStateCannotCreateAnotherPayment() {
        signup(1);
        assertThrows(com.ruoyi.common.exception.ServiceException.class,()->pay.createForSignupCycle(1,8,1L,1000,"foreign"));
        assertThrows(com.ruoyi.common.exception.ServiceException.class,()->pay.createForSignupCycle(1,7,1L,999,"wrong amount"));
        assertEquals(0,sql.queryForObject("select count(*) from kg_pay_order",Integer.class));
        pay.createForSignupCycle(1,7,1L,1000,"first");sql.update("update kg_pay_order set status='paid'");
        assertThrows(com.ruoyi.common.exception.ServiceException.class,()->pay.createForSignupCycle(1,7,1L,1000,"already paid"));
        assertEquals(1,sql.queryForObject("select count(*) from kg_pay_order",Integer.class));
    }
    @Test void laterFailureRollsBackPaymentAndSignupBacklink() {
        signup(1);
        assertThrows(IllegalStateException.class,()->tx.execute(s->{pay.createForSignupCycle(1,7,1L,1000,"rollback");throw new IllegalStateException("later failure");}));
        assertNull(sql.queryForObject("select pay_no from kg_activity_signup where signup_id=1",String.class));
        assertEquals(0,sql.queryForObject("select count(*) from kg_pay_order",Integer.class));
    }
    private void activityRow() {sql.update("insert into kg_activity(activity_id,source,title,start_time,status,signed) values(1,'user','cycle fixture',date_add(now(),interval 1 day),'cancelled',1)");}
    @Test void historicalRefundCannotModifyCurrentConfirmedSignup() {
        signup(1);activityRow();Map<String,Object> old=pay.createForSignupCycle(1,7,1L,1000,"old");
        sql.update("update kg_pay_order set status='paid' where pay_no=?",old.get("payNo"));
        sql.update("update kg_activity_signup set pay_no=null where signup_id=1");
        Object current=pay.createForSignupCycle(1,7,1L,1000,"current").get("payNo");
        sql.update("update kg_activity_signup set status='confirmed' where signup_id=1");
        pay.refund(old.get("payNo").toString(),1000,"historical refund","test");
        assertEquals("confirmed",sql.queryForObject("select status from kg_activity_signup",String.class));
        assertEquals(current,sql.queryForObject("select pay_no from kg_activity_signup",String.class));
        assertEquals(1,sql.queryForObject("select signed from kg_activity",Integer.class));
        assertEquals("refunded",sql.queryForObject("select status from kg_pay_order where pay_no=?",String.class,old.get("payNo")));
        verifyNoInteractions(chats,support);
    }
    @Test void historicalPaidCannotConfirmNewSignupCycle() {
        signup(1);activityRow();Object old=pay.createForSignupCycle(1,7,1L,1000,"old").get("payNo");
        sql.update("update kg_activity_signup set pay_no=null where signup_id=1");
        Object current=pay.createForSignupCycle(1,7,1L,1000,"current").get("payNo");
        assertThrows(com.ruoyi.common.exception.ServiceException.class,()->pay.mockConfirm(old.toString(),7));
        assertEquals("pending_payment",sql.queryForObject("select status from kg_activity_signup",String.class));
        assertEquals(current,sql.queryForObject("select pay_no from kg_activity_signup",String.class));
        assertEquals("pending",sql.queryForObject("select status from kg_pay_order where pay_no=?",String.class,old));
        verifyNoInteractions(chats,support);
    }
    @Test void staleExpiryScanCannotCloseFutureCycle() {
        signup(1);activityRow();Object current=pay.createForSignupCycle(1,7,1L,1000,"current").get("payNo");
        sql.update("update kg_activity_signup set expire_time=date_add(now(),interval 15 minute) where signup_id=1");
        tx.execute(s->{activity.closeExpired(1);return null;});
        assertEquals("pending_payment",sql.queryForObject("select status from kg_activity_signup",String.class));
        assertEquals("pending",sql.queryForObject("select status from kg_pay_order where pay_no=?",String.class,current));
        assertEquals(1,sql.queryForObject("select signed from kg_activity",Integer.class));
    }
    @Test void paymentHeldDuringReuseReturnsRetryConflictWithoutDeadlock() throws Exception {
        signup(1);activityRow();String no=pay.createForSignupCycle(1,7,1L,1000,"first").get("payNo").toString();
        CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService pool=Executors.newSingleThreadExecutor();
        Future<?> callback=pool.submit(()->tx.execute(s->{sql.queryForList("select pay_id from kg_pay_order where pay_no=? for update",no);locked.countDown();try{assertTrue(release.await(8,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}return pay.mockConfirm(no,7);}));
        try {
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            com.ruoyi.common.exception.ServiceException error=assertTimeoutPreemptively(java.time.Duration.ofSeconds(3),()->assertThrows(com.ruoyi.common.exception.ServiceException.class,()->pay.createForSignupCycle(1,7,1L,1000,"retry")));
            assertEquals(Integer.valueOf(409),error.getCode());
        }finally{release.countDown();}
        try{callback.get(10,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        assertEquals("confirmed",sql.queryForObject("select status from kg_activity_signup",String.class));
        assertEquals(1,sql.queryForObject("select count(*) from kg_pay_order where status='paid'",Integer.class));
    }
    @Test void signupHeldDuringCallbackRollsBackThenRetrySucceeds() throws Exception {
        signup(1);activityRow();String no=pay.createForSignupCycle(1,7,1L,1000,"first").get("payNo").toString();
        CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService pool=Executors.newSingleThreadExecutor();
        Future<?> owner=pool.submit(()->tx.execute(s->{sql.queryForList("select signup_id from kg_activity_signup where signup_id=1 for update");locked.countDown();try{assertTrue(release.await(8,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}return null;}));
        try {
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            com.ruoyi.common.exception.ServiceException error=assertTimeoutPreemptively(java.time.Duration.ofSeconds(3),()->assertThrows(com.ruoyi.common.exception.ServiceException.class,()->pay.mockConfirm(no,7)));
            assertEquals(Integer.valueOf(409),error.getCode());
            assertEquals("pending",sql.queryForObject("select status from kg_pay_order",String.class));
        }finally{release.countDown();try{owner.get(10,TimeUnit.SECONDS);}finally{pool.shutdownNow();}}
        pay.mockConfirm(no,7);assertEquals("confirmed",sql.queryForObject("select status from kg_activity_signup",String.class));
    }
    @Test void activityHeldDuringCallbackFailsFastAndRetryCommits() throws Exception {
        signup(1);activityRow();String no=pay.createForSignupCycle(1,7,1L,1000,"first").get("payNo").toString();
        CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService pool=Executors.newSingleThreadExecutor();
        Future<?> owner=pool.submit(()->tx.execute(s->{sql.queryForList("select activity_id from kg_activity where activity_id=1 for update");locked.countDown();try{assertTrue(release.await(8,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}sql.queryForList("select signup_id from kg_activity_signup where signup_id=1 for update");return null;}));
        try {
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            com.ruoyi.common.exception.ServiceException error=assertTimeoutPreemptively(java.time.Duration.ofSeconds(3),()->assertThrows(com.ruoyi.common.exception.ServiceException.class,()->pay.mockConfirm(no,7)));
            assertEquals(Integer.valueOf(409),error.getCode());
            assertEquals("pending",sql.queryForObject("select status from kg_pay_order",String.class));
        }finally{release.countDown();try{owner.get(10,TimeUnit.SECONDS);}finally{pool.shutdownNow();}}
        pay.mockConfirm(no,7);assertEquals("confirmed",sql.queryForObject("select status from kg_activity_signup",String.class));
    }
}
