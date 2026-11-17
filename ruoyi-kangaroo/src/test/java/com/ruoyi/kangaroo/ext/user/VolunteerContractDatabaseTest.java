package com.ruoyi.kangaroo.ext.user;

import java.util.*;
import java.time.*;
import java.io.InputStream;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.*;
import com.ruoyi.kangaroo.security.*;
import com.ruoyi.kangaroo.service.*;
import com.ruoyi.kangaroo.service.impl.KgApplicationServiceImpl;
import com.ruoyi.kangaroo.dto.OperatorDTO;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;

/** Actual local MySQL and application state machine; messages and departure consent isolated fixtures. */
class VolunteerContractDatabaseTest {
    static KgJdbc db;static TransactionTemplate tx;static KgVolunteerContractService service;static StaffSupport support;static KgDepartureNotice notice;static KgApplicationServiceImpl apps;
    static final long USER=99001,STAFF=99002,OTHER=99003,STORE=98001,JOB=97001,APP=96001;
    static final String SECRET="isolated-contract-test-secret-not-a-credential";
    static String ID;
    static String previousRolePhase;
    static StaffSupport.Ctx ctx;
    static void wire(Object o,Object...p){for(int i=0;i<p.length;i+=2)ReflectionTestUtils.setField(o,(String)p[i],p[i+1]);}
    @AfterAll static void restoreRolePhase(){if(previousRolePhase!=null)RolePhase.set(previousRolePhase);}
    @BeforeAll static void init()throws Exception {
        previousRolePhase=RolePhase.code();
        String n=System.getenv("KG_MEMBER_TEST_DB");assertNotNull(n);assertTrue(n.matches("kg_member_exec_[a-f0-9]{32}"));
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/"+n+"?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai",System.getenv("KG_MEMBER_TEST_USER"),System.getenv("KG_TEST_DB_PASSWORD"));
        db=new KgJdbc(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        org.apache.ibatis.session.Configuration cfg=new org.apache.ibatis.session.Configuration(new org.apache.ibatis.mapping.Environment("isolated",new org.mybatis.spring.transaction.SpringManagedTransactionFactory(),ds));
        cfg.getTypeAliasRegistry().registerAlias("KgApplication",com.ruoyi.kangaroo.domain.KgApplication.class);cfg.getTypeAliasRegistry().registerAlias("KgApplicationLog",com.ruoyi.kangaroo.domain.KgApplicationLog.class);
        for(String file:Arrays.asList("KgApplicationMapper","KgApplicationLogMapper")){String path="mapper/kangaroo/"+file+".xml";try(InputStream in=VolunteerContractDatabaseTest.class.getClassLoader().getResourceAsStream(path)){new org.apache.ibatis.builder.xml.XMLMapperBuilder(in,cfg,path,cfg.getSqlFragments()).parse();}}
        org.mybatis.spring.SqlSessionTemplate session=new org.mybatis.spring.SqlSessionTemplate(new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(cfg));
        apps=new KgApplicationServiceImpl();wire(apps,"kgJdbcForQuota",db,"kgApplicationMapper",session.getMapper(com.ruoyi.kangaroo.mapper.KgApplicationMapper.class),"kgApplicationLogMapper",session.getMapper(com.ruoyi.kangaroo.mapper.KgApplicationLogMapper.class),"kgMessageService",mock(IKgMessageService.class),"kgStaffGrantService",mock(IKgStaffGrantService.class));
        support=mock(StaffSupport.class);notice=mock(KgDepartureNotice.class);service=new KgVolunteerContractService();wire(service,"db",db,"support",support,"applications",apps,"departureNotice",notice,"secret",SECRET);RolePhase.set("new");
        for(long id:Arrays.asList(USER,STAFF,OTHER))db.insert("kg_app_user",p("user_id",id,"openid","tripartite-fixture-"+id,"nickname","协议测试","status","0","del_flag","0"));
        db.insert("kg_store",p("store_id",STORE,"store_name","测试小院","city","测试城市","address","测试路一号","contact_phone","13900000000","status","0","del_flag","0"));
        db.insert("kg_job",p("job_id",JOB,"store_id",STORE,"job_type","volunteer","title","测试换工","city","测试城市","daily_hours","4小时","rest_mode","5-2","shift_time","10:00-14:00","board_desc","包住，提供早餐","content","前台接待协助","status","published","del_flag","0"));
        ID=id("11010519900101001");
    }
    static String id(String stem){int[] w={7,9,10,5,8,4,2,1,6,3,7,9,10,5,8,4,2};String c="10X98765432";int sum=0;for(int i=0;i<17;i++)sum+=(stem.charAt(i)-'0')*w[i];return stem+c.charAt(sum%11);}
    @BeforeEach void fresh(){
        for(String t:Arrays.asList("kg_contract_sign","kg_volunteer_contract","kg_application_log","kg_application"))db.update("delete from "+t,p());
        db.update("update kg_app_user set status='0',id_hash=null,real_name_verified_time=null,real_name=null",p());
        db.update("update kg_job set job_type='volunteer',daily_hours='4小时' where job_id=:j",p("j",JOB));
        db.insert("kg_application",p("app_id",APP,"app_no","test-tripartite-"+APP,"job_id",JOB,"store_id",STORE,"app_user_id",USER,"real_name","测试人","phone","13800000000","arrival_date","2026-12-01","stay_days",7,"status","store_confirmed","del_flag","0"));
        ctx=new StaffSupport.Ctx();ctx.storeId=STORE;ctx.role="keeper";ctx.me=mock(AppLoginUser.class);when(ctx.me.getUserId()).thenReturn(STAFF);when(ctx.me.getNickname()).thenReturn("测试店家");reset(support,notice);
    }
    static Map<String,Object> form(){return p("name","测试人","idNo",ID,"phone","13800000000","emergencyName","测试亲属","emergencyPhone","13700000000","health","无特殊情况","familyInformed",true,"privacyAgreed",true,"storeName","测试小院经营方","storeAddress","测试路一号","storeContact","测试店长","storePhone","13900000000","consistent","yes","differences","");}
    Map<String,Object> signed(){Map<String,Object> b=form();b.put("hash",service.preview(USER,APP,b).get("hash"));b.put("agree",true);return tx.execute(s->service.submit(USER,APP,b,"127.0.0.1"));}
    Map<String,Object> last(){return db.one("select * from kg_volunteer_contract where app_id=:a order by revision desc limit 1",p("a",APP));}
    Map<String,Object> decision(String action){Map<String,Object> r=last();return p("action",action,"name","测试店长","agree",true,"contractId",r.get("contractId"),"hash",r.get("contentHash"),"reason","请核对视频差异");}
    static void code(int c,Runnable action){ServiceException e=assertThrows(ServiceException.class,action::run);assertEquals(c,e.getCode());}
    @Test void originalClausesAndAllFieldsRenderAndSensitiveIdIsMasked(){Map<String,Object> p=service.preview(USER,APP,form());String t=(String)p.get("text");assertTrue(t.contains("深圳市袋鼠星辰投资有限公司"));assertTrue(t.contains("提前至少2天"));assertTrue(t.contains("免费续住 2 天"));assertTrue(t.contains("测试亲属"));assertTrue(t.contains("无特殊情况"));assertTrue(t.contains("测试路一号"));assertTrue(t.contains("4小时"));assertTrue(t.contains("前台接待协助"));assertTrue(t.contains("第六条"));assertFalse(t.contains("制）导"));assertFalse(t.contains("___"));assertFalse(t.contains(ID));assertTrue(t.contains(ID.substring(14)));}
    @Test void missingFieldsAreRejected(){for(String key:Arrays.asList("name","idNo","phone","emergencyName","emergencyPhone","health","storeName","storeAddress","storeContact","storePhone","consistent")){Map<String,Object> b=form();b.remove(key);code(422,()->service.preview(USER,APP,b));}for(String k:Arrays.asList("familyInformed","privacyAgreed")){Map<String,Object> b=form();b.put(k,false);code(422,()->service.preview(USER,APP,b));}}
    @Test void identityAgePhoneAndNameValidated(){for(String invalid:Arrays.asList("123456789012345678",id("11010520200101001"),id("11010519900230001"))){Map<String,Object> b=form();b.put("idNo",invalid);code(422,()->service.preview(USER,APP,b));}Map<String,Object>b=form();b.put("name","其他人");code(422,()->service.preview(USER,APP,b));Map<String,Object>x=form();x.put("phone","abc");code(422,()->service.preview(USER,APP,x));}
    @Test void differenceRequiresSpecificExplanation(){Map<String,Object>b=form();b.put("consistent","no");code(422,()->service.preview(USER,APP,b));b.put("differences","视频约定单人间，页面显示多人间");assertTrue(s(service.preview(USER,APP,b).get("text")).contains("视频约定单人间"));}
    @Test void userConfirmationWaitsForMerchantAndEncryptsRawSnapshot(){signed();assertEquals("store_confirmed",db.one("select status from kg_application where app_id=:a",p("a",APP)).get("status"));assertEquals(0,db.count("select count(*) from kg_contract_sign",p()));Map<String,Object>r=last();assertEquals("pending",r.get("status"));assertFalse(r.get("dataEnc").toString().contains(ID));String full=KgTravelIdService.decrypt(s(r.get("contentEnc")),SECRET);assertTrue(full.contains(ID));assertEquals(r.get("contentHash"),KgUserHubService.sha256(full));assertFalse(s(service.view(USER,APP)).contains(ID));}
    @Test void cannotSkipNewAgreementViaOldStateEndpoint(){signed();code(409,()->tx.execute(s->apps.signContract(APP,OperatorDTO.user(USER,"测试人"))));}
    @Test void bothSidesCompleteRealStateMachineAndKeepImmutableCopy(){signed();String hash=s(last().get("contentHash"));Map<String,Object>b=decision("confirm");tx.execute(s->service.decide(ctx,APP,b,"127.0.0.1"));assertEquals("contract_signed",db.one("select status from kg_application where app_id=:a",p("a",APP)).get("status"));assertEquals(1,db.count("select count(*) from kg_contract_sign",p()));assertEquals(1,db.count("select count(*) from kg_application_log where to_status='contract_signed'",p()));db.update("update kg_job set daily_hours='8小时' where job_id=:j",p("j",JOB));Map<String,Object> record=service.record(USER,APP,KgJdbc.asLong(last().get("contractId")));assertEquals(hash,record.get("contentHash"));assertTrue(s(record.get("contentMasked")).contains("4小时"));assertFalse(s(record.get("contentMasked")).contains("8小时"));tx.execute(s->service.decide(ctx,APP,b,"127.0.0.1"));assertEquals(1,db.count("select count(*) from kg_contract_sign",p()));}
    @Test void consentAndPreviewHashAreRequired(){Map<String,Object>b=form();b.put("hash",service.preview(USER,APP,b).get("hash"));code(422,()->tx.execute(s->service.submit(USER,APP,b,"")));b.put("agree",true);b.put("phone","13800000001");code(409,()->tx.execute(s->service.submit(USER,APP,b,"")));assertNull(last());}
    @Test void changedJobOrArrivalCannotBeConfirmedAgainstOldSnapshot(){signed();db.update("update kg_application set arrival_date='2026-12-02' where app_id=:a",p("a",APP));code(409,()->tx.execute(s->service.decide(ctx,APP,decision("confirm"),"")));assertEquals("pending",last().get("status"));}
    @Test void rejectResubmitAndOldVersionLookup(){signed();Map<String,Object>old=last();tx.execute(s->service.decide(ctx,APP,decision("reject"),""));assertEquals("rejected",last().get("status"));signed();assertEquals(2,((Number)last().get("revision")).intValue());assertNotEquals(old.get("agreementNo"),last().get("agreementNo"));assertEquals("rejected",service.record(USER,APP,KgJdbc.asLong(old.get("contractId"))).get("status"));Map<String,Object>stale=decision("confirm");stale.put("contractId",old.get("contractId"));code(409,()->tx.execute(s->service.decide(ctx,APP,stale,"")));}
    @Test void noCrossUserCrossStoreSelfSigningOrUnprivilegedRole(){code(403,()->service.view(OTHER,APP));signed();code(403,()->service.record(OTHER,APP,KgJdbc.asLong(last().get("contractId"))));ctx.storeId=STORE+1;code(403,()->service.staffView(ctx,APP));ctx.storeId=STORE;when(ctx.me.getUserId()).thenReturn(USER);code(403,()->service.staffView(ctx,APP));when(ctx.me.getUserId()).thenReturn(STAFF);ctx.role="merchant_service";code(403,()->service.staffView(ctx,APP));ctx.role="merchant";assertNotNull(service.staffView(ctx,APP).get("record"));}
    @Test void terminatedAndExistingLegacyAgreementsAreNotOverwritten(){db.update("update kg_application set status='terminated' where app_id=:a",p("a",APP));code(409,()->service.preview(USER,APP,form()));db.update("update kg_application set status='store_confirmed' where app_id=:a",p("a",APP));db.insert("kg_contract_sign",p("app_id",APP,"app_user_id",USER,"version","2026-09","content_hash","legacy","signer_name","测试人","signed_time",new Date()));code(409,()->service.preview(USER,APP,form()));assertEquals("2026-09",db.one("select version from kg_contract_sign",p()).get("version"));}
    @Test void realNameMismatchAndNonVolunteerCannotUseThisFlow(){db.update("update kg_app_user set id_hash='another-id',real_name_verified_time=now() where user_id=:u",p("u",USER));code(422,()->service.preview(USER,APP,form()));db.update("update kg_job set job_type='parttime' where job_id=:j",p("j",JOB));code(409,()->service.view(USER,APP));}
    @Test void retriesAndConcurrentClicksCreateOneConfirmation()throws Exception{Map<String,Object>b=form();b.put("hash",service.preview(USER,APP,b).get("hash"));b.put("agree",true);ExecutorService pool=Executors.newFixedThreadPool(2);try{List<Future<?>>fs=new ArrayList<>();for(int i=0;i<2;i++)fs.add(pool.submit(()->tx.execute(s->service.submit(USER,APP,b,""))));for(Future<?>f:fs)f.get(20,TimeUnit.SECONDS);}finally{pool.shutdownNow();}assertEquals(1,db.count("select count(*) from kg_volunteer_contract",p()));verify(support,times(1)).notifyStore(eq(STORE),anyString(),anyString(),anyString(),anyString(),eq(APP),anyString());}
    @Test void merchantMustExplicitlyAgreeAndCannotSignAfterTermination(){signed();Map<String,Object>b=decision("confirm");b.put("agree",false);code(422,()->tx.execute(s->service.decide(ctx,APP,b,"")));db.update("update kg_application set status='terminated' where app_id=:a",p("a",APP));code(409,()->tx.execute(s->service.decide(ctx,APP,decision("confirm"),"")));}
    static String s(Object v){return VolunteerAgreement.s(v);}
}
