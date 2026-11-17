package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.service.IKgMessageService;
import com.ruoyi.system.service.ISysConfigService;

/**
 * 2026-10-17 会员信息改版：「完善个人资料」「首次入住评价」两项一次性积分（每人各一次、分数走参数设置、填 0 暂停）、
 * 「赚取积分」任务列表、「积分规则」逐条、「我的权益」每级的等级说明和可用合作店权益条数。
 */
class KgMemberInfoRewardsTest
{
    private final KgJdbc db = mock(KgJdbc.class);

    private final ISysConfigService configs = mock(ISysConfigService.class);

    private final KgPointsService service = new KgPointsService();

    private static final String ENTRY_SQL = "biz_type in ('profile', 'stay_review')";

    private static final String USER_SQL = "from kg_app_user where user_id = :u and del_flag = '0'";

    private static final String REVIEW_SQL = "from kg_review where app_user_id = :u and biz_type = 'stay_order'";

    @BeforeEach
    void setup()
    {
        KgPointLedger ledger = new KgPointLedger();
        ReflectionTestUtils.setField(ledger, "db", db);
        ReflectionTestUtils.setField(ledger, "configs", configs);
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "ledger", ledger);
        ReflectionTestUtils.setField(service, "invites", mock(KgInviteService.class));
        ReflectionTestUtils.setField(service, "membership", new KgMemberService(db));
        ReflectionTestUtils.setField(service, "support", mock(StaffSupport.class));
        ReflectionTestUtils.setField(service, "messages", mock(IKgMessageService.class));
        ReflectionTestUtils.setField(service, "self", service);
        when(db.list(contains("from kg_member_level"), anyMap())).thenReturn(Arrays.asList(
                p("levelId", 1L, "level", 0, "levelName", "幼崽鼠", "minPoints", 0L, "description", "会员价预订、入住欢迎茶"),
                p("levelId", 2L, "level", 1, "levelName", "小袋鼠", "minPoints", 100L, "description", ""),
                p("levelId", 3L, "level", 2, "levelName", "袋鼠", "minPoints", 300L, "description", null)));
        when(db.one(contains(" earned, "), anyMap())).thenReturn(p("earned", 0L, "spent", 0L));
        when(db.update(startsWith("insert ignore into kg_point_entry"), anyMap())).thenReturn(1);
    }

    private static Map<String, Object> fullProfile()
    {
        return new HashMap<String, Object>(p("nickname", "黑子", "avatar", "/profile/upload/a.jpg", "gender", "2", "birthday", "08-29", "city", "平潭", "intro", "爱看海"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> task(Map<String, Object> m, String key)
    {
        for (Map<String, Object> t : (List<Map<String, Object>>) m.get("tasks"))
        {
            if (key.equals(t.get("key")))
            {
                return t;
            }
        }
        return null;
    }

    @Test
    void profileNeedsAllSixFieldsAndARealGender()
    {
        assertTrue(KgPointsService.profileComplete(fullProfile()));
        for (String k : new String[] { "nickname", "avatar", "birthday", "city", "intro" })
        {
            Map<String, Object> u = fullProfile();
            u.put(k, " ");
            assertFalse(KgPointsService.profileComplete(u), k + " 没填不算完善");
        }
        Map<String, Object> u = fullProfile();
        u.put("gender", "0");
        assertFalse(KgPointsService.profileComplete(u), "性别「未填写」不算完善");
        assertFalse(KgPointsService.profileComplete(null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void completeProfileAndFirstPublishedStayReviewAwardOncePerUser()
    {
        when(db.one(contains(USER_SQL), anyMap())).thenReturn(fullProfile());
        when(db.count(contains(REVIEW_SQL), anyMap())).thenReturn(2L);
        Map<String, Object> m = service.summary(7L, 1);
        verify(db).update(startsWith("insert ignore into kg_point_entry"), argThat(x -> "profile".equals(((Map<String, Object>) x).get("t"))
                && Integer.valueOf(20).equals(((Map<String, Object>) x).get("d")) && Long.valueOf(7).equals(((Map<String, Object>) x).get("b"))
                && Long.valueOf(7).equals(((Map<String, Object>) x).get("u"))));
        verify(db).update(startsWith("insert ignore into kg_point_entry"), argThat(x -> "stay_review".equals(((Map<String, Object>) x).get("t"))
                && Integer.valueOf(10).equals(((Map<String, Object>) x).get("d")) && Long.valueOf(7).equals(((Map<String, Object>) x).get("b"))));
        assertEquals(true, task(m, "profile").get("done"));
        assertEquals(true, task(m, "stay_review").get("done"));
        assertEquals("20积分", task(m, "profile").get("points"));
        assertEquals("10积分", task(m, "stay_review").get("points"));
        assertEquals("review", task(m, "stay_review").get("action"));
    }

    @Test
    void alreadyAwardedTasksAreNotCheckedOrPaidAgain()
    {
        when(db.list(contains(ENTRY_SQL), anyMap())).thenReturn(Arrays.asList(p("bizType", "profile"), p("bizType", "stay_review")));
        Map<String, Object> m = service.summary(7L, 1);
        verify(db, never()).one(contains(USER_SQL), anyMap());
        verify(db, never()).count(contains(REVIEW_SQL), anyMap());
        verify(db, never()).update(startsWith("insert ignore into kg_point_entry"), anyMap());
        assertEquals(true, task(m, "profile").get("done"));
        assertEquals(true, task(m, "stay_review").get("done"));
    }

    @Test
    void incompleteProfileOrNoPublishedReviewPaysNothing()
    {
        Map<String, Object> u = fullProfile();
        u.put("intro", null);
        when(db.one(contains(USER_SQL), anyMap())).thenReturn(u);
        when(db.count(contains(REVIEW_SQL), anyMap())).thenReturn(0L);
        Map<String, Object> m = service.summary(7L, 1);
        verify(db, never()).update(startsWith("insert ignore into kg_point_entry"), anyMap());
        assertEquals(false, task(m, "profile").get("done"));
        assertEquals(false, task(m, "stay_review").get("done"));
        assertEquals("invite", task(m, "invite").get("action"));
        assertEquals("100积分/人", task(m, "invite").get("points"));
        assertEquals("10积分/天", task(m, "volunteer").get("points"));
    }

    @Test
    void zeroInSettingsPausesTheRewardAndHidesTheTask()
    {
        when(configs.selectConfigByKey(KgPointLedger.KEY_PROFILE_REWARD)).thenReturn("0");
        when(configs.selectConfigByKey(KgPointLedger.KEY_STAY_REVIEW_REWARD)).thenReturn("15");
        when(configs.selectConfigByKey(KgPointLedger.KEY_INVITE_REWARD)).thenReturn("0");
        when(db.one(contains(USER_SQL), anyMap())).thenReturn(fullProfile());
        when(db.count(contains(REVIEW_SQL), anyMap())).thenReturn(1L);
        Map<String, Object> m = service.summary(7L, 1);
        verify(db, never()).one(contains(USER_SQL), anyMap());
        assertNull(task(m, "profile"), "停发且没领过的不列");
        assertNull(task(m, "invite"));
        assertEquals("15积分", task(m, "stay_review").get("points"));
        String rules = String.valueOf(m.get("ruleRows"));
        assertFalse(rules.contains("资料获得"), rules);
        assertTrue(rules.contains("获得 15 积分（一次）") && rules.contains("邀请奖励暂时停发"), rules);
    }

    @Test
    void lookupFailureNeverBreaksThePointsPage()
    {
        when(db.list(contains(ENTRY_SQL), anyMap())).thenThrow(new RuntimeException("db down"));
        Map<String, Object> m = service.summary(7L, 1);
        assertEquals(false, task(m, "profile").get("done"));
        assertEquals(0L, m.get("points"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ruleRowsUseSettingsValues()
    {
        when(configs.selectConfigByKey(KgPointLedger.KEY_VOLUNTEER_PER_DAY)).thenReturn("12");
        when(configs.selectConfigByKey(KgPointLedger.KEY_URGENT_COST)).thenReturn("40");
        Map<String, Object> m = service.summary(7L, 1);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) m.get("ruleRows");
        assertEquals("换工换宿", rows.get(0).get("title"));
        assertEquals("换工换宿完成后，每天获得 12 积分", rows.get(0).get("text"));
        assertEquals("门店任务验收后，每小时获得 10 积分，生日当天双倍", rows.get(1).get("text"));
        assertEquals("积分使用", rows.get(rows.size() - 1).get("title"));
        assertTrue(String.valueOf(rows.get(rows.size() - 1).get("text")).contains("花 40 积分加急投递"));
        // 2026-10-17：加上样稿的「消费获得」「攻略获得」两条（排在邀请获得后面），共 8 条
        assertEquals(8, rows.size());
        assertEquals("消费获得", rows.get(3).get("title"));
        assertEquals("每消费1元累计1积分", rows.get(3).get("text"));
        assertEquals("攻略获得", rows.get(4).get("title"));
        assertEquals("发布攻略审核通过获得20积分", rows.get(4).get("text"));
        assertTrue(String.valueOf(m.get("note")).contains("完善个人资料得 20 积分（一次）"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void spendAndGuideRowsAndTaskFollowSettings()
    {
        when(configs.selectConfigByKey(KgPointLedger.KEY_SPEND_PER_YUAN)).thenReturn("2");
        when(configs.selectConfigByKey(KgPointLedger.KEY_GUIDE_REWARD)).thenReturn("30");
        when(configs.selectConfigByKey(KgPointLedger.KEY_GUIDE_DAILY_CAP)).thenReturn("5");
        Map<String, Object> m = service.summary(7L, 1);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) m.get("ruleRows");
        assertEquals("每消费1元累计2积分", rows.get(3).get("text"));
        assertEquals("发布攻略审核通过获得30积分", rows.get(4).get("text"));
        Map<String, Object> guide = task(m, "guide");
        assertEquals("发布攻略", guide.get("title"));
        assertEquals("30积分", guide.get("points"));
        assertEquals("guide", guide.get("action"));
        assertEquals(false, guide.get("done"), "可以反复做，不显示已完成");
        assertTrue(String.valueOf(guide.get("sub")).contains("每天最多 5 篇"));
        Map<String, Object> rules = (Map<String, Object>) m.get("rules");
        assertEquals(2, rules.get("spendPerYuan"));
        assertEquals(30, rules.get("guideReward"));
        assertEquals(5, rules.get("guideDailyCap"));
        // 任务顺序：完善资料、首次入住评价、发布攻略、邀请好友、换工换宿
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) m.get("tasks");
        assertEquals(Arrays.asList("profile", "stay_review", "guide", "invite", "volunteer"), tasks.stream().map(t -> t.get("key")).collect(java.util.stream.Collectors.toList()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void zeroPausesSpendAndGuideRowsAndHidesTheGuideTask()
    {
        when(configs.selectConfigByKey(KgPointLedger.KEY_SPEND_PER_YUAN)).thenReturn("0");
        when(configs.selectConfigByKey(KgPointLedger.KEY_GUIDE_REWARD)).thenReturn("0");
        Map<String, Object> m = service.summary(7L, 1);
        String rules = String.valueOf(m.get("ruleRows"));
        assertFalse(rules.contains("消费获得") || rules.contains("攻略获得"), rules);
        assertEquals(6, ((List<Map<String, Object>>) m.get("ruleRows")).size());
        assertNull(task(m, "guide"));
        assertFalse(String.valueOf(m.get("note")).contains("每消费"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void levelPerksComeFromLevelDescriptionsAndActiveShopBenefits()
    {
        when(db.list(contains("group by min_level"), anyMap())).thenReturn(Arrays.asList(p("minLevel", 0L, "n", 2L), p("minLevel", 2L, "n", 3L)));
        Map<String, Object> m = service.summary(7L, 1);
        List<Map<String, Object>> perks = (List<Map<String, Object>>) m.get("levelPerks");
        assertEquals(3, perks.size());
        assertEquals(Arrays.asList("会员价预订", "入住欢迎茶"), perks.get(0).get("items"));
        assertEquals(Collections.emptyList(), perks.get(1).get("items"));
        assertEquals(2L, perks.get(0).get("shopCount"));
        assertEquals(2L, perks.get(1).get("shopCount"), "适用等级 ≤ 这一级的都能用");
        assertEquals(5L, perks.get(2).get("shopCount"));
        assertEquals("袋鼠", perks.get(2).get("levelName"));
        assertEquals(300L, perks.get(2).get("minPoints"));
    }

    @Test
    void perkItemsPreserveMultilineBenefitsAndAllConditions()
    {
        assertEquals(Arrays.asList("a", "b", "c"), KgMemberService.perkItems("a、b；c"));
        assertEquals(Arrays.asList("每年四人间免费 3 晚、双人间免费 2 晚、单人间免费 1 晚", "每年向亲友转赠最多 3 晚，限 1 次"),
                KgMemberService.perkItems("每年四人间免费 3 晚、双人间免费 2 晚、单人间免费 1 晚\n每年向亲友转赠最多 3 晚，限 1 次"));
        assertEquals(10, KgMemberService.perkItems("1\n2\n3\n4\n5\n6\n7\n8\n9\n10").size());
        assertTrue(KgMemberService.perkItems(null).isEmpty());
    }
}
