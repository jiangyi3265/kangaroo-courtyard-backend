package com.ruoyi.kangaroo.ext.governance;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.AppTokenService;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;
import static org.mockito.Mockito.*;
class KgMerchantSanctionsExecutionTest {
    static KgMerchantSanctions service;static KgJdbc db;
    static long review;static KgMerchantSettlement settlement;static KgMerchantCaseEffects effects;static AppTokenService tokens;static IKgStaffGrantService grants;
    @BeforeAll static void init(){KgGovernanceExecutionTest.setup();db=KgGovernanceExecutionTest.db;service=new KgMerchantSanctions();tokens=mock(AppTokenService.class);grants=mock(IKgStaffGrantService.class);KgGovernanceExecutionTest.field(service,"db",db);KgGovernanceExecutionTest.field(service,"messages",KgGovernanceExecutionTest.messages);KgGovernanceExecutionTest.field(service,"tokens",tokens);KgGovernanceExecutionTest.field(service,"grants",grants);
    settlement=new KgMerchantSettlement();KgGovernanceExecutionTest.field(settlement,"db",db);KgGovernanceExecutionTest.field(service,"settlements",settlement);
    effects=new KgMerchantCaseEffects();KgGovernanceExecutionTest.field(effects,"db",db);
    com.ruoyi.kangaroo.ext.user.KgMembershipOperations ops=new com.ruoyi.kangaroo.ext.user.KgMembershipOperations();KgGovernanceExecutionTest.field(ops,"db",db);KgGovernanceExecutionTest.field(ops,"messages",KgGovernanceExecutionTest.messages);
    com.ruoyi.kangaroo.ext.user.KgMembershipRewards rewards=new com.ruoyi.kangaroo.ext.user.KgMembershipRewards();KgGovernanceExecutionTest.field(rewards,"db",db);KgGovernanceExecutionTest.field(rewards,"ledger",KgGovernanceExecutionTest.ledger);KgGovernanceExecutionTest.field(ops,"rewards",rewards);
    KgGovernanceExecutionTest.field(effects,"members",ops);KgGovernanceExecutionTest.field(service,"effects",effects);}
    @BeforeEach void reset(){for(String t:Arrays.asList("kg_merchant_settlement_log","kg_merchant_settlement","kg_merchant_case_review"))db.update("delete from "+t,p());db.update("delete from kg_merchant_case_log",p());db.update("delete from kg_merchant_case",p());new KgGovernanceExecutionTest().reset();org.mockito.Mockito.reset(tokens,grants);review=db.insert("kg_review",p("app_user_id",1L,"store_id",2L,"target_type","stay","target_id",1L,"rating",5,"content","实际测试的异常评价证据"));}
    static void run(Runnable r){KgGovernanceExecutionTest.run(r);}
    long create(String type){return KgGovernanceExecutionTest.in(()->service.create(p("storeId",2L,"reviewIds",Arrays.asList(review),"requestKey","merchant_test_01","violation",type,"facts","已核实组织虚假评价，相关记录经人工逐项确认。","basis","关联评价编号与原始材料已由总部核实并留档。"),"admin:test"));}
    void elapsed(long c){db.update("update kg_merchant_case set appeal_deadline=date_sub(now(),interval 1 day) where case_id=:c",p("c",c));}
    void execute(long c){run(()->service.decide(c,p("execute",true,"note","已核对商家申诉及原始证据，维持事实认定和合作处理。"),"admin:test"));}
    String status(){return (String)db.one("select status from kg_store where store_id=2",p()).get("status");}
    @Test void noticeIsIdempotentAndDoesNotDisableStore(){long c=create("positive_fraud");assertEquals(c,create("positive_fraud"));assertEquals("0",status());assertEquals(1,db.count("select count(*) from kg_merchant_case",p()));assertThrows(ServiceException.class,()->execute(c));assertEquals("0",status());}
    @Test void merchantCanAppealButOtherStoreCannotReadOrAppeal(){long c=create("positive_fraud");assertThrows(ServiceException.class,()->service.detail(4L,c,false));assertThrows(ServiceException.class,()->run(()->service.appeal(4L,c,p("note","与本门店无关的账号不能查看或者提交申诉。"))));run(()->service.appeal(2L,c,p("note","请求复查涉事评价的关联记录，现提交相应事实说明。")));assertEquals("appealed",service.detail(2L,c,false).get("status"));assertThrows(ServiceException.class,()->run(()->service.appeal(2L,c,p("note","同一个申诉不允许重复提交造成重复处理。"))));}
    @Test void decisionLocksStoreAndLeavesFeeUnpaid(){long c=create("positive_fraud");elapsed(c);execute(c);assertEquals("1",status());assertEquals("suspended",service.detail(2L,c,false).get("status"));assertEquals("settlement_linked",service.detail(2L,c,false).get("feeState"));assertThrows(ServiceException.class,()->execute(c));assertThrows(ServiceException.class,()->run(()->service.assertCanEnable(2L)));assertEquals(0,db.count("select count(*) from kg_point_entry",p()));}
    @Test void thirtyDaysExpireRestoresOnlyStateAppliedByThisCase(){long c=create("positive_fraud");elapsed(c);execute(c);run(()->service.expire(c));assertEquals("1",status());db.update("update kg_merchant_case set ends_at=date_sub(now(),interval 1 second) where case_id=:c",p("c",c));run(()->service.expire(c));assertEquals("0",status());run(()->service.expire(c));assertEquals(1,db.count("select count(*) from kg_merchant_case_log where action='expire'",p()));}
    @Test void expiryNeverReenablesAnIndependentlyDisabledStore(){long c=create("positive_fraud");elapsed(c);execute(c);db.update("update kg_store set update_by='manual-admin' where store_id=2",p());db.update("update kg_merchant_case set ends_at=date_sub(now(),interval 1 second) where case_id=:c",p("c",c));run(()->service.expire(c));assertEquals("1",status());assertEquals("expired_review",service.detail(2L,c,false).get("status"));run(()->service.assertCanEnable(2L));}
    @Test void blacklistDoesNotExpire(){long c=create("competitor_attack");elapsed(c);execute(c);run(()->service.expire(c));assertEquals("blacklisted",service.detail(2L,c,false).get("status"));assertEquals("1",status());assertThrows(ServiceException.class,()->run(()->service.assertCanEnable(2L)));}
    @Test void appealCanBeCancelledWithoutChangingCooperation(){long c=create("collusion");run(()->service.appeal(2L,c,p("note","请重新核对具体订单及评价内容，商家提供了核实线索。")));run(()->service.decide(c,p("execute",false,"note","经再次核实不能证明商家参与刷评，撤销本次复核通知。"),"admin:test"));assertEquals("cancelled",service.detail(2L,c,false).get("status"));assertEquals("0",status());assertNotNull(service.list(2L,1,false));assertNotNull(service.stores());}
    @Test void requireAnAccountWhoCanReceiveAndAppeal(){db.update("delete from kg_staff_grant where store_id=2",p());assertThrows(ServiceException.class,()->create("positive_fraud"));assertEquals(0,db.count("select count(*) from kg_merchant_case",p()));}
    @Test void fullLengthFactsAndBasisArePreservedInAudit(){String text=KgGovernanceExecutionTest.repeat("核实",1000);long c=KgGovernanceExecutionTest.in(()->service.create(p("storeId",2L,"reviewIds",Arrays.asList(review),"requestKey","full_note_01","violation","positive_fraud","facts",text,"basis",text),"admin:test"));assertEquals(text,service.detail(2L,c,false).get("facts"));assertTrue(db.count("select char_length(note) from kg_merchant_case_log where case_id=:c",p("c",c))>4000);}
    @Test void actualStoreEditProxyCannotBypassSanction(){long c=create("positive_fraud");elapsed(c);execute(c);com.ruoyi.kangaroo.service.impl.KgStoreServiceImpl target=org.mockito.Mockito.mock(com.ruoyi.kangaroo.service.impl.KgStoreServiceImpl.class);KgMerchantSanctionGuard guard=new KgMerchantSanctionGuard();KgGovernanceExecutionTest.field(guard,"sanctions",service);KgGovernanceExecutionTest.field(guard,"manager",KgGovernanceExecutionTest.tx.getTransactionManager());org.springframework.aop.aspectj.annotation.AspectJProxyFactory factory=new org.springframework.aop.aspectj.annotation.AspectJProxyFactory(target);factory.setProxyTargetClass(true);factory.addAspect(guard);com.ruoyi.kangaroo.service.impl.KgStoreServiceImpl proxy=factory.getProxy();com.ruoyi.kangaroo.domain.KgStore store=new com.ruoyi.kangaroo.domain.KgStore();store.setStoreId(2L);store.setStatus("0");assertThrows(ServiceException.class,()->proxy.updateKgStore(store));org.mockito.Mockito.verify(target,org.mockito.Mockito.never()).updateKgStore(org.mockito.ArgumentMatchers.any());store.setStatus("1");proxy.updateKgStore(store);org.mockito.Mockito.verify(target).updateKgStore(store);}
    @Test void cooperationChangesRefreshAllAffectedRolesButNotOtherStores(){
        new KgGovernanceExecutionTest().grant(4L,2L,"staff");
        long c=create("positive_fraud");verifyNoInteractions(tokens);elapsed(c);execute(c);
        verify(tokens).refreshGrants(eq(2L),anyList());verify(tokens).refreshGrants(eq(4L),anyList());verifyNoMoreInteractions(tokens);
        org.mockito.Mockito.reset(tokens);
        db.update("update kg_merchant_case set ends_at=date_sub(now(),interval 1 second) where case_id=:c",p("c",c));run(()->service.expire(c));
        verify(tokens).refreshGrants(eq(2L),anyList());verify(tokens).refreshGrants(eq(4L),anyList());verifyNoMoreInteractions(tokens);
        org.mockito.Mockito.reset(tokens);run(()->service.expire(c));verifyNoInteractions(tokens);
    }
    @Test void unchangedManualDisableNeverRefreshesOrGrantsAccessAtExpiry(){
        long c=create("positive_fraud");elapsed(c);execute(c);org.mockito.Mockito.reset(tokens);
        db.update("update kg_store set update_by='other-admin' where store_id=2",p());
        db.update("update kg_merchant_case set ends_at=date_sub(now(),interval 1 second) where case_id=:c",p("c",c));run(()->service.expire(c));
        assertEquals("1",status());verifyNoInteractions(tokens);
    }
}
