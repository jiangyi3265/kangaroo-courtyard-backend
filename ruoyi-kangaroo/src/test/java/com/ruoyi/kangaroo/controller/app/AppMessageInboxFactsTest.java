package com.ruoyi.kangaroo.controller.app;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.domain.KgMessage;
import com.ruoyi.kangaroo.ext.community.KgChatService;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.service.IKgMessageService;

/** 消息页会话行多三个字段（2026-10-31）：只在分页的列表里、只在 chat_conv 行上；老版本不带分页参数时逐字节不变；查失败列表照常回。 */
class AppMessageInboxFactsTest
{
    private final IKgMessageService svc = mock(IKgMessageService.class);
    private final KgChatService chats = mock(KgChatService.class);
    private final AppMessageController controller = new AppMessageController();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(controller, "kgMessageService", svc);
        ReflectionTestUtils.setField(controller, "chats", chats);
        AppLoginUser me = new AppLoginUser();
        me.setUserId(7L);
        AppUserContext.set(me);
        when(svc.selectAppMessagePage(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(rows());
        when(svc.selectAppMessages(any(), any(), any())).thenReturn(rows());
    }

    @AfterEach
    void cleanup()
    {
        AppUserContext.remove();
    }

    private static List<KgMessage> rows()
    {
        KgMessage chat = new KgMessage();
        chat.setMsgId(1L);
        chat.setRecipientId(7L);
        chat.setCategory("chat");
        chat.setBizType("chat_conv");
        chat.setBizId(88L);
        chat.setTitle("小院杨同学 · 平潭海岛体验官");
        chat.setContent("我：好的，明天到");
        KgMessage other = new KgMessage();
        other.setMsgId(2L);
        other.setRecipientId(7L);
        other.setCategory("order");
        other.setBizType("stay_order");
        other.setBizId(88L);
        other.setTitle("预订成功");
        other.setContent("小院 · 房型");
        return Arrays.asList(chat, other);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rowsOf(AjaxResult r)
    {
        return (List<Map<String, Object>>) r.get("rows");
    }

    @Test
    void pagedListAddsFactsOnConversationRowsOnly()
    {
        when(chats.inboxFacts(eq(7L), eq("user"), eq(Collections.singletonList(88L))))
                .thenReturn(Collections.singletonMap(88L, p("peerName", "小院杨同学", "peerAvatar", "/a.jpg", "sourceLabel", "平潭海岛体验官")));
        List<Map<String, Object>> rows = rowsOf(controller.list("user", null, "main", 1, 20));
        assertEquals("小院杨同学", rows.get(0).get("peerName"));
        assertEquals("/a.jpg", rows.get(0).get("peerAvatar"));
        assertEquals("平潭海岛体验官", rows.get(0).get("sourceLabel"));
        assertEquals("小院杨同学 · 平潭海岛体验官", rows.get(0).get("title"), "老字段一个字不动");
        assertEquals("我：好的，明天到", rows.get(0).get("content"));
        assertFalse(rows.get(1).containsKey("peerName"), "不是会话的行不带，哪怕 biz_id 相同");
    }

    @Test
    void unpagedListOfOldClientsIsUntouched()
    {
        List<Map<String, Object>> rows = rowsOf(controller.list("user", null, null, null, null));
        assertFalse(rows.get(0).containsKey("peerName"));
        assertFalse(rows.get(0).containsKey("sourceLabel"));
        verifyNoInteractions(chats);
    }

    @Test
    void aFailureOnlyLosesTheThreeFields()
    {
        when(chats.inboxFacts(any(), any(), any())).thenThrow(new IllegalStateException("db down"));
        AjaxResult r = controller.list("user", null, "main", 1, 20);
        assertEquals(200, r.get("code"));
        List<Map<String, Object>> rows = rowsOf(r);
        assertEquals(2, rows.size());
        assertEquals("小院杨同学 · 平潭海岛体验官", rows.get(0).get("title"));
        assertFalse(rows.get(0).containsKey("peerName"));
    }

    @Test
    void conversationMissingFromTheFactsKeepsTheOldShape()
    {
        when(chats.inboxFacts(any(), any(), any())).thenReturn(Collections.<Long, Map<String, Object>>emptyMap());
        assertFalse(rowsOf(controller.list("user", null, "main", 1, 20)).get(0).containsKey("peerName"));
    }

    @Test
    void noConversationRowsMeansNoQuery()
    {
        KgMessage order = new KgMessage();
        order.setMsgId(2L);
        order.setRecipientId(7L);
        order.setCategory("order");
        when(svc.selectAppMessagePage(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(Collections.singletonList(order));
        controller.list("user", null, "main", 1, 20);
        verifyNoInteractions(chats);
    }

    @Test
    void worksWithoutTheChatServiceInjected()
    {
        ReflectionTestUtils.setField(controller, "chats", null);
        assertEquals(2, rowsOf(controller.list("user", null, "main", 1, 20)).size());
    }
}
