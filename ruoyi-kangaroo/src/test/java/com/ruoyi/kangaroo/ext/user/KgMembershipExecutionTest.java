package com.ruoyi.kangaroo.ext.user;

import java.util.*;
import java.util.concurrent.*;
import java.time.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgCommunityGuard;
import com.ruoyi.common.exception.ServiceException;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;

/** Real MySQL transactions, no production data or payment/message integrations. */
class KgMembershipExecutionTest {
    static KgJdbc db;static TransactionTemplate tx;static KgMemberWallet wallet;static KgMembershipRewards rewards;static KgMembershipOperations ops;static KgPointLedger ledger;
    @BeforeAll static void setup(){
        String name=System.getenv("KG_MEMBER_TEST_DB");assumeTrue(name!=null&&name.matches("kg_member_exec_[a-f0-9]{32}"),"requires isolated local database harness");
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/"+name+"?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai",System.getenv("KG_MEMBER_TEST_USER"),System.getenv("KG_TEST_DB_PASSWORD"));
        db=new KgJdbc(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        ledger=new KgPointLedger();field(ledger,"db",db);wallet=new KgMemberWallet();field(wallet,"db",db);field(wallet,"ledger",ledger);field(wallet,"members",new KgMemberService(db));
        rewards=new KgMembershipRewards();field(rewards,"db",db);field(rewards,"ledger",ledger);field(rewards,"wallet",wallet);KgCommunityGuard guard=mock(KgCommunityGuard.class);when(guard.check(anyString())).thenReturn("pass");field(rewards,"guard",guard);
        ops=new KgMembershipOperations();field(ops,"db",db);field(ops,"wallet",wallet);field(ops,"rewards",rewards);field(ops,"ledger",ledger);field(ops,"messages",mock(com.ruoyi.kangaroo.service.IKgMessageService.class));field(wallet,"messages",mock(com.ruoyi.kangaroo.service.IKgMessageService.class));
    }
    static void field(Object o,String n,Object v){ReflectionTestUtils.setField(o,n,v);}
    static <T>T transaction(Supplier<T> fn){return tx.execute(s->fn.get());}
    static void run(Runnable fn){transaction(()->{fn.run();return null;});}
    @BeforeEach void reset(){
        db.update("set foreign_key_checks=0",p());
        for(String t:Arrays.asList("kg_member_benefit","kg_member_audit","kg_member_case","kg_member_restriction","kg_member_request","kg_member_gift","kg_member_wallet","kg_point_entry","kg_member_pin","kg_member_hot","kg_member_review_check","kg_member_calendar_year","kg_member_holiday","kg_activity_signup","kg_activity","kg_review","kg_post","kg_stay_order","kg_stay_room_type","kg_stay","kg_application","kg_job","kg_app_user"))db.update("delete from "+t,p());
        db.update("update kg_member_execution set starts_at='2026-01-01 00:00:00',enabled='1' where id=1",p());
        for(long uid=1;uid<=3;uid++)db.insert("kg_app_user",p("user_id",uid,"openid","member_test_"+uid,"nickname","会员测试"+uid,"status","0","del_flag","0"));
    }
    void points(Long uid,int n){ledger.award(uid,n,"测试初始余额","fixture",uid);}
    List<Map<String,Object>> rows(Long uid){return db.list("select * from kg_member_wallet where app_user_id=:u order by wallet_id",p("u",uid));}
    @Test void freeStayOrderCreatesWithoutPaymentAndCancellationReleasesWhileIssuanceDisabled(){
        points(1L,120);run(()->wallet.redeem(1L,p("requestId","actual_order_test","roomCode","night4","nights",1)));
        long room=room();Long voucher=KgJdbc.asLong(rows(1L).get(0).get("walletId"));
        run(()->ops.calendar(p("year",KgMembershipPolicy.year(),"dates",KgMembershipPolicy.year()+"-01-01","confirmed",true,"note","隔离测试日历，不用于实际营业"),"admin:test"));
        com.ruoyi.kangaroo.ext.stay.KgStayOrderService orders=new com.ruoyi.kangaroo.ext.stay.KgStayOrderService();
        com.ruoyi.kangaroo.ext.stay.KgStayBrowseService browse=mock(com.ruoyi.kangaroo.ext.stay.KgStayBrowseService.class);
        com.ruoyi.kangaroo.ext.pay.KgPayService pay=mock(com.ruoyi.kangaroo.ext.pay.KgPayService.class);
        field(orders,"db",db);field(orders,"browse",browse);field(orders,"memberWallet",wallet);field(orders,"pay",pay);
        field(orders,"support",mock(com.ruoyi.kangaroo.ext.staff.StaffSupport.class));
        field(orders,"activitySelections",mock(com.ruoyi.kangaroo.ext.stay.KgStayActivitySelection.class));
        Map<String,Object> q=quote(1);q.put("bookable",true);q.put("payFen",10000L);q.put("stay",p("stayId",1L,"storeId",1L,"stayName","测试小院","typeName","测试房型","city","测试城市"));q.put("policy",p());
        Map<String,Object> notice=com.ruoyi.kangaroo.ext.stay.StayBookingNotice.snapshot(null);q.put("agreement",notice);
        when(browse.quote(anyLong(),anyString(),anyString(),anyInt(),isNull())).thenReturn(q);
        Map<String,Object> body=p("roomTypeId",room,"checkIn",q.get("checkIn"),"checkOut",q.get("checkOut"),"guests",1,"guestName","测试入住人","guestPhone","13800000000","agreementVersion",notice.get("version"),"memberWalletIds",Collections.singletonList(voucher),"expectedPayFen",0);
        Map<String,Object> result=transaction(()->orders.create(1L,body));
        assertEquals("confirmed",result.get("status"));assertEquals(0,((Number)result.get("payFen")).longValue());verifyNoInteractions(pay);
        assertEquals("reserved",rows(1L).get(0).get("status"));
        assertThrows(ServiceException.class,()->run(()->orders.cancel(2L,(String)result.get("orderNo"))));
        db.update("update kg_member_execution set enabled='0' where id=1",p());
        run(()->orders.cancel(1L,(String)result.get("orderNo")));
        assertEquals("available",rows(1L).get(0).get("status"));
        assertEquals(0,db.count("select sum(occupied) from kg_stay_calendar where room_type_id=:r",p("r",room)));
        assertEquals("cancelled",db.one("select status from kg_stay_order where order_no=:n",p("n",result.get("orderNo"))).get("status"));verifyNoInteractions(pay);
    }
    @Test void editingHistoricalStayDoesNotMintNewRulesPoints(){stay();db.update("update kg_member_execution set starts_at='2026-03-01' where id=1",p());run(()->rewards.sync(1L));assertEquals(0,ledger.totals(1L).balance());}
    @Test void httpWalletContractUsesAuthenticatedAccountNotSubmittedUserId() throws Exception {
        points(1L,60);
        com.ruoyi.kangaroo.controller.app.AppMemberExecutionController controller=new com.ruoyi.kangaroo.controller.app.AppMemberExecutionController();
        field(controller,"wallet",wallet);field(controller,"rewards",rewards);field(controller,"operations",ops);
        org.springframework.test.web.servlet.MockMvc mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        com.ruoyi.kangaroo.security.AppLoginUser login=new com.ruoyi.kangaroo.security.AppLoginUser();login.setUserId(1L);
        com.ruoyi.kangaroo.security.AppUserContext.set(login);
        try {
            String response=transaction(()->{try{return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/app/member/wallet/redeem").contentType("application/json").content("{\"roomCode\":\"night4\",\"nights\":1,\"requestId\":\"http_redeem_01\",\"appUserId\":2}")).andReturn().getResponse().getContentAsString();}catch(Exception ex){throw new RuntimeException(ex);}});
            com.alibaba.fastjson2.JSONObject result=com.alibaba.fastjson2.JSON.parseObject(response);
            assertEquals(200,result.getIntValue("code"));assertEquals(0,result.getJSONObject("data").getIntValue("balance"));
            assertEquals(1,rows(1L).size());assertTrue(rows(2L).isEmpty());
            assertTrue(controller.getClass().isAnnotationPresent(com.ruoyi.kangaroo.security.AppAuth.class));
        } finally {com.ruoyi.kangaroo.security.AppUserContext.remove();}
    }
    @Test void fixedRewardBoundaries(){assertEquals(0,KgMembershipPolicy.servicePoints("volunteer",29));assertEquals(20,KgMembershipPolicy.servicePoints("volunteer",30));assertEquals(20,KgMembershipPolicy.servicePoints("volunteer",60));assertEquals(3,KgMembershipPolicy.servicePoints("parttime",1));assertEquals(15,KgMembershipPolicy.servicePoints("parttime",3));assertEquals(15,KgMembershipPolicy.servicePoints("training",0));assertEquals(60,KgMembershipPolicy.nightCost("night4"));}
    @Test void claimReplayAndUpgradeOnlyTopUp(){points(1L,90);run(()->wallet.claim(1L));assertEquals(4,rows(1L).size());run(()->wallet.claim(1L));assertEquals(4,rows(1L).size());ledger.award(1L,310,"升级","fixture_more",1L);run(()->wallet.claim(1L));List<Map<String,Object>> all=rows(1L);assertEquals(3,all.stream().filter(w->"night4".equals(w.get("benefitCode"))).count());assertEquals(15000,db.count("select sum(amount_fen) from kg_member_wallet where app_user_id=1 and benefit_code='subsidy'",p()));assertEquals(1,all.stream().filter(w->"merch".equals(w.get("benefitCode"))).count());}
    @Test void redeemUndoDoesNotCreateGrowth(){points(1L,120);run(()->wallet.redeem(1L,p("requestId","redeem_0001","roomCode","night4","nights",1)));assertEquals(60,ledger.totals(1L).balance());assertEquals(120,ledger.totals(1L).earned);run(()->wallet.redeem(1L,p("requestId","redeem_0001","roomCode","night4","nights",1)));assertEquals(1,rows(1L).size());Long id=KgJdbc.asLong(rows(1L).get(0).get("walletId"));run(()->wallet.undoRedeem(1L,id));run(()->wallet.undoRedeem(1L,id));assertEquals(120,ledger.totals(1L).balance());assertEquals(120,ledger.totals(1L).earned);}
    @Test void concurrentSpendingCannotOverdraw()throws Exception{points(1L,60);ExecutorService pool=Executors.newFixedThreadPool(2);try{List<Future<Boolean>> rs=new ArrayList<>();for(int i=0;i<2;i++){String key="concurrent_"+i;rs.add(pool.submit(()->{try{return transaction(()->{wallet.redeem(1L,p("requestId",key,"roomCode","night4","nights",1));return true;});}catch(ServiceException e){return false;}}));}int success=0;for(Future<Boolean> f:rs)if(f.get())success++;assertEquals(1,success);assertEquals(0,ledger.totals(1L).balance());}finally{pool.shutdownNow();}}
    @Test void offlineRequestRejectReleaseThenProofRequiredForSingleFulfillment(){points(1L,90);run(()->wallet.claim(1L));Long id=KgJdbc.asLong(rows(1L).stream().filter(w->"subsidy".equals(w.get("benefitCode"))).findFirst().get().get("walletId"));Long request=transaction(()->KgJdbc.asLong(wallet.request(1L,p("requestId","request_0001","walletId",id,"content","测试出游补贴订单说明")).get("requestId")));assertThrows(ServiceException.class,()->run(()->wallet.fulfill(request,true,"核对订单","","admin:test")));run(()->wallet.fulfill(request,false,"资料不足","","admin:test"));assertEquals("available",db.one("select status from kg_member_wallet where wallet_id=:w",p("w",id)).get("status"));Long second=transaction(()->KgJdbc.asLong(wallet.request(1L,p("requestId","request_0002","walletId",id,"content","补充完整订单与凭证")).get("requestId")));run(()->wallet.fulfill(second,true,"已核对实际支付","TEST_ONLY_RECEIPT","admin:test"));assertThrows(ServiceException.class,()->run(()->wallet.fulfill(second,true,"重复","TEST","admin:test")));assertEquals("redeemed",db.one("select status from kg_member_wallet where wallet_id=:w",p("w",id)).get("status"));}
    @Test void ownershipAndExpiryAreEnforced(){points(1L,120);run(()->wallet.redeem(1L,p("requestId","redeem_0001","roomCode","night1","nights",1)));Long id=KgJdbc.asLong(rows(1L).get(0).get("walletId"));assertThrows(ServiceException.class,()->run(()->wallet.undoRedeem(2L,id)));db.update("update kg_member_wallet set expires_at='2020-01-01' where wallet_id=:w",p("w",id));run(()->wallet.wallet(1L));assertEquals("expired",rows(1L).get(0).get("status"));assertThrows(ServiceException.class,()->run(()->wallet.undoRedeem(1L,id)));}
    @Test void giftOnlyOnceAndNoRedeemRefundByRecipient(){points(1L,500);run(()->wallet.claim(1L));List<Long> ids=new ArrayList<>();for(Map<String,Object>w:rows(1L))if("night4".equals(w.get("benefitCode")))ids.add(KgJdbc.asLong(w.get("walletId")));run(()->wallet.gift(1L,p("recipientId",2L,"walletIds",ids)));assertEquals(3,rows(2L).size());assertThrows(ServiceException.class,()->run(()->wallet.gift(1L,p("recipientId",3L,"walletIds",ids))));assertThrows(ServiceException.class,()->run(()->wallet.undoRedeem(2L,ids.get(0))));}
    @Test void adminReadQueriesAreExecutable(){for(String tab:Arrays.asList("requests","reviews","rooms","activities","posts","cases","audit","calendar"))assertNotNull(ops.overview(tab,1),tab);}
    @Test void syncWithNoEventsNeverCreatesPoints(){run(()->rewards.sync(1L));assertEquals(0,ledger.totals(1L).balance());assertEquals(0,db.count("select count(*) from kg_point_entry",p()));}
    long service(String type,int days){
        long job=db.insert("kg_job",p("store_id",1L,"job_type",type,"title","测试岗位","city","测试城市"));
        return db.insert("kg_application",p("app_no",UUID.randomUUID().toString().replace("-",""),"job_id",job,"store_id",1L,"app_user_id",1L,"real_name","测试","phone","TEST_ONLY","status","onboarded","volunteer_days",days,"volunteer_done_time",LocalDateTime.now(),"volunteer_done_by","staff:3"));
    }
    long stay(){return db.insert("kg_stay_order",p("order_no",UUID.randomUUID().toString().replace("-",""),"stay_id",1L,"store_id",1L,"room_type_id",1L,"app_user_id",1L,"check_in","2026-02-01","check_out","2026-02-15","nights",14,"guest_name","测试","guest_phone","TEST_ONLY","pay_fen",10000,"status","completed","update_time",LocalDateTime.now()));}
    long review(long order){return db.insert("kg_review",p("target_type","room","target_id",1L,"app_user_id",1L,"biz_type","stay_order","biz_id",order,"rating",2,"content","在这里住宿14天，晚上房间隔音一般，早餐每天八点供应，厨房可以做饭。前台会说明公交线路，老板帮忙安排了接站，第三天反映卫生问题后有人来打扫。","images","/profile/test-only.jpg","status","0","del_flag","0","create_time",LocalDateTime.now()));}
    long room(){return db.insert("kg_stay_room_type",p("stay_id",1L,"type_name","测试四人间","base_price_fen",10000,"member_room_code","night4","member_price_fen",8500));}
    Map<String,Object> quote(int nights){LocalDate d=LocalDate.of(KgMembershipPolicy.year(),12,1);List<Map<String,Object>> prices=new ArrayList<>();for(int i=0;i<nights;i++)prices.add(p("priceFen",10000));return p("roomFen",10000L*nights,"discountFen",0,"units",1,"guests",1,"nights",nights,"checkIn",d.toString(),"checkOut",d.plusDays(nights).toString(),"nightly",prices,"addonFen",0,"serviceFeeFen",0);}
    @Test void serviceAndReferralCreditEachCompletedServiceExactlyOnce(){
        db.update("update kg_app_user set invited_by=2 where user_id=1",p());service("volunteer",30);service("parttime",1);service("parttime",3);
        run(()->rewards.sync(1L));assertEquals(38,ledger.totals(1L).balance());assertEquals(8,ledger.totals(2L).balance());
        run(()->rewards.sync(1L));assertEquals(38,ledger.totals(1L).balance());assertEquals(8,ledger.totals(2L).balance());
    }
    @Test void earlyCheckoutAndLateCheckinUseActualNightsThenRefundClawsBack(){
        long id=stay();db.update("update kg_stay_order set verify_time='2026-02-03',checked_out_time='2026-02-10' where order_id=:id",p("id",id));
        run(()->rewards.sync(1L));assertEquals(5,ledger.totals(1L).balance());
        db.update("update kg_stay_order set status='refunded' where order_id=:id",p("id",id));run(()->rewards.sync(1L));run(()->rewards.sync(1L));assertEquals(0,ledger.totals(1L).balance());
    }
    @Test void realNegativeReviewEarnsQualityOnlyAfterPhotoVerificationAndArchiveDoesNotDock(){
        long id=review(stay());run(()->rewards.sync(1L));assertEquals(12,ledger.totals(1L).balance());
        run(()->rewards.verifyImages(id,true,"人工核验本人实拍","admin:test"));assertEquals(17,ledger.totals(1L).balance());
        run(()->rewards.sync(1L));assertEquals(17,ledger.totals(1L).balance());
        run(()->ops.review(id,p("action","archive","note","门店变更归档"),"admin:test"));run(()->rewards.sync(1L));assertEquals(17,ledger.totals(1L).balance());
    }
    @Test void noRealCompletedOrderNoReviewPoints(){review(998L);run(()->rewards.sync(1L));assertEquals(0,ledger.totals(1L).balance());}
    @Test void appealWindowAndMinorPenaltyReverseOnlyThisReviewOnLaterReconciliation(){
        long review=review(stay());run(()->rewards.verifyImages(review,true,"人工实拍核验","admin:test"));
        Long c=transaction(()->ops.notifyCase(p("userId",1L,"reviewId",review,"severity",1,"note","图片复核发现清晰度不足"),"admin:test"));
        assertThrows(ServiceException.class,()->run(()->ops.decideCase(c,p("execute",true,"note","复核处理"),"admin:test")));
        db.update("update kg_member_case set appeal_deadline=date_sub(now(),interval 1 day) where case_id=:c",p("c",c));
        run(()->ops.decideCase(c,p("execute",true,"note","复核维持处理"),"admin:test"));assertEquals(10,ledger.totals(1L).balance());
        run(()->rewards.sync(1L));assertEquals(10,ledger.totals(1L).balance());
        assertThrows(ServiceException.class,()->run(()->ops.decideCase(c,p("execute",true,"note","重复处理"),"admin:test")));
    }
    @Test void seriousPenaltyClearsSpendablePointsAndDropsOneLevel(){
        points(1L,200);Long c=transaction(()->ops.notifyCase(p("userId",1L,"severity",3,"note","核验确认为虚假评价，完成总部复核"),"admin:test"));
        db.update("update kg_member_case set appeal_deadline=date_sub(now(),interval 1 day) where case_id=:c",p("c",c));
        run(()->ops.decideCase(c,p("execute",true,"note","维持处理并执行处罚"),"admin:test"));
        assertEquals(0,ledger.totals(1L).balance());assertEquals(200,ledger.totals(1L).earned);
        assertEquals(1,db.count("select level_penalty from kg_member_restriction where app_user_id=1",p()));
        assertEquals(1,db.count("select count(*) from kg_member_restriction where app_user_id=1 and reviews_blocked_until>date_add(now(),interval 89 day)",p()));
    }
    @Test void maliciousPenaltyDisablesAccountAndFreezesBenefits(){
        points(1L,400);Long c=transaction(()->ops.notifyCase(p("userId",1L,"severity",4,"note","核验组织刷评，已完成总部复核"),"admin:test"));
        db.update("update kg_member_case set appeal_deadline=date_sub(now(),interval 1 day) where case_id=:c",p("c",c));
        run(()->ops.decideCase(c,p("execute",true,"note","封禁并冻结权益，保留审计"),"admin:test"));
        assertEquals("1",db.one("select status from kg_app_user where user_id=1",p()).get("status"));
        assertEquals("1",db.one("select frozen from kg_member_restriction where app_user_id=1",p()).get("frozen"));
        assertThrows(ServiceException.class,()->run(()->wallet.claim(1L)));
    }
    @Test void freeNightQuoteReserveCancelAndCheckinAreConsistent(){
        long room=room();points(1L,90);run(()->wallet.claim(1L));Long id=KgJdbc.asLong(rows(1L).stream().filter(w->"night4".equals(w.get("benefitCode"))).findFirst().get().get("walletId"));
        db.insert("kg_member_calendar_year",p("calendar_year",KgMembershipPolicy.year(),"confirmed","1"));
        Map<String,Object> q=quote(1);run(()->wallet.applyStay(1L,room,q,Arrays.asList(id),false));assertEquals(0L,q.get("payFen"));
        run(()->wallet.reserveStay(1L,99L,Arrays.asList(id)));assertThrows(ServiceException.class,()->run(()->wallet.applyStay(1L,room,quote(1),Arrays.asList(id),false)));
        run(()->wallet.releaseStay(99L));run(()->wallet.applyStay(1L,room,quote(1),Arrays.asList(id),false));
        run(()->wallet.reserveStay(1L,100L,Arrays.asList(id)));run(()->wallet.consumeStay(100L));run(()->wallet.releaseStay(100L));
        assertEquals("redeemed",db.one("select status from kg_member_wallet where wallet_id=:id",p("id",id)).get("status"));
    }
    @Test void holidaysAnnualLimitAndNoStackingAreEnforced(){
        long room=room();points(1L,2000);run(()->wallet.redeem(1L,p("requestId","limit_test_1","roomCode","night4","nights",20)));List<Long> all=new ArrayList<>();for(Map<String,Object>w:rows(1L))all.add(KgJdbc.asLong(w.get("walletId")));
        assertThrows(ServiceException.class,()->run(()->wallet.applyStay(1L,room,quote(1),all.subList(0,1),false)));
        db.insert("kg_member_calendar_year",p("calendar_year",KgMembershipPolicy.year(),"confirmed","1"));db.insert("kg_member_holiday",p("holiday_date",quote(1).get("checkIn"),"title","测试假日"));
        assertThrows(ServiceException.class,()->run(()->wallet.applyStay(1L,room,quote(1),all.subList(0,1),false)));db.update("delete from kg_member_holiday",p());
        assertThrows(ServiceException.class,()->run(()->wallet.applyStay(1L,room,quote(1),all.subList(0,1),true)));
        run(()->wallet.reserveStay(1L,101L,all));run(()->wallet.consumeStay(101L));run(()->wallet.redeem(1L,p("requestId","limit_test_2","roomCode","night4","nights",1)));Long last=KgJdbc.asLong(rows(1L).get(20).get("walletId"));
        assertThrows(ServiceException.class,()->run(()->wallet.applyStay(1L,room,quote(1),Arrays.asList(last),false)));
    }
    @Test void allRoomsOpenByDefaultAndUnconfiguredFloorCannotCreateFreeOrder(){
        assertEquals("night4",KgMembershipPolicy.roomCode(p("roomKind","shared","capacity",6)));
        assertEquals("night4",KgMembershipPolicy.roomCode(p("unitLabel","床位","capacity",1)));
        assertEquals("night2",KgMembershipPolicy.roomCode(p("roomKind","private","capacity",2)));
        assertEquals("night1",KgMembershipPolicy.roomCode(p("typeName","单间独卫","capacity",2)));
        assertEquals("night1",KgMembershipPolicy.roomCode(p("roomKind","whole","capacity",12)));
        assertEquals("night2",KgMembershipPolicy.roomCode(p("roomKind","shared","memberRoomCode","night2")));
        long room=room();points(1L,400);run(()->wallet.claim(1L));
        db.update("update kg_stay_room_type set member_room_code=null,member_price_fen=null,room_kind='shared' where room_type_id=:id",p("id",room));
        run(()->ops.calendar(p("year",KgMembershipPolicy.year(),"dates",KgMembershipPolicy.year()+"-01-01","confirmed",true,"note","仅隔离验收日历"),"test"));
        Long voucher=KgJdbc.asLong(rows(1L).stream().filter(w->"night4".equals(w.get("benefitCode"))).findFirst().get().get("walletId"));
        Map<String,Object> free=quote(1);run(()->wallet.applyStay(1L,room,free,Collections.singletonList(voucher),false));assertEquals(0L,free.get("payFen"));
        Map<String,Object> normal=quote(1);normal.put("payFen",10000L);run(()->wallet.applyStay(1L,room,normal,Collections.emptyList(),false));assertEquals(10000L,normal.get("payFen"));
        assertThrows(ServiceException.class,()->run(()->wallet.applyStay(1L,room,quote(1),Collections.emptyList(),true)));
        run(()->ops.configure("rooms",room,p("roomCode","disabled","priceFen",0,"note","测试暂停"),"test"));
        assertThrows(ServiceException.class,()->run(()->wallet.applyStay(1L,room,quote(1),Collections.singletonList(voucher),false)));
        run(()->ops.configure("rooms",room,p("roomCode","auto","priceFen",0,"note","测试重新开放"),"test"));
        run(()->wallet.applyStay(1L,room,quote(1),Collections.singletonList(voucher),false));
        assertNull(db.one("select member_price_fen from kg_stay_room_type where room_type_id=:id",p("id",room)).get("memberPriceFen"));
    }
    @Test void floorPricesAreIndependentPerStoreAndRoomAndPersistForQuotes(){
        long first=room(),second=room(),third=room();points(1L,40);
        db.update("update kg_stay_room_type set stay_id=2 where room_type_id=:r",p("r",third));
        run(()->ops.configure("rooms",first,p("roomCode","auto","priceFen",6100,"note","按一号店四人间合同定价"),"admin:test"));
        run(()->ops.configure("rooms",second,p("roomCode","auto","priceFen",7800,"note","按一号店另一房型定价"),"admin:test"));
        run(()->ops.configure("rooms",third,p("roomCode","auto","priceFen",9200,"note","按二号店该房型单独定价"),"admin:test"));
        long[] ids={first,second,third},prices={6100,7800,9200};
        for(int i=0;i<ids.length;i++){long rid=ids[i];Map<String,Object> q=quote(2);run(()->wallet.applyStay(1L,rid,q,Collections.emptyList(),true));assertEquals(prices[i]*2,q.get("payFen"));}
        run(()->ops.configure("rooms",second,p("roomCode","auto","priceFen",0,"note","尚未确认底价，先取消配置"),"admin:test"));
        assertThrows(ServiceException.class,()->run(()->wallet.applyStay(1L,second,quote(1),Collections.emptyList(),true)));
        Map<String,Object> unaffected=quote(1);run(()->wallet.applyStay(1L,first,unaffected,Collections.emptyList(),true));assertEquals(6100L,unaffected.get("payFen"));
    }
    @Test void malformedFloorPricesDoNotOverwriteExistingPrice(){
        long room=room();for(Object invalid:Arrays.asList("abc","1.5",-1,100000001L,"999999999999999999999999"))assertThrows(ServiceException.class,()->run(()->ops.configure("rooms",room,p("roomCode","auto","priceFen",invalid,"note","测试错误金额不得修改定价"),"admin:test")));
        assertEquals(8500L,KgJdbc.asLong(db.one("select member_price_fen from kg_stay_room_type where room_type_id=:id",p("id",room)).get("memberPriceFen")));
    }
    @Test void floorDoesNotStackAndNeverMakesExistingPriceWorse(){
        long room=room();points(1L,40);Map<String,Object> q=quote(1);run(()->wallet.applyStay(1L,room,q,Collections.emptyList(),true));assertEquals(8500L,q.get("payFen"));
        Map<String,Object> cheaper=quote(1);cheaper.put("discountFen",2000);assertThrows(ServiceException.class,()->run(()->wallet.applyStay(1L,room,cheaper,Collections.emptyList(),true)));
    }
    @Test void trainingAndActivityUseRequireActualAttendance(){
        points(1L,90);run(()->wallet.claim(1L));Long w=KgJdbc.asLong(rows(1L).stream().filter(x->"activity".equals(x.get("benefitCode"))).findFirst().get().get("walletId"));
        long a=db.insert("kg_activity",p("source","yard","title","测试培训","start_time","2026-02-01 12:00:00","end_time","2026-02-01 18:00:00","status","ended","member_training","1","member_free","1"));
        long signup=db.insert("kg_activity_signup",p("signup_no","TEST_TRAINING","activity_id",a,"app_user_id",1L,"status","confirmed","member_wallet_id",w));
        run(()->wallet.activityVoucher(1L,a,w));run(()->wallet.reserveActivity(1L,w));run(()->rewards.sync(1L));assertEquals(90,ledger.totals(1L).balance());assertFalse(rewards.reviewable(1L,"activity_signup",signup));
        db.update("update kg_activity_signup set checkin_time='2026-02-01 12:01:00' where signup_id=:id",p("id",signup));run(()->wallet.reconcileActivities());run(()->rewards.sync(1L));run(()->rewards.sync(1L));assertEquals(105,ledger.totals(1L).balance());assertEquals("redeemed",db.one("select status from kg_member_wallet where wallet_id=:w",p("w",w)).get("status"));assertTrue(rewards.reviewable(1L,"activity_signup",signup));
        db.update("update kg_activity set member_training='0' where activity_id=:id",p("id",a));assertFalse(rewards.reviewable(1L,"activity_signup",signup));
    }

