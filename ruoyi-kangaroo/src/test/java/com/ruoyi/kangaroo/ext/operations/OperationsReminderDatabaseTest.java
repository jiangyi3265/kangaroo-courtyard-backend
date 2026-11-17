package com.ruoyi.kangaroo.ext.operations;

import java.time.*;
import java.util.*;
import java.io.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.*;
import com.ruoyi.kangaroo.ext.manage.KgManageService;
import com.ruoyi.kangaroo.ext.user.KgCityService;
import com.ruoyi.kangaroo.security.admin.*;
import com.ruoyi.kangaroo.service.impl.KgMessageServiceImpl;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.operations.OpsRules.*;

/** Real MySQL, the production mapper and guard. No records or notifications on the live service. */
class OperationsReminderDatabaseTest {
    static final long CITY=198001,OTHER=198002,KEEPER=199001,MANAGER=199002,SECOND=199003,STRANGER=199004,STORE=197001;
    static KgJdbc db;static TransactionTemplate tx;static OpsScope scope;static OpsDaily daily;static OpsReminders reminders;static KgManageService weekly;static StaffSupport staff;static String previousPhase;
    static void wire(Object o,Object...args){for(int i=0;i<args.length;i+=2)ReflectionTestUtils.setField(o,args[i].toString(),args[i+1]);}
    @BeforeAll static void init()throws Exception{
        String name=System.getenv("KG_MEMBER_TEST_DB");assertTrue(name!=null&&name.matches("kg_member_exec_[a-f0-9]{32}"));
        DataSource ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/"+name+"?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai",System.getenv("KG_MEMBER_TEST_USER"),System.getenv("KG_TEST_DB_PASSWORD"));
        db=new KgJdbc(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));AdminCitySqlGuard guard=new AdminCitySqlGuard(ds);guard.init();wire(db,"adminCityGuard",guard);
        org.apache.ibatis.session.Configuration cfg=new org.apache.ibatis.session.Configuration(new org.apache.ibatis.mapping.Environment("isolated",new org.mybatis.spring.transaction.SpringManagedTransactionFactory(),ds));cfg.getTypeAliasRegistry().registerAlias("KgMessage",com.ruoyi.kangaroo.domain.KgMessage.class);cfg.addInterceptor(new AdminCityMybatisInterceptor(guard));
        String resource="mapper/kangaroo/KgMessageMapper.xml";try(InputStream stream=OperationsReminderDatabaseTest.class.getClassLoader().getResourceAsStream(resource)){new org.apache.ibatis.builder.xml.XMLMapperBuilder(stream,cfg,resource,cfg.getSqlFragments()).parse();}
        com.ruoyi.kangaroo.mapper.KgMessageMapper mapper=new org.mybatis.spring.SqlSessionTemplate(new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(cfg)).getMapper(com.ruoyi.kangaroo.mapper.KgMessageMapper.class);
        KgMessageServiceImpl messaging=new KgMessageServiceImpl();wire(messaging,"kgMessageMapper",mapper,"jdbc",db);staff=new StaffSupport();wire(staff,"messages",messaging);
        previousPhase=RolePhase.code();RolePhase.set("new");KgCityService cities=mock(KgCityService.class);when(cities.jobCity(anyString())).thenAnswer(i->i.getArgument(0));CityGrantScope grants=new CityGrantScope();wire(grants,"db",db,"cities",cities);CityLeadScope leads=new CityLeadScope();wire(leads,"db",db,"cities",cities,"cityGrants",grants);
        scope=new OpsScope();wire(scope,"db",db,"cityGrants",grants,"leads",leads);OpsMetrics metrics=new OpsMetrics();wire(metrics,"db",db);OpsTargets targets=new OpsTargets();wire(targets,"db",db,"scope",scope,"metrics",metrics);OpsForms forms=new OpsForms();wire(forms,"db",db,"scope",scope);daily=new OpsDaily();wire(daily,"db",db,"scope",scope,"metrics",metrics,"targets",targets,"forms",forms);
        weekly=new KgManageService();wire(weekly,"db",db,"cityGrants",grants,"cityLeads",leads,"cities",cities);
        reminders=new OpsReminders();wire(reminders,"db",db,"scope",scope,"daily",daily,"weekly",weekly,"staff",staff,"dataSource",ds);
        db.update("delete from kg_city where city_id in (198001,198002)",p());db.update("delete from kg_app_user where user_id in (199001,199002,199003,199004)",p());db.update("delete from kg_store where store_id in (197001,197002,197003,197004)",p());
        for(long c:Arrays.asList(CITY,OTHER))db.insert("kg_city",p("city_id",c,"name","催报测试市"+c,"full_name","催报测试市"+c,"status","0"));
        for(long uid:Arrays.asList(KEEPER,MANAGER,SECOND,STRANGER))db.insert("kg_app_user",p("user_id",uid,"openid","reminder-fixture-"+uid,"nickname","催报测试人"+uid,"status","0","del_flag","0"));
        for(long sid:Arrays.asList(STORE,197002L,197003L,197004L))db.insert("kg_store",p("store_id",sid,"store_name","催报测试小院"+sid,"city","催报测试市"+(sid==197004?OTHER:CITY),"status","0","del_flag","0","op_type","merchant"));
    }
    @AfterAll static void phase(){RolePhase.set(previousPhase);}
    @BeforeEach void reset(){AdminCityContext.clear();db.update("delete from kg_ops_daily_change",p());db.update("delete from kg_ops_form_change",p());db.update("update kg_ops_form set revision=0,form_json=null",p());for(String t:Arrays.asList("kg_ops_reminder_item","kg_ops_reminder_batch","kg_ops_daily","kg_ops_target","kg_ops_notice","kg_message","kg_weekly_report","kg_staff_grant"))db.update("delete from "+t,p());grant(KEEPER,STORE,"keeper");grant(MANAGER,197003L,"manager");grant(MANAGER,197002L,"manager");grant(STRANGER,197004L,"keeper");at("2026-11-15T18:10:00");wire(reminders,"staff",staff);}
    @AfterEach void clear(){AdminCityContext.clear();}
    static void at(String t){Clock c=Clock.fixed(LocalDateTime.parse(t).atZone(ZONE).toInstant(),ZONE);reminders.clock=c;daily.clock=c;}
    static void grant(long u,long s,String role){db.insert("kg_staff_grant",p("app_user_id",u,"store_id",s,"role_code",role,"status","0","del_flag","0"));}
    List<Map<String,Object>> preview(String kind){return (List<Map<String,Object>>)reminders.preview(CITY,kind,"2026-11-15","",null).get("rows");}
    Map<String,Object> request(String kind,long uid){List<String> keys=new ArrayList<>();for(Map<String,Object> r:preview(kind))if(number(r.get("recipientId"))==uid)keys.add(text(r.get("targetKey")));return p("requestId",UUID.randomUUID().toString(),"cityId",CITY,"kind",kind,"date","2026-11-15","targetKeys",keys);}
    Map<String,Object> send(Map<String,Object> req){return tx.execute(s->reminders.send(req,1,"测试管理员"));}
    long count(String t){return number(db.one("select count(*) n from "+t,p()).get("n"));}
    Map<String,Object> item(Map<String,Object> batch){return ((List<Map<String,Object>>)batch.get("rows")).get(0);}
    @Test void previewIsReadOnlyAndExcludesOtherCities(){List<Map<String,Object>> rows=preview("daily");assertEquals(2,rows.size());assertTrue(rows.stream().noneMatch(r->number(r.get("recipientId"))==STRANGER));assertEquals(0,count("kg_ops_daily"));assertEquals(0,count("kg_message"));assertEquals(0,count("kg_ops_reminder_batch"));}
    @Test void dailyMessageReadSubmitClosesPendingTask(){Map<String,Object> batch=send(request("daily",KEEPER));assertEquals(1L,number(batch.get("sentCount")));assertEquals(1L,number(batch.get("pendingCount")));Map<String,Object> item=item(batch);long report=number(item.get("reportId"));Map<String,Object> message=db.one("select * from kg_message where msg_id=:id",p("id",item.get("messageId")));assertEquals("staff",message.get("mode"));assertEquals("/pages/staff/operations?reportId="+report,message.get("route"));
        db.update("update kg_message set read_flag='1',read_time=now() where msg_id=:id",p("id",item.get("messageId")));assertEquals(1L,number(reminders.detail(number(batch.get("batchId"))).get("readCount")));
        Map<String,Object> draft=daily.get(KEEPER,report);Map<String,Object> manual=p("tomorrowPlan","核对门店","facilities","无","shortages","无","yesterdayIssues","无","activityPlan","徒步计划","offlineIncome","0","expenses",new ArrayList<>(),"remedies",p());tx.execute(s->daily.save(KEEPER,report,p("revision",draft.get("revision"),"manual",manual),true));Map<String,Object> after=reminders.detail(number(batch.get("batchId")));assertEquals(1L,number(after.get("submittedCount")));assertEquals(0L,number(after.get("pendingCount")));assertEquals("已提交",item(after).get("trackingStatus"));assertFalse((Boolean)preview("daily").get(0).get("eligible"));}
    @Test void retriesAndDailyDedupDoNotResend(){Map<String,Object> req=request("daily",KEEPER),first=send(req);assertEquals(first.get("batchId"),send(req).get("batchId"));assertEquals(1,count("kg_message"));req.put("requestId",UUID.randomUUID().toString());Map<String,Object> duplicate=send(req);assertEquals(0L,number(duplicate.get("sentCount")));assertEquals(1L,number(duplicate.get("skippedCount")));assertEquals(1,count("kg_message"));at("2026-11-16T10:00:00");req.put("requestId",UUID.randomUUID().toString());assertEquals(1L,number(send(req).get("sentCount")));assertEquals(2,count("kg_message"));}
    @Test void requestCannotBeReusedForAnotherPayload(){Map<String,Object> req=request("daily",KEEPER);send(req);Map<String,Object> other=request("daily",MANAGER);other.put("requestId",req.get("requestId"));assertThrows(ServiceException.class,()->send(other));assertEquals(1,count("kg_message"));}
    @Test void concurrentRequestsProduceOnlyOneNotification()throws Exception{
        tx.execute(s->daily.open(KEEPER,CITY,"keeper","2026-11-15"));Map<String,Object> a=request("daily",KEEPER),b=request("daily",KEEPER);
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{List<java.util.concurrent.Future<Map<String,Object>>> work=new ArrayList<>();for(Map<String,Object> req:Arrays.asList(a,b))work.add(pool.submit(()->{try{return send(req);}catch(org.springframework.dao.ConcurrencyFailureException retry){return send(req);}}));for(java.util.concurrent.Future<Map<String,Object>> f:work)f.get(30,java.util.concurrent.TimeUnit.SECONDS);assertEquals(1,count("kg_message"));assertEquals(2,count("kg_ops_reminder_batch"));assertEquals(1L,number(db.one("select count(*) n from kg_ops_reminder_item where delivery_status='sent'",p()).get("n")));}finally{pool.shutdownNow();}
    }
    @Test void revokedRecipientFailsClosedAndRollsBackBatch(){Map<String,Object> req=request("daily",KEEPER);db.update("update kg_staff_grant set status='1' where app_user_id=:u",p("u",KEEPER));assertThrows(ServiceException.class,()->send(req));assertEquals(0,count("kg_message"));assertEquals(0,count("kg_ops_reminder_batch"));}
    @Test void cityLimitedAdminCanSendAndOtherCityCannotRead(){Map<String,Object> req=request("daily",KEEPER);AdminCityContext.set(Arrays.asList(CITY));Map<String,Object> sent=send(req);assertEquals(Arrays.asList(CITY),AdminCityContext.cities());assertEquals(1L,number(sent.get("sentCount")));assertEquals(1,reminders.history(CITY).size());assertThrows(ServiceException.class,()->reminders.preview(OTHER,"daily","2026-11-15","",null));AdminCityContext.set(Arrays.asList(OTHER));assertThrows(ServiceException.class,()->reminders.detail(number(sent.get("batchId"))));assertEquals(0,reminders.history(OTHER).size());AdminCityContext.set(Collections.emptyList());assertThrows(ServiceException.class,()->reminders.history(CITY));}
    @Test void weeklyUsesExistingRecruitmentAnchorNotSmallestStoreId(){Map<String,Object> r=preview("weekly").stream().filter(x->number(x.get("recipientId"))==MANAGER).findFirst().get();assertEquals(197003L,number(r.get("targetStoreId")));Map<String,Object> batch=send(request("weekly",MANAGER));Map<String,Object> item=item(batch);Map<String,Object> report=db.one("select * from kg_weekly_report where report_id=:id",p("id",item.get("reportId")));assertEquals(197003L,number(report.get("storeId")));assertEquals("2026-11-09",report.get("weekStart"));assertEquals("recruit",report.get("reportType"));Map<String,Object> message=db.one("select route from kg_message where msg_id=:id",p("id",item.get("messageId")));assertEquals("/pages/manage/weekly?reportId="+report.get("reportId")+"&as=recruit",message.get("route"));}
    @Test void sharedWeeklySubmissionClosesBothRecipients(){grant(SECOND,STORE,"keeper");Map<String,Object> a=send(request("weekly",KEEPER)),b=send(request("weekly",SECOND));assertEquals(item(a).get("reportId"),item(b).get("reportId"));db.update("update kg_weekly_report set status='submitted',submitted_time=now() where report_id=:id",p("id",item(a).get("reportId")));for(Map<String,Object> batch:Arrays.asList(a,b))assertEquals(0L,number(reminders.detail(number(batch.get("batchId"))).get("pendingCount")));}
    @Test void submissionAfterPreviewIsSkipped(){Map<String,Object> req=request("weekly",KEEPER);long id=weekly.openWeeklyReminder(KEEPER,STORE,"keeper",scope.cityStores(CITY),"2026-11-09");db.update("update kg_weekly_report set status='submitted',submitted_time=now() where report_id=:id",p("id",id));Map<String,Object> b=send(req);assertEquals(0L,number(b.get("sentCount")));assertEquals(1L,number(b.get("skippedCount")));assertEquals(0,count("kg_message"));}
    @Test void failureRollsBackAndRestoresAdminScope(){StaffSupport broken=mock(StaffSupport.class);when(broken.notifyStaffWithId(anyLong(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString())).thenThrow(new IllegalStateException("synthetic failure"));wire(reminders,"staff",broken);Map<String,Object> req=request("daily",KEEPER);AdminCityContext.set(Arrays.asList(CITY));assertThrows(IllegalStateException.class,()->send(req));assertEquals(Arrays.asList(CITY),AdminCityContext.cities());assertEquals(0,count("kg_ops_reminder_batch"));assertEquals(0,count("kg_ops_reminder_item"));wire(reminders,"staff",staff);assertEquals(1L,number(send(req).get("sentCount")));}
    @Test void missingHistoricalSnapshotAndFutureDatesAreNotFabricated(){Map<String,Object> r=reminders.preview(CITY,"daily","2026-11-14","keeper",KEEPER);assertEquals(0L,number(r.get("eligibleCount")));assertThrows(ServiceException.class,()->reminders.preview(CITY,"daily","2026-11-16","",null));assertThrows(ServiceException.class,()->reminders.preview(CITY,"weekly","2026-11-16","",null));assertEquals(0,count("kg_ops_daily"));}
    @Test void withdrawnStoreNoLongerAppearsAndManualKeysCannotAddIt(){Map<String,Object> req=request("weekly",KEEPER);db.update("update kg_store set status='1' where store_id=:s",p("s",STORE));try{assertThrows(ServiceException.class,()->send(req));assertEquals(0,count("kg_message"));}finally{db.update("update kg_store set status='0' where store_id=:s",p("s",STORE));}}
}
