package com.ruoyi.kangaroo.ext.governance;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.io.InputStream;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.domain.*;
import com.ruoyi.kangaroo.mapper.*;
import com.ruoyi.kangaroo.security.*;
import com.ruoyi.kangaroo.ext.staff.*;
import com.ruoyi.kangaroo.ext.community.*;
import com.ruoyi.kangaroo.controller.app.AppJobController;
import com.ruoyi.common.exception.ServiceException;

class CustomerScopeDatabaseTest {
 static KgJdbc db;static TransactionTemplate tx;static KgMessageMapper messages;static KgJobMapper jobs;
 @BeforeAll static void init()throws Exception {
  String name=System.getenv("KG_MEMBER_TEST_DB");assertNotNull(name);assertTrue(name.matches("kg_member_exec_[a-f0-9]{32}"));
  DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/"+name+"?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&sessionVariables=time_zone=%27%2B08:00%27",System.getenv("KG_MEMBER_TEST_USER"),System.getenv("KG_TEST_DB_PASSWORD"));
  db=new KgJdbc(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
  Configuration cfg=new Configuration(new Environment("isolated",new SpringManagedTransactionFactory(),ds));cfg.getTypeAliasRegistry().registerAlias("KgMessage",KgMessage.class);cfg.getTypeAliasRegistry().registerAlias("KgJob",KgJob.class);
  for(String n:new String[]{"KgMessage","KgJob"}){String r="mapper/kangaroo/"+n+"Mapper.xml";try(InputStream in=CustomerScopeDatabaseTest.class.getClassLoader().getResourceAsStream(r)){new XMLMapperBuilder(in,cfg,r,cfg.getSqlFragments()).parse();}}
  SqlSessionTemplate session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(cfg));messages=session.getMapper(KgMessageMapper.class);jobs=session.getMapper(KgJobMapper.class);
 }
 @BeforeEach void clean(){
  for(String t:new String[]{"kg_stay_after_refund","kg_stay_staff_refund","kg_pay_refund","kg_pay_order","kg_stay_order","kg_message","kg_chat_member","kg_chat_conv","kg_chat_reception","kg_insurance_apply","kg_online_submission","kg_online_settlement","kg_application","kg_job","kg_post","kg_activity","kg_staff_grant","kg_store","kg_app_user"})db.update("delete from "+t,p());
  for(long id=1;id<=3;id++){db.insert("kg_app_user",p("user_id",id,"openid","issue-test-"+id,"nickname","隔离测试"+id,"phone","1380000000"+id));db.insert("kg_store",p("store_id",id,"store_name","隔离门店"+id,"city","平潭"));}
  db.insert("kg_staff_grant",p("app_user_id",1L,"store_id",1L,"role_code","merchant","status","0","del_flag","0"));
 }
 @AfterEach void clearUser(){AppUserContext.remove();}
 long job(String desc){return db.insert("kg_job",p("store_id",1L,"title","测试换宿","city","平潭","job_type","volunteer","board_desc",desc,"status","published"));}
 long app(long uid,long job,String state){return db.insert("kg_application",p("app_no",UUID.randomUUID().toString().replace("-",""),"app_user_id",uid,"store_id",1L,"job_id",job,"status",state,"real_name","测试人","phone","13800000000"));}
 void notice(long id,long app,String mode,String read){db.insert("kg_message",p("msg_id",id,"recipient_id",1L,"mode",mode,"category","apply","biz_type","application","biz_id",app,"title","申请进度","read_flag",read,"read_time","2026-01-01 00:00:00","create_time","2026-01-01 00:00:00"));}
 @Test void selectedStoreFiltersUnreadReadPaginationAndWritesTogether(){
  long first=job("A"),a=app(2,first,"submitted"),second=job("B"),b=app(3,second,"submitted");
  db.update("update kg_job set store_id=2 where job_id=:j",p("j",second));db.update("update kg_application set store_id=2 where app_id=:a",p("a",b));
  db.insert("kg_staff_grant",p("app_user_id",1L,"store_id",2L,"role_code","merchant","status","0","del_flag","0"));
  notice(1,a,"staff","0");notice(2,b,"staff","0");notice(3,b,"staff","1");notice(4,a,"user","0");
  AppUserContext.messageScope(Arrays.asList(1L),"merchant");
  assertEquals(1,messages.countUnread(1L,"staff"));assertEquals(1,messages.selectAppMessagePage(1L,"staff",null,"apply",0,20).size());
  assertEquals(0,messages.markRead(2L,1L));assertEquals(0,messages.deleteOwnMessage(2L,1L,"staff",null));
  assertEquals(1,messages.markAllRead(1L,"staff",null,null));assertEquals("0",db.one("select read_flag from kg_message where msg_id=2",p()).get("readFlag"));
  AppUserContext.messageScope(Arrays.asList(2L),"merchant");assertEquals(1,messages.countUnread(1L,"staff"));assertEquals(2,messages.selectAppMessagePage(1L,"staff",null,"apply",0,20).size());
  assertEquals(1,messages.countUnread(1L,"user"));AppUserContext.remove();assertNull(AppUserContext.messageStores());
 }
 @Test void personalVolunteerNoticesStayOutOfStaffListsCountsAndBulkRead(){
  long j=job("隔离"),mine=app(1,j,"submitted"),other=app(2,j,"submitted");
  notice(1,mine,"user","0");notice(2,mine,"staff","0");notice(3,other,"staff","0");
  AppUserContext.messageScope(Arrays.asList(1L),"merchant");
  assertEquals(1,messages.countUnread(1L,"staff"));
  assertEquals(1,messages.selectAppMessagePage(1L,"staff",null,"apply",0,20).size());
  assertEquals(1,messages.selectStaffApplicationThreads(1L,0,20).size());
  assertEquals(1,messages.countUnread(1L,"user"));
  assertEquals(0,messages.markRead(2L,1L));
  assertEquals(0,messages.deleteOwnMessage(2L,1L,"staff",null));
  assertEquals(1,messages.markAllRead(1L,"staff",null,null));
  assertEquals("0",db.one("select read_flag from kg_message where msg_id=1",p()).get("readFlag"));
  assertEquals(1,messages.countUnread(1L,"user"));
 }
 @Test void cancelledActivityActuallyUsesActivityId(){
  long a=db.insert("kg_activity",p("source","user","app_user_id",1L,"title","测试活动","city","平潭","store_id",1L,"status","cancelled","start_time","2026-11-20 10:00:00","end_time","2026-11-20 12:00:00"));
  db.insert("kg_post",p("app_user_id",1L,"kind","buddy","title","已取消的活动","content","测试","city","平潭","activity_id",a,"status","online"));
  KgPostService service=new KgPostService();
  com.ruoyi.kangaroo.ext.user.KgCityService cities=mock(com.ruoyi.kangaroo.ext.user.KgCityService.class);
  when(cities.businessCity(any())).thenAnswer(i->i.getArgument(0));
  ReflectionTestUtils.setField(service,"db",db);ReflectionTestUtils.setField(service,"cities",cities);
  assertTrue(((List)service.feed(null,"buddy","平潭","new",2,null).get("rows")).isEmpty());
  assertTrue(((List)service.buddies(null,"平潭",null,null,null,false,null,null).get("rows")).isEmpty());
 }

