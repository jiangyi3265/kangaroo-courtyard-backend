package com.ruoyi.kangaroo.ext.staff;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.AppLoginUser;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;

/** Real isolated MySQL and transactions; never uses a customer account or production data. */
class KgMerchantAgreementsDatabaseTest {
 static KgJdbc db;static TransactionTemplate tx;static KgMerchantAgreements service;
 static Map<String,Object> originals=new HashMap<>();
 @BeforeAll static void setup(){
  String name=System.getenv("KG_MEMBER_TEST_DB");assertNotNull(name);assertTrue(name.matches("kg_member_exec_[a-f0-9]{32}"));
  DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/"+name+"?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai",System.getenv("KG_MEMBER_TEST_USER"),System.getenv("KG_TEST_DB_PASSWORD"));
  db=new KgJdbc(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));service=new KgMerchantAgreements(db);
  for(String t:Arrays.asList("volunteer","parttime","managed","online_task"))originals.put(t,db.one("select notice_content from sys_notice where notice_title=:t",p("t",KgMerchantAgreements.title(t))).get("noticeContent"));
 }
 @BeforeEach void reset(){
  db.update("delete from kg_merchant_agreement_consent",p());
  for(String t:originals.keySet())db.update("update sys_notice set status='0',notice_content=:c where notice_title=:t",p("c",originals.get(t),"t",KgMerchantAgreements.title(t)));
  for(long s:Arrays.asList(801L,802L))if(db.count("select count(*) from kg_store where store_id=:s",p("s",s))==0)db.insert("kg_store",p("store_id",s,"store_name","协议隔离测试店"+s,"city","平潭","status","0","del_flag","0"));
 }
 StaffSupport.Ctx ctx(long store,long user){StaffSupport.Ctx c=new StaffSupport.Ctx();c.storeId=store;c.storeName="协议隔离测试店"+store;c.role="merchant";c.me=new AppLoginUser();c.me.setUserId(user);c.me.setNickname("测试商家"+user);return c;}
 Map<String,Object> accept(StaffSupport.Ctx c,String type){String v=String.valueOf(service.read(c,type).get("version"));return tx.execute(s->service.accept(c,type,p("agreed",true,"version",v)));}
 void code(int expected,Runnable action){assertEquals(Integer.valueOf(expected),assertThrows(ServiceException.class,action::run).getCode());}
 @Test void allFiveBusinessTypesRequireTheirOwnConsentAndRetryIsIdempotent(){
  StaffSupport.Ctx c=ctx(801,901);accept(c,"volunteer_notice");
  for(String type:Arrays.asList("volunteer","parttime","fulltime","managed","online_task")){
   code(428,()->tx.execute(s->{service.requireAccepted(c,type);return null;}));
   Map<String,Object> d=accept(c,type);assertEquals(true,d.get("accepted"));
   assertTrue(d.get("html").toString().contains("深圳市袋鼠星辰投资有限公司"));
   assertFalse(d.get("html").toString().contains("广西袋鼠胜意"));
   accept(c,type);tx.execute(s->{service.requireAccepted(c,type);return null;});
  }
  assertEquals(6,db.count("select count(*) from kg_merchant_agreement_consent",p()));
 }
 @Test void storeAndActorCannotBorrowOthersConsentOrReadTheirRecords(){
  StaffSupport.Ctx c=ctx(801,901);Map<String,Object> d=accept(c,"managed");long id=KgJdbc.asLong(((Map<?,?>)d.get("record")).get("id"));
  for(StaffSupport.Ctx other:Arrays.asList(ctx(802,901),ctx(801,902))){assertEquals(false,service.read(other,"managed").get("accepted"));code(404,()->service.record(other,id));}
  assertEquals("测试商家901",service.record(c,id).get("operatorName"));
 }
 @Test void updatedTermsRequireNewConsentAndHistoricalSnapshotDoesNotChange(){
  StaffSupport.Ctx c=ctx(801,901);Map<String,Object> old=accept(c,"parttime");long id=KgJdbc.asLong(((Map<?,?>)old.get("record")).get("id"));
  String changed=old.get("html")+"<p>测试更新条款</p>";
  db.update("update sys_notice set notice_content=:c where notice_title=:t",p("c",changed,"t",old.get("title")));
  assertEquals(false,service.read(c,"parttime").get("accepted"));
  code(409,()->tx.execute(s->service.accept(c,"parttime",p("agreed",true,"version",old.get("version")))));
  assertEquals(old.get("html"),service.record(c,id).get("contentHtml"));
  accept(c,"parttime");assertEquals(2,((List<?>)service.read(c,"parttime").get("records")).size());
  assertEquals(false,service.read(c,"fulltime").get("accepted"));
 }
 @Test void uncheckedMissingVersionAndUnsupportedTypeNeverWrite(){
  StaffSupport.Ctx c=ctx(801,901);
  for(Object agree:Arrays.asList(false,"true",1))code(422,()->tx.execute(s->service.accept(c,"managed",p("agreed",agree))));
  code(409,()->tx.execute(s->service.accept(c,"managed",p("agreed",true))));
  code(404,()->service.read(c,"online"));assertEquals(0,db.count("select count(*) from kg_merchant_agreement_consent",p()));
 }
 @Test void unpublishedAndEmptyAgreementFailClosed(){
  StaffSupport.Ctx c=ctx(801,901);
  db.update("update sys_notice set status='1' where notice_title=:t",p("t",KgMerchantAgreements.title("managed")));code(503,()->service.read(c,"managed"));
  db.update("update sys_notice set status='0',notice_content='' where notice_title=:t",p("t",KgMerchantAgreements.title("managed")));code(503,()->service.read(c,"managed"));
 }
 @Test void concurrentDuplicateConfirmationsCreateOneImmutableRecord() throws Exception {
  ExecutorService pool=Executors.newFixedThreadPool(4);
  try{List<Future<?>> results=new ArrayList<>();for(int i=0;i<8;i++)results.add(pool.submit(()->accept(ctx(801,901),"volunteer")));for(Future<?> f:results)f.get(15,TimeUnit.SECONDS);}
  finally{pool.shutdownNow();}
  assertEquals(1,db.count("select count(*) from kg_merchant_agreement_consent",p()));
 }
 @Test void transactionRollbackDoesNotLeaveConfirmation(){
  StaffSupport.Ctx c=ctx(801,901);String v=String.valueOf(service.read(c,"managed").get("version"));
  assertThrows(IllegalStateException.class,()->tx.execute(s->{service.accept(c,"managed",p("agreed",true,"version",v));throw new IllegalStateException("rollback fixture");}));
  assertEquals(false,service.read(c,"managed").get("accepted"));
 }

 @Test void merchantNoticeIsRequiredSeparatelyAndStayDraftNeverCreatesConsent(){
  StaffSupport.Ctx c=ctx(801,901);accept(c,"volunteer");
  code(428,()->tx.execute(s->{service.requireAccepted(c,"volunteer");return null;}));
  accept(c,"volunteer_notice");tx.execute(s->{service.requireAccepted(c,"volunteer");return null;});
  assertEquals(true,service.read(c,"stay").get("draft"));code(409,()->accept(c,"stay"));
  tx.execute(s->{service.requireAccepted(c,"stay");return null;});
  db.update("update sys_notice set status='0' where notice_title=:t",p("t",KgMerchantAgreements.title("stay")));
  try{code(428,()->tx.execute(s->{service.requireAccepted(c,"stay");return null;}));accept(c,"stay");tx.execute(s->{service.requireAccepted(c,"stay");return null;});}
  finally{db.update("update sys_notice set status='1' where notice_title=:t",p("t",KgMerchantAgreements.title("stay")));}
 }
 long departureApplication(long user){
  db.update("insert ignore into kg_app_user(user_id,openid,nickname,status,del_flag) values(:u,:o,'行前测试','0','0')",p("u",user,"o","departure-"+user));
  long j=db.insert("kg_job",p("store_id",801,"title","换工测试","city","平潭","job_type","volunteer","status","published","del_flag","0"));
  return db.insert("kg_application",p("app_no",UUID.randomUUID().toString().replace("-",""),"job_id",j,"store_id",801,"app_user_id",user,"real_name","行前测试","phone","13800000000","status","submitted","submit_time",new Date()));
 }
 @Test void departureConsentIsPerApplicationVersionAndOwnerWithImmutableHistory(){
  com.ruoyi.kangaroo.ext.user.KgDepartureNotice notice=new com.ruoyi.kangaroo.ext.user.KgDepartureNotice(db);
  long a=departureApplication(9901),b=departureApplication(9901);Map<String,Object>d=notice.read(9901L,a);
  code(404,()->notice.read(9902L,a));code(422,()->tx.execute(s->notice.accept(9901L,a,p("agreed",false))));
  code(428,()->tx.execute(s->{notice.requireAccepted(9901L,a);return null;}));
  for(int i=0;i<2;i++)tx.execute(s->notice.accept(9901L,a,p("agreed",true,"version",d.get("version"))));
  assertEquals(1,db.count("select count(*) from kg_departure_consent where app_id=:a",p("a",a)));
  tx.execute(s->{notice.requireAccepted(9901L,a);return null;});code(428,()->tx.execute(s->{notice.requireAccepted(9901L,b);return null;}));
  long id=KgJdbc.asLong(((List<Map<String,Object>>)notice.read(9901L,a).get("records")).get(0).get("id"));
  code(404,()->notice.record(9901L,b,id));code(404,()->notice.record(9902L,a,id));
  String title=com.ruoyi.kangaroo.ext.user.KgDepartureNotice.TITLE,old=d.get("html").toString();
  try{
   db.update("update sys_notice set notice_content=:h where notice_title=:t",p("h",old+"<p>测试更新</p>","t",title));
   code(409,()->tx.execute(s->notice.accept(9901L,a,p("agreed",true,"version",d.get("version")))));
   assertEquals(false,notice.read(9901L,a).get("accepted"));assertEquals(old,notice.record(9901L,a,id).get("contentHtml"));
   db.update("update sys_notice set status='1' where notice_title=:t",p("t",title));code(503,()->notice.read(9901L,a));
  }finally{db.update("update sys_notice set status='0',notice_content=:h where notice_title=:t",p("t",title,"h",old));}
 }
 @Test void departureOtherJobTypesSkipGateAndEndedApplicationsCannotConfirm(){
  com.ruoyi.kangaroo.ext.user.KgDepartureNotice notice=new com.ruoyi.kangaroo.ext.user.KgDepartureNotice(db);long a=departureApplication(9903);
  db.update("update kg_application set status='withdrawn' where app_id=:a",p("a",a));
  code(409,()->tx.execute(s->notice.accept(9903L,a,p("agreed",true,"version",notice.read(9903L,a).get("version")))));
  db.update("update kg_job set job_type='parttime' where job_id=(select job_id from kg_application where app_id=:a)",p("a",a));
  tx.execute(s->{notice.requireAccepted(9903L,a);return null;});code(404,()->notice.read(9903L,a));
 }

 @Test void aNewLodgingDraftCannotDisablePreviouslyPublishedConsentGate(){
  String title=KgMerchantAgreements.title("stay");StaffSupport.Ctx c=ctx(801,901);
  db.update("update sys_notice set status='0' where notice_title=:t",p("t",title));
  long draft=db.insert("sys_notice",p("notice_title",title,"notice_type","1","notice_content","<p>New inactive draft</p>","status","1"));
  try{assertEquals(false,service.read(c,"stay").get("draft"));code(428,()->tx.execute(s->{service.requireAccepted(c,"stay");return null;}));}
  finally{db.update("delete from sys_notice where notice_id=:i",p("i",draft));db.update("update sys_notice set status='1' where notice_title=:t",p("t",title));}
 }
}
