package com.ruoyi.kangaroo.ext.community;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.ext.user.KgCityService;
import com.ruoyi.kangaroo.ext.user.KgPointRewards;
import com.ruoyi.kangaroo.security.WxMsgSecCheck;
import com.ruoyi.kangaroo.service.IKgMessageService;

/**
 * 2026-10-17 攻略获得的两个挂点：发旅居攻略时机审直接通过（已发布）、后台审核通过 → 调 KgPointRewards.postOnline；
 * 进人工审核的、找搭子、驳回的不调。发放条件（只认旅居攻略、每篇一次、每天上限）见 KgPointRewardsTest。
 */
class KgPostGuidePointsTest
{
    final KgJdbc db = mock(KgJdbc.class);
    final KgCommunityGuard guard = mock(KgCommunityGuard.class);
    final KgCityService cities = mock(KgCityService.class);
    final KgPointRewards rewards = mock(KgPointRewards.class);
    final KgPostService service = new KgPostService();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "guard", guard);
        ReflectionTestUtils.setField(service, "cities", cities);
        ReflectionTestUtils.setField(service, "messages", mock(IKgMessageService.class));
        ReflectionTestUtils.setField(service, "support", mock(StaffSupport.class));
        ReflectionTestUtils.setField(service, "activities", mock(KgActivityService.class));
        ReflectionTestUtils.setField(service, "pointRewards", rewards);
        when(cities.businessCity(any())).thenAnswer(i -> i.getArgument(0));
        when(db.one(startsWith("select status, del_flag from kg_app_user"), anyMap())).thenReturn(p("status", "0", "delFlag", "0"));
        when(guard.check(any(), any(), any())).thenReturn("pass");
        when(guard.initialStatus(any())).thenReturn("pending");
        when(guard.mode()).thenReturn("manual");
        when(db.insert(anyString(), anyMap())).thenReturn(42L);
    }

    static Map<String, Object> daily()
    {
        return p("kind", "daily", "title", "洱海日落", "content", "傍晚的风很舒服", "city", "大理", "tags", "日落,散步");
    }

    @Test
    void guideOnlineAtPublishEarnsRightAway()
    {
        when(guard.autoApprove("pass", 7L, WxMsgSecCheck.SCENE_FORUM, "洱海日落", "傍晚的风很舒服 日落 散步")).thenReturn(true);
        assertEquals("online", service.publish(7L, daily()).get("status"));
        verify(rewards).postOnline(42L);
    }

    @Test
    void pendingGuideWaitsForTheReview()
    {
        assertEquals("pending", service.publish(7L, daily()).get("status"));
        verify(rewards, never()).postOnline(any());
    }

    @Test
    void otherKindsOnlineAtPublishDoNotEarn()
    {
        when(guard.initialStatus(any())).thenReturn("online");
        Map<String, Object> b = daily();
        b.put("kind", "buddy");
        assertEquals("online", service.publish(7L, b).get("status"));
        verify(rewards, never()).postOnline(any());
    }

    @Test
    void adminApprovalEarnsRejectionDoesNot()
    {
        when(db.must(contains("from kg_post where post_id = :p"), anyMap(), anyString()))
                .thenReturn(p("postId", 11L, "appUserId", 7L, "title", "平潭三天", "status", "pending"));
        when(db.update(contains("update kg_post set status = :to"), anyMap())).thenReturn(1);
        assertEquals("已通过", service.audit(11L, true, null, "admin"));
        verify(rewards).postOnline(11L);
        clearInvocations(rewards);
        assertEquals("已驳回", service.audit(11L, false, "广告", "admin"));
        verify(rewards, never()).postOnline(any());
    }
}