 @Test void earlyCheckoutIsOnlyAReviewedRequestWithOwnerAndStoreChecks(){
  com.ruoyi.kangaroo.ext.pay.KgPayService pay=mock(com.ruoyi.kangaroo.ext.pay.KgPayService.class);
  StaffSupport support=mock(StaffSupport.class);
  com.ruoyi.kangaroo.ext.stay.KgStayAfterRefundService service=new com.ruoyi.kangaroo.ext.stay.KgStayAfterRefundService(db,pay,support);
  java.time.LocalDate today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
  String nightly="[{\"date\":\""+today.minusDays(1)+"\",\"priceFen\":1000},{\"date\":\""+today+"\",\"priceFen\":2000},{\"date\":\""+today.plusDays(1)+"\",\"priceFen\":3000}]";
  long oid=db.insert("kg_stay_order",p("order_no","QA109","stay_id",1L,"store_id",1L,"room_type_id",1L,"app_user_id",2L,"check_in",today.minusDays(1).toString(),"check_out",today.plusDays(2).toString(),"nights",3,"guest_name","测试","guest_phone","13800000000","pay_fen",5400,"nightly",nightly,"status","checked_in","pay_no","QAPAY109"));
  db.insert("kg_pay_order",p("pay_no","QAPAY109","biz_type","stay_order","biz_id",oid,"app_user_id",2L,"store_id",1L,"amount",5400,"description","隔离测试","channel","mock","status","paid"));
  assertEquals(4500L,service.overview(2L,"QA109").get("suggestedFen"));
  assertThrows(ServiceException.class,()->service.overview(3L,"QA109"));
  StaffSupport.Ctx c=new StaffSupport.Ctx();c.storeId=2L;
  assertThrows(ServiceException.class,()->service.staffApply(c,"QA109",4500,"提前退房","test-foreign"));
  c.storeId=1L;
  Map<String,Object> applied=tx.execute(t->service.staffApply(c,"QA109",4500,"提前退房","test-refund-1"));
  long id=KgJdbc.asLong(applied.get("requestId"));assertEquals("pending",applied.get("state"));
  assertEquals(id,KgJdbc.asLong(tx.execute(t->service.apply(2L,"QA109",4500,"提前退房","test-refund-1")).get("requestId")));
  assertEquals(1,db.count("select count(*) from kg_stay_after_refund",p()));
  assertThrows(ServiceException.class,()->tx.execute(t->service.apply(2L,"QA109",4500,"重复申请","test-refund-2")));
  assertThrows(ServiceException.class,()->tx.execute(t->service.review(id,true,"核实","测试审核员")));
  verify(pay,never()).refund(anyString(),anyLong(),anyString(),anyString());
  assertEquals("checked_in",db.one("select status from kg_stay_order where order_id=:id",p("id",oid)).get("status"));
  db.update("update kg_stay_order set status='completed',checked_out_time=now() where order_id=:id",p("id",oid));
  assertEquals("processing",tx.execute(t->service.review(id,true,"核实","测试审核员")).get("state"));
  verify(pay,times(1)).refund("QAPAY109",4500L,"提前退房","测试审核员");
  assertThrows(ServiceException.class,()->tx.execute(t->service.review(id,true,"核实","测试审核员")));
  assertEquals(1,db.count("select count(*) from kg_stay_staff_refund",p()));
 }
}
