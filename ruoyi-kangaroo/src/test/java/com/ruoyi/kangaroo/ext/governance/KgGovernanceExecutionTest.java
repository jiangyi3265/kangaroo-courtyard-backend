package com.ruoyi.kangaroo.ext.governance;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.CityLeadScope;
import com.ruoyi.kangaroo.ext.user.*;
import com.ruoyi.kangaroo.service.IKgMessageService;
import com.ruoyi.common.exception.ServiceException;

/** Isolated real MySQL; delivery mocked, no production customers, payments or notifications. */
class KgGovernanceExecutionTest {
 @Test void removedQualityReviewCannotKeepWornBadge() {
  long review=db.insert("kg_review",p("app_user_id",1L,"target_type","stay","target_id",1L,"rating",5,"content",repeat("真实住宿评价说明干净舒适",5)));
  db.insert("kg_member_review_check",p("review_id",review,"quality","1","reason","已核验评价"));
  run(()->badges.sync(1L));
  long award=db.count("select award_id from kg_badge_award where app_user_id=1 and badge_code='review_gold'",p());
  run(()->badges.display(1L,award,p("worn",true,"visible",true)));
  assertNotNull(members.identity(1L).get("achievement"));
  db.update("update kg_review set status='1' where review_id=:r",p("r",review));
  run(()->badges.sync(1L));
  assertNull(members.identity(1L).get("achievement"),"Hidden reviews cannot continue granting a public quality badge");
  db.update("update kg_review set status='0',del_flag='2' where review_id=:r",p("r",review));
  run(()->badges.sync(1L));
  assertEquals(0,db.count("select count(*) from kg_badge_award where app_user_id=1 and status='active'",p()));
 }

 @Test void disqualifiedJurorLosesIdentityBadgeWithoutErasingHistory() {
  long c=create(),r=jury(c);
  run(()->service.vote(5L,r,p("choice","both","reason","根据已核实证据，双方在沟通中均有不足")));
  run(()->badges.sync(5L));
  long award=db.count("select award_id from kg_badge_award where app_user_id=5 and badge_code='juror'",p());
  run(()->badges.display(5L,award,p("worn",true,"visible",true)));
  run(()->service.sanctionJuror(5L,p("deduction",10,"note","核实该评审员泄露材料，取消其评审资格"),"admin:test"));
  run(()->badges.sync(5L));
  assertNull(members.identity(5L).get("achievement"));
  assertEquals(1,db.count("select count(*) from kg_mediation_vote where app_user_id=5",p()));
  assertEquals(5,ledger.totals(5L).balance(),"Removing an identity must not invent a points penalty");
 }

