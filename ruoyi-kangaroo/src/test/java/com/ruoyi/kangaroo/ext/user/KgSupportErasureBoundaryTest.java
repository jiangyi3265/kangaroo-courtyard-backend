package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
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
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppTokenService;
import com.ruoyi.kangaroo.security.AppUserContext;

/** Actual service methods / Mock JDBC. Does not prove a MySQL race or HTTP authorization. */
class KgSupportErasureBoundaryTest {
    final KgJdbc db=mock(KgJdbc.class);
    final StaffSupport notifications=mock(StaffSupport.class);
    final AppTokenService tokens=mock(AppTokenService.class);
    final KgSupportService service=new KgSupportService();
    final KgAccountService account=new KgAccountService();
    @BeforeEach void setup(){
        ReflectionTestUtils.setField(service,"db",db);
        ReflectionTestUtils.setField(service,"support",notifications);
        ReflectionTestUtils.setField(account,"db",db);
        ReflectionTestUtils.setField(account,"tokens",tokens);
        ReflectionTestUtils.setField(account,"arrivalTickets",mock(KgArrivalTickets.class));
        AppLoginUser me=new AppLoginUser();me.setUserId(7L);AppUserContext.set(me);
        when(db.one(contains("from kg_app_user"),anyMap())).thenReturn(p("status","0","delFlag","0"));
        when(db.must(contains("from kg_app_user"),anyMap(),anyString())).thenReturn(p("nickname","synthetic user"));
        when(db.one(contains("from kg_support_ticket"),anyMap())).thenReturn(p("appUserId",7L));
        when(db.must(contains("from kg_support_ticket"),anyMap(),anyString())).thenReturn(p("appUserId",7L,"question","synthetic question"));
        when(db.insert(eq("kg_support_ticket"),anyMap())).thenReturn(12L);
    }
    @AfterEach void clear(){AppUserContext.remove();}
    Map<String,Object> body(){return p("question","synthetic question","summary","synthetic summary","related","synthetic relation","context","profile");}
    void unavailable(Map<String,Object> state){
        when(db.one(contains("from kg_app_user"),anyMap())).thenReturn(state);
        assertEquals(409,assertThrows(ServiceException.class,()->service.createTicket(7L,body())).getCode());
        verify(db,never()).count(contains("from kg_support_ticket"),anyMap());
        verify(db,never()).insert(anyString(),anyMap());
    }
    @Test void deletedAccountCannotCreateNewPersonalContent(){unavailable(p("status","1","delFlag","2"));}
    @Test void disabledAccountCannotCreateNewPersonalContent(){unavailable(p("status","1","delFlag","0"));}
    @Test void missingAccountCannotCreateNewPersonalContent(){unavailable(null);}
    @Test void activeCreationLocksExactAccountBeforeQuotaAndInsert(){
        when(db.count(contains("from kg_support_ticket"),anyMap())).thenReturn(4L);
        assertEquals(12L,service.createTicket(7L,body()).get("ticketId"));
        InOrder o=inOrder(db);
        o.verify(db).one(argThat(q->q.contains("from kg_app_user")&&q.contains("for update")),eq(p("u",7L)));
        o.verify(db).count(contains("status = 'open'"),eq(p("u",7L)));
        o.verify(db).insert(eq("kg_support_ticket"),argThat(row->Long.valueOf(7L).equals(row.get("app_user_id"))&&"synthetic question".equals(row.get("question"))&&"synthetic summary".equals(row.get("summary"))&&"synthetic relation".equals(row.get("related"))&&"open".equals(row.get("status"))));
    }
    @Test void quotaFiveStillRejectsWithoutWriteAfterAccountLock(){
        when(db.count(contains("from kg_support_ticket"),anyMap())).thenReturn(5L);
        assertEquals(409,assertThrows(ServiceException.class,()->service.createTicket(7L,body())).getCode());
        InOrder o=inOrder(db);o.verify(db).one(contains("for update"),eq(p("u",7L)));o.verify(db).count(contains("status = 'open'"),eq(p("u",7L)));
        verify(db,never()).insert(anyString(),anyMap());
    }
    @Test void invalidQuestionStillRejectsWithoutPersonalWrite(){
        assertEquals(422,assertThrows(ServiceException.class,()->service.createTicket(7L,p("question"," "))).getCode());
        verify(db,never()).insert(anyString(),anyMap());
    }
    @Test void replyCannotRepopulateDeletedAccountOrNotifyIt(){
        when(db.one(contains("from kg_app_user"),anyMap())).thenReturn(p("status","1","delFlag","2"));
        assertEquals(409,assertThrows(ServiceException.class,()->service.reply(12L,"synthetic reply","operator")).getCode());
        verify(db,never()).update(anyString(),anyMap());verifyNoInteractions(notifications);
    }
    @Test void missingTicketIs404WithoutTouchingAccountOrNotification(){
        when(db.one(contains("from kg_support_ticket"),anyMap())).thenReturn(null);
        assertEquals(404,assertThrows(ServiceException.class,()->service.reply(12L,"synthetic reply","operator")).getCode());
        verify(db,never()).one(contains("from kg_app_user"),anyMap());verify(db,never()).update(anyString(),anyMap());verifyNoInteractions(notifications);
    }
    @Test void replyUsesAccountThenExactOwnedTicketAndPreservesNotification(){
        assertEquals("已回复并通知用户",service.reply(12L,"synthetic reply","operator"));
        InOrder o=inOrder(db,notifications);
        o.verify(db).one(contains("select app_user_id from kg_support_ticket"),eq(p("t",12L)));
        o.verify(db).one(contains("from kg_app_user"),eq(p("u",7L)));
        o.verify(db).must(argThat(q->q.contains("app_user_id = :u")&&q.contains("for update")),eq(p("t",12L,"u",7L)),anyString());
        o.verify(db).update(contains("update kg_support_ticket set reply"),eq(p("r","synthetic reply","by","operator","t",12L)));
        o.verify(notifications).notifyUser(7L,"system","客服回复了你的问题","synthetic reply","support_ticket",12L,"/pages/home/assistant?ticket=12");
    }
    @Test void ownershipChangeAfterIdentityLookupRefusesReply(){
        when(db.must(contains("app_user_id = :u for update"),anyMap(),anyString())).thenThrow(new ServiceException("synthetic ownership changed",404));
        assertEquals(404,assertThrows(ServiceException.class,()->service.reply(12L,"synthetic reply","operator")).getCode());
        verify(db,never()).update(anyString(),anyMap());verifyNoInteractions(notifications);
    }
    @Test void erasureScrubsTicketAndCopiedNotificationWithoutDeletingWorkflow(){
        account.deactivate("确认注销","synthetic reason");
        InOrder o=inOrder(db,tokens);
        o.verify(db).one(contains("kg_app_user where user_id = :u for update"),eq(p("u",7L)));
        o.verify(db).list(eq("select ticket_id from kg_support_ticket where app_user_id = :u order by ticket_id for update"),eq(p("u",7L)));
        o.verify(db).update(argThat(q->q.contains("m.biz_type = 'support_ticket'")&&q.contains("m.biz_id = t.ticket_id")&&q.contains("m.route = null")&&q.contains("m.del_flag = '2'")&&q.contains("where t.app_user_id = :u")),eq(p("u",7L)));
        o.verify(db).update(argThat(q->q.startsWith("update kg_support_ticket set question = '关联账号已注销，原问题已清除', summary = null, related = null, reply = null")&&q.endsWith("where app_user_id = :u")&&!q.contains("status =")&&!q.contains("replied_by =")&&!q.contains("ticket_id =")),eq(p("u",7L)));
        o.verify(tokens).kickOut(7L);
        verify(db,never()).update(startsWith("delete from kg_support_ticket"),anyMap());
    }
    @Test void failedSupportScrubRollsBackErasureAndDoesNotRevokeSessions(){
        doThrow(new ServiceException("synthetic database failure",503)).when(db).update(startsWith("update kg_support_ticket set"),anyMap());
        PlatformTransactionManager tm=mock(PlatformTransactionManager.class);TransactionStatus tx=mock(TransactionStatus.class);when(tm.getTransaction(any())).thenReturn(tx);
        ProxyFactory f=new ProxyFactory(account);f.setProxyTargetClass(true);f.addAdvice(new TransactionInterceptor(tm,new AnnotationTransactionAttributeSource()));
        assertEquals(503,assertThrows(ServiceException.class,()->((KgAccountService)f.getProxy()).deactivate("确认注销","synthetic reason")).getCode());
        verify(tm).rollback(tx);verify(tm,never()).commit(any());verifyNoInteractions(tokens);
    }
    @Test void wrongConfirmationDoesNotScrubSupport(){
        assertEquals(422,assertThrows(ServiceException.class,()->account.deactivate("wrong","synthetic reason")).getCode());
        verify(db,never()).insert(anyString(),anyMap());verify(db,never()).update(anyString(),anyMap());verifyNoInteractions(tokens);
    }
}
