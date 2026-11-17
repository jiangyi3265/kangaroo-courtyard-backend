package com.ruoyi.kangaroo.ext.community;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.core.KgArrivalTickets;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.ext.user.KgAccountService;
import com.ruoyi.kangaroo.ext.user.KgSupportService;
import com.ruoyi.kangaroo.security.*;
import com.ruoyi.kangaroo.controller.app.AppActivityController;

/** Actual candidate methods; JDBC Mock. Real lock races require separate HTTP/SQL. */
class KgPersonalWriteErasureTest {
 final KgJdbc db=mock(KgJdbc.class);
 final KgCommunityGuard guard=mock(KgCommunityGuard.class);
 final KgActivityService activity=new KgActivityService();
 final KgPostService post=new KgPostService();
 final KgSupportService support=new KgSupportService();
 final KgAccountService account=new KgAccountService();
 @BeforeEach void setup(){
  for(Object x:Arrays.asList(activity,post,support,account))ReflectionTestUtils.setField(x,"db",db);
  ReflectionTestUtils.setField(activity,"guard",guard);ReflectionTestUtils.setField(post,"guard",guard);
  ReflectionTestUtils.setField(account,"tokens",mock(AppTokenService.class));ReflectionTestUtils.setField(account,"arrivalTickets",mock(KgArrivalTickets.class));ReflectionTestUtils.setField(account,"messages",mock(com.ruoyi.kangaroo.service.IKgMessageService.class));
  AppLoginUser me=new AppLoginUser();me.setUserId(7L);AppUserContext.set(me);
  when(db.one(contains("from kg_app_user"),anyMap())).thenReturn(p("status","0","delFlag","0"));
  when(db.must(contains("from kg_app_user"),anyMap(),anyString())).thenReturn(p("nickname","synthetic"));
 }
 @AfterEach void clear(){AppUserContext.remove();}
 void gone(Runnable fn){
  when(db.one(contains("from kg_app_user"),anyMap())).thenReturn(p("status","1","delFlag","2"));
  ServiceException e=assertThrows(ServiceException.class,fn::run);assertEquals(409,e.getCode());
  verify(db,never()).insert(anyString(),anyMap());verify(db,never()).update(anyString(),anyMap());verify(db,never()).updateRow(anyString(),anyString(),any(),anyMap());verifyNoInteractions(guard);
 }
 @Test void staleSignupCannotWritePersonalSnapshot(){gone(()->activity.signup(7L,11L,"community"));}
 @Test void staleActivityCreateCannotCopyOrganizer(){gone(()->activity.createByUser(7L,p(),null));}
 @Test void staleActivityEditCannotWriteFreeText(){gone(()->activity.editByUser(7L,11L,p()));}
 @Test void staleCancellationCannotWriteReason(){gone(()->activity.cancel(7L,12L,"personal reason"));}
 @Test void staleReminderCannotWriteAfterErasure(){gone(()->activity.setRemind(7L,12L,"2h"));}
 @Test void staleFavoriteCannotRecreateErasedPreference(){gone(()->activity.favorite(7L,11L));}
 @Test void staleSubscriptionCannotRecreateErasedPreference(){gone(()->activity.subscribe(7L,"平潭",true));}
 @Test void stalePostCannotRecreateErasedContent(){gone(()->post.publish(7L,p("kind","daily","content","personal")));}
 @Test void staleCommentCannotRecreateErasedContent(){gone(()->post.comment(11L,7L,null,"personal"));}
 @Test void staleToggleRejectsInactiveAccount(){gone(()->ReflectionTestUtils.invokeMethod(post,"lockToggler",7L));}
 @Test void authenticatedAssistantCannotRecreateHistory(){gone(()->support.ask(7L,"home","synthetic unrelated question",null,null));}
 @Test void anonymousAssistantStillWorksWithoutAccountLock(){
  Map<String,Object> result=support.ask(null,"home","synthetic unrelated question",null,null);
  assertEquals("unknown",result.get("intent"));verify(db,never()).one(contains("from kg_app_user"),anyMap());
  verify(db).insert(eq("kg_support_log"),argThat(x->x.containsKey("app_user_id")&&x.get("app_user_id")==null&&"synthetic unrelated question".equals(x.get("question"))));
 }
 @Test void authenticatedAssistantLocksBeforeHistoryInsert(){
  support.ask(7L,"home","synthetic unrelated question",null,null);
  InOrder o=inOrder(db);o.verify(db).one(contains("for update"),eq(p("u",7L)));o.verify(db).insert(eq("kg_support_log"),argThat(x->Long.valueOf(7L).equals(x.get("app_user_id"))));
 }
 @Test void activeSignupAccountThenConsentThenActivityLock(){
  when(db.one(startsWith("select * from kg_activity where"),anyMap())).thenReturn(null);
  assertEquals(404,assertThrows(ServiceException.class,()->activity.signup(7L,11L,"community")).getCode());
  InOrder o=inOrder(db,guard);o.verify(db).one(contains("from kg_app_user"),eq(p("u",7L)));o.verify(guard).requireConsent(7L,"activity");o.verify(db).one(contains("from kg_activity where"),eq(p("id",11L)));
 }
 @Test void cancellationPreservesExistingActivityBeforeSignupLockOrder(){
  Map<String,Object> s=p("activityId",11L,"status","waitlist");
  when(db.one(startsWith("select * from kg_activity_signup where signup_id = :s and app_user_id = :u"),eq(p("s",12L,"u",7L)))).thenReturn(s);
  when(db.one(eq("select activity_id from kg_activity_signup where signup_id=:s"),eq(p("s",12L)))).thenReturn(p("activityId",11L));
  when(db.one(eq("select activity_id from kg_activity where activity_id=:a for update nowait"),eq(p("a",11L)))).thenReturn(p("activityId",11L));
  assertEquals("已取消候补",activity.cancel(7L,12L,"synthetic"));
  InOrder o=inOrder(db);
  o.verify(db).one(contains("from kg_app_user"),eq(p("u",7L)));
  o.verify(db).one(eq("select * from kg_activity_signup where signup_id = :s and app_user_id = :u"),eq(p("s",12L,"u",7L)));
  o.verify(db).one(eq("select activity_id from kg_activity_signup where signup_id=:s"),eq(p("s",12L)));
  o.verify(db).one(eq("select activity_id from kg_activity where activity_id=:a for update nowait"),eq(p("a",11L)));
  o.verify(db).one(eq("select * from kg_activity_signup where signup_id = :s and app_user_id = :u for update"),eq(p("s",12L,"u",7L)));
  o.verify(db).one(eq("select * from kg_activity where activity_id = :id for update"),eq(p("id",11L)));
  o.verify(db).update(contains("where signup_id = :s and status = 'waitlist'"),eq(p("r","synthetic","s",12L)));
 }
 @Test void cancellationRejectsForeignIdentityBeforeBusinessLocks(){
  when(db.one(startsWith("select * from kg_activity_signup"),anyMap())).thenReturn(null);
  assertEquals(404,assertThrows(ServiceException.class,()->activity.cancel(7L,12L,"synthetic")).getCode());
  verify(db,never()).one(contains("from kg_activity where"),anyMap());verify(db,never()).update(anyString(),anyMap());
 }
 @Test void cancellationRechecksOwnedSignupAfterActivityLock(){
  when(db.one(eq("select * from kg_activity_signup where signup_id = :s and app_user_id = :u"),anyMap())).thenReturn(p("activityId",11L,"status","waitlist"));
  when(db.one(eq("select activity_id from kg_activity_signup where signup_id=:s"),anyMap())).thenReturn(p("activityId",11L));
  when(db.one(eq("select * from kg_activity_signup where signup_id = :s and app_user_id = :u for update"),anyMap())).thenReturn(null);
  assertEquals(404,assertThrows(ServiceException.class,()->activity.cancel(7L,12L,"synthetic")).getCode());
  verify(db).one(eq("select activity_id from kg_activity where activity_id=:a for update nowait"),eq(p("a",11L)));
  verify(db,never()).update(anyString(),anyMap());
 }
 AppActivityController controller(KgActivityService service){AppActivityController x=new AppActivityController();ReflectionTestUtils.setField(x,"activities",service);return x;}
 void staleControllerAction(String action){
  KgActivityService service=spy(activity);Map<String,Object> old=p("activityId",11L,"appUserId",7L,"source","user");
  doReturn(old).when(service).ownUserActivity(7L,11L);doReturn("sent").when(service).broadcast(anyMap(),anyString(),anyString());doReturn("checked").when(service).checkinByCode(anyMap(),anyString());doReturn("cancelled").when(service).transition(anyMap(),anyString(),anyString(),anyString());
  when(db.one(contains("from kg_app_user"),anyMap())).thenReturn(p("status","1","delFlag","2"));
  assertEquals(409,assertThrows(ServiceException.class,()->controller(service).act(11L,action,p("text","private text","code","123456","reason","private reason"))).getCode());
  verify(service,never()).broadcast(anyMap(),anyString(),anyString());verify(service,never()).checkinByCode(anyMap(),anyString());verify(service,never()).transition(anyMap(),anyString(),anyString(),anyString());
 }
 @Test void staleOrganizerCannotBroadcastViaActualController(){staleControllerAction("broadcast");}
 @Test void staleOrganizerCannotCancelViaActualController(){staleControllerAction("cancel");}
 @Test void staleOrganizerCannotCheckinViaActualController(){staleControllerAction("checkin");}
 @Test void userManagementLocksAccountThenCurrentActivityBeforeBroadcast(){
  KgActivityService service=spy(activity);Map<String,Object> snapshot=p("activityId",11L,"appUserId",7L,"source","user","title","old"),locked=p("activityId",11L,"appUserId",7L,"source","user","title","new");
  doReturn(snapshot).when(service).ownUserActivity(7L,11L);when(db.one(eq("select * from kg_activity where activity_id = :id and del_flag = '0' for update"),eq(p("id",11L)))).thenReturn(locked);doReturn("sent").when(service).broadcast(locked,"private text","发起人");
  assertEquals("sent",controller(service).act(11L,"broadcast",p("text","private text")).get("msg"));
  InOrder o=inOrder(db,service);o.verify(db).one(contains("from kg_app_user"),eq(p("u",7L)));o.verify(service).ownUserActivity(7L,11L);o.verify(db).one(eq("select * from kg_activity where activity_id = :id and del_flag = '0' for update"),eq(p("id",11L)));o.verify(service).broadcast(locked,"private text","发起人");
 }
 @Test void userManagementRejectsChangedOwnerAfterLock(){
  KgActivityService service=spy(activity);doReturn(p("activityId",11L,"appUserId",7L,"source","user")).when(service).ownUserActivity(7L,11L);
  when(db.one(contains("from kg_activity where"),anyMap())).thenReturn(p("activityId",11L,"appUserId",8L,"source","user"));
  assertEquals(403,assertThrows(ServiceException.class,()->controller(service).act(11L,"broadcast",p("text","private text"))).getCode());verify(service,never()).broadcast(anyMap(),anyString(),anyString());
 }
 @Test void userManagementRejectsChangedSourceAfterLock(){
  KgActivityService service=spy(activity);doReturn(p("activityId",11L,"appUserId",7L,"source","user")).when(service).ownUserActivity(7L,11L);
  when(db.one(contains("from kg_activity where"),anyMap())).thenReturn(p("activityId",11L,"appUserId",7L,"source","yard"));
  assertEquals(403,assertThrows(ServiceException.class,()->controller(service).act(11L,"broadcast",p("text","private text"))).getCode());verify(service,never()).broadcast(anyMap(),anyString(),anyString());
 }
 @Test void userManagementWrapperIsTransactional()throws Exception{
  Transactional tx=KgActivityService.class.getMethod("actAsUser",Long.class,Long.class,String.class,String.class,String.class,String.class).getAnnotation(Transactional.class);assertNotNull(tx);assertTrue(Arrays.asList(tx.rollbackFor()).contains(Exception.class));
 }
 @Test void reminderSubscriptionAndAssistantAreTransactional()throws Exception{
  for(java.lang.reflect.Method m:Arrays.asList(KgActivityService.class.getMethod("setRemind",Long.class,Long.class,String.class),KgActivityService.class.getMethod("subscribe",Long.class,String.class,boolean.class),KgSupportService.class.getMethod("ask",Long.class,String.class,String.class,String.class,String.class))){
   Transactional tx=m.getAnnotation(Transactional.class);assertNotNull(tx);assertTrue(Arrays.asList(tx.rollbackFor()).contains(Exception.class));
  }
 }
 @Test void phoneReadDeniesInactiveOwnerIndependentlyOfStoredSnapshot(){
  KgActivityService s=spy(activity);doReturn(p("phoneWindow",true)).when(s).manageView(anyMap());
  when(db.one(contains("from kg_activity_signup"),anyMap())).thenAnswer(i->{String q=i.getArgument(0);return q.contains("u.status = '0'")&&q.contains("u.del_flag = '0'")?null:p("appUserId",7L,"name","old personal name","phone","13800138000","status","confirmed");});
  assertEquals(404,assertThrows(ServiceException.class,()->s.revealPhone(8L,p("activityId",11L),12L)).getCode());verify(db,never()).update(anyString(),anyMap());
 }
 @Test void activePhoneStillReturnsExactSnapshotAndAudit(){
  KgActivityService s=spy(activity);doReturn(p("phoneWindow",true)).when(s).manageView(anyMap());
  when(db.one(contains("from kg_activity_signup"),anyMap())).thenReturn(p("appUserId",7L,"name","synthetic","phone","13800138000","status","confirmed"));
  assertEquals(p("name","synthetic","phone","13800138000"),s.revealPhone(8L,p("activityId",11L),12L));
  verify(db).one(argThat(q->q.contains("join kg_app_user")&&q.contains("u.status = '0'")&&q.contains("u.del_flag = '0'")),eq(p("s",12L,"a",11L)));verify(db).update(contains("insert into kg_pii_access"),eq(p("v",8L,"t",7L,"a",11L)));
 }
 @Test void erasureLocksActivitiesThenSignupsAndOnlyClearsSnapshotFields(){
  account.deactivate("确认注销","synthetic");InOrder o=inOrder(db);
  o.verify(db).one(contains("from kg_app_user"),eq(p("u",7L)));
  o.verify(db).list(argThat(q->q.startsWith("select activity_id from kg_activity")&&q.contains("order by activity_id for update")),eq(p("u",7L)));
  o.verify(db).list(argThat(q->q.startsWith("select signup_id from kg_activity_signup")&&q.contains("order by activity_id, signup_id for update")),eq(p("u",7L)));
  o.verify(db).update(eq("update kg_activity_signup set name = '已注销用户', phone = null, update_time = now() where app_user_id = :u"),eq(p("u",7L)));
  verify(db).update(eq("delete from kg_activity_subscribe where app_user_id = :u"),eq(p("u",7L)));
  verify(db,never()).update(argThat(q->q.startsWith("delete from kg_activity_signup")||q.startsWith("delete from kg_activity ")||q.startsWith("update kg_pay_order")),anyMap());
 }
 @Test void legacyNoticeProjectionIsExactActivityTemplateWithoutDeletionOrRouteChange(){
  account.deactivate("确认注销","synthetic");
  verify(db).update(argThat(q->q.startsWith("update kg_message set content =")&&q.contains("category = 'system'")&&q.contains("title in ('活动有新报名','你发起的活动有新报名')")&&q.contains("biz_type = 'activity'")&&q.contains("where app_user_id = :u")&&!q.contains("route =")&&!q.contains("del_flag =")&&!q.contains("update_time =")),eq(p("u",7L)));
  verify(db).update(argThat(q->q.startsWith("update kg_message set content =")&&q.contains("category = 'order'")&&q.contains("title = '活动退款待审批'")&&q.contains("biz_id in (select activity_id from kg_activity_signup where app_user_id = :u)")),eq(p("u",7L)));
 }
}
