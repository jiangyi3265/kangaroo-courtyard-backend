package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.*;
import com.ruoyi.kangaroo.domain.KgMessage;
import com.ruoyi.kangaroo.mapper.KgMessageMapper;
import com.ruoyi.kangaroo.service.impl.KgMessageServiceImpl;
import com.ruoyi.kangaroo.security.*;

/** Real isolated SQL and message preference service; WeChat is a mock and never delivers externally. */
class KgUrgentRecruitmentNotificationDatabaseTest {
    static KgJdbc db; static TransactionTemplate tx;
    KgDispatchService service; KgMessageServiceImpl messages; KgSubscribeService subscribe;
    KgMessageMapper mapper; StaffSupport.Ctx context;
    @BeforeAll static void database() {
        String name=System.getenv("KG_MEMBER_TEST_DB");assertNotNull(name);assertTrue(name.matches("kg_member_exec_[a-f0-9]{32}"));
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/"+name+"?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai",System.getenv("KG_MEMBER_TEST_USER"),System.getenv("KG_TEST_DB_PASSWORD"));
        db=new KgJdbc(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
    }
    @BeforeEach void setup() {
        for(String table:Arrays.asList("kg_message","kg_dispatch_log","kg_notify_pref","kg_job_favorite","kg_job_subscribe","kg_application","kg_staff_grant","kg_job","kg_store","kg_app_user"))db.update("delete from "+table,p());
        db.insert("kg_store",p("store_id",11,"store_name","本店","city","平潭","status","0","del_flag","0"));
        db.insert("kg_store",p("store_id",22,"store_name","外店","city","平潭","status","0","del_flag","0"));
        for(long id=100;id<=110;id++)db.insert("kg_app_user",p("user_id",id,"openid","urgent-test-"+id,"nickname","测试"+id,"status","0","del_flag",id==109?"2":"0"));
        job(11,11,"0");job(12,11,"0");job(13,11,"2");job(21,22,"0");
        favorite(101,11);favorite(102,12);favorite(108,21);favorite(110,13);
        follow(102,11,"0","instant",null);follow(102,11,"0","instant",null);follow(103,11,"0","instant",null);
        follow(104,11,"1","instant",null);follow(105,22,"0","instant",null);follow(106,11,"0","instant","摄影");
        follow(107,11,"0","daily",null);follow(109,11,"0","instant",null);
        mapper=mock(KgMessageMapper.class);subscribe=mock(KgSubscribeService.class);messages=spy(new KgMessageServiceImpl());
        ReflectionTestUtils.setField(messages,"jdbc",db);ReflectionTestUtils.setField(messages,"subscribe",subscribe);ReflectionTestUtils.setField(messages,"kgMessageMapper",mapper);
        when(mapper.insertKgMessage(any(KgMessage.class))).thenAnswer(i->{KgMessage m=i.getArgument(0);long id=db.insert("kg_message",p("recipient_id",m.getRecipientId(),"mode",m.getMode(),"category",m.getCategory(),"title",m.getTitle(),"content",m.getContent(),"biz_type",m.getBizType(),"biz_id",m.getBizId(),"route",m.getRoute(),"read_flag",m.getReadFlag(),"del_flag",m.getDelFlag(),"create_time",new Date()));m.setMsgId(id);return 1;});
        StaffSupport support=spy(new StaffSupport());ReflectionTestUtils.setField(support,"messages",messages);
        doNothing().when(support).op(any(),anyString(),anyString(),anyString(),anyString(),anyBoolean(),any());
        KgUserHubService hub=new KgUserHubService();ReflectionTestUtils.setField(hub,"db",db);
        service=new KgDispatchService();ReflectionTestUtils.setField(service,"db",db);ReflectionTestUtils.setField(service,"support",support);ReflectionTestUtils.setField(service,"hub",hub);
        context=new StaffSupport.Ctx();context.role="manager";context.storeId=11L;context.me=new AppLoginUser();context.me.setUserId(100L);context.me.setNickname("主理人");
        StaffGrantView grant=new StaffGrantView();grant.setStoreId(11L);grant.setRoleCode("manager");grant.setActive(true);context.me.setStaffGrants(Collections.singletonList(grant));
    }
    void job(long id,long store,String deleted){db.insert("kg_job",p("job_id",id,"store_id",store,"job_type","volunteer","title","急招换工","city","平潭","headcount",10,"hired_count",0,"status","published","del_flag",deleted));}
    void favorite(long user,long job){db.insert("kg_job_favorite",p("app_user_id",user,"job_id",job));}
    void follow(long user,long store,String status,String freq,String keyword){db.insert("kg_job_subscribe",p("app_user_id",user,"store_id",store,"status",status,"freq",freq,"keyword",keyword));}
    String deadline(){return StaffSupport.fmt(StaffSupport.addDays(new Date(),2),"yyyy-MM-dd HH:mm");}
    Map<String,Object> send(boolean push,List<String> targets){return tx.execute(s->service.urgent(context,11L,3,deadline(),targets,push));}
    long n(Map<String,Object> m,String key){return ((Number)m.get(key)).longValue();}
    @SuppressWarnings("unchecked") Map<String,Object> nested(Map<String,Object> m,String key){return (Map<String,Object>)m.get(key);}
    void onlyFollower(){db.update("delete from kg_job_favorite",p());db.update("delete from kg_job_subscribe where app_user_id<>103",p());}

    @Test void previewIncludesOnlyActiveStoreFollowersAndFavoritesWithOverlapRemoved(){
        Map<String,Object> info=service.urgentInfo(context,11L);
        assertEquals(4,n(info,"subscribers"));assertEquals(2,n(nested(info,"subscriberBreakdown"),"favorites"));
        assertEquals(3,n(nested(info,"subscriberBreakdown"),"followers"));
    }
    @Test void actualInboxAndSubscriptionPipelineAreDeduplicatedAndThrottled(){
        Map<String,Object> result=send(true,Arrays.asList("favorites"));
        assertEquals(4,n(result,"usersMatched"));assertEquals(3,n(result,"usersNotified"));assertEquals(1,n(result,"usersSkipped"));
        assertEquals(1,n(nested(result,"skipReasons"),"dailyDigest"));
        assertEquals(3,db.count("select count(*) from kg_message where biz_type='job_urgent' and biz_id=11 and route='/pages/jobs/detail?jobId=11'",p()));
        assertEquals(1,db.count("select count(*) from kg_message where recipient_id=103",p()),"An explicitly followed store now reaches its follower");
        verify(subscribe,times(3)).push(anyLong(),eq("job_new"),anyString(),anyString(),eq("/pages/jobs/detail?jobId=11"),anyLong());
        result=send(true,Arrays.asList("favorites"));assertEquals(true,result.get("throttled"));assertEquals(0,n(result,"usersNotified"));
        assertEquals(4,n(nested(result,"skipReasons"),"recent"));assertEquals(3,db.count("select count(*) from kg_message",p()));
    }
    @Test void pushOffStillMarksPublicUrgencyWithoutSendingToSubscribers(){
        Map<String,Object> result=send(false,Collections.emptyList());
        assertEquals(0,n(result,"usersMatched"));assertEquals(0,n(result,"usersNotified"));
        assertEquals(3,db.count("select urgent_level from kg_job where job_id=11",p()));
        assertEquals(0,db.count("select count(*) from kg_message",p()));verifyNoInteractions(subscribe);
    }
    @Test void preferencesQuietHoursPastCandidatesAndDailyChoiceRemainRespected(){
        db.insert("kg_notify_pref",p("app_user_id",101,"pref_key","job_new","enabled","0"));
        java.time.format.DateTimeFormatter hm=java.time.format.DateTimeFormatter.ofPattern("HH:mm");
        String quiet=java.time.LocalTime.now().minusHours(1).format(hm)+"-"+java.time.LocalTime.now().plusHours(1).format(hm);
        db.insert("kg_notify_pref",p("app_user_id",102,"pref_key","quiet","enabled","1","freq",quiet));
        db.insert("kg_application",p("app_no","URGENT-PAST","job_id",11,"store_id",11,"app_user_id",103,"real_name","候选人","phone","13800000000","status","withdrawn","del_flag","0"));
        Map<String,Object> result=send(true,Collections.emptyList());
        assertEquals(0,n(result,"usersNotified"));assertEquals(4,n(result,"usersSkipped"));
        Map<String,Object> reasons=nested(result,"skipReasons");
        for(String key:Arrays.asList("preferenceOff","quietHours","pastApplication","dailyDigest"))assertEquals(1,n(reasons,key),key);
        assertEquals(0,db.count("select count(*) from kg_dispatch_log where reason like '%推送用户%'",p()));verifyNoInteractions(subscribe);
    }
    @Test void nullMessageIdDoesNotClaimSuccessOrConsumeCooldown(){
        onlyFollower();
        doReturn(null).when(messages).send(eq(103L),eq("user"),anyString(),anyString(),anyString(),eq("job_urgent"),eq(11L),anyString());
        Map<String,Object> result=send(true,Collections.emptyList());assertEquals(0,n(result,"usersNotified"));assertEquals(1,n(nested(result,"skipReasons"),"notWritten"));
        assertEquals(0,db.count("select count(*) from kg_dispatch_log where reason like '%推送用户%'",p()));
        doCallRealMethod().when(messages).send(eq(103L),eq("user"),anyString(),anyString(),anyString(),eq("job_urgent"),eq(11L),anyString());
        result=send(true,Collections.emptyList());assertEquals(false,result.get("throttled"));assertEquals(1,n(result,"usersNotified"));
    }
    @Test void concurrentMarkingSendsOnlyOneUserNotificationWindow()throws Exception{
        onlyFollower();ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{
            Callable<Map<String,Object>> task=()->{start.await();return send(true,Collections.emptyList());};
            Future<Map<String,Object>> a=pool.submit(task),b=pool.submit(task);start.countDown();
            assertEquals(1,n(a.get(15,TimeUnit.SECONDS),"usersNotified")+n(b.get(15,TimeUnit.SECONDS),"usersNotified"));
            assertEquals(1,db.count("select count(*) from kg_message",p()));
        }finally{pool.shutdownNow();}
    }
    @Test void pausedAndUnauthorizedJobsNeverSend(){
        assertThrows(ServiceException.class,()->tx.execute(s->service.urgent(context,21L,3,deadline(),Collections.emptyList(),true)));
        db.update("update kg_job set status='paused' where job_id=11",p());assertThrows(ServiceException.class,()->send(true,Collections.emptyList()));
        assertEquals(0,db.count("select count(*) from kg_dispatch_log",p()));assertEquals(0,db.count("select count(*) from kg_message",p()));
    }
    @Test void staffTargetsDeduplicateCityGrantsAndUseTheCorrectLandingPage(){
        db.insert("kg_staff_grant",p("app_user_id",101,"store_id",11,"role_code","manager","status","0","del_flag","0"));
        db.insert("kg_staff_grant",p("app_user_id",102,"store_id",11,"role_code","staff","status","0","del_flag","0"));
        db.insert("kg_staff_grant",p("app_user_id",105,"store_id",22,"role_code","manager","status","0","del_flag","0"));
        db.insert("kg_staff_grant",p("app_user_id",109,"store_id",11,"role_code","manager","status","0","del_flag","0"));
        CityGrantScope city=mock(CityGrantScope.class);when(city.holders(11L)).thenReturn(Arrays.asList(p("appUserId",101L,"roleCode","manager"),p("appUserId",109L,"roleCode","manager")));
        ReflectionTestUtils.setField(service,"cityGrants",city);
        @SuppressWarnings("unchecked") List<Map<String,Object>> targets=(List<Map<String,Object>>)service.urgentInfo(context,11L).get("targets");
        assertEquals(1,n(targets.get(0),"count"));
        Map<String,Object> result=send(false,Arrays.asList("manager","staff","city"));assertEquals(3,n(result,"staffNotified"));
        assertEquals(2,db.count("select count(*) from kg_message where route='/pages/staff/dispatch?s=3&jobId=11' and mode='staff'",p()));
        assertEquals(1,db.count("select count(*) from kg_message where recipient_id=105 and route='/pages/jobs/detail?jobId=11'",p()));
        assertEquals(0,db.count("select count(*) from kg_message where recipient_id=109",p()));
    }
}
