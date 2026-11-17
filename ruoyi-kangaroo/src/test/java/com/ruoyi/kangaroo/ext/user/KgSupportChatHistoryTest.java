package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.controller.app.AppUserExtController;
import com.ruoyi.kangaroo.ext.KgJdbc;

/** 主人（2026-11-03）：「历史聊天记录，永久保存，不能删除」。AI袋鼠聊天记录（V72 kg_support_chat）：登录了才记、只追加、按页往前翻、没有删除口。Mock JDBC。 */
class KgSupportChatHistoryTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final KgSupportService service = new KgSupportService();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        when(db.one(contains("from kg_app_user"), anyMap())).thenReturn(p("status", "0", "delFlag", "0"));
        when(db.list(contains("from kg_support_faq"), anyMap())).thenReturn(Collections.<Map<String, Object>>emptyList());
        when(db.insert(eq("kg_support_log"), anyMap())).thenReturn(99L);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private List<Map<String, Object>> chatRows()
    {
        ArgumentCaptor<Map> rows = ArgumentCaptor.forClass(Map.class);
        verify(db, atLeast(0)).insert(eq("kg_support_chat"), rows.capture());
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map m : rows.getAllValues()) out.add((Map<String, Object>) m);
        return out;
    }

    private static String repeat(char c, int n)
    {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(c);
        return b.toString();
    }

    @Test
    void signedInQuestionAndAnswerAreAppendedAfterTheAccountLock()
    {
        Map<String, Object> a = service.ask(7L, "profile", "积分明细", null, null);
        List<Map<String, Object>> rows = chatRows();
        assertEquals(2, rows.size());
        assertEquals(Arrays.asList(7L, "user", "积分明细", "points", "profile"),
                Arrays.asList(rows.get(0).get("app_user_id"), rows.get(0).get("role"), rows.get(0).get("content"), rows.get(0).get("intent"), rows.get(0).get("context")));
        assertNull(rows.get(0).get("source"));
        assertEquals("bot", rows.get(1).get("role"));
        assertEquals(a.get("text"), rows.get(1).get("content"), "答案原文整段存（不是 kg_support_log 那样的摘要）");
        assertEquals(a.get("source"), rows.get(1).get("source"));
        String extra = String.valueOf(rows.get(1).get("extra"));
        assertTrue(extra.contains("\"label\":\"查看我的积分\"") && extra.contains("/pages/profile/index?s=3"), extra);
        assertTrue(rows.get(1).get("create_time") instanceof Date);
        InOrder o = inOrder(db);
        o.verify(db).one(contains("for update"), eq(p("u", 7L)));
        o.verify(db, times(2)).insert(eq("kg_support_chat"), anyMap());
    }

    @Test
    void guestsAreNotStored()
    {
        service.ask(null, "home", "积分明细", null, null);
        verify(db, never()).insert(eq("kg_support_chat"), anyMap());
        verify(db).insert(eq("kg_support_log"), anyMap());
    }

    @Test
    void erasedAccountCannotWriteHistory()
    {
        when(db.one(contains("from kg_app_user"), anyMap())).thenReturn(p("status", "1", "delFlag", "2"));
        assertThrows(ServiceException.class, () -> service.ask(7L, "home", "积分明细", null, null));
        verify(db, never()).insert(eq("kg_support_chat"), anyMap());
    }

    @Test
    void longTextIsCappedAndCardsKeepOnlyDisplayFields()
    {
        service.appendChat(7L, "profile", "bot", repeat('字', 2100), "faq", "常见问题", null, null);
        assertEquals(2000, String.valueOf(chatRows().get(0).get("content")).length());

        List<Map<String, Object>> cards = new ArrayList<Map<String, Object>>();
        cards.add(p("type", "insurance", "title", "咖啡馆", "status", "已生效", "meta", "保单号 P1", "policyIssued", true, "coverageStatus", "active", "secret", "x"));
        String extra = KgSupportService.chatExtra(cards, Collections.singletonList(p("label", "我的报名", "url", "/pages/jobs/progress", "token", "t")));
        assertTrue(extra.contains("\"title\":\"咖啡馆\"") && extra.contains("\"meta\":\"保单号 P1\""), extra);
        assertFalse(extra.contains("secret") || extra.contains("policyIssued") || extra.contains("token"), extra);
        assertNull(KgSupportService.chatExtra(null, null), "没有卡片和按钮就不存");

        List<Map<String, Object>> many = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 40; i++) many.add(p("type", "job", "title", repeat('岗', 200)));
        String trimmed = KgSupportService.chatExtra(many, Collections.singletonList(p("label", "去找机会", "url", "/pages/jobs/list")));
        assertTrue(trimmed.length() <= 4000 && trimmed.contains("去找机会") && trimmed.contains("\"cards\":[]"), "太长先丢卡片、按钮留着");
    }

    @Test
    @SuppressWarnings("unchecked")
    void historyPagesBackwardsAndReturnsOldestFirst()
    {
        List<Map<String, Object>> desc = new ArrayList<Map<String, Object>>();
        desc.add(p("chatId", 42L, "role", "bot", "content", "你的保单已登记", "source", "袋鼠小院保单登记",
                "extra", "{\"cards\":[{\"type\":\"insurance\",\"title\":\"咖啡馆\"}],\"links\":[{\"label\":\"我的报名\",\"url\":\"/pages/jobs/progress\"}]}", "createTime", new Date()));
        desc.add(p("chatId", 41L, "role", "user", "content", "保险查询", "source", null, "extra", null, "createTime", new Date()));
        desc.add(p("chatId", 40L, "role", "bot", "content", "坏的卡片", "source", "x", "extra", "{not json", "createTime", new Date()));
        when(db.list(contains("from kg_support_chat"), anyMap())).thenReturn(desc);
        Map<String, Object> page = service.history(7L, null, 2);
        assertEquals(true, page.get("more"));
        List<Map<String, Object>> list = (List<Map<String, Object>>) page.get("list");
        assertEquals(2, list.size());
        assertEquals(Arrays.asList(41L, 42L), Arrays.asList(list.get(0).get("id"), list.get(1).get("id")), "正序：老的在上");
        assertEquals("me", list.get(0).get("from"));
        assertEquals("bot", list.get(1).get("from"));
        assertEquals("袋鼠小院保单登记", list.get(1).get("source"));
        assertEquals(1, ((List<?>) list.get(1).get("cards")).size());
        assertEquals(1, ((List<?>) list.get(1).get("links")).size());
        verify(db).list(argThat(sql -> sql.contains("where app_user_id = :u order by chat_id desc limit 3") && !sql.contains("chat_id < :b")),
                argThat(m -> Long.valueOf(7L).equals(m.get("u"))));

        service.history(7L, 41L, null);
        verify(db).list(argThat(sql -> sql.contains("and chat_id < :b") && sql.contains("limit 31")), argThat(m -> Long.valueOf(41L).equals(m.get("b"))));
        service.history(7L, null, 1000);
        verify(db).list(argThat(sql -> sql.contains("limit 51")), anyMap());

        when(db.list(contains("from kg_support_chat"), anyMap())).thenReturn(Collections.singletonList(desc.get(2)));
        List<Map<String, Object>> bad = (List<Map<String, Object>>) service.history(7L, null, null).get("list");
        assertEquals("坏的卡片", bad.get(0).get("text"), "存坏的 JSON 不挡文字");
        assertTrue(((List<?>) bad.get(0).get("cards")).isEmpty());
    }

    @Test
    void faqTapStoresTheServerSideFaqTextOnly()
    {
        when(db.one(contains("from kg_support_faq where faq_id = :f"), anyMap())).thenReturn(p("faqId", 3L, "question", "发票怎么开？", "answer", "离店后联系门店开具。"));
        service.faqRead(7L, "home", 3L);
        List<Map<String, Object>> rows = chatRows();
        assertEquals(Arrays.asList("user", "bot"), Arrays.asList(rows.get(0).get("role"), rows.get(1).get("role")));
        assertEquals("发票怎么开？", rows.get(0).get("content"));
        assertEquals("离店后联系门店开具。", rows.get(1).get("content"));
        assertEquals("常见问题", rows.get(1).get("source"));
        InOrder o = inOrder(db);
        o.verify(db).one(contains("for update"), eq(p("u", 7L)));
        o.verify(db, times(2)).insert(eq("kg_support_chat"), anyMap());
    }

    @Test
    void offlineFaqIsRejectedWithoutWriting()
    {
        ServiceException e = assertThrows(ServiceException.class, () -> service.faqRead(7L, "home", 3L));
        assertEquals(404, e.getCode());
        assertThrows(ServiceException.class, () -> service.faqRead(7L, "home", null));
        verify(db, never()).insert(eq("kg_support_chat"), anyMap());
    }

    @Test
    void noDeleteEndpointAndOnlyAccountErasureClearsHistory() throws Exception
    {
        for (Method m : AppUserExtController.class.getDeclaredMethods())
        {
            DeleteMapping d = m.getAnnotation(DeleteMapping.class);
            if (d != null)
            {
                for (String path : d.value()) assertFalse(path.contains("support"), "客服没有删除接口：" + path);
            }
            PostMapping post = m.getAnnotation(PostMapping.class);
            if (post != null)
            {
                for (String path : post.value()) assertFalse(path.contains("history") || path.contains("clear"), "聊天记录没有清空接口：" + path);
            }
        }
        assertNotNull(AppUserExtController.class.getMethod("supportHistory", Long.class, Integer.class).getAnnotation(GetMapping.class));
        assertFalse(Arrays.stream(KgSupportService.class.getDeclaredMethods()).anyMatch(m -> m.getName().toLowerCase().matches(".*(delete|clear|remove).*")),
                "服务里没有删聊天记录的方法");
        String account = new String(Files.readAllBytes(Paths.get(System.getProperty("basedir", "."), "src", "main", "java", "com", "ruoyi", "kangaroo", "ext", "user", "KgAccountService.java")),
                StandardCharsets.UTF_8);
        assertTrue(account.contains("\"delete from kg_support_chat where app_user_id = :u\""), "注销账号时随账号清掉");
    }
}