 @Test void invalidAssessmentRefreshesSharedResultAndPointsTogether() {
  long c=create(),r=jury(c);
  run(()->service.vote(5L,r,p("choice","both","reason","根据已核实材料和双方陈述，双方均有责任")));
  db.update("update kg_mediation_round set deadline=date_sub(now(),interval 1 minute) where round_id=:r",p("r",r));
  run(()->service.finishRound(r));
  long vote=db.count("select vote_id from kg_mediation_vote where round_id=:r and app_user_id=5",p("r",r));
  run(()->service.assess(vote,p("valid",false,"note","核实该意见未依据证据作出，应撤回本次奖励"),"admin:test"));
  for(long viewer:Arrays.asList(1L,2L,3L)) {
   Map<?,?> round=(Map<?,?>)service.detail(viewer,c,false).get("jury");
   com.alibaba.fastjson2.JSONObject result=com.alibaba.fastjson2.JSON.parseObject(String.valueOf(round.get("resultJson")));
   assertEquals(0,result.getIntValue("both"),"All parties must see the corrected valid vote count");
   if(viewer==3L)assertEquals(0,result.getJSONArray("reasons").size());
  }
  assertEquals(0,ledger.totals(5L).balance());
  assertEquals(0,ledger.totals(5L).earned);
  assertEquals("ready",state(c));
 }
 static KgJdbc db;static TransactionTemplate tx;static KgMediationOrders orders;static KgMediationService service;static KgPointLedger ledger;static KgBadgeService badges;static KgMemberService members;static IKgMessageService messages;
 static void field(Object o,String n,Object v){ReflectionTestUtils.setField(o,n,v);}static <T>T in(Supplier<T> f){return tx.execute(s->f.get());}static void run(Runnable f){in(()->{f.run();return null;});}
 @BeforeAll static void setup(){String name=System.getenv("KG_MEMBER_TEST_DB");assumeTrue(name!=null&&name.matches("kg_member_exec_[a-f0-9]{32}"));DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/"+name+"?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&sessionVariables=time_zone=%27%2B08:00%27",System.getenv("KG_MEMBER_TEST_USER"),System.getenv("KG_TEST_DB_PASSWORD"));db=new KgJdbc(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));orders=new KgMediationOrders();field(orders,"db",db);ledger=new KgPointLedger();field(ledger,"db",db);service=new KgMediationService();field(service,"db",db);field(service,"orders",orders);field(service,"ledger",ledger);messages=mock(IKgMessageService.class);field(service,"messages",messages);CityLeadScope scope=mock(CityLeadScope.class);when(scope.covers(anyLong(),anyLong())).thenAnswer(i->Long.valueOf(3).equals(i.getArgument(0))&&Long.valueOf(2).equals(i.getArgument(1)));when(scope.stores(3L)).thenReturn(Collections.singletonMap(2L,p("storeId",2L)));when(scope.leadsOf(anyLong())).thenReturn(Collections.singletonList(3L));field(service,"cityScope",scope);members=new KgMemberService(db);badges=new KgBadgeService();field(badges,"db",db);field(badges,"members",members);field(badges,"rewards",mock(KgMembershipRewards.class));
  KgBadgeModeration moderation=new KgBadgeModeration();field(moderation,"db",db);field(moderation,"ledger",ledger);field(moderation,"rewards",mock(KgMembershipRewards.class));
  KgBadgeMetrics metrics=new KgBadgeMetrics();field(metrics,"db",db);field(metrics,"moderation",moderation);field(badges,"metrics",metrics);
  field(service,"badges",badges);field(service,"moderation",moderation);
  field(moderation,"badges",badges);
 }
 @BeforeEach void reset(){for(String table:Arrays.asList("kg_badge_quarter_review","kg_badge_content_check","kg_badge_violation","kg_badge_integrity","kg_mediation_event","kg_mediation_vote","kg_mediation_round","kg_mediation_log","kg_mediation_file","kg_mediation_case","kg_mediation_juror","kg_badge_feedback","kg_badge_log","kg_badge_award","kg_staff_grant","kg_user_follow","kg_member_case","kg_member_restriction","kg_member_review_check","kg_point_entry","kg_post","kg_review","kg_application","kg_job","kg_stay_order","kg_activity_signup","kg_activity","kg_store","kg_app_user"))db.update("delete from "+table,p());
  db.update("update kg_member_execution set enabled='1',starts_at='2026-01-01' where id=1",p());
  for(long u=1;u<=15;u++)db.insert("kg_app_user",p("user_id",u,"openid","governance_test_"+u,"nickname","隔离测试"+u,"phone",String.format("138%08d",u),"status","0","del_flag","0"));
  for(long s=1;s<=4;s++)db.insert("kg_store",p("store_id",s,"store_name","测试院"+s,"city",s<=2?"平潭":"丽江","rating",4.8,"status","0","create_time",Governance.now().minusMonths(5)));
  grant(2L,2L,"merchant");grant(3L,1L,"city_lead");grant(10L,3L,"merchant");org.mockito.Mockito.reset(messages);
 }
 void grant(Long u,Long s,String role){db.insert("kg_staff_grant",p("app_user_id",u,"store_id",s,"role_code",role,"status","0","del_flag","0"));}
 long app(Long uid){long j=db.insert("kg_job",p("store_id",1L,"recruit_store_id",2L,"job_type","volunteer","title","隔离测试岗位","city","平潭"));return db.insert("kg_application",p("app_no",UUID.randomUUID().toString().replace("-",""),"job_id",j,"store_id",1L,"app_user_id",uid,"real_name","测试人","phone","13800000000","status","onboarded","volunteer_done_time",Governance.now().minusDays(1)));}
 long file(Long uid,boolean redacted){return db.insert("kg_mediation_file",p("owner_actor","app:"+uid,"file_key",UUID.randomUUID().toString().replace("-","")+".png","original_name","隐私姓名-证据.png","mime_type","image/png","size_bytes",10L,"sha256","fixture","redacted",redacted?"1":"0"));}
 Map<String,Object> body(Long uid,long oid){return p("requestKey",UUID.randomUUID().toString(),"orderType","application","orderId",oid,"category","食宿不符","narrative",repeat("到店后发现住宿条件和约定有差异，已经联系店家沟通，希望核实并解决。",3),"claims","希望双方协商改善住宿条件","phone","13800000000","incidentAt",Governance.now().minusHours(1).toString(),"fileIds",Collections.singletonList(file(uid,false)));}
 static String repeat(String s,int n){StringBuilder out=new StringBuilder();while(n-->0)out.append(s);return out.toString();}
 long create(){return in(()->service.create(1L,body(1L,app(1L))));}
 void command(long uid,long cid,String action,Map<String,Object>body){run(()->service.command(uid,cid,action,body,false,""));}
 void ready(long cid){command(3,cid,"accept",p());command(3,cid,"verify",p("userStatement","用户说明住宿条件与约定存在差异","merchantStatement","店家解释临时调房但没有及时通知","evidenceList","已核对订单和双方照片","finding","双方对住宿安排存在理解差异，需要协商"));}
 void decide(long cid){command(3,cid,"decision",p("decision","店家需要解释并改善约定住宿条件","basis","结合订单约定和核验照片与双方陈述","measures","安排符合约定的房间并跟进确认","creditImpact","无影响","executeDeadline",Governance.now().plusDays(3),"juryAdopted",true,"juryReason","综合参考评审意见与真实证据"));}
 String state(long c){return (String)db.one("select status from kg_mediation_case where case_id=:c",p("c",c)).get("status");}
 @Test void actualRecruitingShopAndCityOwnTheCase(){long c=create();assertEquals(2L,KgJdbc.asLong(service.detail(1L,c,false).get("storeId")));assertEquals("merchant",service.detail(2L,c,false).get("viewerRole"));assertEquals("city",service.detail(3L,c,false).get("viewerRole"));assertThrows(ServiceException.class,()->service.detail(4L,c,false));assertThrows(ServiceException.class,()->command(4,c,"accept",p()));}
 @Test void invalidAttachmentsRollbackWholeSubmission(){long a=app(1L);Map<String,Object>b=body(1L,a);b.put("fileIds",Collections.singletonList(file(2L,false)));assertThrows(ServiceException.class,()->in(()->service.create(1L,b)));assertEquals(0,db.count("select count(*) from kg_mediation_case",p()));}
 @Test void duplicateSubmitIsIdempotentAndNewDuplicateRejected(){long a=app(1L);Map<String,Object>b=body(1L,a);long c=in(()->service.create(1L,b));assertEquals(c,in(()->service.create(1L,b)));assertThrows(ServiceException.class,()->in(()->service.create(2L,body(2L,a))));assertEquals(1,db.count("select count(*) from kg_mediation_case",p()));}
 @Test void concurrentPartiesCannotOpenTwoCases()throws Exception{long a=app(1L);Map<String,Object>x=body(1L,a),y=body(2L,a);ExecutorService pool=Executors.newFixedThreadPool(2);try{List<Future<Boolean>>f=new ArrayList<>();for(long u=1;u<=2;u++){final long me=u;f.add(pool.submit(()->{try{in(()->service.create(me,me==1?x:y));return true;}catch(RuntimeException ex){return false;}}));}int n=0;for(Future<Boolean>v:f)if(v.get())n++;assertEquals(1,n);assertEquals(1,db.count("select count(*) from kg_mediation_case",p()));}finally{pool.shutdownNow();}}
 @Test void directCaseRoundTripAndPrivacy(){long c=create();ready(c);assertEquals("ready",state(c));assertFalse(service.detail(2L,c,false).containsKey("phone"));assertFalse(service.detail(2L,c,false).containsKey("verification"));assertTrue(((List<?>)service.detail(2L,c,false).get("evidence")).isEmpty());decide(c);command(1,c,"confirm",p("accept",true));assertEquals("confirmation",state(c));command(2,c,"confirm",p("accept",true));assertEquals("closed",state(c));assertThrows(ServiceException.class,()->command(3,c,"decision",p()));assertTrue(db.count("select count(*) from kg_mediation_log where case_id=:c",p("c",c))>=7);verify(messages,atLeastOnce()).send(eq(1L),eq("user"),eq("system"),anyString(),anyString(),eq("mediation"),eq(c),anyString());}
 /** 2026-11-09 主人拍板：调解出了结果（双方接受 / 总部复核）后同一订单双方都不能再发起，发起页按原因显示 */
 @SuppressWarnings("unchecked")
 @Test void settledOrderCannotBeMediatedAgainByEitherParty(){long a=app(1L);long c=in(()->service.create(1L,body(1L,a)));ready(c);decide(c);command(1,c,"confirm",p("accept",true));command(2,c,"confirm",p("accept",true));assertEquals("closed",state(c));
  assertTrue(orders.settled("application",a));
  Map<String,Object> one=orders.mineOne(1L,"application",a);assertEquals(false,one.get("eligible"));assertEquals(KgMediationOrders.SETTLED,one.get("reason"));assertEquals(false,orders.mineOne(2L,"application",a).get("eligible"));
  for(long u=1;u<=2;u++){final long me=u;ServiceException e=assertThrows(ServiceException.class,()->in(()->service.create(me,body(me,a))));assertEquals(KgMediationOrders.SETTLED,e.getMessage());}
  Map<String,Object> row=null;for(Map<String,Object> r:(List<Map<String,Object>>)orders.mine(1L,"user",1).get("rows"))if(Long.valueOf(a).equals(KgJdbc.asLong(r.get("orderId"))))row=r;
  assertNotNull(row);assertEquals(false,row.get("eligible"));assertEquals(KgMediationOrders.SETTLED,row.get("reason"));
  assertEquals(1,db.count("select count(*) from kg_mediation_case",p()));}
 /** 没出结果就关闭的（没有裁决 / 复核结果）不算结案，仍可再发起 */
 @Test void closedWithoutResultCanStillBeMediatedAgain(){long a=app(1L);long c=in(()->service.create(1L,body(1L,a)));db.update("update kg_mediation_case set status='closed' where case_id=:c",p("c",c));
  assertFalse(orders.settled("application",a));assertEquals(true,orders.mineOne(1L,"application",a).get("eligible"));assertNull(orders.mineOne(1L,"application",a).get("reason"));
  long again=in(()->service.create(2L,body(2L,a)));assertNotEquals(c,again);}
 @Test void appealRequiresHqAndIsVisibleToBothParties(){long c=create();ready(c);decide(c);command(1,c,"confirm",p("accept",false,"note","不同意处理结果，请总部重新核实全部材料"));assertEquals("appealed",state(c));assertThrows(ServiceException.class,()->command(3,c,"hqReview",p("result",repeat("经复核确认处理措施符合实际。",3))));run(()->service.command(99L,c,"hqReview",p("result",repeat("总部核验双方材料后，维持改善住宿的处理意见。",3)),true,"admin:test"));assertEquals("closed",state(c));assertNotNull(service.detail(2L,c,false).get("hqResult"));assertEquals(0,db.count("select count(*) from kg_mediation_event where status='pending'",p()));assertEquals(0,ledger.totals(1L).balance());}
 @Test void emergencyUsesTwoHoursAndNeverInventsSmsSuccess(){long a=app(1L);Map<String,Object>b=body(1L,a);b.put("safety",true);long c=in(()->service.create(1L,b));assertEquals(2,db.count("select timestampdiff(hour,create_time,accept_deadline) from kg_mediation_case where case_id=:c",p("c",c)));assertEquals(1,db.count("select count(*) from kg_mediation_event where case_id=:c and status='manual_required'",p("c",c)));command(3,c,"accept",p());assertEquals(1,db.count("select count(*) from kg_mediation_event where case_id=:c and status='manual_required'",p("c",c)));command(3,c,"contact",p("note","已经电话联络当事人并核实其当前安全情况"));assertEquals(0,db.count("select count(*) from kg_mediation_event where case_id=:c and status='manual_required'",p("c",c)));}
 @Test void overdueEscalationAndConfirmationReminderAreIdempotent(){long c=create();db.update("update kg_mediation_case set accept_deadline=date_sub(now(),interval 1 hour) where case_id=:c",p("c",c));run(()->service.deadlines(c));run(()->service.deadlines(c));assertEquals(1,db.count("select count(*) from kg_mediation_event where event_key=:k",p("k",c+":overdue")));ready(c);decide(c);db.update("update kg_mediation_case set confirm_deadline=date_sub(now(),interval 1 hour) where case_id=:c",p("c",c));run(()->service.deadlines(c));run(()->service.deadlines(c));assertEquals("confirmation",state(c));assertEquals(1,db.count("select count(*) from kg_mediation_event where event_key=:k",p("k",c+":confirmation_due")));}
 void pool(){for(long u=5;u<=8;u++){for(int n=0;n<3;n++)app(u);final long id=u;run(()->service.optIn(id,p("willing",true,"kind","user")));}run(()->service.optIn(10L,p("willing",true,"kind","merchant","storeId",3L)));}
 long jury(long c){ready(c);pool();command(3,c,"jury",p("anonymousNarrative",repeat("用户与门店因住宿条件产生分歧，双方提供的材料经过核验，所有身份资料已经去除。",3),"anonymousClaims","希望改善约定的居住条件","redactionConfirmed",true,"fileIds",Collections.singletonList(file(3L,true))));return db.count("select round_id from kg_mediation_round where case_id=:c",p("c",c));}
 @Test void juryIsAnonymousAndRewardsCannotBeDuplicated(){long c=create(),r=jury(c);assertEquals(5,db.count("select count(*) from kg_mediation_vote where round_id=:r",p("r",r)));Map<String,Object>seen=service.juryDetail(5L,r);String json=com.alibaba.fastjson2.JSON.toJSONString(seen);assertFalse(json.contains("隐私姓名"));assertFalse(seen.containsKey("caseId"));assertFalse(seen.containsKey("appUserId"));assertThrows(ServiceException.class,()->service.juryDetail(4L,r));run(()->service.vote(5L,r,p("choice","both","reason","根据已脱敏的证据，双方沟通均有不足")));assertEquals(5,ledger.totals(5L).balance());assertThrows(ServiceException.class,()->run(()->service.vote(5L,r,p("choice","user","reason","尝试重复提交的测试评审理由"))));run(()->badges.sync(5L));assertEquals(1,db.count("select count(*) from kg_badge_award where app_user_id=5 and badge_code='juror' and status='active'",p()));long raw=db.count("select file_id from kg_mediation_file where case_id=:c and redacted='0' limit 1",p("c",c));assertFalse(service.fileAccess(5L,db.one("select * from kg_mediation_file where file_id=:f",p("f",raw)),false));}
 @Test void changingQualificationPreventsVote(){long c=create(),r=jury(c);db.update("update kg_mediation_juror set credit_score=80 where app_user_id=5",p());assertThrows(ServiceException.class,()->run(()->service.vote(5L,r,p("choice","both","reason","资格改变后不应允许继续提交投票"))));assertThrows(ServiceException.class,()->run(()->service.optIn(5L,p("willing",false))));}
 @Test void juryDeadlineQualityAndInvalidReversalReachPointsAndBadges(){long c=create(),r=jury(c);run(()->service.vote(5L,r,p("choice","both","reason","根据双方已核实的材料与陈述，双方均有责任")));db.update("update kg_mediation_round set deadline=date_sub(now(),interval 1 minute) where round_id=:r",p("r",r));run(()->service.finishRound(r));run(()->service.finishRound(r));assertEquals("ready",state(c));long vote=db.count("select vote_id from kg_mediation_vote where round_id=:r and app_user_id=5",p("r",r));run(()->service.assess(vote,p("valid",false,"note","核实评审内容存在敷衍情况，撤回本次奖励"),"admin:test"));assertEquals(0,ledger.totals(5L).balance());assertEquals(0,ledger.totals(5L).earned);run(()->badges.sync(5L));assertEquals(0,db.count("select count(*) from kg_badge_award where app_user_id=5 and status='active'",p()));assertThrows(ServiceException.class,()->run(()->service.assess(vote,p("valid",true,"quality",true,"note","重复评定不应允许额外发放积分奖励"),"admin:test")));}
 @Test void hqQualityAssessmentAwardsExactlyTenExtra(){long c=create(),r=jury(c);run(()->service.vote(5L,r,p("choice","user","reason","结合订单约定和已核实照片，支持改善用户住宿条件")));db.update("update kg_mediation_round set deadline=date_sub(now(),interval 1 minute) where round_id=:r",p("r",r));run(()->service.finishRound(r));long v=db.count("select vote_id from kg_mediation_vote where round_id=:r and app_user_id=5",p("r",r));run(()->service.assess(v,p("valid",true,"quality",true,"note","意见引用清晰且与已核实证据一致，评定优质"),"admin:test"));assertEquals(15,ledger.totals(5L).balance());decide(c);}
 @Test void insufficientOrConflictedPoolDoesNotStart(){long c=create();ready(c);pool();db.insert("kg_user_follow",p("follower_id",5L,"followee_id",1L));assertThrows(ServiceException.class,()->command(3,c,"jury",p("anonymousNarrative",repeat("用户与门店对工作内容及食宿条件产生分歧，现有材料经过核实后提交评议。",3),"anonymousClaims","希望妥善解决","redactionConfirmed",true,"fileIds",Collections.singletonList(file(3L,true)))));assertEquals(0,db.count("select count(*) from kg_mediation_round",p()));}
 // 总部把待接单的工单转给另一位负责人后，原来的负责人不能再接（会把转交覆盖掉）；转交给无关账号说清原因；对方看不到发起人的 requestKey
 @Test void reassignedPendingCaseCanOnlyBeAcceptedByTheNewHandler(){long c=create();grant(11L,1L,"city_lead");
  CityLeadScope scope=(CityLeadScope)ReflectionTestUtils.getField(service,"cityScope");
  // 此测试新增第二位同城负责人；同步与授权相对应的范围夹具，不扩大其他账号范围。
  when(scope.covers(11L,2L)).thenReturn(true);
  try{
  assertEquals(Integer.valueOf(409),assertThrows(ServiceException.class,()->run(()->service.command(99L,c,"reassign",p("handlerId",4L,"note","转交给不是本城负责人的账号"),true,"admin:test"))).getCode());
  run(()->service.command(99L,c,"reassign",p("handlerId",11L,"note","原负责人请假，转交本城另一位负责人"),true,"admin:test"));
  ServiceException taken=assertThrows(ServiceException.class,()->command(3,c,"accept",p()));assertEquals(Integer.valueOf(409),taken.getCode());assertEquals("该工单已转交给其他城市负责人",taken.getMessage());
  command(11,c,"accept",p());assertEquals(Long.valueOf(11L),KgJdbc.asLong(db.one("select handler_id from kg_mediation_case where case_id=:c",p("c",c)).get("handlerId")));
  assertFalse(service.detail(2L,c,false).containsKey("requestKey"));assertTrue(service.detail(1L,c,false).containsKey("requestKey"));
  }finally{when(scope.covers(11L,2L)).thenReturn(false);}}
 @Test void ownStoreCoordinatorCannotAdjudicate(){long c=create();grant(3L,2L,"manager");assertThrows(ServiceException.class,()->command(3,c,"accept",p()));}
 @Test void closedWindowUsesActualCompletionNotBookedDates(){long o=db.insert("kg_stay_order",p("order_no","CANCELLED_TEST","stay_id",1L,"store_id",2L,"room_type_id",1L,"app_user_id",1L,"check_in","2026-01-01","check_out",LocalDate.now().minusDays(1),"nights",1,"guest_name","测试","guest_phone","TEST","pay_fen",1,"status","cancelled"));assertFalse(orders.eligible(orders.order("stay",o)));db.update("update kg_stay_order set checked_out_time=date_sub(now(),interval 5 day) where order_id=:o",p("o",o));assertTrue(orders.eligible(orders.order("stay",o)));db.update("update kg_stay_order set checked_out_time=date_sub(now(),interval 16 day) where order_id=:o",p("o",o));assertFalse(orders.eligible(orders.order("stay",o)));}
 @Test void manualAwardsAreIdempotentAndRespectPrivacyAndCityUniqueness(){Map<String,Object>b=p("userId",1L,"code","comfort","sourceKey","annual-test-1","note","经过实际核验的关怀纪念荣誉");long a=in(()->badges.grant(b,"admin:test"));assertEquals(a,in(()->badges.grant(b,"admin:test")));assertEquals("0",db.one("select visible from kg_badge_award where award_id=:a",p("a",a)).get("visible"));run(()->badges.display(1L,a,p("visible",true,"worn",true)));assertEquals("暖心纪念章",((Map<?,?>)members.identity(1L).get("achievement")).get("name"));assertThrows(ServiceException.class,()->run(()->badges.display(2L,a,p("visible",true))));run(()->badges.revoke(a,"核验原任命记录后撤回","admin:test"));assertNull(members.identity(1L).get("achievement"));run(()->badges.grant(p("userId",1L,"code","city_youth","sourceKey","city-test-01","city","平潭","note","正式任命经平台确认"),"admin:test"));assertThrows(ServiceException.class,()->run(()->badges.grant(p("userId",2L,"code","city_youth","sourceKey","city-test-02","city","平潭","note","重复同城任命需要拒绝"),"admin:test")));}
 @Test void qualityReviewAwardsAndRevocationReconcile(){long rv=db.insert("kg_review",p("app_user_id",1L,"target_type","stay","target_id",1L,"rating",5,"content",repeat("真实住宿评价说明干净舒适",5)));db.insert("kg_member_review_check",p("review_id",rv,"quality","1","reason","已核验评价"));run(()->badges.sync(1L));run(()->badges.sync(1L));assertEquals(1,db.count("select count(*) from kg_badge_award where app_user_id=1 and badge_code='review_gold'",p()));assertEquals(1,db.count("select count(*) from kg_badge_log where action='automatic_grant'",p()));db.update("update kg_member_review_check set revoked='1' where review_id=:r",p("r",rv));run(()->badges.sync(1L));assertEquals("inactive",db.one("select status from kg_badge_award where badge_code='review_gold'",p()).get("status"));}
 @Test void threeCalendarMonthsCombineApprovedFeedbackAndPosts(){for(int m=1;m<=3;m++){for(int n=0;n<4;n++)db.insert("kg_post",p("app_user_id",1L,"content","已审核的真实社区动态","status","online","create_time","2026-0"+m+"-10 12:00:00"));db.insert("kg_badge_feedback",p("app_user_id",1L,"request_key","month-test-"+m,"content","管理员已核验的真实意见箱建议内容","status","valid","create_time","2026-0"+m+"-12 12:00:00"));}run(()->badges.sync(1L));assertEquals(1,db.count("select count(*) from kg_badge_award where badge_code='recorder' and app_user_id=1 and status='active'",p()));db.update("delete from kg_post",p());run(()->badges.sync(1L));assertEquals("active",db.one("select status from kg_badge_award where badge_code='recorder'",p()).get("status"));}
 @Test void feedbackCannotApproveItselfOrCountTwice(){Map<String,Object>b=p("requestKey","test_feedback_01","content","这是一条实际意见反馈，建议改善入住前的路线指引内容。");run(()->badges.feedback(1L,b));run(()->badges.feedback(1L,b));assertEquals(1,badges.feedbacks(1L).size());assertEquals("pending",badges.feedbacks(1L).get(0).get("status"));long f=KgJdbc.asLong(badges.feedbacks(1L).get(0).get("feedbackId"));run(()->badges.assessFeedback(f,p("valid",true,"note","已核验真实且有效的反馈"),"admin:test"));assertThrows(ServiceException.class,()->run(()->badges.assessFeedback(f,p("valid",false,"note","重复审核不被接受"),"admin:test")));}
 @Test void signedEvidenceRejectsForgeryAndOtherUsers()throws Exception{long c=create();long f=db.count("select file_id from kg_mediation_file where case_id=:c limit 1",p("c",c));KgMediationFiles files=new KgMediationFiles("test-only-sign-key-01234567890123456789");field(files,"db",db);field(files,"mediation",service);assertNotNull(files.link(1L,f,false).get("url"));assertThrows(ServiceException.class,()->files.link(4L,f,false));org.springframework.mock.web.MockHttpServletResponse response=new org.springframework.mock.web.MockHttpServletResponse();files.serve(f,4L,"hq",System.currentTimeMillis()/1000+120,"forged",response);assertEquals(403,response.getStatus());}
 @Test void allReadModelsExecuteOnRealSchema(){assertNotNull(service.context(1L));assertNotNull(orders.mine(1L,"user",1));assertNotNull(service.list(3L,"city","",1,false));assertNotNull(service.stats());assertNotNull(badges.admin(1));assertNotNull(in(()->badges.wall(1L)));}
 @Test void headquartersMayReassignOnlyConflictFreeCityLead(){long c=create();ready(c);run(()->service.command(99L,c,"reassign",p("handlerId",3L,"note","负责人需要重新接收并跟进本次调解"),true,"admin:test"));assertThrows(ServiceException.class,()->run(()->service.command(99L,c,"reassign",p("handlerId",2L,"note","不能转交给涉及本次纠纷的商家本人"),true,"admin:test")));assertThrows(ServiceException.class,()->command(1,c,"reassign",p("handlerId",3L,"note","普通用户不能转交负责人")));run(()->service.command(99L,c,"contact",p("note","已通过电话联络处理负责人并核实当前情况"),true,"admin:test"));assertEquals(1,db.count("select count(*) from kg_mediation_log where action='contact'",p()));}
 @Test void signedEvidenceSupportsRangeAndStillChecksAccess()throws Exception{
  java.nio.file.Path dir=java.nio.file.Files.createTempDirectory("kg-private-test-");String old=com.ruoyi.common.config.RuoYiConfig.getProfile();new com.ruoyi.common.config.RuoYiConfig().setProfile(dir.resolve("profile").toString());
  try{KgMediationFiles files=new KgMediationFiles("test-only-sign-key-01234567890123456789");field(files,"db",db);field(files,"mediation",service);Map<String,Object> uploaded=files.upload(1L,new org.springframework.mock.web.MockMultipartFile("file","clip.mp4","video/mp4",new byte[]{0,1,2,3,4,5,6,7,8,9}),false);long f=KgJdbc.asLong(uploaded.get("fileId"));String url=(String)files.link(1L,f,false).get("url");Map<String,String> q=new HashMap<>();for(String pair:url.substring(url.indexOf('?')+1).split("&")){String[] kv=pair.split("=");q.put(kv[0],kv[1]);}
   org.springframework.mock.web.MockHttpServletResponse r=new org.springframework.mock.web.MockHttpServletResponse();files.serve(f,1L,"app",Long.parseLong(q.get("e")),q.get("s"),"bytes=2-5",r);assertEquals(206,r.getStatus());assertArrayEquals(new byte[]{2,3,4,5},r.getContentAsByteArray());assertEquals("bytes 2-5/10",r.getHeader("Content-Range"));
   r=new org.springframework.mock.web.MockHttpServletResponse();files.serve(f,1L,"app",Long.parseLong(q.get("e")),q.get("s"),"bytes=99-",r);assertEquals(416,r.getStatus());
   db.update("update kg_app_user set status='1' where user_id=1",p());r=new org.springframework.mock.web.MockHttpServletResponse();files.serve(f,1L,"app",Long.parseLong(q.get("e")),q.get("s"),"bytes=2-5",r);assertEquals(403,r.getStatus());
  }finally{new com.ruoyi.common.config.RuoYiConfig().setProfile(old);try(java.util.stream.Stream<java.nio.file.Path> paths=java.nio.file.Files.walk(dir)){paths.sorted(java.util.Comparator.reverseOrder()).forEach(f->{try{java.nio.file.Files.delete(f);}catch(Exception e){throw new RuntimeException(e);}});}}
 }
}
