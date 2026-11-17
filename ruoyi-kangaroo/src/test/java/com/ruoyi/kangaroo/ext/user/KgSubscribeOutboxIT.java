package com.ruoyi.kangaroo.ext.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import java.util.UUID;
import java.util.concurrent.*;
import javax.sql.DataSource;
import com.ruoyi.kangaroo.config.KangarooProperties;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.WxMiniAppClient;
import com.ruoyi.kangaroo.security.WxMiniAppClient.SubscribeDelivery;
import static com.ruoyi.kangaroo.security.WxMiniAppClient.SubscribeDelivery.State.*;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual MySQL + Spring transactions; WeChat transport is a mock and never accesses the network. */
class KgSubscribeOutboxIT
{
    private String database;
    private JdbcTemplate root, sql;
    private KgSubscribeOutbox service;
    private WxMiniAppClient wx;
    private TransactionTemplate tx;
    private KgSubscribeService subscriptions;

    private DataSource source(String db)
    {
        String password = System.getenv("KG_TEST_DB_PASSWORD");
        assertNotNull(password, "Run via .dev/tools/check_oct01_integrations.py; credentials must not appear in command arguments");
        return new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/" + db
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false", "root", password);
    }

    @BeforeEach void setup()
    {
        database = "kg_outbox_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        root = new JdbcTemplate(source("dkxy"));
        root.execute("create database " + database + " character set utf8mb4");
        DataSource ds = source(database); sql = new JdbcTemplate(ds);
        for (String table : new String[]{"kg_app_user","kg_subscribe_template","kg_subscribe_grant","kg_subscribe_log","kg_subscribe_outbox","kg_message","kg_staff_grant","kg_chat_member"})
            sql.execute("create table " + table + " like dkxy." + table);
        sql.execute("alter table kg_subscribe_outbox drop index uk_subscribe_outbox_msg, add column event_key varchar(128), add unique key uk_subscribe_event(event_key)");
        sql.execute("create table kg_subscribe_consent(ticket varchar(64) primary key,app_user_id bigint,template_ids text,expires_time datetime,used_time datetime,create_time datetime)");
        sql.update("insert into kg_app_user(user_id,openid,nickname) values(1,'isolated-outbox-user','模拟用户')");
        sql.update("insert into kg_subscribe_template(biz_key,title,template_id,fields) values('community','测试','test-template','thing1=title')");
        sql.update("insert into kg_subscribe_grant(app_user_id,template_id,quota) values(1,'test-template',2)");
        KangarooProperties props=mock(KangarooProperties.class); when(props.getSubscribeMode()).thenReturn("wx");
        wx=mock(WxMiniAppClient.class);
        when(wx.deliverSubscribeMessage(anyString(),anyString(),anyString(),anyMap())).thenAnswer(inv -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "network delivery is inside a database transaction");
            assertEquals(1, sql.queryForObject("select count(*) from kg_subscribe_outbox where status='sending'", Integer.class));
            return new SubscribeDelivery(SENT, "");
        });
        KgSubscribeOutbox target=new KgSubscribeOutbox();
        ReflectionTestUtils.setField(target,"db",new KgJdbc(ds));
        ReflectionTestUtils.setField(target,"properties",props); ReflectionTestUtils.setField(target,"wx",wx);
        DataSourceTransactionManager manager=new DataSourceTransactionManager(ds); tx=new TransactionTemplate(manager);
        ProxyFactory factory=new ProxyFactory(target); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        service=(KgSubscribeOutbox)factory.getProxy(); ReflectionTestUtils.setField(target,"self",service);
        KgSubscribeService sub=new KgSubscribeService();ReflectionTestUtils.setField(sub,"db",new KgJdbc(ds));ReflectionTestUtils.setField(sub,"properties",props);ReflectionTestUtils.setField(sub,"outbox",service);
        ProxyFactory sf=new ProxyFactory(sub);sf.setProxyTargetClass(true);sf.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));subscriptions=(KgSubscribeService)sf.getProxy();

    }

    @AfterEach void cleanup()
    {
        if (root!=null && database!=null && database.matches("kg_outbox_test_[a-f0-9]{8}")) root.execute("drop database " + database);
    }

    private void enqueue(Long uid,String key,String title,String content,String route,Long mid) {
        sql.update("insert ignore into kg_message(msg_id,recipient_id,mode,category,title,content,read_flag,del_flag) values(?,?,'user','system','test','test','0','0')",mid,uid);
        service.enqueue(uid,key,title,content,route,mid);
        if(!TransactionSynchronizationManager.isActualTransactionActive())service.drain();
    }
    private int quota() { return sql.queryForObject("select quota from kg_subscribe_grant where app_user_id=1", Integer.class); }
    private String status() { return sql.queryForObject("select status from kg_subscribe_outbox",String.class); }
    private long queued()
    {
        sql.update("insert ignore into kg_message(msg_id,recipient_id,mode,category,title,content,read_flag,del_flag) values(101,1,'user','system','test','test','0','0')");
        sql.update("insert into kg_subscribe_outbox(msg_id,app_user_id,biz_key,channel,payload,status,create_time,update_time) values(101,1,'community','wx','{\"title\":\"test\",\"route\":\"/pages/home/index\"}','queued',now(),now())");
        return sql.queryForObject("select outbox_id from kg_subscribe_outbox",Long.class);
    }

    @Test void businessRollbackCannotDeliverOrConsumeConsent()
    {
        assertThrows(IllegalStateException.class, () -> tx.execute(s -> {
            sql.update("insert into kg_message(msg_id,recipient_id,mode,category,title,content) values(101,1,'user','system','test','test')");
            enqueue(1L,"community","test","test","/pages/home/index",101L);
            assertEquals(0,sql.queryForObject("select count(*) from kg_subscribe_log",Integer.class));
            verifyNoInteractions(wx);
            throw new IllegalStateException("business fails after enqueue");
        }));
        assertEquals(0,sql.queryForObject("select count(*) from kg_message",Integer.class));
        assertEquals(0,sql.queryForObject("select count(*) from kg_subscribe_outbox",Integer.class));
        assertEquals(2,quota()); verifyNoInteractions(wx);
    }

    @Test void commitDeliversOutsideTransactionAndPersistsOneResult()
    {
        enqueue(1L,"community","test","test","/pages/home/index",101L);
        assertEquals("sent",status()); assertEquals(1,quota());
        assertEquals(1,sql.queryForObject("select count(*) from kg_subscribe_log where status='sent'",Integer.class));
        enqueue(1L,"community","duplicate","duplicate","/pages/home/index",101L);
        verify(wx,times(1)).deliverSubscribeMessage(anyString(),anyString(),anyString(),anyMap());
    }

    @Test void queuedTaskSurvivesRestartAndConcurrentDispatch() throws Exception
    {
        long id=queued(); ExecutorService pool=Executors.newFixedThreadPool(6);
        try {
            CountDownLatch gate=new CountDownLatch(1); List<Future<?>> jobs=new ArrayList<>();
            for(int i=0;i<6;i++) jobs.add(pool.submit(() -> { gate.await(); service.dispatch(id); return null; }));
            gate.countDown(); for(Future<?> job:jobs) job.get(20,TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertEquals("sent",status()); assertEquals(1,quota());
        verify(wx,times(1)).deliverSubscribeMessage(anyString(),anyString(),anyString(),anyMap());
    }

    @Test void ambiguousTransportDoesNotRestoreConsentOrResend()
    {
        doReturn(new SubscribeDelivery(UNKNOWN,"调用微信接口失败")).when(wx).deliverSubscribeMessage(anyString(),anyString(),anyString(),anyMap());
        long id=queued(); service.dispatch(id); service.dispatch(id); service.drain();
        assertEquals("unknown",status()); assertEquals(1,quota());
        verify(wx,times(1)).deliverSubscribeMessage(anyString(),anyString(),anyString(),anyMap());
    }

    @Test void definiteRejectionRestoresConsentExactlyOnce()
    {
        doReturn(new SubscribeDelivery(REJECTED,"微信返回 errcode=47003")).when(wx).deliverSubscribeMessage(anyString(),anyString(),anyString(),anyMap());
        long id=queued(); service.dispatch(id); service.finish(id,"failed","duplicate",true);
        assertEquals("failed",status()); assertEquals(2,quota());
        assertEquals(1,sql.queryForObject("select count(*) from kg_subscribe_log",Integer.class));
    }

    @Test void databaseFailureAfterDeliveryLeavesObservableUnknownWithoutResend()
    {
        sql.execute("create trigger reject_delivery_log before insert on kg_subscribe_log for each row signal sqlstate '45000' set message_text='injected delivery log failure'");
        // afterCommit maintenance failure cannot change the already committed business response.
        enqueue(1L,"community","test","test","/pages/home/index",101L);
        assertEquals("sending",status()); assertEquals(1,quota());
        assertEquals(0,sql.queryForObject("select count(*) from kg_subscribe_log",Integer.class));
        sql.execute("drop trigger reject_delivery_log");
        sql.update("update kg_subscribe_outbox set update_time=date_sub(now(),interval 6 minute)");
        service.drain(); assertEquals("unknown",status()); assertEquals(1,quota());
        verify(wx,times(1)).deliverSubscribeMessage(anyString(),anyString(),anyString(),anyMap());
    }
    @Test void failureCannotRestoreRevokedConsentOrSendNextTask()
    {
        doAnswer(inv -> {
            sql.update("update kg_subscribe_grant set quota=0,grant_version=grant_version+1");
            return new SubscribeDelivery(REJECTED,"微信返回 errcode=43101");
        }).when(wx).deliverSubscribeMessage(anyString(),anyString(),anyString(),anyMap());
        long id=queued(); service.dispatch(id); service.finish(id,"failed","replayed",true);
        assertEquals(0,quota()); assertEquals("failed",status());
        enqueue(1L,"community","next","next","/pages/home/index",102L);
        assertEquals("skipped",sql.queryForObject("select status from kg_subscribe_outbox where msg_id=102",String.class));
        verify(wx,times(1)).deliverSubscribeMessage(anyString(),anyString(),anyString(),anyMap());
    }

    @Test void failureRefundCannotExceedQuotaCapAfterNewConsent()
    {
        sql.update("update kg_subscribe_grant set quota=5");
        doAnswer(inv -> {
            sql.update("update kg_subscribe_grant set quota=least(5,quota+1)");
            return new SubscribeDelivery(REJECTED,"微信返回 errcode=47003");
        }).when(wx).deliverSubscribeMessage(anyString(),anyString(),anyString(),anyMap());
        service.dispatch(queued()); assertEquals(5,quota()); assertEquals("failed",status());
    }

    @Test void unattemptedDeliveryRetriesWithBackoffAndOnlyOneFinalDebit()
    {
        doReturn(new SubscribeDelivery(NOT_ATTEMPTED,"token unavailable"),new SubscribeDelivery(SENT,"")).when(wx)
                .deliverSubscribeMessage(anyString(),anyString(),anyString(),anyMap());
        long id=queued(); service.dispatch(id);
        assertEquals("queued",status()); assertEquals(2,quota());
        service.dispatch(id); service.drain();
        verify(wx,times(1)).deliverSubscribeMessage(anyString(),anyString(),anyString(),anyMap());
        sql.update("update kg_subscribe_outbox set next_attempt_time=date_sub(now(),interval 1 second)");
        service.drain(); assertEquals("sent",status()); assertEquals(1,quota());
        assertEquals(2,sql.queryForObject("select attempts from kg_subscribe_outbox",Integer.class));
    }

    @Test void unattemptedDeliveryStopsAfterThreeAttemptsWithoutLosingConsent()
    {
        doReturn(new SubscribeDelivery(NOT_ATTEMPTED,"token unavailable")).when(wx)
                .deliverSubscribeMessage(anyString(),anyString(),anyString(),anyMap());
        long id=queued();
        for(int i=0;i<3;i++) {
            sql.update("update kg_subscribe_outbox set next_attempt_time=null"); service.dispatch(id);
        }
        assertEquals("failed",status()); assertEquals(2,quota()); service.drain();
        verify(wx,times(3)).deliverSubscribeMessage(anyString(),anyString(),anyString(),anyMap());
    }

    @Test void consentReceiptCannotBeReplayedOrUsedByAnotherAccount(){
        Map<String,Object> prepared=subscriptions.prepare(1L);String ticket=String.valueOf(prepared.get("ticket"));
        Map<String,Object> answer=p("ticket",ticket,"results",p("test-template","accept"));
        assertThrows(RuntimeException.class,()->subscriptions.accept(2L,answer));assertEquals(2,quota());
        subscriptions.accept(1L,answer);assertEquals(3,quota());subscriptions.accept(1L,answer);assertEquals(3,quota());
    }
    @Test void expiredReceiptAndFabricatedTemplateCannotGrantConsent(){
        Map<String,Object> prepared=subscriptions.prepare(1L);String ticket=String.valueOf(prepared.get("ticket"));
        subscriptions.accept(1L,p("ticket",ticket,"results",p("fake-template","accept")));assertEquals(2,quota());
        String next=String.valueOf(subscriptions.prepare(1L).get("ticket"));sql.update("update kg_subscribe_consent set expires_time=date_sub(now(),interval 1 second)");
        assertThrows(RuntimeException.class,()->subscriptions.accept(1L,p("ticket",next,"results",p("test-template","accept"))));assertEquals(2,quota());
    }
    @Test void realWechatConsentRejectionClearsStaleLocalCredits(){
        doReturn(new SubscribeDelivery(REJECTED,"微信返回 errcode=43101")).when(wx).deliverSubscribeMessage(anyString(),anyString(),anyString(),anyMap());
        service.dispatch(queued());assertEquals("failed",status());assertEquals(0,quota());
    }
    @Test void malformedTemplateDoesNotConsumeConsentOrSend(){
        sql.update("update kg_subscribe_template set fields='amount1=title'");service.dispatch(queued());assertEquals("failed",status());assertEquals(2,quota());verifyNoInteractions(wx);
    }
    @Test void readConversationDoesNotProduceAnExternalReminder(){
        long id=queued();sql.update("update kg_subscribe_outbox set event_key='chat:1:10:1:user'");sql.update("update kg_message set read_flag='1'");
        service.dispatch(id);assertEquals("skipped",status());assertEquals(2,quota());verifyNoInteractions(wx);
    }
    @Test void pairedPersonalAndStaffCopiesOnlyUseOneSubscription(){
        tx.execute(x->{
            sql.update("insert into kg_message(msg_id,recipient_id,mode,category,biz_type,biz_id,title,content) values(201,1,'user','system','insurance_finance',8,'退款完成','退款已完成'),(202,1,'staff','system','insurance_finance',8,'退款完成','退款已完成')");
            service.enqueue(1L,"community","退款完成","退款已完成","/pages/staff/insurance",201L);
            service.enqueue(1L,"community","退款完成","退款已完成","/pages/staff/insurance",202L);
            return null;
        });
        assertEquals(1,sql.queryForObject("select count(*) from kg_subscribe_outbox",Integer.class));
        service.drain();assertEquals(1,quota());verify(wx,times(1)).deliverSubscribeMessage(anyString(),anyString(),anyString(),anyMap());
    }
    @Test void conversationMutedAfterEnqueueDoesNotDeliver(){
        long id=queued();sql.update("update kg_subscribe_outbox set event_key='chat:5:10:1:user'");sql.update("update kg_message set biz_type='chat_conv',biz_id=5");
        sql.update("insert into kg_chat_member(conv_id,app_user_id,role,muted) values(5,1,'member','1')");
        service.dispatch(id);assertEquals("skipped",status());assertEquals(2,quota());verifyNoInteractions(wx);
    }
    @Test void personalAccountsDoNotGetStaffOnlyConsentTemplates(){
        sql.update("insert into kg_subscribe_template(biz_key,title,template_id,fields) values('ops_daily','日报','staff-template','thing1=title')");
        assertEquals(1,((List<?>)subscriptions.prepare(1L).get("rows")).size());
    }

}