    @Test void cancelledActivityCannotReleaseVoucherReservedByAnotherSignup(){
        points(1L,90);run(()->wallet.claim(1L));
        Long w=KgJdbc.asLong(rows(1L).stream().filter(x->"activity".equals(x.get("benefitCode"))).findFirst().get().get("walletId"));
        long a=db.insert("kg_activity",p("source","yard","title","原活动","start_time","2026-12-20 12:00:00","status","open","member_free","1"));
        long first=db.insert("kg_activity_signup",p("signup_no","CANCELLED_ACTIVITY","activity_id",a,"app_user_id",1L,"status","confirmed","member_wallet_id",w));
        run(()->wallet.reserveActivity(1L,w));
        db.update("update kg_activity_signup set status='cancelled' where signup_id=:s",p("s",first));
        run(()->wallet.reconcileActivities());assertEquals("available",rows(1L).stream().filter(x->w.equals(KgJdbc.asLong(x.get("walletId")))).findFirst().get().get("status"));
        long b=db.insert("kg_activity",p("source","yard","title","新活动","start_time","2026-12-21 12:00:00","status","open","member_free","1"));
        long second=db.insert("kg_activity_signup",p("signup_no","CURRENT_ACTIVITY","activity_id",b,"app_user_id",1L,"status","confirmed","member_wallet_id",w));
        run(()->wallet.reserveActivity(1L,w));run(()->wallet.reconcileActivities());run(()->wallet.reconcileActivities());
        assertEquals("reserved",db.one("select status from kg_member_wallet where wallet_id=:w",p("w",w)).get("status"));
        assertThrows(ServiceException.class,()->run(()->wallet.activityVoucher(1L,a,w)));
        db.update("update kg_activity_signup set status='cancelled' where signup_id=:s",p("s",second));
        run(()->wallet.reconcileActivities());assertEquals("available",db.one("select status from kg_member_wallet where wallet_id=:w",p("w",w)).get("status"));
        db.update("update kg_activity_signup set status='confirmed' where signup_id=:s",p("s",second));
        run(()->wallet.reserveActivity(1L,w));
        db.update("update kg_activity_signup set checkin_time=now() where signup_id=:s",p("s",second));
        run(()->wallet.reconcileActivities());assertEquals("redeemed",db.one("select status from kg_member_wallet where wallet_id=:w",p("w",w)).get("status"));
        run(()->wallet.reconcileActivities());assertEquals("redeemed",db.one("select status from kg_member_wallet where wallet_id=:w",p("w",w)).get("status"));
    }
    @Test void activityCancellationReturnsExistingBenefitEvenWhenNewIssuanceDisabled(){
        points(1L,90);run(()->wallet.claim(1L));
        Long w=KgJdbc.asLong(rows(1L).stream().filter(x->"activity".equals(x.get("benefitCode"))).findFirst().get().get("walletId"));
        long a=db.insert("kg_activity",p("source","yard","title","取消报名验收","start_time","2099-12-20 12:00:00","status","open","capacity",10,"signed",1,"member_free","1"));
        long s=db.insert("kg_activity_signup",p("signup_no","DISABLED_CANCEL","activity_id",a,"app_user_id",1L,"status","confirmed","member_wallet_id",w));
        run(()->wallet.reserveActivity(1L,w));db.update("update kg_member_execution set enabled='0' where id=1",p());
        com.ruoyi.kangaroo.ext.community.KgActivityService activities=new com.ruoyi.kangaroo.ext.community.KgActivityService();
        field(activities,"db",db);field(activities,"memberWallet",wallet);
        field(activities,"chats",mock(com.ruoyi.kangaroo.ext.community.KgChatService.class));
        assertEquals("已取消报名",transaction(()->activities.cancel(1L,s,"行程调整")));
        assertEquals("available",db.one("select status from kg_member_wallet where wallet_id=:w",p("w",w)).get("status"));
        assertEquals(0,db.count("select signed from kg_activity where activity_id=:a",p("a",a)));
    }
    @Test void merchantEligibilityRequestReplayAndAuditedFulfillment(){
        long b=db.insert("kg_member_benefit",p("merchant_name","测试合作店","title","住宿会员底价","city","测试城市","category","stay","discount_text","测试最终价100元","min_level",0,"rules","不叠加折扣","status","0","del_flag","0"));
        Map<String,Object> body=p("benefitId",b,"content","测试预订日期与需求","requestId","merchant_0001");
        assertThrows(ServiceException.class,()->run(()->wallet.request(1L,body)));
        points(1L,40);long request=transaction(()->KgJdbc.asLong(wallet.request(1L,body).get("requestId")));
        run(()->wallet.request(1L,body));assertEquals(1,db.count("select count(*) from kg_member_request",p()));
        assertTrue(String.valueOf(db.one("select content from kg_member_request",p()).get("content")).contains("测试合作店"));
        run(()->ops.fulfill(request,p("approve",true,"note","商家确认已经履约","proof","TEST_RECEIPT"),"admin:test"));
        assertEquals("completed",db.one("select status from kg_member_request",p()).get("status"));
        db.update("update kg_member_benefit set category='food' where benefit_id=:b",p("b",b));
        body.put("requestId","merchant_0002");assertThrows(ServiceException.class,()->run(()->wallet.request(1L,body)));
        ledger.award(1L,50,"升级","fixture_more",1L);run(()->wallet.request(1L,body));
    }
    @Test void serviceTierAndFrozenMembershipCannotBeBypassed(){
        assertThrows(ServiceException.class,()->run(()->wallet.request(1L,p("serviceType","recommend","content","推荐岗位","requestId","service_0001"))));
        points(1L,15);run(()->wallet.request(1L,p("serviceType","recommend","content","推荐岗位","requestId","service_0001")));
        assertThrows(ServiceException.class,()->run(()->wallet.request(1L,p("serviceType","booking","content","优先预订","requestId","service_0002"))));
        ledger.award(1L,185,"升级","fixture_more",1L);run(()->wallet.request(1L,p("serviceType","booking","content","优先预订","requestId","service_0002")));
        db.insert("kg_member_restriction",p("app_user_id",1L,"frozen","1"));
        assertThrows(ServiceException.class,()->run(()->wallet.redeem(1L,p("roomCode","night4","nights",1,"requestId","frozen_0001"))));
        assertEquals(0,((Number)new KgMemberService(db).identity(1L).get("level")).intValue());
        assertNotNull(ops.cases(1L));
    }
    long post(Long uid,String status,Object created,int likes,int comments,int favorites){return db.insert("kg_post",p("app_user_id",uid,"kind","daily","title","测试动态","content","隔离测试的社区内容","status",status,"city","测试城市","create_time",created,"likes",likes,"comments",comments,"favorites",favorites));}
    @Test void pinQuotaOwnershipTwoHoursAndWeeklyHotAreIndependent(){
        points(1L,40);long id=post(1L,"online",LocalDateTime.now(),0,0,0);long other=post(2L,"online",LocalDateTime.now(),0,0,0);
        assertThrows(ServiceException.class,()->run(()->ops.pin(1L,p("postId",other,"requestId","pin_other_0001"))));
        run(()->ops.pin(1L,p("postId",id,"requestId","pin_0001")));run(()->ops.pin(1L,p("postId",id,"requestId","pin_0001")));
        assertEquals(40,ledger.totals(1L).balance());assertEquals(7200,db.count("select timestampdiff(second,starts_at,ends_at) from kg_member_pin",p()));
        db.update("update kg_member_pin set ends_at=date_sub(now(),interval 1 second)",p());
        assertThrows(ServiceException.class,()->run(()->ops.pin(1L,p("postId",id,"requestId","pin_0002"))));
        java.time.LocalDate prev=KgMembershipPolicy.today().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).minusWeeks(1);
        for(int n=0;n<4;n++)post(1L,"online",prev.plusDays(1).atTime(12,n),n,n,n);
        post(1L,"offline",prev.plusDays(1).atTime(12,0),999,999,999);
        run(()->ops.weeklyHot());run(()->ops.weeklyHot());
        assertEquals(3,db.count("select count(*) from kg_member_hot",p()));assertEquals(55,ledger.totals(1L).balance());
    }
    @Test void disablingRulesStopsSpendingAndConcurrentClaimsIssueOnlyOnce()throws Exception{
        points(1L,400);ExecutorService pool=Executors.newFixedThreadPool(2);
        try{List<Future<?>> fs=new ArrayList<>();for(int i=0;i<2;i++)fs.add(pool.submit(()->run(()->wallet.claim(1L))));for(Future<?> f:fs)f.get();}finally{pool.shutdownNow();}
        assertEquals(3,rows(1L).stream().filter(w->"night4".equals(w.get("benefitCode"))).count());
        assertEquals(15000,db.count("select sum(amount_fen) from kg_member_wallet where benefit_code='subsidy'",p()));
        db.update("update kg_member_execution set enabled='0' where id=1",p());
        assertThrows(ServiceException.class,()->run(()->wallet.redeem(1L,p("roomCode","night4","nights",1,"requestId","disabled_0001"))));
    }
}
