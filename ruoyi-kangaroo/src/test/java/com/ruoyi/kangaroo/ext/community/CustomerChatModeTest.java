package com.ruoyi.kangaroo.ext.community;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.AppUserContext;

class CustomerChatModeTest {
    KgJdbc db; KgChatService service; Map<String,Object> member;
    @BeforeEach void setup() {
        db=mock(KgJdbc.class);service=new KgChatService();
        ReflectionTestUtils.setField(service,"db",db);
        KgChatReception reception=mock(KgChatReception.class);
        ReflectionTestUtils.setField(service,"reception",reception);
        when(reception.staff(anyLong(),anyBoolean())).thenReturn(Arrays.asList(7L,9L));
        member=p("role","member");
        when(db.one(startsWith("select * from kg_chat_member"),anyMap())).thenAnswer(i->member);
        when(db.one(startsWith("select conv_id,conv_type"),anyMap())).thenReturn(p("convType","store","storeId",1L,"convId",12L,"pairKey","offline:1:u7","source","progress"));
        when(db.one(startsWith("select c.conv_type"),anyMap())).thenReturn(p("convType","store","storeId",1L,"convId",12L,"pairKey","offline:1:u7","source","progress"));
    }
    @AfterEach void cleanup(){AppUserContext.remove();}
    Object member(){return ReflectionTestUtils.invokeMethod(service,"requireMember",7L,12L,false);}
    @Test void volunteerConversationRejectsStaffModeButRemainsAvailableToUser(){
        AppUserContext.chatMode("staff");assertThrows(ServiceException.class,()->member());
        AppUserContext.chatMode("user");assertSame(member,member());
    }
    @Test void receptionConversationRejectsPersonalModeButKeepsColleagueReception(){
        member=p("role","store");AppUserContext.chatMode("user");assertThrows(ServiceException.class,()->member());
        AppUserContext.chatMode("staff");assertSame(member,member());
    }
    @Test void legacyClientsRetainSeatAuthorizationAndContextIsCleared(){
        assertSame(member,member());AppUserContext.chatMode("staff");AppUserContext.remove();assertNull(AppUserContext.chatMode());
    }
}
