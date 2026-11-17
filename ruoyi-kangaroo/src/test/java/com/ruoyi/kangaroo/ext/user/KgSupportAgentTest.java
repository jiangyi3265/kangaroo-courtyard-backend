package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.stay.KgStayOrderService;

/** 聊天式客服（内测反馈 2.0 客服 c）：按用户状态预测问题、新意图（客服回复 / 线上成果）、常见问题兜底、最近订单试算。Mock JDBC。 */
class KgSupportAgentTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final KgStayOrderService orders = mock(KgStayOrderService.class);
    private final KgSupportService service = new KgSupportService();

    private static final List<Map<String, Object>> FAQS = Arrays.asList(
            p("faqId", 1L, "question", "报名后在哪里查看审核结果？", "answer", "进入报名记录查看审核进度；有进展时会收到消息通知。"),
            p("faqId", 7L, "question", "如何办理到店入住？", "answer", "到店后出示订单页的核销码，门店核对后办理入住。"),
            p("faqId", 8L, "question", "发票怎么开？", "answer", "住宿发票请在离店后联系门店开具。"));

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "stayOrders", orders);
        when(db.one(contains("from kg_app_user"), anyMap())).thenReturn(p("status", "0", "delFlag", "0"));
        when(db.list(contains("from kg_support_faq"), anyMap())).thenReturn(FAQS);
        when(db.insert(eq("kg_support_log"), anyMap())).thenReturn(99L);
    }

    @Test
    void predictedChipsUseTheSameWordsAskUnderstands()
    {
        assertEquals("progress", KgSupportService.intentOf("报名进度"));
        assertEquals("refund", KgSupportService.intentOf("入住和退款"));
        assertEquals("ticket", KgSupportService.intentOf("查看客服回复"));
        assertEquals("online", KgSupportService.intentOf("怎么提交成果"));
        // 「成果审核要多久」是线上任务，不是报名审核；「线上任务有工作吗」也不落到岗位推荐
        assertEquals("online", KgSupportService.intentOf("成果审核要多久"));
        assertEquals("online", KgSupportService.intentOf("线上任务工作量大吗"));
        // 老问题不受影响
        assertEquals("refund", KgSupportService.intentOf("取消要扣多少？"));
        assertEquals("unknown", KgSupportService.intentOf("如何办理到店入住"));
    }

    @Test
    void predictedQuestionsComeFirstThenHotOnesWithoutDuplicates()
    {
        when(db.count(anyString(), anyMap())).thenReturn(1L);
        List<String> q = service.quickQuestions(7L, "profile");
        assertEquals(Arrays.asList("报名进度", "入住和退款", "查看客服回复", "怎么提交成果", "退款规则", "保险查询", "积分明细", "攻略申请"), q);
        verify(db).count(argThat(sql -> sql.contains("from kg_application where app_user_id = :u") && sql.contains("status in (:st)")),
                argThat(ps -> Long.valueOf(7L).equals(ps.get("u")) && ((List<?>) ps.get("st")).contains("onboarded") && !((List<?>) ps.get("st")).contains("completed")));
        verify(db).count(argThat(sql -> sql.contains("from kg_stay_order") && sql.contains("('confirmed','checked_in')") && sql.contains("check_out >= curdate()")), anyMap());
        verify(db).count(argThat(sql -> sql.contains("from kg_support_ticket") && sql.contains("status = 'replied'")), anyMap());
        verify(db).count(argThat(sql -> sql.contains("j.job_type = 'online'") && sql.contains("a.status = 'review_passed'")), anyMap());
    }

    @Test
    void guestsAndQuietAccountsOnlyGetHotQuestions()
    {
        assertEquals(service.quickQuestions("order"), service.quickQuestions(null, "order"));
        verify(db, never()).count(anyString(), anyMap());
        when(db.count(anyString(), anyMap())).thenReturn(0L);
        assertEquals(Arrays.asList("报名进度", "退款规则", "保险查询", "积分明细", "攻略申请"), service.quickQuestions(7L, "profile"));
    }

    @Test
    void unknownQuestionFallsBackToTheClosestFaq()
    {
        Map<String, Object> a = service.ask(null, "profile", "到店入住要办什么手续", null, null);
        assertEquals("faq", a.get("intent"));
        assertEquals("到店后出示订单页的核销码，门店核对后办理入住。", a.get("text"));
        assertEquals("常见问题：如何办理到店入住？", a.get("source"));
        assertEquals(7L, a.get("faqId"));
        verify(db).insert(eq("kg_support_log"), argThat(row -> "faq".equals(row.get("intent"))));
    }

    @Test
    void fillerWordsAloneNeverPickAFaq()
    {
        assertNull(service.faqMatch("怎么样啊"));
        assertNull(service.faqMatch("请问在哪里"));
        assertNull(service.faqMatch("synthetic unrelated question"));
        Map<String, Object> a = service.ask(null, "profile", "今天天气怎么样", null, null);
        assertEquals("unknown", a.get("intent"));
        assertTrue(String.valueOf(a.get("text")).contains("转人工"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void stayRefundChipEstimatesTheNearestUpcomingOrder()
    {
        when(db.one(contains("from kg_stay_order"), anyMap())).thenReturn(p("orderNo", "SO20261020", "checkIn", "2026-10-20"));
        when(orders.refundPreview(7L, "SO20261020")).thenReturn(p("allowed", Boolean.TRUE, "refundFen", 9800L, "paidFen", 19600L, "hoursLeftText", "3 天"));
        Map<String, Object> a = service.ask(7L, "profile", "入住和退款", null, null);
        assertEquals("refund", a.get("intent"));
        assertTrue(String.valueOf(a.get("text")).startsWith("你 10-20 入住的那张订单：按这张订单的退订政策现在取消"));
        assertTrue(String.valueOf(a.get("text")).contains("入住须知"));
        List<Map<String, Object>> links = (List<Map<String, Object>>) a.get("links");
        assertEquals("查看订单", links.get(0).get("label"));
        assertEquals("/pages/stays/order?orderNo=SO20261020&view=refund", links.get(1).get("url"));
        assertEquals(9800L, ((List<Map<String, Object>>) a.get("cards")).get(0).get("refundFen"));
        verify(db).one(argThat(sql -> sql.contains("from kg_stay_order where app_user_id = :u") && sql.contains("del_flag = '0'")), eq(p("u", 7L)));
    }

    @Test
    void refundWithoutAnyOrderKeepsTheGeneralRules()
    {
        Map<String, Object> a = service.ask(7L, "profile", "退款规则", null, null);
        assertTrue(String.valueOf(a.get("text")).startsWith("不同业务的退款规则不一样"));
        verify(orders, never()).refundPreview(anyLong(), anyString());
    }

    @Test
    void orderPageKeepsItsOwnOrder()
    {
        when(orders.refundPreview(7L, "SO1")).thenReturn(p("allowed", Boolean.FALSE, "reason", "已入住"));
        Map<String, Object> a = service.ask(7L, "order", "取消要扣多少？", null, "SO1");
        assertEquals("这张订单现在不能申请退款：已入住。", a.get("text"));
        verify(db, never()).one(contains("from kg_stay_order"), anyMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    void ticketChipShowsTheLatestReplyAndLinksEveryTicket()
    {
        when(db.list(contains("from kg_support_ticket"), anyMap())).thenReturn(Arrays.asList(
                p("ticketId", 3L, "question", "还在处理的问题", "status", "open", "createTime", "2026-10-16 10:00:00"),
                p("ticketId", 2L, "question", "押金什么时候退", "status", "replied", "reply", "离店后 3 个工作日内原路退回", "createTime", "2026-10-15 10:00:00")));
        Map<String, Object> a = service.ask(7L, "profile", "查看客服回复", null, null);
        assertEquals("ticket", a.get("intent"));
        assertEquals("客服最近一次回复（押金什么时候退）：\n离店后 3 个工作日内原路退回", a.get("text"));
        List<Map<String, Object>> cards = (List<Map<String, Object>>) a.get("cards");
        assertEquals(2, cards.size());
        assertEquals("/pages/home/assistant?ticket=3", cards.get(0).get("url"));
        assertEquals("处理中", cards.get(0).get("status"));
        assertEquals("已回复", cards.get(1).get("status"));
    }

    @Test
    void ticketChipForGuestsAsksToLogIn()
    {
        Map<String, Object> a = service.ask(null, "profile", "查看客服回复", null, null);
        assertEquals("登录后可以在这里查看客服给你的回复。", a.get("text"));
        verify(db, never()).list(contains("from kg_support_ticket"), anyMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    void onlineChipLinksToTheDeliveryPage()
    {
        when(db.list(contains("j.job_type = 'online'"), anyMap())).thenReturn(Collections.singletonList(p("appId", 41L, "title", "小红书探店图文")));
        Map<String, Object> a = service.ask(7L, "profile", "怎么提交成果", null, null);
        assertEquals("online", a.get("intent"));
        List<Map<String, Object>> links = (List<Map<String, Object>>) a.get("links");
        assertEquals(1, links.size());
        assertEquals("/pages/jobs/deliver?appId=41", links.get(0).get("url"));
        assertEquals("交付：小红书探店图文", links.get(0).get("label"));
        Map<String, Object> guest = service.ask(null, "profile", "怎么提交成果", null, null);
        assertEquals("/pages/jobs/list?type=online", ((List<Map<String, Object>>) guest.get("links")).get(0).get("url"));
    }
}
