package com.ruoyi.kangaroo.ext.community;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;

/** Real openActivity body, mocked JDBC. Does not claim real lock concurrency. */
class KgChatActivityAccountTest {
    final KgJdbc db=mock(KgJdbc.class);
    final KgChatService service=spy(new KgChatService());
    static final String ACCOUNT="select status, del_flag from kg_app_user where user_id=:u for update";
    static final String ACTIVITY="select activity_id, title, conv_id, app_user_id, city from kg_activity where activity_id = :a for update";
    @BeforeEach void setup(){
        ReflectionTestUtils.setField(service,"db",db);
        when(db.one(eq(ACCOUNT),anyMap())).thenReturn(p("status","0","delFlag","0"));
        when(db.one(eq(ACTIVITY),anyMap())).thenReturn(p("activityId",31L,"convId",12L,"appUserId",9L));
        when(db.count(contains("from kg_activity_signup"),anyMap())).thenReturn(1L);
        when(db.one(startsWith("select * from kg_chat_member"),anyMap())).thenReturn(p("role","member"));
        doReturn(p("convId",12L)).when(service).conversation(anyLong(),anyLong());
    }
    void rejected(Map<String,Object> account){
        when(db.one(eq(ACCOUNT),anyMap())).thenReturn(account);
        assertEquals(409,assertThrows(ServiceException.class,()->service.openActivity(7L,31L)).getCode());
        verify(db,never()).one(eq(ACTIVITY),anyMap());
        verify(db,never()).count(anyString(),anyMap());
        verify(db,never()).insert(anyString(),anyMap());verify(db,never()).update(anyString(),anyMap());
        verify(service,never()).conversation(anyLong(),anyLong());
    }
    @Test void deletedConfirmedApplicantCannotRestoreSeat(){rejected(p("status","1","delFlag","2"));}
    @Test void disabledConfirmedApplicantCannotRestoreSeat(){rejected(p("status","1","delFlag","0"));}
    @Test void missingAccountCannotCreateGroup(){rejected(null);}
    @Test void activeConfirmedApplicantLocksAccountBeforeActivityAndRestoresSameSeat(){
        assertEquals(12L,service.openActivity(7L,31L).get("convId"));
        InOrder order=inOrder(db);
        order.verify(db).one(eq(ACCOUNT),eq(p("u",7L)));
        order.verify(db).one(eq(ACTIVITY),eq(p("a",31L)));
        order.verify(db).count(contains("status = 'confirmed'"),eq(p("a",31L,"u",7L)));
        order.verify(db).update(startsWith("insert into kg_chat_member"),eq(p("c",12L,"u",7L,"r","member")));
        order.verify(db).one(startsWith("select * from kg_chat_member"),eq(p("c",12L,"u",7L)));
    }
    @Test void activeUnconfirmedNonmemberStillDenied(){
        when(db.count(contains("from kg_activity_signup"),anyMap())).thenReturn(0L);
        when(db.one(startsWith("select * from kg_chat_member"),anyMap())).thenReturn(null);
        assertEquals(403,assertThrows(ServiceException.class,()->service.openActivity(7L,31L)).getCode());
        verify(db,never()).update(anyString(),anyMap());verify(service,never()).conversation(anyLong(),anyLong());
    }
    @Test void activeExistingOwnerStillOpensWithoutSignupOrUpsert(){
        when(db.count(contains("from kg_activity_signup"),anyMap())).thenReturn(0L);
        when(db.one(startsWith("select * from kg_chat_member"),anyMap())).thenReturn(p("role","owner"));
        assertEquals(12L,service.openActivity(9L,31L).get("convId"));verify(db,never()).update(anyString(),anyMap());
    }
    @Test void missingActivityRemains404AndTransactionRetained() throws Exception {
        when(db.one(eq(ACTIVITY),anyMap())).thenReturn(null);
        assertEquals(404,assertThrows(ServiceException.class,()->service.openActivity(7L,31L)).getCode());
        verify(db,never()).update(anyString(),anyMap());
        assertTrue(Arrays.asList(KgChatService.class.getMethod("openActivity",Long.class,Long.class).getAnnotation(Transactional.class).rollbackFor()).contains(Exception.class));
    }
}
