package com.ruoyi.kangaroo.ext.user;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.core.KgArrivalTickets;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;

/** Service-flow and transaction-interceptor regression with explicit Mock resources.
 * The separate real MySQL probe proves row-lock/concurrent notification behavior. */
class KgArrivalNotificationIdempotencyTest {
    final KgJdbc db=mock(KgJdbc.class);
    final StaffSupport support=mock(StaffSupport.class);
    final KgArrivalTickets tickets=mock(KgArrivalTickets.class);
    final KgUserHubService service=spy(new KgUserHubService());
    final AtomicReference<Map<String,Object>> saved=new AtomicReference<>();
    // 到达日期不能早于今天：用一个远期日期，测试不随日子过期
    Map<String,Object> body() {return p("arriveDate","2099-10-09","arriveTime","14:25","transport","火车","transportNo"," G12 ","needPickup",true,"tickets",Collections.emptyList(),"note"," 备注 ");}
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service,"db",db);ReflectionTestUtils.setField(service,"support",support);ReflectionTestUtils.setField(service,"arrivalTickets",tickets);
        when(db.one(eq("select status, del_flag from kg_app_user where user_id=:u for update"),eq(p("u",7L)))).thenReturn(p("status","0","delFlag","0"));
        when(db.one(eq("select app_id from kg_application where app_id=:a for update"),eq(p("a",12L)))).thenReturn(p("appId",12L));
        when(db.one(startsWith("select a.*, j.title"),anyMap())).thenReturn(p("appUserId",7L,"jobType","volunteer","status","contract_signed","storeId",4L,"realName","用户"));
        when(db.one(startsWith("select app_user_id u, date_format"),anyMap())).thenAnswer(i->saved.get());
        when(db.update(startsWith("insert into kg_app_arrival"),anyMap())).thenAnswer(i->{saved.set(new LinkedHashMap<>((Map<String,Object>)i.getArgument(1)));return 1;});
        doReturn(p("appId",12L)).when(service).contract(7L,12L);
    }
    @Test void identicalReplayPersistsAndNotifiesOnlyOnce() {
        assertEquals(12L,service.saveArrival(7L,12L,body()).get("appId"));service.saveArrival(7L,12L,body());
        verify(db,times(2)).one(contains("kg_application where app_id=:a for update"),anyMap());
        verify(db,times(1)).update(startsWith("insert into kg_app_arrival"),anyMap());
        verify(support,times(1)).notifyStore(eq(4L),eq("apply"),eq("报名人更新了到店信息"),anyString(),eq("application"),eq(12L),anyString());
        verify(tickets,times(2)).validateForSave(eq(7L),eq(12L),eq(Collections.emptyList()));
    }
    @Test void actualRevisionAndChangeBackAreEachNotified() {
        Map<String,Object> body=body();service.saveArrival(7L,12L,body);body.put("note","新备注");service.saveArrival(7L,12L,body);body.put("note","备注");service.saveArrival(7L,12L,body);
        verify(db,times(3)).update(startsWith("insert into kg_app_arrival"),anyMap());
        verify(support,times(3)).notifyStore(anyLong(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString());
    }
    @Test void canonicalWhitespaceNullAndTruncationUsePersistedSemantics() {
        Map<String,Object> body=body();body.put("note",String.join("",Collections.nCopies(210,"字")));service.saveArrival(7L,12L,body);
        body.put("note",String.join("",Collections.nCopies(200,"字")));body.put("transportNo","G12");service.saveArrival(7L,12L,body);
        verify(db,times(1)).update(startsWith("insert into kg_app_arrival"),anyMap());
        Map<String,Object> old=p("u",7L,"d",null,"t",null,"tr",null,"no",null,"pk","0","tk",null,"n",null);
        assertTrue(KgUserHubService.sameArrival(old,p("u",7L,"d","","t","","tr","","no","","pk","0","tk","","n","")));
    }
    @Test void changedTicketOrderIsARealRevision() {
        Map<String,Object> old=p("u",7L,"tk","a,b"),next=p("u",7L,"tk","b,a");assertFalse(KgUserHubService.sameArrival(old,next));
    }
    @Test void noOpStillChecksOwnershipStateAndPrivateTickets() {
        service.saveArrival(7L,12L,body());clearInvocations(db,support);
        doThrow(new ServiceException("ticket rejected",403)).when(tickets).validateForSave(anyLong(),anyLong(),anyList());
        assertEquals(403,assertThrows(ServiceException.class,()->service.saveArrival(7L,12L,body())).getCode());
        verify(db,never()).update(anyString(),anyMap());verifyNoInteractions(support);
    }
    @Test void mismatchedStoredOwnerFailsClosed() {
        service.saveArrival(7L,12L,body());saved.get().put("u",8L);clearInvocations(db,support);
        assertEquals(409,assertThrows(ServiceException.class,()->service.saveArrival(7L,12L,body())).getCode());verify(db,never()).update(anyString(),anyMap());verifyNoInteractions(support);
    }
    @Test void onlineAndUnownedApplicationNeverWriteOrNotify() {
        when(db.one(startsWith("select a.*, j.title"),anyMap())).thenReturn(null);
        assertEquals(404,assertThrows(ServiceException.class,()->service.saveArrival(7L,12L,body())).getCode());
        when(db.one(startsWith("select a.*, j.title"),anyMap())).thenReturn(p("jobType","online","status","store_confirmed"));
        assertEquals(409,assertThrows(ServiceException.class,()->service.saveArrival(7L,12L,body())).getCode());
        verify(db,never()).update(anyString(),anyMap());verifyNoInteractions(support,tickets);
    }
    // 「接站需求」已下线：旧版小程序照旧带 needPickup:true，服务端不读、不写 need_pickup（新行按建表默认值 '0'，老行原值不动），通知里也不再有「（需要接站）」
    @Test void legacyPickupFlagIsIgnoredOnWriteAndInNotice() {
        service.saveArrival(7L,12L,body());
        ArgumentCaptor<String> reads=ArgumentCaptor.forClass(String.class),writes=ArgumentCaptor.forClass(String.class),notice=ArgumentCaptor.forClass(String.class);
        verify(db,atLeastOnce()).one(reads.capture(),anyMap());verify(db).update(writes.capture(),anyMap());
        assertTrue(reads.getAllValues().stream().noneMatch(sql->sql.contains("need_pickup")),"查旧值不再取 need_pickup");
        assertFalse(writes.getValue().contains("need_pickup"),"insert / on duplicate 都不再写 need_pickup");
        assertFalse(saved.get().containsKey("pk"));
        verify(support).notifyStore(eq(4L),eq("apply"),eq("报名人更新了到店信息"),notice.capture(),eq("application"),eq(12L),anyString());
        assertEquals("用户：2099-10-09 14:25 火车",notice.getValue());assertFalse(notice.getValue().contains("接站"));
    }
    // 页面要求先签责任书：商家已确认（还没签）时不能存、不通知门店
    @Test void unsignedApplicationCannotSaveArrival() {
        when(db.one(startsWith("select a.*, j.title"),anyMap())).thenReturn(p("appUserId",7L,"jobType","volunteer","status","store_confirmed","storeId",4L,"realName","用户"));
        ServiceException e=assertThrows(ServiceException.class,()->service.saveArrival(7L,12L,body()));
        assertEquals(409,e.getCode());assertEquals("请先签署责任书，再填写到店信息",e.getMessage());
        verify(db,never()).update(anyString(),anyMap());verifyNoInteractions(support);
    }
    // 到达日期不能早于今天；已经存过的旧日期原样带回（到店后改备注）照样能存
    @Test void pastArrivalDateIsRejectedUnlessAlreadySaved() {
        Map<String,Object> body=body();body.put("arriveDate","2020-01-01");
        assertEquals(422,assertThrows(ServiceException.class,()->service.saveArrival(7L,12L,body)).getCode());
        verify(db,never()).update(anyString(),anyMap());verifyNoInteractions(support);
        saved.set(p("u",7L,"d","2020-01-01","t","14:25","tr","火车","no","G12","tk","","n","备注"));
        body.put("note","到店后补一句");service.saveArrival(7L,12L,body);
        verify(db,times(1)).update(startsWith("insert into kg_app_arrival"),anyMap());
    }
    @Test void togglingOnlyTheRetiredPickupSwitchIsNotARevision() {
        Map<String,Object> body=body();service.saveArrival(7L,12L,body);
        body.put("needPickup",false);service.saveArrival(7L,12L,body);body.remove("needPickup");service.saveArrival(7L,12L,body);
        verify(db,times(1)).update(startsWith("insert into kg_app_arrival"),anyMap());
        verify(support,times(1)).notifyStore(anyLong(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString());
    }
    @Test void confirmedArrivalCannotBeMovedOrClearedButNotesRemainEditable() {
        String day="2020-01-01";
        for(String state: new String[]{"onboarded","completed"}) {
            when(db.one(startsWith("select a.*, j.title"),anyMap())).thenReturn(p("appUserId",7L,"jobType","volunteer","status",state,"storeId",4L,"realName","用户","arrivalDate",day));
            saved.set(p("u",7L,"d",day,"t","14:25","tr","火车","no","G12","tk","","n","备注"));
            Map<String,Object> input=body();
            clearInvocations(db,support);
            assertEquals(409,assertThrows(ServiceException.class,()->service.saveArrival(7L,12L,input)).getCode());
            input.put("arriveDate","");
            assertEquals(409,assertThrows(ServiceException.class,()->service.saveArrival(7L,12L,input)).getCode());
            verify(db,never()).update(anyString(),anyMap());verifyNoInteractions(support);
            input.put("arriveDate",day);input.put("note","补交材料");
            service.saveArrival(7L,12L,input);
            verify(db,times(1)).update(startsWith("insert into kg_app_arrival"),anyMap());
            assertEquals(day,saved.get().get("d"));
        }
    }
    @Test void confirmedApplicationWithoutArrivalRecordCanKeepFallbackDateAndAddNotes() {
        String day="2020-01-01";
        when(db.one(startsWith("select a.*, j.title"),anyMap())).thenReturn(p("appUserId",7L,"jobType","volunteer","status","onboarded","storeId",4L,"realName","用户","arrivalDate",day));
        Map<String,Object> input=body();input.put("arriveDate",day);
        service.saveArrival(7L,12L,input);
        assertEquals(day,saved.get().get("d"));
    }
    @Test void historicalEarlyConfirmationIsNotSilentlyRewrittenWhenAddingNotes() {
        String historical="2099-10-09";
        when(db.one(startsWith("select a.*, j.title"),anyMap())).thenReturn(p("appUserId",7L,"jobType","volunteer","status","onboarded","storeId",4L,"realName","用户"));
        saved.set(p("u",7L,"d",historical,"t","14:25","tr","火车","no","G12","tk","","n","备注"));
        Map<String,Object> input=body();input.put("note","补资料");service.saveArrival(7L,12L,input);
        assertEquals(historical,saved.get().get("d"));
        verify(db,never()).update(contains("update kg_application"),anyMap());
    }
    @Test void sameArrivalNoLongerComparesPickup() {
        Map<String,Object> old=p("u",7L,"d","2026-10-09","t","14:25","tr","火车","no","G12","pk","1","tk","","n","备注");
        Map<String,Object> next=new LinkedHashMap<>(old);next.remove("pk");assertTrue(KgUserHubService.sameArrival(old,next));
        next.put("pk","0");assertTrue(KgUserHubService.sameArrival(old,next));
        next.put("n","新备注");assertFalse(KgUserHubService.sameArrival(old,next));
    }
    @Test void notificationFailureRequestsTransactionRollback() {
        doThrow(new IllegalStateException("fixture notification failure")).when(support).notifyStore(anyLong(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString());
        PlatformTransactionManager manager=mock(PlatformTransactionManager.class);TransactionStatus tx=mock(TransactionStatus.class);when(manager.getTransaction(any())).thenReturn(tx);
        ProxyFactory f=new ProxyFactory(service);f.setProxyTargetClass(true);f.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        assertThrows(IllegalStateException.class,()->((KgUserHubService)f.getProxy()).saveArrival(7L,12L,body()));verify(manager).rollback(tx);verify(manager,never()).commit(any());
    }
}
