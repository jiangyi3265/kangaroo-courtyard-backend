package com.ruoyi.kangaroo.ext.community;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;

/** Real openPrivate body, Mock JDBC; opening response decoration is stubbed. Not a real database test.
 * 找搭子帖限定了同行性别时，「发起认识一下」（source = buddy, sourceId = 帖子）在服务端校验发起人性别。 */
class KgChatBuddyGenderTest {
    static final String POST = "select app_user_id, kind, gender_limit from kg_post";
    static final String GENDER = "select gender from kg_app_user";
    final KgJdbc db = mock(KgJdbc.class);
    final KgCommunityGuard guard = mock(KgCommunityGuard.class);
    final KgChatService service = spy(new KgChatService());

    @BeforeEach void setup() {
        when(db.one(eq("select config_value from sys_config where config_key=:k"), eq(p("k", KgCommunityFeature.PRIVATE_KEY))))
                .thenReturn(p("configValue", "true"));
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "guard", guard);
        ReflectionTestUtils.setField(service, "grants", mock(IKgStaffGrantService.class));
        ReflectionTestUtils.setField(service, "reception", mock(KgChatReception.class));
        when(db.one(startsWith("select status, del_flag from kg_app_user"), anyMap())).thenReturn(p("status", "0", "delFlag", "0"));
        when(db.one(startsWith("select conv_id from kg_chat_conv where pair_key"), anyMap())).thenReturn(p("convId", 12L));
        doReturn(p("convId", 12L)).when(service).conversation(anyLong(), anyLong());
        post("buddy", "female");
    }

    void post(String kind, String limit) { when(db.one(startsWith(POST), anyMap())).thenReturn(p("appUserId", 9L, "kind", kind, "genderLimit", limit)); }
    void gender(String g) { when(db.one(startsWith(GENDER), anyMap())).thenReturn(g == null ? null : p("gender", g)); }
    void noWrites() { verify(db, never()).update(anyString(), anyMap()); verify(db, never()).insert(anyString(), anyMap()); }

    @Test void maleCannotJoinFemaleOnlyBuddy() {
        gender("1");
        ServiceException e = assertThrows(ServiceException.class, () -> service.openPrivate(7L, 9L, "buddy", 5L));
        assertEquals(403, e.getCode());
        assertEquals("这条找搭子仅限女生参加", e.getMessage());
        verify(db).one(startsWith(POST), eq(p("p", 5L)));
        verify(db).one(startsWith(GENDER), eq(p("u", 7L)));
        verify(guard, never()).requireConsent(anyLong(), anyString());
        noWrites();
    }

    @Test void femaleCannotJoinMaleOnlyBuddy() {
        post("buddy", "male");
        gender("2");
        ServiceException e = assertThrows(ServiceException.class, () -> service.openPrivate(7L, 9L, "buddy", 5L));
        assertEquals(403, e.getCode());
        assertEquals("这条找搭子仅限男生参加", e.getMessage());
        noWrites();
    }

    @Test void unknownGenderIsAskedToFillProfile() {
        for (String g : new String[] { "0", "", null }) {
            gender(g);
            ServiceException e = assertThrows(ServiceException.class, () -> service.openPrivate(7L, 9L, "buddy", 5L));
            assertEquals(422, e.getCode());
            assertEquals("这条找搭子仅限女生参加，请先在「我的 → 个人资料」里填写性别", e.getMessage());
        }
        when(db.one(startsWith(GENDER), anyMap())).thenReturn(null);
        assertEquals(422, assertThrows(ServiceException.class, () -> service.openPrivate(7L, 9L, "buddy", 5L)).getCode());
        noWrites();
    }

    @Test void matchingGenderOpensConversationAfterAccountLocks() {
        gender("2");
        assertEquals(12L, service.openPrivate(7L, 9L, "buddy", 5L).get("convId"));
        InOrder o = inOrder(db, guard);
        o.verify(db).one(startsWith("select status, del_flag from kg_app_user"), eq(p("u", 7L)));
        o.verify(db).one(startsWith("select status, del_flag from kg_app_user"), eq(p("u", 9L)));
        o.verify(db).one(startsWith(GENDER), eq(p("u", 7L)));
        o.verify(guard).requireConsent(7L, "community");
        o.verify(guard).requireNotBlocked(7L, 9L);
        verify(db).update(startsWith("insert into kg_chat_member"), anyMap());
    }

    @Test void authorIsNeverGenderChecked() {
        // 发帖人 9 以这条帖子为来源发起私信：不读性别、不拦
        gender("1");
        assertEquals(12L, service.openPrivate(9L, 7L, "buddy", 5L).get("convId"));
        verify(db, never()).one(startsWith(GENDER), anyMap());
    }

    @Test void unlimitedBuddyNonBuddyPostAndOtherSourcesSkipTheCheck() {
        gender("1");
        post("buddy", "");
        service.openPrivate(7L, 9L, "buddy", 5L);
        post("daily", "female");
        service.openPrivate(7L, 9L, "buddy", 5L);
        service.openPrivate(7L, 9L, "post", 5L);
        service.openPrivate(7L, 9L, "profile", null);
        service.openPrivate(7L, 9L, "buddy", null);
        verify(db, times(2)).one(startsWith(POST), anyMap());
        verify(db, never()).one(startsWith(GENDER), anyMap());
    }

    @Test void inactiveAccountsStillFailBeforeAnyGenderRead() {
        when(db.one(startsWith("select status, del_flag from kg_app_user"), anyMap())).thenReturn(p("status", "0", "delFlag", "2"));
        assertEquals(409, assertThrows(ServiceException.class, () -> service.openPrivate(7L, 9L, "buddy", 5L)).getCode());
        verify(db, never()).one(startsWith(POST), anyMap());
        verifyNoInteractions(guard);
        noWrites();
    }
}
