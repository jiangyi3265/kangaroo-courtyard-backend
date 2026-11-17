package com.ruoyi.kangaroo.controller.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.domain.*;
import com.ruoyi.kangaroo.mapper.*;
import com.ruoyi.kangaroo.security.*;

class AppApplicationMessageControllerTest
{
    private final KgApplicationMapper applications = mock(KgApplicationMapper.class);
    private final KgMessageMapper messages = mock(KgMessageMapper.class);
    private final AppApplicationMessageController controller = new AppApplicationMessageController();
    private KgApplication application;

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(controller, "applications", applications);
        ReflectionTestUtils.setField(controller, "messages", messages);
        AppLoginUser me = new AppLoginUser(); me.setUserId(7L); AppUserContext.set(me);
        application = new KgApplication(); application.setAppId(81L); application.setAppUserId(7L);
        application.setDelFlag("0"); application.setStoreName("平潭小院");
        when(applications.selectKgApplicationByAppId(81L)).thenReturn(application);
        when(messages.selectApplicationNotices(anyLong(), anyLong(), any(), anyInt())).thenReturn(Collections.emptyList());
    }
    @AfterEach void cleanup() { AppUserContext.remove(); }
    private KgMessage notice(long id) {
        KgMessage m = new KgMessage(); m.setMsgId(id); m.setBizType("application"); m.setBizId(81L);
        m.setTitle("录用通知"); m.setContent("门店已确认录用\n请查看协议"); m.setReadFlag("0"); return m;
    }
    @SuppressWarnings("unchecked") private Map<String, Object> get(Long before, Integer size) {
        return (Map<String, Object>) controller.messages(81L, before, size).get("data");
    }
    @Test void rejectsUnknownOtherOwnersAndDeletedBeforeQueryingMessages() {
        assertEquals(404, assertThrows(ServiceException.class, () -> controller.messages(82L, null, 20)).getCode());
        application.setAppUserId(8L);
        assertEquals(404, assertThrows(ServiceException.class, () -> get(null, 20)).getCode());
        application.setAppUserId(7L); application.setDelFlag("2");
        assertEquals(404, assertThrows(ServiceException.class, () -> get(null, 20)).getCode());
        verifyNoInteractions(messages);
    }
    @Test void invalidIdentifiersAreRejectedWithoutQueries() {
        assertEquals(400, assertThrows(ServiceException.class, () -> controller.messages(0L, null, 20)).getCode());
        assertEquals(400, assertThrows(ServiceException.class, () -> get(-1L, 20)).getCode());
        verifyNoInteractions(applications, messages);
    }
    @Test void previewIsReadOnlyAndReturnsOnlyPagePlusScopedUnread() {
        when(messages.selectApplicationNotices(7L, 81L, null, 2)).thenReturn(Arrays.asList(notice(103), notice(102)));
        when(messages.countApplicationUnread(7L, 81L)).thenReturn(2);
        KgMessage chat = notice(99); chat.setBizType("chat_conv"); chat.setBizId(91L); chat.setContent("我：好的，明天到"); chat.setReadFlag("1");
        when(messages.selectApplicationChat(7L, 81L)).thenReturn(chat);
        Map<String, Object> data = get(null, 1);
        assertEquals(81L, data.get("appId")); assertEquals("平潭小院", data.get("storeName"));
        assertEquals(true, data.get("hasMore")); assertEquals(103L, data.get("nextBefore")); assertEquals(2, data.get("unread"));
        List<?> rows = (List<?>) data.get("rows"); assertEquals(1, rows.size());
        Map<?, ?> row = (Map<?, ?>) rows.get(0); assertEquals(false, row.get("read"));
        assertEquals("门店已确认录用\n请查看协议", row.get("content"));
        assertEquals("我：好的，明天到", ((Map<?, ?>) data.get("store")).get("content"));
        verify(messages).selectApplicationNotices(7L, 81L, null, 2);
        verify(messages).selectApplicationChat(7L, 81L); verify(messages).countApplicationUnread(7L, 81L);
        verifyNoMoreInteractions(messages);
    }
    @Test void cursorPaginationAndSizeAreBounded() {
        get(103L, 500); verify(messages).selectApplicationNotices(7L, 81L, 103L, 51);
        get(null, 0); verify(messages).selectApplicationNotices(7L, 81L, null, 2);
        Map<String, Object> empty = get(null, null); verify(messages).selectApplicationNotices(7L, 81L, null, 21);
        assertEquals(false, empty.get("hasMore")); assertNull(empty.get("nextBefore")); assertNull(empty.get("store"));
    }
}
