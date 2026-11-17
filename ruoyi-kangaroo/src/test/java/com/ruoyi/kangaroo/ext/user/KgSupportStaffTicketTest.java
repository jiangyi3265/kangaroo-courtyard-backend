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
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;

/**
 * 员工身份工单（来源保留页面、身份记在摘要标记里、回复按标记发回员工身份）、助手按岗位类型作答、找机会房源理由。
 * 真实 service 方法 + Mock JDBC；不证明 MySQL 与消息中心的可见范围（那部分在 KgMessageMapper.xml）。
 */
class KgSupportStaffTicketTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final StaffSupport notifications = mock(StaffSupport.class);
    private final KgHomeService home = mock(KgHomeService.class);
    private final KgSupportService service = new KgSupportService();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "support", notifications);
        ReflectionTestUtils.setField(service, "home", home);
        when(db.one(contains("from kg_app_user"), anyMap())).thenReturn(p("status", "0", "delFlag", "0"));
        when(db.insert(eq("kg_support_ticket"), anyMap())).thenReturn(30L);
        when(db.one(contains("select app_user_id from kg_support_ticket"), anyMap())).thenReturn(p("appUserId", 7L));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> insertedTicket()
    {
        org.mockito.ArgumentCaptor<Map<String, Object>> row = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(db).insert(eq("kg_support_ticket"), row.capture());
        return row.getValue();
    }

    @Test
    void staffTicketKeepsPageContextAndMarksIdentityInSummary()
    {
        service.createTicket(7L, p("question", "保险怎么买", "context", "city", "summary", "保险怎么买"), true);
        Map<String, Object> row = insertedTicket();
        assertEquals("city", row.get("context"), "来源仍是提交的页面，不再整个改成 staff");
        assertEquals(KgSupportService.STAFF_MARK + "保险怎么买", row.get("summary"));
    }

    @Test
    void staffTicketWithoutSummaryStillCarriesMark()
    {
        service.createTicket(7L, p("question", "排班在哪里改", "context", "profile"), true);
        Map<String, Object> row = insertedTicket();
        assertEquals("profile", row.get("context"));
        assertEquals(KgSupportService.STAFF_MARK, row.get("summary"));
    }

    @Test
    void userTicketCannotForgeStaffMarkAndFollowUpDoesNotDoubleIt()
    {
        service.createTicket(7L, p("question", "q", "context", "home", "summary", "追问工单 3：" + KgSupportService.STAFF_MARK + "原问题"), false);
        assertEquals("追问工单 3：原问题", insertedTicket().get("summary"));
    }

    @Test
    void tooLongQuestionSaysTheLimitPlainly()
    {
        char[] big = new char[501];
        Arrays.fill(big, '字');
        ServiceException e = assertThrows(ServiceException.class, () -> service.createTicket(7L, p("question", new String(big)), false));
        assertEquals(422, (int) e.getCode());
        assertEquals("问题最多 500 字（含补充内容），请删减一些", e.getMessage());
        // 消息页提交的长问题也走这里：提示不能提「追问」
        assertFalse(e.getMessage().contains("追问"));
        verify(db, never()).insert(anyString(), anyMap());
        ServiceException empty = assertThrows(ServiceException.class, () -> service.createTicket(7L, p("question", "  "), false));
        assertEquals("请先写下你的问题", empty.getMessage());
    }

    private void ticket(String context, String summary, long activeGrants)
    {
        when(db.must(contains("from kg_support_ticket"), anyMap(), anyString()))
                .thenReturn(p("appUserId", 7L, "question", "q", "context", context, "summary", summary));
        when(db.count(contains("from kg_staff_grant"), anyMap())).thenReturn(activeGrants);
    }

    @Test
    void markedTicketReplyGoesToStaffIdentityWhileGrantIsActive()
    {
        ticket("city", KgSupportService.STAFF_MARK + "保险怎么买", 1L);
        service.reply(30L, "回复内容", "admin");
        verify(notifications).notifyStaff(7L, "system", "客服回复了你的问题", "回复内容", "support_ticket", 30L, "/pages/home/assistant?ticket=30");
        verify(notifications, never()).notifyUser(anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void cityGrantWithoutStoreCountsAsStaffIdentity()
    {
        // 「还有员工身份」和登录同一口径：按城市授权（本城还没门店）也算，不能只 join kg_store
        ticket("city", KgSupportService.STAFF_MARK + "任务怎么交", 1L);
        service.reply(30L, "回复内容", "admin");
        verify(db).count(eq(com.ruoyi.kangaroo.ext.staff.CityGrantScope.COUNT_ACTIVE_IDENTITY), anyMap());
        assertTrue(com.ruoyi.kangaroo.ext.staff.CityGrantScope.COUNT_ACTIVE_IDENTITY.contains("g.store_id is null"));
        assertTrue(com.ruoyi.kangaroo.ext.staff.CityGrantScope.COUNT_ACTIVE_IDENTITY.contains("c.status = '0'"));
        verify(notifications).notifyStaff(eq(7L), anyString(), anyString(), anyString(), eq("support_ticket"), eq(30L), anyString());
    }

    @Test
    void legacyStaffContextStillRoutesToStaff()
    {
        ticket("staff", null, 1L);
        service.reply(30L, "回复内容", "admin");
        verify(notifications).notifyStaff(eq(7L), anyString(), anyString(), anyString(), eq("support_ticket"), eq(30L), anyString());
    }

    @Test
    void markedTicketFallsBackToUserIdentityAfterGrantRevoked()
    {
        ticket("home", KgSupportService.STAFF_MARK, 0L);
        service.reply(30L, "回复内容", "admin");
        verify(notifications).notifyUser(eq(7L), anyString(), anyString(), anyString(), eq("support_ticket"), eq(30L), anyString());
        verify(notifications, never()).notifyStaff(anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void unmarkedTicketReplyNeverChecksGrantsAndGoesToUser()
    {
        ticket("profile", "用户咨询：会员积分怎么用？", 1L);
        service.reply(30L, "回复内容", "admin");
        verify(notifications).notifyUser(eq(7L), anyString(), anyString(), anyString(), eq("support_ticket"), eq(30L), anyString());
        verify(db, never()).count(contains("from kg_staff_grant"), anyMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    void myTicketsHideTheIdentityMark()
    {
        when(db.list(contains("from kg_support_ticket"), anyMap())).thenReturn(Arrays.asList(
                p("ticketId", 2L, "question", "q", "summary", KgSupportService.STAFF_MARK + "用户咨询：q", "status", "open"),
                p("ticketId", 1L, "question", "q", "summary", null, "status", "replied")));
        List<Map<String, Object>> rows = service.myTickets(7L);
        assertEquals("用户咨询：q", rows.get(0).get("summary"));
        assertNull(rows.get(1).get("summary"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void partTimeQuestionAnswersOnlyPartTimeJobs()
    {
        when(home.jobsOfTypes(eq("全部城市"), eq(Collections.singletonList("parttime")), eq(3)))
                .thenReturn(Collections.singletonList(p("jobId", 5L, "title", "咖啡兼职", "typeLabel", "兼职", "city", "大理", "price", "20", "unit", "元/时")));
        Map<String, Object> a = service.ask(null, "home", "附近有什么兼职？", "全部城市", null);
        assertEquals("job", a.get("intent"));
        assertEquals("这些兼职机会正在招募：", a.get("text"));
        assertEquals("兼职 · 大理", ((List<Map<String, Object>>) a.get("cards")).get(0).get("meta"));
        verify(home, never()).feed(any(), any());
    }

    @Test
    void volunteerQuestionSaysSoWhenNothingOpen()
    {
        when(home.jobsOfTypes(anyString(), anyList(), anyInt())).thenReturn(Collections.<Map<String, Object>>emptyList());
        Map<String, Object> a = service.ask(null, "home", "附近换工", "大理", null);
        assertEquals("当前城市暂时没有招募中的换工换宿，可以订阅新机会。", a.get("text"));
        verify(home).jobsOfTypes("大理", Collections.singletonList("volunteer"), 3);
    }

    @Test
    void untypedJobQuestionStillUsesHomeFeed()
    {
        when(home.feed(any(), any())).thenReturn(p("jobs", Collections.<Map<String, Object>>emptyList()));
        Map<String, Object> a = service.ask(null, "home", "有什么工作", "大理", null);
        assertEquals("当前城市暂时没有招募中的岗位，可以订阅新机会。", a.get("text"));
        verify(home).feed(null, "大理");
        verify(home, never()).jobsOfTypes(any(), any(), anyInt());
    }

    @Test
    void jobTypesOfReadsTheWordsUsersType()
    {
        assertEquals(Collections.singletonList("parttime"), KgSupportService.jobTypesOf("附近有什么兼职？"));
        assertEquals(Collections.singletonList("volunteer"), KgSupportService.jobTypesOf("附近换工"));
        assertEquals(Arrays.asList("parttime", "fulltime"), KgSupportService.jobTypesOf("兼职或者全职都行"));
        assertTrue(KgSupportService.jobTypesOf("有什么工作").isEmpty());
        // 「打工换宿」是换工换宿，不把兼职也答进来
        assertEquals(Collections.singletonList("volunteer"), KgSupportService.jobTypesOf("附近有打工换宿吗"));
        assertEquals(Arrays.asList("parttime", "volunteer"), KgSupportService.jobTypesOf("打工或者打工换宿"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void stayReasonHasNoTrailingCommaWhenAreaIsBlank()
    {
        KgHomeService realHome = new KgHomeService();
        KgCityService cities = mock(KgCityService.class);
        when(cities.businessCity("大理")).thenReturn("大理");
        ReflectionTestUtils.setField(realHome, "db", db);
        ReflectionTestUtils.setField(realHome, "cities", cities);
        when(db.list(contains("from kg_stay s where s.city"), anyMap())).thenReturn(Arrays.asList(
                p("stayId", 1L, "stayName", "空区域", "cover", "", "area", "", "city", "大理", "price", 10000L),
                p("stayId", 2L, "stayName", "有区域", "cover", "", "area", " 古城 ", "city", "大理", "price", 10000L)));
        Map<String, Object> res = realHome.match(null, p("text", "想住一周", "city", "大理", "people", 4));
        List<Map<String, Object>> stays = (List<Map<String, Object>>) res.get("stays");
        assertEquals("在你的预算内，住得下 4 人", stays.get(0).get("reason"));
        assertEquals("在你的预算内，住得下 4 人，古城", stays.get(1).get("reason"));
    }
}
