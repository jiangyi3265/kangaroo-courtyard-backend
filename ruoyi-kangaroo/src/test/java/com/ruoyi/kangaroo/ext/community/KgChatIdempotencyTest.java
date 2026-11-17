package com.ruoyi.kangaroo.ext.community;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;

/** Real service and Spring transaction interceptor; JDBC/transaction resource are Mock.
 * Does not claim real MySQL concurrency, persistence or rollback verification. */
class KgChatIdempotencyTest {
    final KgJdbc db=mock(KgJdbc.class);
    final KgCommunityGuard guard=mock(KgCommunityGuard.class);
    final KgChatService service=new KgChatService();
    final String key="m0123456789abcdef";
    @BeforeEach void setup() {
        when(db.one(startsWith("select status, del_flag from kg_app_user"),anyMap())).thenReturn(p("status","0","delFlag","0"));
        ReflectionTestUtils.setField(service,"db",db);ReflectionTestUtils.setField(service,"guard",guard);
        when(db.one(startsWith("select * from kg_chat_member"),anyMap())).thenReturn(p("role","member"));
        when(db.must(startsWith("select * from kg_chat_conv"),anyMap(),anyString())).thenReturn(p("convId",12L,"convType","group","status","active","title","测试群"));
        when(db.claimIdem(anyString(),eq("chat_message"))).thenReturn(true);
        when(db.insert(eq("kg_chat_msg"),anyMap())).thenReturn(73L);
        when(db.update(startsWith("update kg_idempotency set biz_id"),anyMap())).thenReturn(1);
    }
    void duplicate(Map<String,Object> original) {
        when(db.claimIdem(anyString(),eq("chat_message"))).thenReturn(false);
        when(db.one(startsWith("select biz_type, biz_id"),anyMap())).thenReturn(p("bizType","chat_message","bizId",73L));
        when(db.one(startsWith("select msg_id, conv_id"),anyMap())).thenReturn(original);
    }
    @Test void firstSendWritesRealMessageThenBindsResult() {
        assertEquals(73L,service.send(7L,12L," 中文🙂 ",key).get("msgId"));
        org.mockito.InOrder order=inOrder(db);order.verify(db).claimIdem("chat:7:12:"+key,"chat_message");
        order.verify(db).insert(eq("kg_chat_msg"),argThat(v->"中文🙂".equals(v.get("content"))));
        order.verify(db).update(startsWith("update kg_idempotency set biz_id"),argThat(v->Long.valueOf(73).equals(v.get("m"))&&("chat:7:12:"+key).equals(v.get("k"))));
    }
    @Test void sameKeyAndBodyReturnsStoredIdWithoutNewMessageOrQuota() {
        duplicate(p("msgId",73L,"convId",12L,"senderId",7L,"content","正文"));
        assertEquals(73L,service.send(7L,12L," 正文 ",key).get("msgId"));
        verify(db,never()).insert(anyString(),anyMap());verify(db,never()).update(anyString(),anyMap());verify(db,never()).count(anyString(),anyMap());
        verify(db).one(contains("where idem_key = :k for share"),anyMap());
        verify(db).one(contains("where msg_id = :m for share"),anyMap());
    }
    @Test void sameKeyDifferentBodyIsConflictAndDoesNotWrite() {
        duplicate(p("msgId",73L,"convId",12L,"senderId",7L,"content","已保存"));
        assertEquals(409,assertThrows(ServiceException.class,()->service.send(7L,12L,"替换",key)).getCode());
        verify(db,never()).insert(anyString(),anyMap());verify(db,never()).update(anyString(),anyMap());
    }
    @Test void replayChecksOriginalOwnerAndConversation() {
        for(Map<String,Object> row:new Map[]{p("msgId",73L,"convId",13L,"senderId",7L,"content","正文"),p("msgId",73L,"convId",12L,"senderId",8L,"content","正文")}) {
            duplicate(row);assertEquals(409,assertThrows(ServiceException.class,()->service.send(7L,12L,"正文",key)).getCode());
        }
        verify(db,never()).insert(anyString(),anyMap());
    }
    @Test void missingResultNeverFabricatesSuccess() {
        duplicate(null);assertThrows(ServiceException.class,()->service.send(7L,12L,"正文",key));
        when(db.one(startsWith("select biz_type, biz_id"),anyMap())).thenReturn(p("bizType","chat_message","bizId",null));
        assertThrows(ServiceException.class,()->service.send(7L,12L,"正文",key));
    }
    @Test void membershipIsRequiredBeforeLookingUpAnOldKey() {
        when(db.one(startsWith("select * from kg_chat_member"),anyMap())).thenReturn(null);
        assertEquals(403,assertThrows(ServiceException.class,()->service.send(7L,12L,"正文",key)).getCode());
        verify(db,never()).claimIdem(anyString(),anyString());verify(db,never()).one(contains("kg_idempotency"),anyMap());
    }
    @Test void blockIsRequiredBeforeLookingUpAnOldKey() {
        when(db.must(startsWith("select * from kg_chat_conv"),anyMap(),anyString())).thenReturn(p("convId",12L,"convType","private","status","active"));
        when(db.one(startsWith("select app_user_id from kg_chat_member"),anyMap())).thenReturn(p("appUserId",8L));
        doThrow(new ServiceException("blocked",403)).when(guard).requireNotBlocked(7L,8L);
        assertEquals(403,assertThrows(ServiceException.class,()->service.send(7L,12L,"正文",key)).getCode());
        verify(db,never()).claimIdem(anyString(),anyString());
    }
    @Test void strictKeyContractAndNamespace() {
        assertNull(KgChatService.messageKey(7L,12L,null));
        for(Object raw:new Object[]{true,1,""," ","UPPERCASE0123456789","short",String.join("",Collections.nCopies(65,"a")),"0123456789abcde:"})
            assertEquals(422,assertThrows(ServiceException.class,()->KgChatService.messageKey(7L,12L,raw)).getCode());
        assertNotEquals(KgChatService.messageKey(7L,12L,key),KgChatService.messageKey(8L,12L,key));
        assertNotEquals(KgChatService.messageKey(7L,12L,key),KgChatService.messageKey(7L,13L,key));
        assertTrue(KgChatService.messageKey(Long.MAX_VALUE,Long.MAX_VALUE,String.join("",Collections.nCopies(64,"a"))).length()<=128);
    }
    @Test void legacyClientStillWritesWithoutAnIdempotencyClaim() {
        assertEquals(73L,service.send(7L,12L,"旧客户端").get("msgId"));verify(db,never()).claimIdem(anyString(),anyString());
    }
    @Test void failedNotificationRollsBackThroughRealSpringInterceptor() {
        when(db.list(startsWith("select app_user_id, role"),anyMap())).thenReturn(Collections.singletonList(p("appUserId",8L,"role","member")));
        when(db.update(startsWith("update kg_message set title"),anyMap())).thenThrow(new IllegalStateException("fixture notification failure"));
        PlatformTransactionManager manager=mock(PlatformTransactionManager.class);TransactionStatus tx=mock(TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(tx);
        ProxyFactory f=new ProxyFactory(service);f.setProxyTargetClass(true);f.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        assertThrows(IllegalStateException.class,()->((KgChatService)f.getProxy()).send(7L,12L,"正文",key));
        verify(manager).rollback(tx);verify(manager,never()).commit(any());
        verify(db,never()).update(startsWith("update kg_idempotency set biz_id"),anyMap());
    }
    @Test void failedResultBindingIsNotReturnedAsSuccess() {
        when(db.update(startsWith("update kg_idempotency set biz_id"),anyMap())).thenReturn(0);
        assertEquals(409,assertThrows(ServiceException.class,()->service.send(7L,12L,"正文",key)).getCode());
    }
}
