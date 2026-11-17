package com.ruoyi.kangaroo.ext.community;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;

/** Real service and Spring transaction interceptor; JDBC/resource are Mock.
 * Real MySQL locking, SQL visibility and paging are tested separately. */
class KgChatWatermarkTest {
    final KgJdbc db=mock(KgJdbc.class);
    final KgCommunityGuard guard=mock(KgCommunityGuard.class);
    final IKgStaffGrantService grants=mock(IKgStaffGrantService.class);
    final KgChatService service=new KgChatService();
    final AtomicLong watermark=new AtomicLong(0);
    final List<Map<String,Object>> rows=new ArrayList<>();
    @BeforeEach void setup() {
        when(db.one(startsWith("select status, del_flag from kg_app_user"),anyMap())).thenReturn(p("status","0","delFlag","0"));
        ReflectionTestUtils.setField(service,"db",db);
        ReflectionTestUtils.setField(service,"guard",guard);
        ReflectionTestUtils.setField(service,"grants",grants);
        KgChatReception reception=mock(KgChatReception.class);ReflectionTestUtils.setField(service,"reception",reception);
        when(reception.staff(anyLong(),anyBoolean())).thenAnswer(i->grants.listActiveStaffUserIds(i.getArgument(0)));
        when(db.one(startsWith("select * from kg_chat_member"),anyMap())).thenReturn(p("role","member"));
        when(db.must(startsWith("select conv_id, conv_type from kg_chat_conv"),anyMap(),anyString())).thenReturn(p("convId",12L));
        when(db.must(startsWith("select * from kg_chat_conv"),anyMap(),anyString())).thenReturn(p("convId",12L,"convType","group","status","active","title","群"));
        when(db.list(contains("from kg_chat_msg m"),anyMap())).thenAnswer(i->new ArrayList<>(rows));
        when(db.update(startsWith("update kg_chat_member set last_read_id"),anyMap())).thenAnswer(i->{Map<String,Object> v=i.getArgument(1);watermark.accumulateAndGet(((Number)v.get("l")).longValue(),Math::max);return 1;});
        when(db.must(startsWith("select last_read_id from kg_chat_member"),anyMap(),anyString())).thenAnswer(i->p("lastReadId",watermark.get()));
        when(db.claimIdem(anyString(),eq("chat_message"))).thenReturn(true);
        when(db.insert(eq("kg_chat_msg"),anyMap())).thenReturn(73L);
        when(db.update(startsWith("update kg_idempotency set biz_id"),anyMap())).thenReturn(1);
    }
    Map<String,Object> row(long id,long sender,String status) {return p("msgId",id,"senderId",sender,"senderRole","user","content","消息"+id,"status",status);}
    void page(int first,int last) {for(int i=first;i<=last;i++)rows.add(row(i,8L,"visible"));}
    void noClear() {verify(db,never()).update(startsWith("update kg_message set read_flag"),anyMap());}
    @Test void firstFiftyOfFiftyOneKeepsReminder() {
        page(2,51);when(db.count(contains("from kg_chat_msg m where"),anyMap())).thenReturn(1L);
        Map<String,Object> r=service.messages(7L,12L,1L);
        assertEquals(50,((List<?>)r.get("rows")).size());assertEquals(51L,r.get("lastId"));assertEquals(51L,watermark.get());noClear();
        verify(db).count(anyString(),argThat(v->Long.valueOf(51).equals(v.get("l"))));
    }
    @Test void finalPageClearsOnlyItsRecipientConversation() {
        page(52,52);Map<String,Object> r=service.messages(7L,12L,51L);
        assertEquals(52L,r.get("lastId"));assertEquals(52L,watermark.get());
        verify(db).update(startsWith("update kg_message set read_flag"),eq(p("u",7L,"c",12L)));
    }
    @Test void fullPageWithoutRemainderCanClear() {page(2,51);service.messages(7L,12L,1L);verify(db).update(startsWith("update kg_message set read_flag"),anyMap());}
    @Test void emptyInitialReadDoesNotWriteReadState() {assertEquals(0L,service.messages(7L,12L,0L).get("lastId"));verify(db,never()).update(anyString(),anyMap());}
    @Test void blockedRowsDoNotAdvanceWatermarkOrExposeText() {
        rows.add(row(9,8,"visible"));when(guard.blocked(7L,8L)).thenReturn(true);
        Map<String,Object> r=service.messages(7L,12L,0L);assertEquals(0,((List<?>)r.get("rows")).size());assertEquals(0L,r.get("lastId"));verify(db,never()).update(anyString(),anyMap());
    }
    @Test void stalePageUsesAlreadyAdvancedWatermark() {
        watermark.set(99L);page(2,3);service.messages(7L,12L,1L);assertEquals(99L,watermark.get());
        verify(db).count(contains("from kg_chat_msg m where"),argThat(v->Long.valueOf(99).equals(v.get("l"))));
    }
    @Test void recalledMessageRemainsVisibleAndAcknowledged() {
        rows.add(row(8,8,"recalled"));Map<String,Object> r=service.messages(7L,12L,0L);
        assertEquals("（消息已撤回）",((Map<?,?>)((List<?>)r.get("rows")).get(0)).get("content"));assertEquals(8L,watermark.get());
    }
    @Test void reminderRemainderUsesOtherVisibleMessagesAndBilateralBlocks() {
        page(2,2);service.messages(7L,12L,1L);ArgumentCaptor<String> sql=ArgumentCaptor.forClass(String.class);
        verify(db).count(sql.capture(),eq(p("c",12L,"u",7L,"l",2L)));String q=sql.getValue();
        for(String fragment:new String[]{"m.conv_id = :c","m.msg_id > :l","m.sender_id <> :u","m.status in ('visible','recalled')","not exists","b.user_id = :u and b.blocked_id = m.sender_id","b.user_id = m.sender_id and b.blocked_id = :u"})assertTrue(q.contains(fragment),fragment);
    }
    @Test void unauthorizedUserCannotAcquireConversationLockOrWrite() {
        when(db.one(startsWith("select * from kg_chat_member"),anyMap())).thenReturn(null);
        assertEquals(403,assertThrows(ServiceException.class,()->service.messages(7L,12L,0L)).getCode());
        verify(db,never()).must(anyString(),anyMap(),anyString());verify(db,never()).update(anyString(),anyMap());verify(db,never()).list(anyString(),anyMap());
    }
    @Test void revokedStaffCannotAcquireConversationLock() {
        when(db.one(startsWith("select * from kg_chat_member"),anyMap())).thenReturn(p("role","store"));
        when(db.one(startsWith("select c.conv_type"),anyMap())).thenReturn(p("convType","store","storeId",4L));
        when(grants.listActiveStaffUserIds(4L)).thenReturn(Collections.emptyList());
        assertEquals(403,assertThrows(ServiceException.class,()->service.messages(7L,12L,0L)).getCode());verify(db,never()).must(anyString(),anyMap(),anyString());
    }
    @Test void closedConversationStillAllowsAuthorizedHistory() {
        when(db.must(startsWith("select conv_id, conv_type from kg_chat_conv"),anyMap(),anyString())).thenReturn(p("convId",12L,"status","closed"));
        page(1,1);assertEquals(1,((List<?>)service.messages(7L,12L,0L).get("rows")).size());
    }
    @Test void missingConversationFailsBeforeDataReadOrWatermarkWrite() {
        when(db.must(startsWith("select conv_id, conv_type from kg_chat_conv"),anyMap(),anyString())).thenThrow(new ServiceException("会话不存在",404));
        assertEquals(404,assertThrows(ServiceException.class,()->service.messages(7L,12L,0L)).getCode());verify(db,never()).list(anyString(),anyMap());verify(db,never()).update(anyString(),anyMap());
    }
    @Test void existingMemberLockPrecedesPlainCommittedMessageRead() {
        page(2,2);service.messages(7L,12L,1L);InOrder order=inOrder(db);
        order.verify(db).one(startsWith("select * from kg_chat_member"),anyMap());
        order.verify(db).must(endsWith("for update"),eq(p("c",12L)),anyString());
        order.verify(db).one(startsWith("select * from kg_chat_member"),anyMap());
        order.verify(db).list(argThat(q->q.contains("from kg_chat_msg m")&&!q.contains("for update")&&!q.contains("for share")),anyMap());
        order.verify(db).update(startsWith("update kg_chat_member set last_read_id"),anyMap());
    }
    void autoStaff() {
        when(db.one(startsWith("select * from kg_chat_member"),anyMap())).thenAnswer(i->{String q=i.getArgument(0);return q.contains("left_time")?null:p("role","store");});
        when(db.one(startsWith("select c.conv_type"),anyMap())).thenReturn(p("convType","store","storeId",4L));
        when(grants.listActiveStaffUserIds(4L)).thenReturn(Collections.singletonList(7L));
    }
    @Test void autoJoinedReaderLocksConversationBeforeWritingMember() {
        autoStaff();service.messages(7L,12L,0L);InOrder order=inOrder(db);
        order.verify(db).must(endsWith("for update"),anyMap(),anyString());order.verify(db).update(startsWith("insert into kg_chat_member"),anyMap());
        verify(db,times(1)).update(startsWith("insert into kg_chat_member"),anyMap());
    }
    @Test void autoJoinedSenderUsesSameConversationThenMemberOrder() {
        autoStaff();assertEquals(73L,service.send(7L,12L,"正文","m0123456789abcdef").get("msgId"));InOrder order=inOrder(db);
        order.verify(db).must(endsWith("for update"),anyMap(),anyString());order.verify(db).update(startsWith("insert into kg_chat_member"),anyMap());
        order.verify(db).claimIdem(anyString(),eq("chat_message"));verify(db,times(1)).update(startsWith("insert into kg_chat_member"),anyMap());
    }
    @Test void grantRevokedWhileWaitingIsRecheckedBeforeMessageRead() {
        when(db.one(startsWith("select * from kg_chat_member"),anyMap())).thenReturn(p("role","store"));
        when(db.one(startsWith("select c.conv_type"),anyMap())).thenReturn(p("convType","store","storeId",4L));
        when(grants.listActiveStaffUserIds(4L)).thenReturn(Collections.singletonList(7L),Collections.emptyList());
        assertEquals(403,assertThrows(ServiceException.class,()->service.messages(7L,12L,0L)).getCode());
        verify(db).must(endsWith("for update"),anyMap(),anyString());verify(db,never()).list(anyString(),anyMap());verify(db,never()).update(anyString(),anyMap());
    }
    @Test void transactionUsesReadCommittedAndCommitsAcknowledgementTogether() {
        page(2,2);PlatformTransactionManager manager=mock(PlatformTransactionManager.class);TransactionStatus tx=mock(TransactionStatus.class);when(manager.getTransaction(any())).thenReturn(tx);
        proxy(manager).messages(7L,12L,1L);ArgumentCaptor<TransactionDefinition> def=ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(manager).getTransaction(def.capture());assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED,def.getValue().getIsolationLevel());
        verify(manager).commit(tx);verify(manager,never()).rollback(any());
    }
    @Test void reminderFailureRollsBackAcknowledgement() {
        page(2,2);when(db.update(startsWith("update kg_message set read_flag"),anyMap())).thenThrow(new IllegalStateException("fixture reminder failure"));
        PlatformTransactionManager manager=mock(PlatformTransactionManager.class);TransactionStatus tx=mock(TransactionStatus.class);when(manager.getTransaction(any())).thenReturn(tx);
        assertThrows(IllegalStateException.class,()->proxy(manager).messages(7L,12L,1L));verify(manager).rollback(tx);verify(manager,never()).commit(any());
    }
    @Test void bothSendContractsUseCommittedReadsAfterConversationLockWait() {
        PlatformTransactionManager manager=mock(PlatformTransactionManager.class);TransactionStatus tx=mock(TransactionStatus.class);when(manager.getTransaction(any())).thenReturn(tx);
        KgChatService proxied=proxy(manager);proxied.send(7L,12L,"新客户端","m0123456789abcdef");proxied.send(7L,12L,"旧客户端");
        ArgumentCaptor<TransactionDefinition> defs=ArgumentCaptor.forClass(TransactionDefinition.class);verify(manager,times(2)).getTransaction(defs.capture());
        for(TransactionDefinition d:defs.getAllValues())assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED,d.getIsolationLevel());
        verify(manager,times(2)).commit(tx);
    }
    KgChatService proxy(PlatformTransactionManager manager) {ProxyFactory f=new ProxyFactory(service);f.setProxyTargetClass(true);f.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));return (KgChatService)f.getProxy();}
}
