package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgActivityService;
import com.ruoyi.kangaroo.ext.staff.*;
import com.ruoyi.kangaroo.security.AppLoginUser;

/** Financial and inventory regressions run only against an isolated local schema. */
class KgCommerceCompletionTest {
    static KgJdbc db;
    static TransactionTemplate tx;
    static KgPointLedger ledger;
    static KgPointMallService mall;
    static KgPublicationFeeService fees;
    static KgActivityService activities;

    @BeforeAll static void setup() {
        String name=System.getenv("KG_MEMBER_TEST_DB");
        assumeTrue(name!=null && name.matches("kg_member_exec_[a-f0-9]{32}"));
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/"+name
                +"?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&sessionVariables=time_zone=%27%2B08:00%27",
                System.getenv("KG_MEMBER_TEST_USER"),System.getenv("KG_TEST_DB_PASSWORD"));
        db=new KgJdbc(ds);
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        ledger=new KgPointLedger(); ReflectionTestUtils.setField(ledger,"db",db);
        KgMemberWallet wallet=new KgMemberWallet(); ReflectionTestUtils.setField(wallet,"db",db);
        mall=new KgPointMallService(); ReflectionTestUtils.setField(mall,"db",db);
        ReflectionTestUtils.setField(mall,"ledger",ledger); ReflectionTestUtils.setField(mall,"wallet",wallet);
        fees=new KgPublicationFeeService(); ReflectionTestUtils.setField(fees,"db",db);
        activities=new KgActivityService(); ReflectionTestUtils.setField(activities,"db",db);
    }

    @BeforeEach void reset() {
        for(String table:Arrays.asList("kg_point_goods_order","kg_point_goods","kg_publication_fee_log","kg_publication_fee",
                "kg_member_restriction","kg_point_entry","kg_activity_signup","kg_activity","kg_job","kg_store","kg_app_user")) db.update("delete from "+table,p());
        db.update("delete from kg_idempotency where biz_type='publication_task'",p());
        db.update("update kg_member_execution set enabled='1' where id=1",p());
        db.update("update kg_publication_fee_config set enabled='0',amount_fen=0,version=0 where id=1",p());
        for(long id=1;id<=2;id++) {
            db.insert("kg_app_user",p("user_id",id,"openid","commerce-"+id,"nickname","测试用户"));
            ledger.award(id,100,"测试积分","fixture",id);
        }
    }

    <T> T run(Supplier<T> work) { return tx.execute(status->work.get()); }
    void action(Runnable work) { tx.execute(status->{work.run();return null;}); }
    Map<String,Object> product(String mode,int stock) { return p("title","测试商品","description","测试商品规格及领取安排","pointsCost",60,"stock",stock,"deliveryMode",mode,"pickupPlace","测试门店","status","online","cover",""); }
    long goods(String mode,int stock) { return run(()->mall.save(null,product(mode,stock),"test")); }
    Map<String,Object> body(String key) { return p("requestKey",key,"pointsCost",60,"recipient","领取人","phone","13800000000","address","测试市测试区测试街道1号"); }
    long redeem(long user,long goods,String key) { return ((Number)run(()->mall.redeem(user,goods,body(key))).get("orderId")).longValue(); }
    Map<String,Object> order(long id) { return db.one("select * from kg_point_goods_order where order_id=:id",p("id",id)); }

    @Test void snapshotAndRequestRetryUseOneDebit() {
        long g=goods("shipping",2),id=redeem(1,g,"request_001");
        assertEquals(id,redeem(1,g,"request_001"));
        db.update("update kg_point_goods set title='改名',points_cost=80 where goods_id=:g",p("g",g));
        assertEquals("测试商品",order(id).get("title"));
        assertEquals(60,((Number)order(id).get("pointsCost")).intValue());
        assertEquals(40,ledger.totals(1L).balance());
        assertEquals(100,ledger.totals(1L).earned);
        assertEquals(1,db.count("select stock from kg_point_goods where goods_id=:g",p("g",g)));
    }

    @Test void cancellationRefundsOnceWithoutRaisingGrowth() {
        long g=goods("pickup",1),id=redeem(1,g,"request_001");
        action(()->mall.command(id,1L,"cancel",p(),"user:1"));
        action(()->mall.command(id,1L,"cancel",p(),"user:1"));
        assertEquals(100,ledger.totals(1L).balance());
        assertEquals(100,ledger.totals(1L).earned);
        assertEquals("cancelled",order(id).get("status"));
        assertEquals(1,db.count("select stock from kg_point_goods where goods_id=:g",p("g",g)));
    }

    @Test void insufficientPointsAndPriceChangeRollbackInventory() {
        long g=goods("shipping",2);
        Map<String,Object> stale=body("request_001"); stale.put("pointsCost",50);
        assertThrows(ServiceException.class,()->run(()->mall.redeem(1L,g,stale)));
        redeem(1,g,"request_001");
        assertThrows(ServiceException.class,()->redeem(1,g,"request_002"));
        assertEquals(1,db.count("select count(*) from kg_point_goods_order",p()));
        assertEquals(1,db.count("select stock from kg_point_goods where goods_id=:g",p("g",g)));
    }

    @Test void shippingCanOnlyBeReceivedByOwnerAndCannotBeCancelledAfterDispatch() {
        long g=goods("shipping",1),id=redeem(1,g,"request_001");
        assertThrows(ServiceException.class,()->action(()->mall.command(id,2L,"cancel",p(),"user:2")));
        assertThrows(ServiceException.class,()->action(()->mall.command(id,1L,"ship",p(),"user:1")));
        action(()->mall.command(id,null,"ship",p("carrier","测试快递","trackingNo","TEST123"),"admin:test"));
        assertThrows(ServiceException.class,()->action(()->mall.command(id,1L,"cancel",p(),"user:1")));
        action(()->mall.command(id,1L,"receive",p(),"user:1"));
        assertEquals("completed",order(id).get("status"));
        assertEquals(40,ledger.totals(1L).balance());
        assertEquals(0,db.count("select count(*) from kg_point_goods_order where app_user_id=2",p()));
    }

    @Test void pickupRequiresAdminVerificationAndClosedAccountCanReceiveRefund() {
        long g=goods("pickup",2),id=redeem(1,g,"request_001");
        assertThrows(ServiceException.class,()->action(()->mall.command(id,1L,"pickup",p("note","已领取"),"user:1")));
        action(()->mall.command(id,null,"pickup",p("note","已核对姓名与兑换编号"),"admin:test"));
        assertEquals("completed",order(id).get("status"));
        long second=redeem(2,g,"request_002");
        db.update("update kg_app_user set status='1' where user_id=2",p());
        action(()->mall.command(second,null,"cancel",p("note","商品暂时无法供应"),"admin:test"));
        assertEquals(100,ledger.totals(2L).balance());
    }

    @Test void inventoryChangeRejectsStaleAdminEditor() {
        long g=goods("shipping",2);
        Map<String,Object> edit=product("shipping",10);edit.put("version",0);
        redeem(1,g,"request_001");
        assertThrows(ServiceException.class,()->run(()->mall.save(g,edit,"test")));
        assertEquals(1,db.count("select stock from kg_point_goods where goods_id=:g",p("g",g)));
    }

    @Test void disabledMembershipCannotRedeemAndFractionalStockIsRejected() {
        long g=goods("pickup",1);
        db.update("update kg_member_execution set enabled='0' where id=1",p());
        assertThrows(ServiceException.class,()->redeem(1,g,"request_001"));
        Map<String,Object> b=product("pickup",1);b.put("stock",1.5);
        assertThrows(ServiceException.class,()->run(()->mall.save(null,b,"test")));
        assertThrows(ServiceException.class,()->KgPointMallService.integer("NaN",0,10,"库存"));
    }

    @Test void lastItemCannotBeOversold() throws Exception {
        long g=goods("shipping",1);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> results=new ArrayList<>();
            for(long uid=1;uid<=2;uid++) { final long user=uid;
                results.add(pool.submit(()->{start.await();try{redeem(user,g,"request_001");return true;}catch(ServiceException e){return false;}}));
            }
            start.countDown();int success=0;
            for(Future<Boolean> result:results) if(result.get(10,TimeUnit.SECONDS)) success++;
            assertEquals(1,success);
            assertEquals(0,db.count("select stock from kg_point_goods where goods_id=:g",p("g",g)));
            assertEquals(1,db.count("select count(*) from kg_point_goods_order",p()));
        } finally { pool.shutdownNow(); }
    }

    @Test void simultaneousRetriesHaveOneOrder() throws Exception {
        long g=goods("shipping",2);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<Long> one=pool.submit(()->redeem(1,g,"request_001"));
            Future<Long> two=pool.submit(()->redeem(1,g,"request_001"));
            assertEquals(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS));
            assertEquals(40,ledger.totals(1L).balance());
        } finally { pool.shutdownNow(); }
    }

    @Test void simultaneousRedemptionAndCancellationKeepBalanceAndStockConsistent() throws Exception {
        long g = goods("shipping", 2);
        long previous = redeem(1, g, "request_001");
        ledger.award(1L, 100, "测试补充积分", "fixture", 3L);
        CountDownLatch redemptionHasGoods = new CountDownLatch(1);
        CountDownLatch cancellationHasLedger = new CountDownLatch(1);
        CountDownLatch resumeRedemption = new CountDownLatch(1);
        KgPointLedger controlledLedger = spy(ledger);
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("mall-redeem-test")) {
                redemptionHasGoods.countDown();
                assertTrue(resumeRedemption.await(5, TimeUnit.SECONDS));
            }
            Object result = invocation.callRealMethod();
            if (Thread.currentThread().getName().equals("mall-cancel-test")) cancellationHasLedger.countDown();
            return result;
        }).when(controlledLedger).lockedTotals(1L);
        ReflectionTestUtils.setField(mall, "ledger", controlledLedger);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Long> redemption = pool.submit(() -> {
                Thread.currentThread().setName("mall-redeem-test");
                return redeem(1, g, "request_002");
            });
            assertTrue(redemptionHasGoods.await(5, TimeUnit.SECONDS));
            Future<?> cancellation = pool.submit(() -> {
                Thread.currentThread().setName("mall-cancel-test");
                action(() -> mall.command(previous, 1L, "cancel", p(), "user:1"));
            });
            // Before the fix cancellation can hold the ledger while redemption holds goods.
            // With account-first locking it waits for redemption before reaching the ledger.
            cancellationHasLedger.await(2, TimeUnit.SECONDS);
            resumeRedemption.countDown();
            long next = redemption.get(10, TimeUnit.SECONDS);
            cancellation.get(10, TimeUnit.SECONDS);
            assertEquals("cancelled", order(previous).get("status"));
            assertEquals("pending", order(next).get("status"));
            assertEquals(140, ledger.totals(1L).balance());
            assertEquals(200, ledger.totals(1L).earned);
            assertEquals(1, db.count("select stock from kg_point_goods where goods_id=:g", p("g", g)));
            assertEquals(1, db.count("select count(*) from kg_point_entry where biz_type='mall_refund'", p()));
        } finally {
            resumeRedemption.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
            ReflectionTestUtils.setField(mall, "ledger", ledger);
        }
    }

    StaffSupport.Ctx merchant() {
        StaffSupport.Ctx c=new StaffSupport.Ctx();c.storeId=10L;c.role="merchant";
        c.me=new AppLoginUser();c.me.setUserId(1L);return c;
    }
    Map<String,Object> taskBody() { return p("title","测试线上任务","publicationRequestKey","publish_001","publicationFeeVersion",1); }
    void enableFee() { action(()->fees.configure(p("enabled","1","amountFen",500,"version",0),"admin:test")); }
    long capture(long job,Map<String,Object> body) { return run(()->{fees.retry(merchant(),body);fees.capture(merchant(),job,body);return db.count("select fee_id from kg_publication_fee where job_id=:j",p("j",job));}); }

    @Test void publicationStartsDisabledAndNeverRetrospectivelyCharges() {
        long id=capture(1,taskBody());enableFee();action(()->fees.published(1L));
        Map<String,Object> record=db.one("select * from kg_publication_fee where fee_id=:id",p("id",id));
        assertEquals("free",record.get("status"));assertEquals(0L,((Number)record.get("amountFen")).longValue());
        action(()->fees.published(999L));assertEquals(1,db.count("select count(*) from kg_publication_fee",p()));
    }

    @Test void publicationUsesQuoteSnapshotAndRetriesDoNotChargeAgain() {
        enableFee();long id=capture(1,taskBody());
        action(()->fees.configure(p("enabled","1","amountFen",900,"version",1),"admin:test"));
        assertEquals(1L,((Number)run(()->fees.retry(merchant(),taskBody())).get("jobId")).longValue());
        action(()->fees.published(1L));action(()->fees.published(1L));action(()->fees.closed(1L));
        assertEquals("due",db.one("select status from kg_publication_fee where fee_id=:id",p("id",id)).get("status"));
        assertEquals(500,db.count("select amount_fen from kg_publication_fee where fee_id=:id",p("id",id)));
    }

    @Test void changedQuoteAndUnpublishedCancellationDoNotCreateDebt() {
        enableFee();Map<String,Object> stale=taskBody();stale.put("publicationFeeVersion",0);
        assertThrows(ServiceException.class,()->capture(1,stale));
        assertEquals(0,db.count("select count(*) from kg_publication_fee",p()));
        capture(1,taskBody());action(()->fees.closed(1L));action(()->fees.published(1L));
        assertEquals("void",db.one("select status from kg_publication_fee where job_id=1",p()).get("status"));
    }

    @Test void receiptNeedsExactAmountAndIsIdempotent() {
        enableFee();long id=capture(1,taskBody());
        Map<String,Object> receipt=p("amountFen",500,"receiptNo","TEST-RECEIPT","note","已核对实际到账","proof","");
        assertThrows(ServiceException.class,()->action(()->fees.command(id,"receive",receipt,"admin:test")));
        action(()->fees.published(1L));receipt.put("amountFen",499);
        assertThrows(ServiceException.class,()->action(()->fees.command(id,"receive",receipt,"admin:test")));
        receipt.put("amountFen",500);action(()->fees.command(id,"receive",receipt,"admin:test"));action(()->fees.command(id,"receive",receipt,"admin:test"));
        assertEquals("paid",db.one("select status from kg_publication_fee where fee_id=:id",p("id",id)).get("status"));
        assertEquals(1,db.count("select count(*) from kg_publication_fee_log where fee_id=:id and action='receive'",p("id",id)));
    }

    @Test void travelCatalogSeparatesApprovedTourismFromDraftsAndUserActivities() {
        for(String[] row:new String[][]{{"travel","merchant","open","厦门"},{"activity","merchant","open","厦门"},{"travel","merchant","pending","厦门"},{"travel","user","open","厦门"},{"travel","yard","open","杭州"}})
            db.update("insert into kg_activity(title,product_kind,source,status,city,start_time,end_time,capacity) values('测试场次',:k,:s,:status,:c,date_add(now(),interval 2 day),date_add(now(),interval 3 day),10)",p("k",row[0],"s",row[1],"status",row[2],"c",row[3]));
        assertEquals(1L,activities.travel("厦门",1).get("total"));
        assertEquals(2L,activities.travel("",1).get("total"));
        @SuppressWarnings("unchecked") List<Map<String,Object>> rows=(List<Map<String,Object>>)activities.travel("厦门",1).get("rows");
        assertEquals("travel",rows.get(0).get("productKind"));
    }
}
