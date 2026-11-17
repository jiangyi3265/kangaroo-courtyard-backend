package com.ruoyi.kangaroo.ext.staff;
import java.util.*;
import java.util.concurrent.*;
import java.time.LocalDateTime;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgCommunityGuard;
import com.ruoyi.kangaroo.security.*;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;

class KgOnlineRewardDatabaseTest {
 static KgJdbc db;static TransactionTemplate tx;
 KgOnlineTaskService service;KgMerchantAgreements agreements;StaffSupport.Ctx merchant;
 @BeforeAll static void database(){
  String name=System.getenv("KG_MEMBER_TEST_DB");assertNotNull(name);assertTrue(name.matches("kg_member_exec_[a-f0-9]{32}"));
  DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/"+name+"?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai",System.getenv("KG_MEMBER_TEST_USER"),System.getenv("KG_TEST_DB_PASSWORD"));
  db=new KgJdbc(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
 }
 @BeforeEach void setup(){
  for(String table:Arrays.asList("kg_online_recipient","kg_online_settlement","kg_online_submission","kg_application","kg_online_task","kg_job","kg_merchant_agreement_consent"))db.update("delete from "+table,p());
  db.update("insert ignore into kg_store(store_id,store_name,city,status,del_flag) values(881,'计件测试店','平潭','0','0'),(882,'其他门店','平潭','0','0')",p());
  db.update("insert ignore into kg_app_user(user_id,openid,nickname,status,del_flag) values(981,'online-reward-merchant','测试商家','0','0'),(982,'online-reward-worker','测试接单人','0','0')",p());
  agreements=new KgMerchantAgreements(db);service=new KgOnlineTaskService();
  ReflectionTestUtils.setField(service,"db",db);ReflectionTestUtils.setField(service,"merchantAgreements",agreements);
  ReflectionTestUtils.setField(service,"support",mock(StaffSupport.class));ReflectionTestUtils.setField(service,"guard",mock(KgCommunityGuard.class));
  merchant=new StaffSupport.Ctx();merchant.storeId=881L;merchant.storeName="计件测试店";merchant.role="merchant";merchant.me=new AppLoginUser();merchant.me.setUserId(981L);merchant.me.setNickname("测试商家");
 }
 @AfterEach void clear(){AppUserContext.remove();}
 Map<String,Object> body(){return p("title","整理公开旅游资料","taskType","数据整理","deliverables","整理资料并逐条编号","criteria","内容完整、无重复","deadline",LocalDateTime.now().plusDays(3).format(KgOnlineTaskService.DT),"quota",2,"itemsPerPerson",10,"commissionFen",1,"unitRewardFen",800,"platformFeeUnitFen",1);}
 void consent(){Map<String,Object>d=agreements.read(merchant,"online_task");tx.execute(s->agreements.accept(merchant,"online_task",p("agreed",true,"version",d.get("version"))));}
 long create(){consent();return KgJdbc.asLong(tx.execute(s->service.create(merchant,body())).get("jobId"));}
 long assignment(long job){return db.insert("kg_application",p("app_no",UUID.randomUUID().toString().replace("-",""),"job_id",job,"store_id",881L,"app_user_id",982L,"real_name","测试接单人","phone","13800000000","status","review_passed","submit_time",new Date()));}
 Map<String,Object> submit(long app,Object count){AppLoginUser worker=new AppLoginUser();worker.setUserId(982L);AppUserContext.set(worker);return tx.execute(s->service.submit(app,p("links",Arrays.asList("https://example.com/result-collection"),"images",Collections.emptyList(),"note","多条编号成果合集","submittedCount",count)));}
 void code(int value,Runnable r){assertEquals(Integer.valueOf(value),assertThrows(ServiceException.class,r::run).getCode());}
 @Test void merchantChoosesWorkerRewardButCannotChangePlatformFee(){
  code(428,()->tx.execute(s->service.create(merchant,body())));assertEquals(0,db.count("select count(*) from kg_job",p()));
  long job=create();Map<String,Object> t=db.one("select * from kg_online_task where job_id=:j",p("j",job));
  assertEquals(8000L,KgJdbc.asLong(t.get("commissionFen")));assertEquals(800L,KgJdbc.asLong(t.get("unitRewardFen")));assertEquals(300L,KgJdbc.asLong(t.get("platformFeeUnitFen")));assertEquals("per_item",t.get("rewardMode"));
  assertEquals(1,db.count("select count(*) from kg_merchant_agreement_consent where business_type='online_task'",p()));
  code(422,()->service.syncFromJobEdit(job,9000,"fixture"));
 }
 @Test void taskDetailsAndListDistinguishPendingApplicationsFromAssignees(){
  db.update("insert ignore into kg_app_user(user_id,openid,nickname,status,del_flag) values(983,'audit-worker-two','报名测试二','0','0'),(984,'audit-worker-three','报名测试三','0','0')",p());
  long job=create(),a=assignment(job);
  db.update("update kg_application set app_user_id=983 where app_id=:a",p("a",a));
  long b=assignment(job);db.update("update kg_application set app_user_id=984 where app_id=:a",p("a",b));
  long cancelled=assignment(job);
  db.update("update kg_application set status='submitted' where app_id in (:a,:b)",p("a",a,"b",b));
  db.update("update kg_application set status='withdrawn' where app_id=:a",p("a",cancelled));
  Map<String,Object> detail=service.detail(merchant,job);
  assertEquals(2L,KgJdbc.asLong(detail.get("pendingApplications")));assertEquals(0L,KgJdbc.asLong(detail.get("assignees")));
  assertEquals(true,detail.get("canAuditApplications"));
  assertEquals(2L,KgJdbc.asLong(service.tasks(merchant).get(0).get("pendingApplications")));
  db.update("update kg_application set status='review_passed' where app_id=:a",p("a",a));
  detail=service.detail(merchant,job);
  assertEquals(1L,KgJdbc.asLong(detail.get("pendingApplications")));assertEquals(1L,KgJdbc.asLong(detail.get("assignees")));
  db.update("update kg_application set status='review_rejected' where app_id=:a",p("a",b));
  assertEquals(0L,KgJdbc.asLong(service.tasks(merchant).get(0).get("pendingApplications")));
  merchant.storeId=882L;code(404,()->service.detail(merchant,job));
 }
 @Test void validatedSubmissionAndPartialAcceptanceProduceExactSnapshot(){
  long job=create(),app=assignment(job);
  for(Object bad:Arrays.asList(0,-1,11,"1.5","1e1","",true))code(422,()->submit(app,bad));
  assertEquals(0,db.count("select count(*) from kg_online_submission",p()));
  submit(app,7);code(422,()->tx.execute(s->service.review(merchant,app,true,"",null,8)));
  code(422,()->tx.execute(s->service.review(merchant,app,true,"",null,0)));
  Map<String,Object> view=tx.execute(s->service.review(merchant,app,true,"五条合格",null,5));
  Map<?,?> st=(Map<?,?>)view.get("settlement");assertEquals("¥40",st.get("amount"));assertEquals(5L,KgJdbc.asLong(st.get("qualifiedCount")));
  assertEquals(800L,KgJdbc.asLong(st.get("unitRewardFen")));assertEquals(1500L,KgJdbc.asLong(st.get("platformFeeFen")));assertEquals(false,view.get("canSubmit"));
  db.update("update kg_online_task set commission_fen=99999 where job_id=:j",p("j",job));
  assertEquals("¥40",service.assignment(merchant,app).get("commission"));
  assertEquals("¥40",service.mine(app).get("commission"));
  code(409,()->tx.execute(s->service.review(merchant,app,true,"",null,7)));
  assertEquals(1,db.count("select count(*) from kg_online_settlement",p()));
 }
 @Test void concurrentReviewCreatesOnlyOneSettlement() throws Exception {
  long app=assignment(create());submit(app,6);ExecutorService pool=Executors.newFixedThreadPool(2);
  try{List<Future<Boolean>> fs=new ArrayList<>();for(int i=0;i<2;i++)fs.add(pool.submit(()->{try{tx.execute(s->service.review(merchant,app,true,"",null,4));return true;}catch(ServiceException e){assertEquals(Integer.valueOf(409),e.getCode());return false;}}));int successes=0;for(Future<Boolean> f:fs)if(f.get(20,TimeUnit.SECONDS))successes++;assertEquals(1,successes);}
  finally{pool.shutdownNow();}
  assertEquals(1,db.count("select count(*) from kg_online_settlement",p()));assertEquals(3200L,KgJdbc.asLong(db.one("select amount_fen from kg_online_settlement",p()).get("amountFen")));
 }
 @Test void rollbackCannotLeaveAcceptedSubmissionOrDebt(){
  long app=assignment(create());submit(app,3);
  assertThrows(IllegalStateException.class,()->tx.execute(s->{service.review(merchant,app,true,"",null,2);throw new IllegalStateException("fixture rollback");}));
  assertEquals(0,db.count("select count(*) from kg_online_settlement",p()));assertEquals("submitted",db.one("select status from kg_online_submission",p()).get("status"));
 }
 @Test void concurrentSubmissionWritesOnlyOneRound() throws Exception {
  long app=assignment(create());ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
  try {
   List<Future<Boolean>> results=new ArrayList<>();
   for(int i=0;i<2;i++)results.add(pool.submit(()->{start.await();try{submit(app,2);return true;}catch(ServiceException e){assertEquals(Integer.valueOf(409),e.getCode());return false;}finally{AppUserContext.remove();}}));
   start.countDown();int successes=0;for(Future<Boolean> f:results)if(f.get(20,TimeUnit.SECONDS))successes++;
   assertEquals(1,successes);assertEquals(1,db.count("select count(*) from kg_online_submission where app_id=:a",p("a",app)));
  } finally {start.countDown();pool.shutdownNow();}
 }
 @Test void recipientSaveWaitsForPaymentCommitAndRejectsLockedDetails() throws Exception {
  long app=assignment(create());submit(app,1);tx.execute(s->service.review(merchant,app,true,"通过",null,1));
  KgOnlineRecipient recipient=new KgOnlineRecipient();ReflectionTestUtils.setField(recipient,"db",db);
  ReflectionTestUtils.setField(recipient,"files",mock(com.ruoyi.kangaroo.ext.core.KgPrivateFiles.class));
  tx.execute(s->recipient.save(app,p("realName","测试接单人","method","alipay","account","original@example.com")));
  ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1),saving=new CountDownLatch(1);
  try {
   Future<?> payment=pool.submit(()->tx.execute(s->{
    db.one("select status from kg_online_settlement where app_id=:a for update",p("a",app));
    db.update("update kg_online_settlement set status='payout_registered' where app_id=:a",p("a",app));locked.countDown();
    try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return null;
   }));
   assertTrue(locked.await(10,TimeUnit.SECONDS));
   Future<?> change=pool.submit(()->{AppLoginUser worker=new AppLoginUser();worker.setUserId(982L);AppUserContext.set(worker);saving.countDown();try{code(409,()->tx.execute(s->recipient.save(app,p("realName","测试接单人","method","alipay","account","changed@example.com"))));}finally{AppUserContext.remove();}});
   assertTrue(saving.await(10,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->change.get(200,TimeUnit.MILLISECONDS));
   release.countDown();payment.get(10,TimeUnit.SECONDS);change.get(10,TimeUnit.SECONDS);
   assertEquals("original@example.com",db.one("select account from kg_online_recipient where app_id=:a",p("a",app)).get("account"));
  } finally {release.countDown();pool.shutdownNow();}
 }
 @Test void legacyTaskAndSettlementKeepOriginalAmountWithoutQuantity(){
  long job=create();db.update("update kg_online_task set reward_mode='fixed',items_per_person=null,unit_reward_fen=null,commission_fen=1250 where job_id=:j",p("j",job));
  db.update("delete from kg_merchant_agreement_consent",p());Map<String,Object>b=body();b.put("commissionFen",1250);
  tx.execute(s->{service.updateDraftTask(merchant,job,b);return null;});
  assertEquals("fixed",db.one("select reward_mode from kg_online_task where job_id=:j",p("j",job)).get("rewardMode"));
  long app=assignment(job);submit(app,null);Map<String,Object> v=tx.execute(s->service.review(merchant,app,true,""));
  assertEquals("¥12.50",((Map<?,?>)v.get("settlement")).get("amount"));assertNull(((Map<?,?>)v.get("settlement")).get("qualifiedCount"));
 }
 @Test void newTaskResubmitRequiresCurrentConsentAndCannotDowngradePricing(){
  long job=create();db.update("delete from kg_merchant_agreement_consent",p());
  code(428,()->tx.execute(s->{service.updateDraftTask(merchant,job,body());return null;}));consent();
  Map<String,Object> b=body();b.put("rewardMode","fixed");b.put("commissionFen",99999);b.put("itemsPerPerson",4);
  tx.execute(s->{service.updateDraftTask(merchant,job,b);return null;});
  assertEquals(3200L,KgJdbc.asLong(db.one("select commission_fen from kg_online_task where job_id=:j",p("j",job)).get("commissionFen")));
  merchant.storeId=882L;code(403,()->tx.execute(s->{service.updateDraftTask(merchant,job,b);return null;}));
 }

 @Test void recipientIsPrivateAndLocksAfterPaymentRegistration() {
  long app=assignment(create());submit(app,1);tx.execute(s->service.review(merchant,app,true,"通过",null,1));
  KgOnlineRecipient recipient=new KgOnlineRecipient();ReflectionTestUtils.setField(recipient,"db",db);
  com.ruoyi.kangaroo.ext.core.KgPrivateFiles privateFiles=mock(com.ruoyi.kangaroo.ext.core.KgPrivateFiles.class);ReflectionTestUtils.setField(recipient,"files",privateFiles);ReflectionTestUtils.setField(service,"recipients",recipient);
  Map<String,Object> saved=tx.execute(s->recipient.save(app,p("realName","测试接单人","method","alipay","account","worker@example.com")));
  assertEquals(true,saved.get("submitted"));assertEquals("worker@example.com",((Map<?,?>)service.mine(app).get("recipient")).get("account"));
  assertEquals("worker@example.com",((Map<?,?>)service.assignment(merchant,app).get("recipient")).get("account"));
  merchant.role="online_staff";Map<String,Object> reviewer=service.assignment(merchant,app);
  assertFalse(reviewer.containsKey("recipient"));assertEquals(false,reviewer.get("canPay"));
  assertEquals(new java.util.HashSet<String>(java.util.Arrays.asList("settleNo","status","statusLabel","amount")),((Map<?,?>)reviewer.get("settlement")).keySet());merchant.role="merchant";
  code(422,()->tx.execute(s->recipient.save(app,p("method","alipay","account","other@example.com"))));
  code(422,()->tx.execute(s->recipient.save(app,p("realName","张\n\n三","method","alipay","account","other@example.com"))));
  code(422,()->tx.execute(s->recipient.save(app,p("realName","测试接单人","method","alipay"))));
  assertEquals("worker@example.com",db.one("select account from kg_online_recipient where app_id=:a",p("a",app)).get("account"));
  merchant.storeId=882L;code(404,()->service.assignment(merchant,app));merchant.storeId=881L;
  AppLoginUser stranger=new AppLoginUser();stranger.setUserId(983L);AppUserContext.set(stranger);
  code(404,()->tx.execute(s->recipient.save(app,p("realName","另一个人","method","alipay","account","other@example.com"))));
  stranger.setUserId(982L);AppUserContext.set(stranger);
  db.update("update kg_online_settlement set status='payout_registered' where app_id=:a",p("a",app));
  code(409,()->tx.execute(s->recipient.save(app,p("realName","测试接单人","method","alipay","account","changed@example.com"))));
  assertEquals("worker@example.com",db.one("select account from kg_online_recipient where app_id=:a",p("a",app)).get("account"));
 }
 @Test void invalidRewardAndQuantityCannotCreateTask() {
  consent();
  for(Object invalid:Arrays.asList(0,99,500001,"1.5",true,"")){Map<String,Object>b=body();b.put("unitRewardFen",invalid);code(422,()->tx.execute(s->service.create(merchant,b)));}
  assertEquals(0,db.count("select count(*) from kg_job",p()));
 }
 @Test void qrRecipientUsesPrivateStorageAndRejectsClientKeysAndFakeImages() {
  long app=assignment(create());submit(app,1);tx.execute(s->service.review(merchant,app,true,"通过",null,1));
  KgOnlineRecipient recipient=new KgOnlineRecipient();ReflectionTestUtils.setField(recipient,"db",db);
  com.ruoyi.kangaroo.ext.core.KgPrivateFiles files=mock(com.ruoyi.kangaroo.ext.core.KgPrivateFiles.class);ReflectionTestUtils.setField(recipient,"files",files);
  code(422,()->tx.execute(s->recipient.save(app,p("realName","测试接单人","method","qr","qrKey","someone/else.png"))));
  org.springframework.mock.web.MockMultipartFile bad=new org.springframework.mock.web.MockMultipartFile("file","fake.jpg","image/jpeg","<html>wrong</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  code(422,()->tx.execute(s->recipient.saveQr(app,"测试接单人",bad)));verifyNoInteractions(files);
  byte[] png={(byte)137,80,78,71,13,10,26,10,0,0,0,0};
  org.springframework.mock.web.MockMultipartFile image=new org.springframework.mock.web.MockMultipartFile("file","receiver.png","image/png",png);
  when(files.save(image,"onlinereceiver")).thenReturn(p("key","onlinereceiver/fixture/receipt.png"));
  when(files.sign("onlinereceiver/fixture/receipt.png",600)).thenReturn("/app/private-file?key=signed-fixture");
  Map<String,Object> saved=tx.execute(s->recipient.saveQr(app,"测试接单人",image));assertEquals("qr",saved.get("method"));assertEquals("/app/private-file?key=signed-fixture",saved.get("qrUrl"));assertFalse(saved.containsKey("qrKey"));
  Map<String,Object> changed=tx.execute(s->recipient.save(app,p("realName","更新姓名","method","qr","qrKey","injected/other.png")));
  assertEquals(saved.get("qrUrl"),changed.get("qrUrl"));assertEquals("更新姓名",changed.get("realName"));
  db.update("update kg_online_settlement set status='payout_registered' where app_id=:a",p("a",app));
  code(409,()->tx.execute(s->recipient.saveQr(app,"测试接单人",image)));verify(files,times(1)).save(image,"onlinereceiver");
 }
 @Test void currentAgreementSeparatesPlatformFeeAndWorkerReward() {
  String html=String.valueOf(agreements.read(merchant,"online_task").get("html"));
  assertTrue(html.contains("协议版本：V1.2"));assertTrue(html.contains("此费用不是接单人报酬"));assertTrue(html.contains("实际验收合格的成果条数"));assertFalse(html.contains("平台或任务承接方仅以"));
 }
}
