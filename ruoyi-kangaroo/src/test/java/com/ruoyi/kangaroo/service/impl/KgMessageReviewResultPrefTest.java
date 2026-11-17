package com.ruoyi.kangaroo.service.impl;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.kangaroo.domain.KgMessage;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.KgSubscribeService;
import com.ruoyi.kangaroo.mapper.KgMessageMapper;

/**
 * 内测反馈 2.0（2026-10-16）的 bug：关掉「社区互动」开关后，动态 / 评论 / 公开行程的审核结果也一起不写库了（prefKeyOf 只看 bizType 前缀）。
 * 现在审核结果（动态、评论、行程、活动、报名的通过 / 驳回 / 隐藏）任何开关都关不掉；点赞、评论等互动照旧受开关控制；
 * 订阅消息的模板选择（prefKeyOf 两参版）不变。标题是发送方写死的，改了这里的测试会红。
 */
class KgMessageReviewResultPrefTest
{
    final KgJdbc jdbc = mock(KgJdbc.class);
    final KgMessageMapper mapper = mock(KgMessageMapper.class);
    final KgSubscribeService subscribe = mock(KgSubscribeService.class);
    final KgMessageServiceImpl s = new KgMessageServiceImpl();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(s, "jdbc", jdbc);
        ReflectionTestUtils.setField(s, "subscribe", subscribe);
        ReflectionTestUtils.setField(s, "kgMessageMapper", mapper);
        // 这个人把所有开关都关了
        when(jdbc.one(startsWith("select enabled from kg_notify_pref"), anyMap())).thenReturn(p("enabled", "0"));
    }

    /** 各发送方现在用的 bizType + 标题（KgPostService.audit / auditComment、KgTripService、KgActivityService、KgApplicationServiceImpl） */
    static final String[][] REVIEW_RESULTS = {
            { "system", "post", "你的动态已通过审核" },
            { "system", "post", "你的动态未通过审核" },
            { "system", "post", "你的动态已被隐藏" },
            { "system", "post", "你的评论已通过审核" },
            { "system", "post", "你的评论未通过审核" },
            { "system", "trip_plan", "你的公开行程已通过审核" },
            { "system", "trip_plan", "你的公开行程未通过审核" },
            { "system", "activity", "活动已通过审核，开始报名" },
            { "system", "activity", "活动未通过审核" },
            { "apply", "application", "报名审核通过" },
            { "apply", "application", "报名未通过" },
            { "apply", "application", "接单申请已通过" },
            { "apply", "application", "接单申请未通过" },
    };

    @Test
    void reviewResultsAreRecognised()
    {
        for(String type:java.util.Arrays.asList("member_case","member_case_result","merchant_case")){
            assertTrue(KgMessageServiceImpl.reviewResult(type,"流程通知"));
            assertEquals("governance_case",KgMessageServiceImpl.prefKeyOf("system",type));
            assertNull(KgMessageServiceImpl.prefKeyOf("system",type,"流程通知"));
        }
        for (String[] r : REVIEW_RESULTS)
        {
            assertTrue(KgMessageServiceImpl.reviewResult(r[1], r[2]), r[2]);
            assertNull(KgMessageServiceImpl.prefKeyOf(r[0], r[1], r[2]), r[2]);
        }
    }

    @Test
    void ordinaryNoticesKeepTheirSwitch()
    {
        assertFalse(KgMessageServiceImpl.reviewResult("comment", "小七 评论了你的动态"));
        assertEquals("community", KgMessageServiceImpl.prefKeyOf("comment", "comment", "小七 评论了你的动态"));
        assertEquals("community", KgMessageServiceImpl.prefKeyOf("like", "post_like", "小七 赞了你的动态"));
        assertEquals("activity", KgMessageServiceImpl.prefKeyOf("system", "activity", "大理有新活动"));
        assertEquals("activity", KgMessageServiceImpl.prefKeyOf("system", "activity", "集合提醒"));
        assertEquals("activity", KgMessageServiceImpl.prefKeyOf("system", "activity", "你发起的活动有新报名"));
        assertEquals("apply_progress", KgMessageServiceImpl.prefKeyOf("system", "application", "换工换宿已完成"));
        assertEquals("apply_progress", KgMessageServiceImpl.prefKeyOf("interview", "application", "面试提醒"));
        assertEquals("order", KgMessageServiceImpl.prefKeyOf("order", "activity", "活动退款未通过"));
        assertFalse(KgMessageServiceImpl.reviewResult("job", "岗位已通过平台审核"), "门店岗位审核不在这次范围，照旧（本来就没有开关）");
    }

    @Test
    void subscribeTemplateKeyIsUnchanged()
    {
        assertEquals("community", KgMessageServiceImpl.prefKeyOf("system", "post"));
        assertEquals("community", KgMessageServiceImpl.prefKeyOf("system", "trip_plan"));
        assertEquals("activity", KgMessageServiceImpl.prefKeyOf("system", "activity"));
        assertEquals("apply_progress", KgMessageServiceImpl.prefKeyOf("apply", "application"));
    }

    @Test
    void reviewResultIsWrittenEvenWithEverySwitchOff()
    {
        for (String[] r : REVIEW_RESULTS)
        {
            s.send(3L, "user", r[0], r[2], "内容", r[1], 11L, "/pages/community/mine");
        }
        verify(mapper, times(REVIEW_RESULTS.length)).insertKgMessage(any(KgMessage.class));
        verify(jdbc, never()).one(startsWith("select enabled from kg_notify_pref"), anyMap());
        // 订阅消息照旧按原来的开关键选模板
        verify(subscribe, times(7)).push(eq(3L), eq("community"), anyString(), anyString(), anyString(), any());
        verify(subscribe, times(2)).push(eq(3L), eq("activity"), anyString(), anyString(), anyString(), any());
        verify(subscribe, times(4)).push(eq(3L), eq("apply_progress"), anyString(), anyString(), anyString(), any());
    }

    @Test
    void reviewResultToStaffIsAlsoNotMutable()
    {
        s.send(5L, "staff", "system", "活动已通过审核，开始报名", "周六徒步", "activity", 9L, "/pages/staff/activities?s=4&id=9");
        verify(mapper).insertKgMessage(any(KgMessage.class));
    }

    @Test
    void interactionNoticesAreStillMutedByTheCommunitySwitch()
    {
        assertNull(s.send(3L, "user", "comment", "小七 评论了你的动态", "好看", "comment", 33L, "/pages/community/index?postId=1", 7L));
        assertNull(s.send(3L, "user", "system", "大理有新活动", "周六徒步", "activity", 9L, "/pages/community/activity?id=9"));
        verify(mapper, never()).insertKgMessage(any(KgMessage.class));
    }
}
