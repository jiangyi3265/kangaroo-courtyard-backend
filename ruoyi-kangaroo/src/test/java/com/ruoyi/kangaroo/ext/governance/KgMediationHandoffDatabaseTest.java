package com.ruoyi.kangaroo.ext.governance;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.CityLeadScope;
import com.ruoyi.kangaroo.security.*;
import com.ruoyi.kangaroo.mapper.KgMessageMapper;
import com.ruoyi.kangaroo.domain.KgMessage;
import com.ruoyi.kangaroo.service.*;

/** Actual candidate SQL, service transactions and message ACL, only synthetic isolated records. */
class KgMediationHandoffDatabaseTest {
    static KgJdbc db;static TransactionTemplate tx;static KgMessageMapper mapper;
    KgMediationService service;KgMediationOrders orders;IKgMessageService messages;IKgStaffGrantService grants;
    String note="店家已核对住宿安排并补充现场照片，请主理人协助双方沟通。";
    @BeforeAll static void init()throws Exception{
        String name=System.getenv("KG_MESSAGE_SCOPE_TEST_DB");assertNotNull(name);assertTrue(name.matches("kg_message_scope_[a-f0-9]{32}"));
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/"+name+"?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai",System.getenv("KG_MESSAGE_SCOPE_TEST_USER"),System.getenv("KG_TEST_DB_PASSWORD"));
        db=new KgJdbc(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));tx.setIsolationLevel(java.sql.Connection.TRANSACTION_READ_COMMITTED);
        Configuration cfg=new Configuration(new Environment("isolated",new SpringManagedTransactionFactory(),ds));cfg.getTypeAliasRegistry().registerAlias("KgMessage",KgMessage.class);
        String r="mapper/kangaroo/KgMessageMapper.xml";try(InputStream in=KgMediationHandoffDatabaseTest.class.getClassLoader().getResourceAsStream(r)){new XMLMapperBuilder(in,cfg,r,cfg.getSqlFragments()).parse();}
        mapper=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(cfg)).getMapper(KgMessageMapper.class);
    }
    static void field(Object o,String k,Object v){ReflectionTestUtils.setField(o,k,v);}static <T>T in(Supplier<T> f){return tx.execute(s->f.get());}
    @BeforeEach void setup(){
        AppUserContext.remove();for(String t:Arrays.asList("kg_message","kg_mediation_event","kg_mediation_log","kg_mediation_file","kg_mediation_round","kg_mediation_case","kg_staff_grant","kg_application","kg_job","kg_store","kg_app_user"))db.update("delete from "+t,p());
        for(long u=1;u<=10;u++)db.insert("kg_app_user",p("user_id",u,"openid","handoff-fixture-"+u,"nickname","隔离测试"+u));
        for(long s=1;s<=3;s++)db.insert("kg_store",p("store_id",s,"store_name","合成门店"+s,"city",s==3?"丽江":"平潭"));
        grant(2,2,"merchant");grant(3,1,"city_lead");grant(8,2,"manager");grant(9,2,"manager");grant(10,3,"manager");
        service=new KgMediationService();orders=new KgMediationOrders();messages=mock(IKgMessageService.class);grants=mock(IKgStaffGrantService.class);
        field(orders,"db",db);field(service,"db",db);field(service,"orders",orders);field(service,"messages",messages);field(service,"grants",grants);
        when(grants.loadActiveGrantViews(anyLong())).thenAnswer(call->views(call.getArgument(0)));
        CityLeadScope city=mock(CityLeadScope.class);when(city.covers(anyLong(),anyLong())).thenAnswer(c->Long.valueOf(3).equals(c.getArgument(0))&&Long.valueOf(2).equals(c.getArgument(1)));when(city.leadsOf(2L)).thenReturn(Arrays.asList(3L));field(service,"cityScope",city);
    }
    @AfterEach void cleanup(){AppUserContext.remove();}
    void grant(long uid,long store,String role){db.insert("kg_staff_grant",p("app_user_id",uid,"store_id",store,"role_code",role));}
    List<StaffGrantView> views(long uid){List<StaffGrantView> all=new ArrayList<>();for(Map<String,Object> r:db.list("select g.store_id,g.role_code from kg_staff_grant g join kg_store s on s.store_id=g.store_id join kg_app_user u on u.user_id=g.app_user_id where g.app_user_id=:u and g.status='0' and g.del_flag='0' and s.status='0' and s.del_flag='0' and u.status='0' and u.del_flag='0'",p("u",uid))){StaffGrantView g=new StaffGrantView();g.setStoreId(KgJdbc.asLong(r.get("storeId")));g.setRoleCode(String.valueOf(r.get("roleCode")));g.setActive(true);all.add(g);}return all;}
    long file(long uid){return db.insert("kg_mediation_file",p("owner_actor","app:"+uid,"file_key",UUID.randomUUID()+".png","original_name","合成材料.png","mime_type","image/png","size_bytes",10L,"sha256","fixture"));}
    long app(){long j=db.insert("kg_job",p("store_id",1L,"recruit_store_id",2L,"job_type","volunteer","title","合成换工岗位","city","平潭"));return db.insert("kg_application",p("app_no",UUID.randomUUID().toString().replace("-",""),"job_id",j,"store_id",1L,"app_user_id",1L,"real_name","测试人","phone","13800000000","status","onboarded"));}
    Map<String,Object> body(long uid){return p("requestKey",UUID.randomUUID().toString(),"orderType","application","orderId",app(),"category","食宿不符","narrative","到店后发现住宿条件与约定有差异，需要核实。到店后发现住宿条件与约定有差异，需要核实。希望双方沟通后合理解决并提供材料。","claims","希望核实并改善住宿安排","phone","13800000000","incidentAt",Governance.now().minusHours(1),"fileIds",Arrays.asList(file(uid)));}
    long create(){return in(()->service.create(1L,body(1)));}
    void cmd(long uid,long cid,String action,Map<String,Object> b){in(()->{service.command(uid,cid,action,b,false,"");return null;});}
    Map<String,Object> response(Long... files){return p("requestKey",UUID.randomUUID().toString(),"note",note,"fileIds",Arrays.asList(files));}
    @Test void persistentNotificationResponseAndClaimRoundTrip(){
        when(messages.send(anyLong(),anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString())).thenAnswer(call->db.insert("kg_message",p("recipient_id",call.getArgument(0),"mode",call.getArgument(1),"category",call.getArgument(2),"title",call.getArgument(3),"content",call.getArgument(4),"biz_type",call.getArgument(5),"biz_id",call.getArgument(6),"route",call.getArgument(7),"create_time",new Date())));
        long c=create();scope(2,2,"merchant");List<KgMessage> inbox=mapper.selectAppMessages(2L,"staff",null);assertEquals(1,inbox.size());assertTrue(inbox.get(0).getRoute().contains("mode=merchant"));assertEquals(1,mapper.countUnread(2L,"staff"));
        scope(8,2,"manager");assertEquals(0,mapper.countUnread(8L,"staff"));cmd(2,c,"merchantResponse",response(file(2)));assertEquals(1,mapper.countUnread(8L,"staff"));assertTrue(mapper.selectAppMessages(8L,"staff",null).get(0).getRoute().contains("mode=support"));cmd(8,c,"supportAccept",p());
        scope(2,2,"merchant");assertEquals(2,mapper.countUnread(2L,"staff"));assertEquals(true,service.detail(2L,c,false).get("supportClaimed"));assertNull(db.one("select handler_id from kg_mediation_case where case_id=:c",p("c",c)).get("handlerId"));
    }
    @Test void createNotifiesMerchantInStaffInboxAndRetryDoesNotResend(){Map<String,Object>b=body(1);long c=in(()->service.create(1L,b));assertEquals(c,in(()->service.create(1L,b)));verify(messages,times(1)).send(eq(2L),eq("staff"),eq("system"),contains("补充"),anyString(),eq("mediation"),eq(c),eq("/pages/governance/mediation?id="+c+"&mode=merchant"));verify(messages,never()).send(eq(1L),anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString());}
    @Test void merchantOriginNotifiesUserAndMakesMaterialsReady(){long c=in(()->service.create(2L,body(2)));assertEquals(true,service.detail(8L,c,false).get("canSupportAccept"));verify(messages).send(eq(1L),eq("user"),anyString(),contains("门店"),anyString(),eq("mediation"),eq(c),anyString());verify(messages).send(eq(8L),eq("staff"),anyString(),contains("可接单"),anyString(),eq("mediation"),eq(c),contains("mode=support"));verify(messages,never()).send(eq(2L),anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString());}
    @Test void responseClaimSharesOnlyExplicitMerchantMaterials(){long c=create(),f=file(2);long userFile=db.count("select file_id from kg_mediation_file where case_id=:c and owner_actor='app:1'",p("c",c));assertEquals(false,service.detail(8L,c,false).get("canSupportAccept"));assertThrows(ServiceException.class,()->cmd(8,c,"supportAccept",p()));
        Map<String,Object>b=response(f);cmd(2,c,"merchantResponse",b);cmd(2,c,"merchantResponse",b);assertEquals(1,db.count("select count(*) from kg_mediation_log where case_id=:c and action='merchant_response'",p("c",c)));
        assertFalse(service.detail(8L,c,false).containsKey("merchantResponses"));cmd(8,c,"supportAccept",p());cmd(8,c,"supportAccept",p());Map<String,Object>d=service.detail(8L,c,false);assertEquals(true,d.get("supportMine"));assertEquals(note,((Map<?,?>)((List<?>)d.get("merchantResponses")).get(0)).get("note"));
        assertEquals(1,db.count("select count(*) from kg_mediation_log where case_id=:c and action='support_accept'",p("c",c)));assertNull(db.one("select handler_id from kg_mediation_case where case_id=:c",p("c",c)).get("handlerId"));assertEquals("pending",d.get("status"));
        assertTrue(service.fileAccess(8L,db.one("select * from kg_mediation_file where file_id=:f",p("f",f)),false));assertFalse(service.fileAccess(9L,db.one("select * from kg_mediation_file where file_id=:f",p("f",f)),false));assertFalse(service.fileAccess(8L,db.one("select * from kg_mediation_file where file_id=:f",p("f",userFile)),false));assertEquals(false,service.detail(9L,c,false).get("canAssist"));
        assertThrows(ServiceException.class,()->cmd(8,c,"accept",p()));assertThrows(ServiceException.class,()->cmd(8,c,"decision",p()));cmd(3,c,"accept",p());assertEquals(3L,KgJdbc.asLong(db.one("select handler_id from kg_mediation_case where case_id=:c",p("c",c)).get("handlerId")));
    }
    @Test void responseRejectsForeignFilesAndChangedRetryWithoutWrites(){long c=create(),mine=file(2),foreign=file(1);Map<String,Object>b=response(mine,foreign);assertThrows(ServiceException.class,()->cmd(2,c,"merchantResponse",b));assertNull(db.one("select case_id from kg_mediation_file where file_id=:f",p("f",mine)).get("caseId"));assertEquals(false,service.detail(8L,c,false).get("merchantMaterialsReady"));b.put("fileIds",Arrays.asList(mine));cmd(2,c,"merchantResponse",b);b.put("note",note+"不同内容");assertThrows(ServiceException.class,()->cmd(2,c,"merchantResponse",b));assertEquals(1,db.count("select count(*) from kg_mediation_log where action='merchant_response'",p()));}
    @Test void attachmentOnlyAndLegacySupplementsUnlockClaim(){long c=create();cmd(2,c,"merchantResponse",p("requestKey",UUID.randomUUID().toString(),"note","","fileIds",Arrays.asList(file(2))));assertEquals(true,service.detail(8L,c,false).get("canSupportAccept"));long second=create();cmd(2,second,"statement",p("note",note));assertEquals(true,service.detail(8L,second,false).get("canSupportAccept"));long third=create();cmd(2,third,"evidence",p("fileIds",Arrays.asList(file(2))));assertEquals(true,service.detail(8L,third,false).get("canSupportAccept"));}
    @Test void disabledOrRevokedOwnerCanBeReplacedWithoutLosingAudit(){long c=create(),f=file(2);cmd(2,c,"merchantResponse",response(f));cmd(8,c,"supportAccept",p());db.update("update kg_staff_grant set status='1' where app_user_id=8",p());assertThrows(ServiceException.class,()->service.detail(8L,c,false));assertEquals(true,service.detail(9L,c,false).get("canSupportAccept"));cmd(9,c,"supportAccept",p());assertEquals(2,db.count("select count(*) from kg_mediation_log where action='support_accept'",p()));assertTrue(service.fileAccess(9L,db.one("select * from kg_mediation_file where file_id=:f",p("f",f)),false));assertFalse(service.fileAccess(8L,db.one("select * from kg_mediation_file where file_id=:f",p("f",f)),false));}
    @Test void concurrentManagersHaveOneSuccessfulClaim()throws Exception{long c=create();cmd(2,c,"merchantResponse",response());ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);try{List<Future<Boolean>> futures=new ArrayList<>();for(long u:new long[]{8,9})futures.add(pool.submit(()->{start.await();try{cmd(u,c,"supportAccept",p());return true;}catch(ServiceException conflict){return false;}}));start.countDown();int count=0;for(Future<Boolean> f:futures)if(f.get(15,TimeUnit.SECONDS))count++;assertEquals(1,count);}finally{pool.shutdownNow();}assertEquals(1,db.count("select count(*) from kg_mediation_log where action='support_accept'",p()));}
    @Test void partiesOtherStoresAndClosedCasesCannotClaim(){long c=create();cmd(2,c,"merchantResponse",response());grant(1,2,"manager");assertEquals("user",service.role(1L,db.one("select * from kg_mediation_case where case_id=:c",p("c",c)),false));for(long uid:new long[]{1,2,10})assertThrows(ServiceException.class,()->cmd(uid,c,"supportAccept",p()));assertThrows(ServiceException.class,()->cmd(8,c,"merchantResponse",response()));db.update("update kg_mediation_case set status='closed' where case_id=:c",p("c",c));assertThrows(ServiceException.class,()->cmd(8,c,"supportAccept",p()));assertThrows(ServiceException.class,()->cmd(2,c,"merchantResponse",response()));assertEquals(false,service.detail(8L,c,false).get("canSupportAccept"));}
    @Test void withdrawnRoleStopsAssistanceAndExplicitAttachments(){long c=create();cmd(2,c,"merchantResponse",response());cmd(8,c,"supportAccept",p());assertThrows(ServiceException.class,()->cmd(9,c,"assistance",response()));cmd(8,c,"assistance",response());db.update("update kg_app_user set status='1' where user_id=8",p());assertEquals(true,service.detail(9L,c,false).get("canSupportAccept"));cmd(9,c,"supportAccept",p());}
    void scope(long uid,long store,String role){AppLoginUser user=new AppLoginUser();user.setUserId(uid);user.setStaffGrants(views(uid));if("city_lead".equals(role)){StaffGrantView g=new StaffGrantView();g.setStoreId(store);g.setRoleCode(role);g.setActive(true);user.getStaffGrants().add(g);}AppUserContext.set(user);AppUserContext.messageScope(Arrays.asList(store),role);}
    void message(long id,long uid,long cid,String mode){db.insert("kg_message",p("msg_id",id,"recipient_id",uid,"mode",mode,"category","system","title","合成调解消息","content","仅合成数据","biz_type","mediation","biz_id",cid,"read_flag","0","create_time",new Date()));}
    @Test void notificationListsUnreadAndMutationsUseSameRoleAndStoreAcl(){long c=create();long other=create();db.update("update kg_mediation_case set store_id=3 where case_id=:c",p("c",other));long stay=create();db.update("update kg_mediation_case set order_type='stay' where case_id=:c",p("c",stay));message(1,8,c,"staff");message(2,8,other,"staff");message(3,8,stay,"staff");message(4,8,stay,"user");scope(8,2,"manager");assertEquals(1,mapper.countUnread(8L,"staff"));assertEquals(1,mapper.selectAppMessages(8L,"staff",null).size());assertEquals(0,mapper.markRead(3L,8L));assertEquals(0,mapper.deleteOwnMessage(2L,8L,"staff",null));assertEquals(1,mapper.markAllRead(8L,"staff",null,99L));assertEquals(1,mapper.countUnread(8L,"user"));AppUserContext.messageScope(Arrays.asList(3L),"manager");assertEquals(0,mapper.countUnread(8L,"staff"));message(5,2,c,"staff");message(6,2,stay,"staff");scope(2,2,"merchant");assertEquals(2,mapper.countUnread(2L,"staff"));message(7,3,c,"staff");scope(3,2,"city_lead");assertEquals(1,mapper.countUnread(3L,"staff"));AppUserContext.messageScope(Arrays.asList(2L),"manager");assertEquals(0,mapper.countUnread(3L,"staff"));}
}
