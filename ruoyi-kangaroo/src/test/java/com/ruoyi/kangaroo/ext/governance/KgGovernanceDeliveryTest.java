package com.ruoyi.kangaroo.ext.governance;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.governance.KgGovernanceExecutionTest.*;
import org.junit.jupiter.api.*;
import com.alibaba.fastjson2.*;
import java.util.*;
import com.ruoyi.kangaroo.config.KangarooProperties;
class KgGovernanceDeliveryTest {
    @Test void concurrentWorkersClaimOnceAndTransportRunsOutsideTransaction() throws Exception {
        org.springframework.aop.framework.ProxyFactory factory=new org.springframework.aop.framework.ProxyFactory(service);factory.setProxyTargetClass(true);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(tx.getTransactionManager(),new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        KgGovernanceDelivery proxy=(KgGovernanceDelivery)factory.getProxy();field(service,"self",proxy);
        when(provider.send(anyString(),anyString(),anyString())).thenAnswer(i->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());return JSON.parseObject("{\"SendStatusSet\":[{\"Code\":\"Ok\",\"SerialNo\":\"ONCE\"}]}");});
        long id=enqueue();java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{java.util.concurrent.Future<?> a=pool.submit(()->proxy.dispatch(id)),b=pool.submit(()->proxy.dispatch(id));a.get();b.get();}finally{pool.shutdownNow();}
        verify(provider,times(1)).send(anyString(),anyString(),anyString());assertEquals("accepted",status());
    }
    KgGovernanceDelivery service;TencentGovernanceSms provider;
    @BeforeAll static void init(){setup();}
    @BeforeEach void reset(){new KgGovernanceExecutionTest().reset();db.update("delete from kg_governance_sms",p());db.update("delete from kg_notify_pref",p());provider=mock(TencentGovernanceSms.class);when(provider.ready()).thenReturn(true);when(provider.appId()).thenReturn("test-app");when(provider.template()).thenReturn("test-template");service=new KgGovernanceDelivery();field(service,"db",db);field(service,"sms",provider);field(service,"self",service);field(service,"properties",new KangarooProperties());db.update("update kg_app_user set phone='13800000000',phone_verified_time=now() where user_id=1",p());run(()->service.consent(1L,true));}
    long enqueue(){run(()->service.enqueue(1L,100L,"merchant_case"));return db.count("select sms_id from kg_governance_sms where msg_id=100",p());}
    String status(){return (String)db.one("select status from kg_governance_sms where msg_id=100",p()).get("status");}
    @Test void disabledChannelIsVisibleAndNeverSent(){when(provider.ready()).thenReturn(false);long id=enqueue();service.dispatch(id);assertEquals("blocked",status());verify(provider,never()).template();}
    @Test void consentCanBeRevokedBeforeDispatch(){long id=enqueue();run(()->service.consent(1L,false));service.dispatch(id);assertEquals("skipped",status());}
    @Test void unverifiedNumberCannotOptIn(){db.update("update kg_app_user set phone_verified_time=null where user_id=1",p());assertThrows(com.ruoyi.common.exception.ServiceException.class,()->run(()->service.consent(1L,true)));long id=enqueue();service.dispatch(id);assertEquals("skipped",status());}
    @Test void rollbackCreatesNoExternalTask(){assertThrows(IllegalStateException.class,()->run(()->{service.enqueue(1L,100L,"merchant_case");throw new IllegalStateException();}));assertEquals(0,db.count("select count(*) from kg_governance_sms",p()));}
    @Test void acceptedNeedsMatchingReceiptAndDoesNotResend()throws Exception{when(provider.send(anyString(),anyString(),anyString())).thenReturn(JSON.parseObject("{\"SendStatusSet\":[{\"Code\":\"Ok\",\"SerialNo\":\"S1\"}]}"));long id=enqueue();enqueue();service.dispatch(id);service.dispatch(id);assertEquals("accepted",status());verify(provider,times(1)).send(anyString(),anyString(),anyString());Map<String,Object> row=db.one("select * from kg_governance_sms where sms_id=:i",p("i",id));when(provider.receipt(anyString(),anyString(),anyLong())).thenReturn(JSON.parseObject("{\"PullSmsSendStatusSet\":[{\"SerialNo\":\"OTHER\",\"PhoneNumber\":\"+8613800000000\",\"ReportStatus\":\"SUCCESS\"}]}"));service.reconcile(row);assertEquals("accepted",status());when(provider.receipt(anyString(),anyString(),anyLong())).thenReturn(JSON.parseObject("{\"PullSmsSendStatusSet\":[{\"SerialNo\":\"S1\",\"PhoneNumber\":\"+8613800000000\",\"ReportStatus\":\"SUCCESS\",\"UserReceiveTime\":1794000000}]}"));service.reconcile(row);assertEquals("delivered",status());assertNotNull(db.one("select received_at from kg_governance_sms where sms_id=:i",p("i",id)).get("receivedAt"));}
    @Test void interruptionNeverAutomaticallyResends()throws Exception{when(provider.send(anyString(),anyString(),anyString())).thenThrow(new java.io.IOException("test failure"));long id=enqueue();service.dispatch(id);service.dispatch(id);assertEquals("unknown",status());verify(provider,times(1)).send(anyString(),anyString(),anyString());}
    @Test void rejectionAndCarrierFailureAreNotDelivery()throws Exception{when(provider.send(anyString(),anyString(),anyString())).thenReturn(JSON.parseObject("{\"Error\":{\"Code\":\"UnauthorizedOperation.RequestPermissionDeny\"}}"));service.dispatch(enqueue());assertEquals("failed",status());}
    @Test void unrelatedMessagesAreNotSms(){run(()->service.enqueue(1L,101L,"job_subscribe"));assertEquals(0,db.count("select count(*) from kg_governance_sms",p()));}
    @Test void signingBindsPayloadAndDoesNotExposeSecret()throws Exception{String a=TencentGovernanceSms.authorization("test-id","test-secret","SendSms",1794000000L,"{}");assertTrue(a.contains("Credential=test-id/2026-11-06/sms/tc3_request"));assertFalse(a.contains("test-secret"));assertNotEquals(a,TencentGovernanceSms.authorization("test-id","test-secret","SendSms",1794000000L,"{\"x\":1}"));}
}
