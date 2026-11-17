package com.ruoyi.kangaroo.ext.community;

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
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.KgCityService;
import com.ruoyi.kangaroo.security.WxMsgSecCheck;
import com.ruoyi.kangaroo.service.IKgMessageService;

/**
 * 内测反馈 2.0（2026-10-16）：
 * - 机审直接发布（review-mode=auto）：评论、不带图的旅居日常 / 经验分享，本地检查 + 微信内容安全都通过就直接上线（记 review_mode=auto）；
 *   带图的动态、找搭子不问机审，照旧待审；机审没通过（接口出错 / 超时 / 有风险，guard.autoApprove 返回 false）照旧待审。
 * - 经验分享：已到岗还没做完的报名能选，也能发；暂时不能用的报名单独列出来、带原因。
 * Real KgPostService bodies, Mock JDBC / guard（guard 自己的判定在 KgCommunityGuardAutoReviewTest）。
 */
class KgPostAutoReviewTest
{
    final KgJdbc db = mock(KgJdbc.class);
    final KgCommunityGuard guard = mock(KgCommunityGuard.class);
    final KgCityService cities = mock(KgCityService.class);
    final IKgMessageService messages = mock(IKgMessageService.class);
    final KgPostService service = new KgPostService();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "guard", guard);
        ReflectionTestUtils.setField(service, "cities", cities);
        ReflectionTestUtils.setField(service, "messages", messages);
        ReflectionTestUtils.setField(service, "activities", mock(KgActivityService.class));
        when(cities.businessCity(any())).thenAnswer(i -> i.getArgument(0));
        when(db.one(startsWith("select status, del_flag from kg_app_user"), anyMap())).thenReturn(p("status", "0", "delFlag", "0"));
        when(db.one(startsWith("select p.* from kg_post p"), anyMap())).thenReturn(p("postId", 11L, "appUserId", 3L, "status", "online", "city", "大理", "title", "大理三天"));
        when(db.one(startsWith("select nickname from kg_app_user"), anyMap())).thenReturn(p("nickname", "小七"));
        // 评论 check(text) 一个参数，发帖 check(title, content, tags) 三个参数（Mockito 5 按个数匹配可变参数）
        when(guard.check(any())).thenReturn("pass");
        when(guard.check(any(), any(), any())).thenReturn("pass");
        // 生产 auto 模式下 initialStatus 一律 pending，mode() 记 manual
        when(guard.initialStatus(any())).thenReturn("pending");
        when(guard.mode()).thenReturn("manual");
        when(db.insert(anyString(), anyMap())).thenReturn(42L);
    }

    // ---------------------------------------------------------------- 评论

    @Test
    void commentPassingMachineReviewIsVisibleAtOnceAndNotifiesTheAuthor()
    {
        when(guard.autoApprove("pass", 7L, WxMsgSecCheck.SCENE_COMMENT, "", "好看")).thenReturn(true);
        Map<String, Object> r = service.comment(11L, 7L, null, " 好看 ");
        assertEquals("visible", r.get("status"));
        assertEquals("评论已发布", r.get("message"));
        verify(db).insert(eq("kg_post_comment"), argThat(m -> "visible".equals(m.get("status"))));
        verify(db).update(startsWith("update kg_post set comments = comments + 1"), anyMap());
        verify(messages).send(eq(3L), eq("user"), eq("comment"), eq("小七 评论了你的动态"), eq("好看"), eq("comment"), eq(42L), anyString(), eq(7L));
    }

    @Test
    void commentNotPassingMachineReviewStaysPendingAsToday()
    {
        // autoApprove 默认 false：manual 模式、接口出错、超时、判有风险都走到这里
        Map<String, Object> r = service.comment(11L, 7L, null, "好看");
        assertEquals("pending", r.get("status"));
        assertEquals("评论已提交，审核通过后展示", r.get("message"));
        verify(guard).autoApprove("pass", 7L, WxMsgSecCheck.SCENE_COMMENT, "", "好看");
        verify(db, never()).update(startsWith("update kg_post set comments = comments + 1"), anyMap());
        verifyNoInteractions(messages);
    }

    @Test
    void commentWithContactInfoNeverAsksTheMachine()
    {
        when(guard.check(any())).thenReturn("review");
        when(guard.autoApprove(eq("review"), any(), anyInt(), any(), any())).thenReturn(false);
        assertEquals("pending", service.comment(11L, 7L, null, "加我 vx abcdef1").get("status"));
    }

    @Test
    void mockModeShortcutStillWorksWithoutAskingTheMachine()
    {
        when(guard.initialStatus("pass")).thenReturn("online");
        assertEquals("visible", service.comment(11L, 7L, null, "好看").get("status"));
        verify(guard, never()).autoApprove(any(), any(), anyInt(), any(), any());
    }

    // ---------------------------------------------------------------- 动态

    static Map<String, Object> daily(Object... extra)
    {
        Map<String, Object> b = p("kind", "daily", "title", "洱海日落", "content", "傍晚的风很舒服", "city", "大理", "tags", "日落,散步");
        b.putAll(p(extra));
        return b;
    }

    @Test
    void textOnlyDailyPassingMachineReviewGoesOnlineAndIsMarkedAuto()
    {
        when(guard.autoApprove("pass", 7L, WxMsgSecCheck.SCENE_FORUM, "洱海日落", "傍晚的风很舒服 日落 散步")).thenReturn(true);
        Map<String, Object> r = service.publish(7L, daily());
        assertEquals("online", r.get("status"));
        assertEquals("发布成功", r.get("message"));
        verify(db).insert(eq("kg_post"), argThat(m -> "online".equals(m.get("status")) && "auto".equals(m.get("review_mode"))));
    }

    @Test
    void textOnlyDailyNotPassingStaysPendingAndManual()
    {
        Map<String, Object> r = service.publish(7L, daily());
        assertEquals("pending", r.get("status"));
        verify(db).insert(eq("kg_post"), argThat(m -> "pending".equals(m.get("status")) && "manual".equals(m.get("review_mode"))));
    }

    @Test
    void dailyWithImagesIsNeverMachineApproved()
    {
        when(guard.autoApprove(any(), any(), anyInt(), any(), any())).thenReturn(true);
        Map<String, Object> r = service.publish(7L, daily("images", Arrays.asList("/profile/upload/2026/10/16/a.jpg")));
        assertEquals("pending", r.get("status"));
        verify(guard, never()).autoApprove(any(), any(), anyInt(), any(), any());
        verify(db).insert(eq("kg_post"), argThat(m -> "pending".equals(m.get("status")) && "manual".equals(m.get("review_mode"))));
    }

    @Test
    void buddyIsNeverMachineApproved()
    {
        when(guard.autoApprove(any(), any(), anyInt(), any(), any())).thenReturn(true);
        Map<String, Object> r = service.publish(7L, p("kind", "buddy", "title", "周六徒步", "content", "苍山", "city", "大理"));
        assertEquals("pending", r.get("status"));
        verify(guard, never()).autoApprove(any(), any(), anyInt(), any(), any());
    }

    @Test
    void autoReviewableKinds()
    {
        assertTrue(KgPostService.autoReviewable("daily", Collections.<String>emptyList()));
        assertTrue(KgPostService.autoReviewable("diary", null));
        assertFalse(KgPostService.autoReviewable("buddy", Collections.<String>emptyList()));
        assertFalse(KgPostService.autoReviewable("daily", Arrays.asList("/profile/upload/a.jpg")));
        assertFalse(KgPostService.autoReviewable("diary", Arrays.asList("/profile/upload/a.jpg")));
    }

    // ---------------------------------------------------------------- 经验分享

    @Test
    void ongoingExperienceCanBePublishedAndMachineApproved()
    {
        when(db.list(startsWith("select a.app_id, a.arrival_date"), anyMap())).thenReturn(Collections.singletonList(
                p("appId", 5L, "arrivalDate", "2026-10-01", "stayDays", 60L, "title", "前台换工", "city", "大理", "jobType", "volunteer")));
        when(guard.autoApprove(eq("pass"), eq(7L), eq(WxMsgSecCheck.SCENE_FORUM), eq("换宿第三周"), anyString())).thenReturn(true);
        Map<String, Object> r = service.publish(7L, p("kind", "diary", "title", "换宿第三周", "content", "学会了做咖啡", "city", "大理", "refType", "application", "refId", 5L));
        assertEquals("online", r.get("status"));
        verify(db).insert(eq("kg_post"), argThat(m -> "diary".equals(m.get("kind")) && Long.valueOf(5L).equals(m.get("ref_id")) && "auto".equals(m.get("review_mode"))));
    }

    @Test
    void notYetOnboardedApplicationCannotBeUsedForDiary()
    {
        when(db.list(startsWith("select a.app_id, a.arrival_date"), anyMap())).thenReturn(Collections.<Map<String, Object>>emptyList());
        com.ruoyi.common.exception.ServiceException e = assertThrows(com.ruoyi.common.exception.ServiceException.class,
                () -> service.publish(7L, p("kind", "diary", "title", "还没去", "content", "", "city", "大理", "refType", "application", "refId", 6L)));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("已到岗、还在做的也可以"), e.getMessage());
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    void unavailableApplicationsComeWithPlainReasons()
    {
        when(db.list(startsWith("select a.app_id, a.status, a.arrival_date"), anyMap())).thenReturn(Arrays.asList(
                p("appId", 9L, "status", "submitted", "arrivalDate", "2026-11-01", "stayDays", 30L, "title", "客栈换工", "city", "丽江", "jobType", "volunteer"),
                p("appId", 8L, "status", "interview_booked", "arrivalDate", null, "stayDays", 0L, "title", "咖啡店兼职", "city", "大理", "jobType", "parttime"),
                p("appId", 7L, "status", "store_confirmed", "arrivalDate", "2026-11-01", "stayDays", 10L, "title", "民宿管家", "city", "大理", "jobType", "fulltime"),
                p("appId", 6L, "status", "contract_signed", "arrivalDate", "2026-10-20", "stayDays", 10L, "title", "前台", "city", "大理", "jobType", "volunteer"),
                p("appId", 5L, "status", "onboarded", "arrivalDate", null, "stayDays", 0L, "title", "写文案", "city", "全国", "jobType", "online"),
                p("appId", 4L, "status", "weird_state", "arrivalDate", null, "stayDays", 0L, "title", "怪状态", "city", "大理", "jobType", "volunteer")));
        List<Map<String, Object>> list = service.experiencesUnavailable(7L);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db).list(sql.capture(), eq(p("u", 7L)));
        assertTrue(sql.getValue().contains("a.status not in ('withdrawn','review_rejected','terminated')"), sql.getValue());
        assertTrue(sql.getValue().contains("(j.job_type = 'online' or a.status not in ('onboarded','completed'))"), sql.getValue());
        assertTrue(sql.getValue().contains("a.app_user_id = :u"));
        assertEquals(5, list.size(), "不认识的状态不列");
        assertEquals("报名还在等门店审核，到岗后才能分享", list.get(0).get("reason"));
        assertEquals("已提交", list.get(0).get("statusLabel"));
        assertEquals("换宿旅行", list.get(0).get("jobTypeLabel"));
        assertEquals("客栈换工（丽江）", list.get(0).get("title"));
        assertEquals("2026.11.01–2026.11.30", list.get(0).get("date"));
        assertEquals("还在面试阶段，到岗后才能分享", list.get(1).get("reason"));
        assertEquals("到岗日期待定", list.get(1).get("date"));
        assertEquals("门店已录用，签好责任书、到岗后才能分享", list.get(2).get("reason"));
        assertEquals("还没确认到岗，门店标记你到岗后就能分享", list.get(3).get("reason"));
        assertEquals("线上任务不算旅居经验，不能用来发经验分享", list.get(4).get("reason"));
        assertEquals("写文案", list.get(4).get("title"));
        assertEquals("线上任务", list.get(4).get("date"));
        for (Map<String, Object> m : list)
        {
            assertNotNull(m.get("appId"));
            assertNotNull(m.get("status"));
        }
    }

    @Test
    void everyInProgressStatusHasAReason()
    {
        for (String st : Arrays.asList("submitted", "review_passed", "interview_booked", "interview_done", "user_confirmed", "store_confirmed", "contract_signed"))
        {
            assertNotNull(KgPostService.NOT_YET_REASON.get(st), st);
            assertTrue(KgPostService.NOT_YET_REASON.get(st).contains("分享"), st);
        }
        assertNull(KgPostService.NOT_YET_REASON.get("onboarded"));
    }
}
