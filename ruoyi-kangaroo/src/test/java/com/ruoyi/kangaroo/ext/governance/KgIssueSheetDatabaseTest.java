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

class KgIssueSheetDatabaseTest {
 static KgJdbc db;static TransactionTemplate tx;static KgMessageMapper messages;static KgJobMapper jobs;
 @BeforeAll static void init()throws Exception {
  String name=System.getenv("KG_MEMBER_TEST_DB");assertNotNull(name);assertTrue(name.matches("kg_member_exec_[a-f0-9]{32}"));
  DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/"+name+"?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&sessionVariables=time_zone=%27%2B08:00%27",System.getenv("KG_MEMBER_TEST_USER"),System.getenv("KG_TEST_DB_PASSWORD"));
  db=new KgJdbc(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
  Configuration cfg=new Configuration(new Environment("isolated",new SpringManagedTransactionFactory(),ds));cfg.getTypeAliasRegistry().registerAlias("KgMessage",KgMessage.class);cfg.getTypeAliasRegistry().registerAlias("KgJob",KgJob.class);
  for(String n:new String[]{"KgMessage","KgJob"}){String r="mapper/kangaroo/"+n+"Mapper.xml";try(InputStream in=KgIssueSheetDatabaseTest.class.getClassLoader().getResourceAsStream(r)){new XMLMapperBuilder(in,cfg,r,cfg.getSqlFragments()).parse();}}
  SqlSessionTemplate session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(cfg));messages=session.getMapper(KgMessageMapper.class);jobs=session.getMapper(KgJobMapper.class);
 }
 @BeforeEach void clean(){
  for(String t:new String[]{"kg_message","kg_chat_member","kg_chat_conv","kg_chat_reception","kg_insurance_apply","kg_online_submission","kg_online_settlement","kg_application","kg_job","kg_post","kg_activity","kg_staff_grant","kg_store","kg_app_user"})db.update("delete from "+t,p());
  for(long id=1;id<=3;id++){db.insert("kg_app_user",p("user_id",id,"openid","issue-test-"+id,"nickname","隔离测试"+id,"phone","1380000000"+id));db.insert("kg_store",p("store_id",id,"store_name","隔离门店"+id,"city","平潭"));}
  db.insert("kg_staff_grant",p("app_user_id",1L,"store_id",1L,"role_code","merchant","status","0","del_flag","0"));
 }
 @AfterEach void clearUser(){AppUserContext.remove();}
 long job(String desc){return db.insert("kg_job",p("store_id",1L,"title","测试换宿","city","平潭","job_type","volunteer","board_desc",desc,"status","published"));}
 long app(long uid,long job,String state){return db.insert("kg_application",p("app_no",UUID.randomUUID().toString().replace("-",""),"app_user_id",uid,"store_id",1L,"job_id",job,"status",state,"real_name","测试人","phone","13800000000"));}
 void notice(long id,long app,String mode,String read){db.insert("kg_message",p("msg_id",id,"recipient_id",1L,"mode",mode,"category","apply","biz_type","application","biz_id",app,"title","申请进度","read_flag",read,"read_time","2026-01-01 00:00:00","create_time","2026-01-01 00:00:00"));}
 @Test void applicationThreadsGroupByPersonAndKeepAllHistory(){
  long j=job("独卫"),a=app(2,j,"submitted"),b=app(3,j,"submitted");notice(1,a,"staff","1");notice(2,a,"staff","0");notice(3,b,"staff","0");notice(4,a,"user","0");
  List<Map<String,Object>> threads=messages.selectStaffApplicationThreads(1L,0,20);assertEquals(2,threads.size());
  Map<String,Object> person=threads.stream().filter(x->((Number)x.get("personId")).longValue()==2).findFirst().get();assertEquals(2L,((Number)person.get("threadCount")).longValue());
  assertEquals(2,messages.selectStaffApplicationThreadMessages(1L,2L,0,20).size());assertEquals(1,messages.markRead(2L,1L));assertEquals(1,messages.countUnread(1L,"staff"));
  assertEquals(1,messages.selectStaffApplicationThreads(1L,0,1).size());assertEquals(1,messages.selectStaffApplicationThreads(1L,1,1).size());
 }
 @Test void chatsFollowSeatIdentityIncludingLegacyWrongMode(){
  db.insert("kg_chat_conv",p("conv_id",91L,"conv_type","direct","pair_key","d:1:2"));db.insert("kg_chat_member",p("conv_id",91L,"app_user_id",1L,"role","member"));
  notice(1,91,"staff","0");notice(2,91,"user","1");db.update("update kg_message set category='chat',biz_type='chat_conv'",p());
  assertTrue(messages.selectAppMessagePage(1L,"staff",null,"main",0,20).isEmpty());assertEquals(1,messages.selectAppMessagePage(1L,"user",null,"main",0,20).size());
  assertEquals(0,messages.countUnread(1L,"staff"));assertEquals(1,messages.selectAppMessages(1L,"user",null).size());
 }
 @Test void bathroomFiltersUseExplicitDescriptionBeforePagination(){
  long priv=job("双人间 独立卫生间"),shared=job("四人间 公共卫生间");job("无独立卫生间，房型待定");job("房型待定");
  KgJob q=new KgJob();q.setJobType("volunteer");q.getParams().put("bathroom","private");List<KgJob> a=jobs.selectAppJobList(q);assertEquals(1,a.size());assertEquals(priv,a.get(0).getJobId());
  q.getParams().put("bathroom","shared");List<KgJob> b=jobs.selectAppJobList(q);assertEquals(1,b.size());assertEquals(shared,b.get(0).getJobId());
 }
 @Test void onlineApplicationStatusIsCurrentAccountOnly(){
  long j=job("");db.update("update kg_job set job_type='online' where job_id=:j",p("j",j));long a=app(1,j,"submitted");app(2,j,"onboarded");
  AppJobController c=new AppJobController();ReflectionTestUtils.setField(c,"db",db);AppLoginUser me=new AppLoginUser();me.setUserId(1L);AppUserContext.set(me);
  Map<String,Object> row=p("jobId",j,"jobType","online");List<Map<String,Object>> rows=Collections.singletonList(row);
  ReflectionTestUtils.invokeMethod(c,"attachMyApplications",rows);assertEquals(a,((Map<?,?>)row.get("myApplication")).get("appId"));assertEquals("审核中 · 查看进度",((Map<?,?>)row.get("myApplication")).get("label"));
  db.update("update kg_application set status='store_confirmed' where app_id=:a",p("a",a));ReflectionTestUtils.invokeMethod(c,"attachMyApplications",rows);assertEquals("提交成果",((Map<?,?>)row.get("myApplication")).get("label"));
  me.setUserId(3L);Map<String,Object> other=p("jobId",j,"jobType","online");ReflectionTestUtils.invokeMethod(c,"attachMyApplications",Collections.singletonList(other));assertFalse(other.containsKey("myApplication"));
 }
 @Test void merchantInsuranceUsesActualRecruitingStoreAndDeniesManagementStore(){
  long j=job("");db.update("update kg_job set recruit_store_id=2 where job_id=:j",p("j",j));long a=app(3,j,"onboarded");
  KgInsuranceService service=new KgInsuranceService();ReflectionTestUtils.setField(service,"db",db);ReflectionTestUtils.setField(service,"support",mock(StaffSupport.class));
  StaffSupport.Ctx c=new StaffSupport.Ctx();c.storeId=2L;c.role="merchant";c.me=new AppLoginUser();c.me.setUserId(2L);StaffGrantView grant=new StaffGrantView();grant.setStoreId(2L);grant.setRoleCode("merchant");grant.setActive(true);c.me.setStaffGrants(Collections.singletonList(grant));
  Map<String,Object> detail=service.forApplication(c,a);assertEquals(Boolean.TRUE,((Map<?,?>)detail.get("candidate")).get("insurable"));
  long plan=db.insert("kg_insurance_plan",p("plan_name","测试保险","days",30,"price",0));
  tx.execute(s->service.apply(c,a,plan,String.valueOf(detail.get("defaultStart"))));assertEquals(1,db.count("select count(*) from kg_insurance_apply where app_id=:a and store_id=2",p("a",a)));
  c.storeId=1L;assertThrows(ServiceException.class,()->service.forApplication(c,a));
 }
 @Test void cancellingLinkedActivityAndRemovingPostIsAtomicAndOwnerOnly(){
  long a=db.insert("kg_activity",p("app_user_id",1L,"source","user","title","测试活动","start_time",Governance.now().plusDays(1),"status","open","store_id",1L));long post=db.insert("kg_post",p("app_user_id",1L,"activity_id",a,"content","测试动态","status","online"));
  KgPostService service=new KgPostService();ReflectionTestUtils.setField(service,"db",db);KgActivityService act=mock(KgActivityService.class);ReflectionTestUtils.setField(service,"activities",act);
  assertThrows(ServiceException.class,()->tx.execute(s->service.removeWithActivity(post,2L,true,"取消")));
  doAnswer(i->{db.update("update kg_activity set status='cancelled' where activity_id=:a",p("a",a));throw new ServiceException("模拟取消失败");}).when(act).actAsUser(eq(1L),eq(a),eq("cancel"),isNull(),isNull(),anyString());
  assertThrows(ServiceException.class,()->tx.execute(s->service.removeWithActivity(post,1L,true,"取消")));assertEquals("0",db.one("select del_flag from kg_post where post_id=:p",p("p",post)).get("delFlag"));assertEquals("open",db.one("select status from kg_activity where activity_id=:a",p("a",a)).get("status"));
  doAnswer(i->{db.update("update kg_activity set status='cancelled' where activity_id=:a",p("a",a));return "已取消";}).when(act).actAsUser(eq(1L),eq(a),eq("cancel"),isNull(),isNull(),anyString());
  tx.execute(s->service.removeWithActivity(post,1L,true,"取消"));assertEquals("2",db.one("select del_flag from kg_post where post_id=:p",p("p",post)).get("delFlag"));assertEquals("cancelled",db.one("select status from kg_activity where activity_id=:a",p("a",a)).get("status"));
 }
 @Test void activityFiltersDistinguishEndedRunningOpenAndCancelled(){
  for(String phase:new String[]{"ended","ongoing","open"}){
   String clause=ReflectionTestUtils.invokeMethod(KgPostService.class,"activityPhaseSql",phase);assertNotNull(clause);
   db.update("delete from kg_activity",p());
   for(String state:new String[]{"ended","cancelled","open"})db.insert("kg_activity",p("store_id",1L,"title",state,"source","user","status",state,"start_time",Governance.now().minusHours(1),"end_time",Governance.now().plusHours(1),"capacity",6,"signed",0));
   long count=db.count("select count(*) from kg_activity ap where "+clause,p());assertEquals("open".equals(phase)?0:1,count);
  }
 }
}
