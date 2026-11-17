package com.ruoyi.kangaroo.ext.community;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;

/** Real service body, Mock JDBC/grants. Not a real database lock or race test.
 * Opening response decoration is stubbed; authorization and all member writes run. */
class KgChatActiveLifecycleTest {
    final KgJdbc db=mock(KgJdbc.class);
    final KgCommunityGuard guard=mock(KgCommunityGuard.class);
    final IKgStaffGrantService grants=mock(IKgStaffGrantService.class);
    final KgChatService service=spy(new KgChatService());
    final Map<Long,Map<String,Object>> accounts=new HashMap<>();
    static final String ACCOUNT="select status, del_flag from kg_app_user";
    static final String APPLICATION="select a.app_id, a.app_user_id, a.store_id";
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service,"db",db);ReflectionTestUtils.setField(service,"guard",guard);ReflectionTestUtils.setField(service,"grants",grants);
        KgChatReception reception=mock(KgChatReception.class);ReflectionTestUtils.setField(service,"reception",reception);
        when(reception.staff(anyLong(),anyBoolean())).thenAnswer(i->grants.listActiveStaffUserIds(i.getArgument(0)));
        for(long id:new long[]{2,7,9})accounts.put(id,p("status","0","delFlag","0"));
        when(db.one(startsWith(ACCOUNT),anyMap())).thenAnswer(i->accounts.get(((Number)((Map<?,?>)i.getArgument(1)).get("u")).longValue()));
        when(db.count(contains("from kg_app_user"),anyMap())).thenReturn(1L);
        when(db.one(startsWith("select conv_id from kg_chat_conv where pair_key"),anyMap())).thenReturn(p("convId",12L));
        when(db.one(startsWith("select * from kg_chat_member"),anyMap())).thenReturn(p("role","member"));
        when(db.one(startsWith(APPLICATION),anyMap())).thenReturn(app(7L,4L));
        when(grants.listActiveStaffUserIds(4L)).thenReturn(Arrays.asList(2L,9L));
        when(db.must(startsWith("select * from kg_chat_conv"),anyMap(),anyString())).thenReturn(p("convId",12L,"convType","group","status","active","title","保留历史"));
        when(db.claimIdem(anyString(),eq("chat_message"))).thenReturn(true);
        when(db.insert(eq("kg_chat_msg"),anyMap())).thenReturn(73L);
        when(db.update(startsWith("update kg_idempotency set biz_id"),anyMap())).thenReturn(1);
        doReturn(p("convId",12L)).when(service).conversation(anyLong(),anyLong());
    }
    Map<String,Object> app(long uid,long store) {return p("appId",22L,"appUserId",uid,"storeId",store,"storeName","真实门店","title","岗位");}
    void noWrites(){verify(db,never()).insert(anyString(),anyMap());verify(db,never()).update(anyString(),anyMap());verify(db,never()).claimIdem(anyString(),anyString());}
    void inactive(long uid){accounts.put(uid,p("status","0","delFlag","2"));}
    List<Long> accountOrder() {ArgumentCaptor<Map> cap=ArgumentCaptor.forClass(Map.class);verify(db,atLeastOnce()).one(startsWith(ACCOUNT),cap.capture());List<Long> ids=new ArrayList<>();for(Map v:cap.getAllValues())ids.add(((Number)v.get("u")).longValue());return ids;}
    @Test void deletedSenderCannotWriteOrReplay() {
        inactive(7);assertEquals(409,assertThrows(ServiceException.class,()->service.send(7L,12L,"正文","m0123456789abcdef")).getCode());noWrites();verify(db,never()).must(anyString(),anyMap(),anyString());
    }
    @Test void disabledAndMissingSendersCannotUseLegacyContract() {
        accounts.put(7L,p("status","1","delFlag","0"));assertEquals(409,assertThrows(ServiceException.class,()->service.send(7L,12L,"正文")).getCode());
        accounts.remove(7L);assertEquals(409,assertThrows(ServiceException.class,()->service.send(7L,12L,"正文")).getCode());noWrites();
    }
    @Test void activeSenderLocksAccountBeforeMemberConversationAndMessage() {
        assertEquals(73L,service.send(7L,12L,"正文","m0123456789abcdef").get("msgId"));InOrder order=inOrder(db);
        order.verify(db).one(startsWith(ACCOUNT),eq(p("u",7L)));order.verify(db).one(startsWith("select * from kg_chat_member"),anyMap());order.verify(db).must(endsWith("for update"),anyMap(),anyString());order.verify(db).insert(eq("kg_chat_msg"),anyMap());
    }
    @Test void deletedPrivateCallerCannotReopenMembers() {
        inactive(7);assertEquals(409,assertThrows(ServiceException.class,()->service.openPrivate(7L,9L,"profile",null)).getCode());noWrites();verifyNoInteractions(guard);
    }
    @Test void deletedPrivateOtherCannotBeRecreatedAsMember() {
        inactive(9);assertEquals(404,assertThrows(ServiceException.class,()->service.openPrivate(7L,9L,"profile",null)).getCode());noWrites();verifyNoInteractions(guard);
    }
    @Test void disabledPrivateOtherIsNotAnActiveRecipient() {
        accounts.put(9L,p("status","1","delFlag","0"));assertEquals(404,assertThrows(ServiceException.class,()->service.openPrivate(7L,9L,"profile",null)).getCode());noWrites();
    }
    @Test void missingPrivateTargetKeepsOriginal404WithoutMemberWrites() {
        accounts.remove(9L);when(db.count(contains("from kg_app_user"),anyMap())).thenReturn(0L);
        assertEquals(404,assertThrows(ServiceException.class,()->service.openPrivate(7L,9L,"profile",null)).getCode());noWrites();
    }
    @Test void oppositeDirectionPrivateOpensTakeSameSortedPairLocks() {
        service.openPrivate(9L,2L,"profile",null);service.openPrivate(2L,9L,"profile",null);
        assertEquals(Arrays.asList(2L,9L,2L,9L),accountOrder());InOrder order=inOrder(db,guard);order.verify(db).one(startsWith(ACCOUNT),eq(p("u",2L)));order.verify(db).one(startsWith(ACCOUNT),eq(p("u",9L)));order.verify(guard).requireConsent(9L,"community");
    }
    @Test void privateBlockStillPreventsAllMemberWrites() {
        doThrow(new ServiceException("拉黑",403)).when(guard).requireNotBlocked(7L,9L);assertEquals(403,assertThrows(ServiceException.class,()->service.openPrivate(7L,9L,"profile",null)).getCode());noWrites();
    }
    @Test void privateSelfStillRejectedBeforeLocks() {
        assertEquals(422,assertThrows(ServiceException.class,()->service.openPrivate(7L,7L,"profile",null)).getCode());verifyNoInteractions(db);noWrites();
    }
    @Test void storeReopenRejectsDeletedApplicantWithoutRecreatingSeat() {
        inactive(7);assertEquals(409,assertThrows(ServiceException.class,()->service.openStore(2L,22L)).getCode());noWrites();verifyNoInteractions(grants);
    }
    @Test void storeCallerMustAlsoRemainActive() {
        inactive(2);assertEquals(409,assertThrows(ServiceException.class,()->service.openStore(2L,22L)).getCode());noWrites();
    }
    @Test void applicationDeletedDuringAccountWaitIsRechecked() {
        when(db.one(startsWith(APPLICATION),anyMap())).thenReturn(app(7,4),null);assertEquals(404,assertThrows(ServiceException.class,()->service.openStore(2L,22L)).getCode());noWrites();
    }
    @Test void applicationOwnerOrStoreChangeDoesNotAcquireUnorderedNewAccount() {
        for(Map<String,Object> changed:Arrays.asList(app(9,4),app(7,5))) {
            when(db.one(startsWith(APPLICATION),anyMap())).thenReturn(app(7,4),changed);assertEquals(409,assertThrows(ServiceException.class,()->service.openStore(2L,22L)).getCode());
        }
        assertEquals(Arrays.asList(2L,7L,2L,7L),accountOrder());noWrites();
    }
    @Test void revokedStoreCallerCannotCreateConversationOrMember() {
        when(grants.listActiveStaffUserIds(4L)).thenReturn(Collections.emptyList());assertEquals(403,assertThrows(ServiceException.class,()->service.openStore(2L,22L)).getCode());noWrites();
    }
    @Test void authorizedStoreOpenRereadsAfterOrderedPairBeforeUpsert() {
        assertEquals(12L,service.openStore(2L,22L).get("convId"));InOrder order=inOrder(db,grants);
        order.verify(db).one(startsWith(APPLICATION),anyMap());order.verify(db).one(startsWith(ACCOUNT),eq(p("u",2L)));order.verify(db).one(startsWith(ACCOUNT),eq(p("u",7L)));order.verify(db).one(startsWith(APPLICATION),anyMap());order.verify(grants).listActiveStaffUserIds(4L);order.verify(db).update(startsWith("update kg_chat_conv set source_id"),anyMap());order.verify(db).update(startsWith("insert into kg_chat_member"),eq(p("c",12L,"u",7L,"r","member")));
    }
    @Test void applicantOpeningOwnStoreLocksOnlyOneAccount() {
        service.openStore(7L,22L);assertEquals(Collections.singletonList(7L),accountOrder());
    }
    @Test void pairOpenUsesCommittedRereadAfterAccountWait() throws Exception {
        for(java.lang.reflect.Method m:new java.lang.reflect.Method[]{KgChatService.class.getMethod("openPrivate",Long.class,Long.class,String.class,Long.class),KgChatService.class.getMethod("openStore",Long.class,Long.class)})assertEquals(Isolation.READ_COMMITTED,m.getAnnotation(Transactional.class).isolation());
    }
}
