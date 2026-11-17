package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.service.IKgMessageService;
import com.ruoyi.system.service.ISysConfigService;

/**
 * 积分：等级按累计获得算（花积分不降级）、换工完成发积分（天数计算 / 只发一次 / 自动完成要等宽限期、入职标记满 3 天 /
 * 计划在积分上线日之前结束的不发积分）、加急投递（余额校验、状态校验、先锁账号再锁报名、员工端排序标记）。
 */
class KgPointsServiceTest
{
    private final KgJdbc db = mock(KgJdbc.class);

    private final ISysConfigService configs = mock(ISysConfigService.class);

    private final KgInviteService invites = mock(KgInviteService.class);

    private final StaffSupport support = mock(StaffSupport.class);

    private final IKgMessageService messages = mock(IKgMessageService.class);

    private final KgPointsService service = new KgPointsService();

    private final StaffSupport.Ctx ctx = new StaffSupport.Ctx();

    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void setup()
    {
        KgPointLedger ledger = new KgPointLedger();
        ReflectionTestUtils.setField(ledger, "db", db);
        ReflectionTestUtils.setField(ledger, "configs", configs);
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "ledger", ledger);
        ReflectionTestUtils.setField(service, "invites", invites);
        ReflectionTestUtils.setField(service, "membership", new KgMemberService(db));
        ReflectionTestUtils.setField(service, "support", support);
        ReflectionTestUtils.setField(service, "messages", messages);
        ReflectionTestUtils.setField(service, "self", service);
        ctx.storeId = 102L;
        ctx.storeName = "平潭袋鼠小院";
        ctx.role = "manager";
        ctx.me = new AppLoginUser();
        ctx.me.setUserId(1L);
        ctx.me.setNickname("黑子");
        when(db.list(contains("from kg_member_level"), anyMap())).thenReturn(Arrays.asList(
                p("levelId", 1L, "level", 0, "levelName", "幼崽鼠", "minPoints", 0L),
                p("levelId", 2L, "level", 1, "levelName", "小袋鼠", "minPoints", 100L),
                p("levelId", 3L, "level", 2, "levelName", "袋鼠", "minPoints", 300L)));
    }

    private void points(long earned, long spent)
    {
        when(db.one(contains(" earned, "), anyMap())).thenReturn(p("earned", earned, "spent", spent));
    }

    // ---------------------------------------------------------------- 等级与可用积分

    @Test
    @SuppressWarnings("unchecked")
    void levelFollowsEarnedPointsWhileBalanceIsWhatIsLeft()
    {
        points(350, 300);
        when(db.count(contains("select count(*) from kg_point_entry"), anyMap())).thenReturn(3L);
        when(db.list(contains("order by id desc limit"), anyMap())).thenReturn(Collections.singletonList(
                p("delta", -50L, "reason", "加急投递：前台换工", "bizType", "urgent", "createTime", "2026-10-10 10:00:00")));
        Map<String, Object> m = service.summary(7L, 1);
        assertEquals(50L, m.get("points"), "points 表示可用积分");
        assertEquals(50L, m.get("balance"));
        assertEquals(350L, m.get("earned"));
        assertEquals(300L, m.get("spent"));
        assertEquals("袋鼠", m.get("levelName"), "等级按累计获得 350 算，花掉 300 也不降级");
        List<Map<String, Object>> records = (List<Map<String, Object>>) m.get("records");
        assertEquals("加急投递", records.get(0).get("kind"));
        assertEquals("-50", records.get(0).get("pts"));
        assertEquals(50, ((Map<String, Object>) m.get("rules")).get("urgentCost"));
        assertTrue(String.valueOf(m.get("note")).contains("每天得 10 积分") && String.valueOf(m.get("note")).contains("花 50 积分加急投递"), String.valueOf(m.get("note")));
        assertEquals(4, ((List<?>) m.get("ways")).size());
    }

    // ---------------------------------------------------------------- 换工完成

    /** 和 KgJdbc 读出来的 datetime 一样的格式 */
    private static String dt(LocalDateTime t)
    {
        return t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private Map<String, Object> volunteerApp(LocalDate arrival, int stayDays)
    {
        Map<String, Object> a = new HashMap<String, Object>(p("appId", 9L, "appUserId", 7L, "storeId", 102L, "jobId", 3L, "status", "onboarded", "delFlag", "0",
                "arrivalDate", arrival.toString(), "stayDays", stayDays, "onboardTime", dt(LocalDateTime.now().minusDays(4))));
        a.put("volunteerDoneTime", null);
        when(db.one(contains("from kg_application where app_id = :a for update"), anyMap())).thenReturn(a);
        when(db.one(contains("select job_type, title from kg_job"), anyMap())).thenReturn(p("jobType", "volunteer", "title", "前台换工"));
        when(db.update(contains("set volunteer_done_time = now()"), anyMap())).thenReturn(1);
        when(db.update(startsWith("insert ignore into kg_point_entry"), anyMap())).thenReturn(1);
        return a;
    }

    @Test
    @SuppressWarnings("unchecked")
    void storeConfirmAwardsActualDaysTimesPerDayOnceAndRewardsInviter()
    {
        when(configs.selectConfigByKey(KgPointLedger.KEY_VOLUNTEER_PER_DAY)).thenReturn("12");
        volunteerApp(today.minusDays(9), 30);
        Map<String, Object> r = service.finishVolunteer(ctx, 9L, 8);
        assertEquals(96, r.get("points"));
        verify(db).update(contains("set volunteer_done_time = now()"), argThat(m -> Integer.valueOf(8).equals(((Map<String, Object>) m).get("d"))
                && "staff:1".equals(((Map<String, Object>) m).get("by"))));
        verify(db).update(startsWith("insert ignore into kg_point_entry"), argThat(m -> Integer.valueOf(96).equals(((Map<String, Object>) m).get("d"))
                && "volunteer".equals(((Map<String, Object>) m).get("t")) && Long.valueOf(9).equals(((Map<String, Object>) m).get("b"))
                && Long.valueOf(7).equals(((Map<String, Object>) m).get("u"))));
        // 2026-10-16 内测反馈 2.0：完成消息请 TA 评价这家店，点开直接进写评价页
        verify(messages).send(eq(7L), eq("user"), eq("system"), eq("换工换宿已完成"), eq("换工换宿已完成，获得 96 积分。说说这家店怎么样？"), eq("application"), eq(9L),
                eq("/pages/profile/review?bizType=application&bizId=9"));
        verify(invites).rewardInviter(7L, "volunteer", 102L, 1L);
    }

    @Test
    void autoCompletionSendsTheSameReviewInvitation()
    {
        when(configs.selectConfigByKey(KgPointLedger.KEY_VOLUNTEER_PER_DAY)).thenReturn("10");
        volunteerApp(today.minusDays(40), 30);
        assertTrue(service.autoFinishVolunteer(9L));
        verify(messages).send(eq(7L), eq("user"), eq("system"), eq("换工换宿已完成"), eq("换工换宿已完成，获得 300 积分。说说这家店怎么样？"), eq("application"), eq(9L),
                eq("/pages/profile/review?bizType=application&bizId=9"));
    }

    @Test
    void alreadyReviewedStintIsNotAskedAgain()
    {
        when(configs.selectConfigByKey(KgPointLedger.KEY_VOLUNTEER_PER_DAY)).thenReturn("12");
        volunteerApp(today.minusDays(9), 30);
        when(db.count(startsWith("select count(*) from kg_review where biz_type = 'application' and biz_id = :a and app_user_id = :u"), eq(p("a", 9L, "u", 7L)))).thenReturn(1L);
        service.finishVolunteer(ctx, 9L, 8);
        verify(messages).send(eq(7L), eq("user"), eq("system"), eq("换工换宿已完成"), eq("换工换宿已完成，获得 96 积分。"), eq("application"), eq(9L), eq("/pages/profile/index?s=3"));
    }

    @Test
    void pausedVolunteerPointsStillSendTheCompletionWithoutPoints()
    {
        when(configs.selectConfigByKey(KgPointLedger.KEY_VOLUNTEER_PER_DAY)).thenReturn("0");
        volunteerApp(today.minusDays(9), 30);
        assertEquals(0, service.finishVolunteer(ctx, 9L, 8).get("points"));
        verify(messages).send(eq(7L), eq("user"), eq("system"), eq("换工换宿已完成"), eq("换工换宿已完成。说说这家店怎么样？"), eq("application"), eq(9L),
                eq("/pages/profile/review?bizType=application&bizId=9"));
    }

    @Test
    void completionTextVariants()
    {
        assertEquals("换工换宿已完成，获得 5 积分。说说这家店怎么样？", KgPointsService.completionText(5, false));
        assertEquals("换工换宿已完成。说说这家店怎么样？", KgPointsService.completionText(0, false));
        assertEquals("换工换宿已完成，获得 5 积分。", KgPointsService.completionText(5, true));
        assertEquals("/pages/profile/review?bizType=application&bizId=12", KgPointsService.reviewRoute(12L));
    }

    @Test
    void daysCannotExceedPlannedOrElapsedDaysAndDefaultToTheMax()
    {
        volunteerApp(today.minusDays(9), 30);
        ServiceException e = assertThrows(ServiceException.class, () -> service.finishVolunteer(ctx, 9L, 11));
        assertEquals(Integer.valueOf(422), e.getCode());
        assertTrue(e.getMessage().contains("1–10"), e.getMessage());
        assertThrows(ServiceException.class, () -> service.finishVolunteer(ctx, 9L, 0));
        verify(db, never()).update(contains("set volunteer_done_time"), anyMap());
        assertEquals(10, service.finishVolunteer(ctx, 9L, null).get("days"), "不填天数 = 到今天为止的天数（不超过报名天数）");
        assertEquals(5, KgPointsService.maxDays(today.minusDays(9).toString(), 5, today), "不超过报名天数");
        assertEquals(180, KgPointsService.maxDays(today.minusDays(400).toString(), 365, today), "最多 180 天");
        assertEquals(0, KgPointsService.maxDays(today.plusDays(1).toString(), 5, today), "还没到岗不能确认");
    }

    @Test
    void onlyThisStoresOtherManagersCanConfirmAnOnboardedVolunteerStintOnce()
    {
        Map<String, Object> a = volunteerApp(today.minusDays(3), 10);
        a.put("appUserId", 1L);
        assertEquals(Integer.valueOf(403), assertThrows(ServiceException.class, () -> service.finishVolunteer(ctx, 9L, 2)).getCode(), "不能确认自己的");
        a.put("appUserId", 7L);
        a.put("storeId", 999L);
        assertEquals(Integer.valueOf(404), assertThrows(ServiceException.class, () -> service.finishVolunteer(ctx, 9L, 2)).getCode(), "别家门店的报名");
        a.put("storeId", 102L);
        a.put("status", "contract_signed");
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class, () -> service.finishVolunteer(ctx, 9L, 2)).getCode(), "还没入职");
        a.put("status", "onboarded");
        a.put("volunteerDoneTime", "2026-10-09 10:00:00");
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class, () -> service.finishVolunteer(ctx, 9L, 2)).getCode(), "已经完成过");
        a.put("volunteerDoneTime", null);
        when(db.one(contains("select job_type, title from kg_job"), anyMap())).thenReturn(p("jobType", "parttime", "title", "兼职"));
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class, () -> service.finishVolunteer(ctx, 9L, 2)).getCode(), "不是换工岗位");
        verify(db, never()).update(anyString(), anyMap());
    }

    @Test
    void concurrentCompletionLosesTheConditionalUpdateAndAwardsNothing()
    {
        volunteerApp(today.minusDays(3), 10);
        when(db.update(contains("set volunteer_done_time = now()"), anyMap())).thenReturn(0);
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class, () -> service.finishVolunteer(ctx, 9L, 2)).getCode());
        verify(db, never()).update(startsWith("insert ignore into kg_point_entry"), anyMap());
        verifyNoInteractions(messages);
        verify(invites, never()).rewardInviter(any(), any(), any(), any());
    }

    @Test
    void alreadyAwardedEntryDoesNotNotifyAgain()
    {
        volunteerApp(today.minusDays(3), 10);
        when(db.update(startsWith("insert ignore into kg_point_entry"), anyMap())).thenReturn(0);
        assertEquals(0, service.finishVolunteer(ctx, 9L, 2).get("points"));
        verifyNoInteractions(messages);
    }

    @Test
    @SuppressWarnings("unchecked")
    void autoCompletionWaitsThreeDaysAfterThePlannedEndAndCapsAt180Days()
    {
        // 计划 30 天：最后一天是到岗日 + 29；再过 3 天（到岗日 + 32）才自动完成
        volunteerApp(today.minusDays(31), 30);
        assertFalse(service.autoFinishVolunteer(9L));
        verify(db, never()).update(anyString(), anyMap());
        volunteerApp(today.minusDays(32), 30);
        assertTrue(service.autoFinishVolunteer(9L));
        verify(db).update(startsWith("insert ignore into kg_point_entry"), argThat(m -> Integer.valueOf(300).equals(((Map<String, Object>) m).get("d"))));
        verify(db).update(contains("set volunteer_done_time = now()"), argThat(m -> "system".equals(((Map<String, Object>) m).get("by"))));
        verify(invites).rewardInviter(7L, "volunteer", 102L, null);

        reset(db);
        setup();
        volunteerApp(today.minusDays(400), 365);
        assertTrue(service.autoFinishVolunteer(9L));
        verify(db).update(startsWith("insert ignore into kg_point_entry"), argThat(m -> Integer.valueOf(1800).equals(((Map<String, Object>) m).get("d"))));
    }

    @Test
    void scanSkipsQuietlyBeforeTheMigrationAndProcessesEachDueStint()
    {
        when(db.list(contains("a.volunteer_done_time is null"), anyMap())).thenThrow(new RuntimeException("Unknown column 'a.volunteer_done_time'"));
        assertDoesNotThrow(() -> service.scanVolunteerCompletions());
        reset(db);
        setup();
        when(db.list(contains("a.volunteer_done_time is null"), anyMap())).thenReturn(Collections.singletonList(p("appId", 9L)));
        volunteerApp(today.minusDays(40), 30);
        service.scanVolunteerCompletions();
        verify(db).update(contains("set volunteer_done_time = now()"), anyMap());
    }

    // ---------------------------------------------------------------- 积分上线前已结束的换工 / 入职标记满 3 天才自动完成

    private void launchedOn(LocalDate day)
    {
        when(db.one(contains("from kg_schema_version"), anyMap())).thenReturn(p("d", day.toString()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void storeConfirmOfAStintThatEndedBeforeLaunchRecordsCompletionWithoutPoints()
    {
        // 上线 5 天前就结束了（到岗 20 天前、报名 10 天）：上线时还停在已签约，上线后才补标入职，门店现在确认
        launchedOn(today.minusDays(5));
        volunteerApp(today.minusDays(20), 10);
        Map<String, Object> r = service.finishVolunteer(ctx, 9L, 8);
        assertEquals(0, r.get("points"));
        assertEquals(8, r.get("days"));
        assertEquals("已确认完成。这段换工在积分功能上线前已结束，不计积分", r.get("message"));
        verify(db).update(contains("set volunteer_done_time = now()"), argThat(m -> Integer.valueOf(0).equals(((Map<String, Object>) m).get("d"))
                && "staff:1".equals(((Map<String, Object>) m).get("by"))));
        verify(db, never()).update(startsWith("insert ignore into kg_point_entry"), anyMap());
        verifyNoInteractions(messages);
        verify(invites, never()).rewardInviter(any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void autoCompletionOfAStintThatEndedBeforeLaunchAwardsNothing()
    {
        launchedOn(today.minusDays(5));
        volunteerApp(today.minusDays(40), 30);
        assertTrue(service.autoFinishVolunteer(9L), "照样记完成，免得每半小时扫一遍");
        verify(db).update(contains("set volunteer_done_time = now()"), argThat(m -> Integer.valueOf(0).equals(((Map<String, Object>) m).get("d"))
                && "system".equals(((Map<String, Object>) m).get("by"))));
        verify(db, never()).update(startsWith("insert ignore into kg_point_entry"), anyMap());
        verifyNoInteractions(messages);
        verify(invites, never()).rewardInviter(any(), any(), any(), any());
    }

    @Test
    void launchBoundaryMatchesTheV37MigrationRule()
    {
        LocalDate launch = LocalDate.of(2026, 10, 11);
        assertTrue(KgPointsService.endedBeforeLaunch("2026-10-01", 10, launch), "最后一天 10-10（上线前一天）：不计积分");
        assertFalse(KgPointsService.endedBeforeLaunch("2026-10-02", 10, launch), "最后一天就是上线当天：照常发");
        assertFalse(KgPointsService.endedBeforeLaunch("2026-10-20", 3, launch));
        assertFalse(KgPointsService.endedBeforeLaunch("2026-10-01", 10, null), "账本里没有 V37：不限制");
        assertFalse(KgPointsService.endedBeforeLaunch(null, 10, launch));
    }

    @Test
    @SuppressWarnings("unchecked")
    void withoutTheV37LedgerRowThereIsNoRestrictionAndTheLaunchDayIsCachedOnceFound()
    {
        volunteerApp(today.minusDays(40), 30);
        assertTrue(service.autoFinishVolunteer(9L));
        verify(db).update(startsWith("insert ignore into kg_point_entry"), argThat(m -> Integer.valueOf(300).equals(((Map<String, Object>) m).get("d"))));
        // 查到之后缓存：之后再完成也不再查
        launchedOn(today.minusDays(100));
        service.autoFinishVolunteer(9L);
        service.autoFinishVolunteer(9L);
        verify(db, times(2)).one(contains("select date(applied_time) d from kg_schema_version where version = :v"), eq(p("v", 37)));
    }

    @Test
    void autoCompletionAlsoWaitsThreeDaysAfterTheOnboardingMark()
    {
        // 计划早就结束（到岗 60 天前、报名 30 天），门店前天才补标入职：先不自动完成，留时间给门店填实际天数
        Map<String, Object> a = volunteerApp(today.minusDays(60), 30);
        a.put("onboardTime", dt(LocalDateTime.now().minusDays(2)));
        assertFalse(service.autoFinishVolunteer(9L));
        a.put("onboardTime", null);
        assertFalse(service.autoFinishVolunteer(9L), "没有入职时间的不自动完成（门店可以手动确认）");
        verify(db, never()).update(anyString(), anyMap());
        a.put("onboardTime", dt(LocalDateTime.now().minusDays(3).minusMinutes(1)));
        assertTrue(service.autoFinishVolunteer(9L));
    }

    @Test
    void scanOnlyPicksStintsOnboardedAtLeastThreeDaysAgo()
    {
        service.scanVolunteerCompletions();
        verify(db).list(contains("and a.onboard_time is not null and a.onboard_time <= date_sub(now(), interval 3 day)"), anyMap());
    }

    @Test
    void staffDetailForAStintThatEndedBeforeLaunchPromisesNoPoints()
    {
        launchedOn(today.minusDays(5));
        Map<String, Object> a = new HashMap<String, Object>(p("status", "onboarded", "arrivalDate", today.minusDays(20).toString(), "stayDays", 10, "urgentFlag", "0"));
        when(db.one(contains("volunteer_done_by"), anyMap())).thenReturn(a);
        Map<String, Object> m = service.staffExtras(9L, "volunteer", true);
        assertEquals(true, m.get("canFinishVolunteer"));
        assertEquals(0, m.get("volunteerPerDay"), "确认弹窗就不会写「给 TA 发多少积分」");
        assertTrue(String.valueOf(m.get("volunteerFinishTip")).startsWith("这段换工在积分功能上线前就已结束，确认完成后不计积分"), String.valueOf(m.get("volunteerFinishTip")));
        // 确认之后：计分天数记 0，显示「不计积分」，不是「按 0 天计积分」
        a.put("volunteerDoneTime", "2026-10-12 10:00:00");
        a.put("volunteerDays", 0);
        a.put("volunteerDoneBy", "staff:1");
        assertEquals("门店已确认换工完成。这段换工在积分功能上线前已结束，不计积分", service.staffExtras(9L, "volunteer", true).get("volunteerDoneText"));
        assertEquals("这段换工在积分功能上线前已结束，不计积分", KgPointsService.doneText("migration", 0));
        assertTrue(KgPointsService.finishTip(false, 0).startsWith("换工积分暂时停发"), "运营暂停换工积分时不写「每天 0 积分」");
    }

    // ---------------------------------------------------------------- 加急投递

    private Map<String, Object> waitingApp()
    {
        when(db.one(contains("from kg_app_user where user_id = :u for update"), anyMap())).thenReturn(p("userId", 7L, "status", "0", "delFlag", "0"));
        Map<String, Object> a = new HashMap<String, Object>(p("appId", 9L, "appUserId", 7L, "storeId", 102L, "jobId", 3L, "realName", "尚可凯",
                "status", "submitted", "urgentFlag", "0", "delFlag", "0"));
        when(db.one(contains("from kg_application where app_id = :a for update"), anyMap())).thenReturn(a);
        when(db.one(contains("select title from kg_job"), anyMap())).thenReturn(p("title", "前台换工"));
        when(db.update(contains("set urgent_flag = '1'"), anyMap())).thenReturn(1);
        return a;
    }

    @Test
    @SuppressWarnings("unchecked")
    void urgentLocksAccountThenApplicationSpendsAndMarks()
    {
        waitingApp();
        points(80, 0);
        Map<String, Object> r = service.urgent(7L, 9L);
        assertEquals(30L, r.get("balance"));
        InOrder order = inOrder(db, support);
        order.verify(db).one(contains("from kg_app_user where user_id = :u for update"), anyMap());
        order.verify(db).one(contains("from kg_application where app_id = :a for update"), anyMap());
        order.verify(db).update(startsWith("insert into kg_point_entry"), argThat(m -> Integer.valueOf(-50).equals(((Map<String, Object>) m).get("d"))
                && "urgent".equals(((Map<String, Object>) m).get("t")) && Long.valueOf(9).equals(((Map<String, Object>) m).get("b"))));
        order.verify(db).update(contains("set urgent_flag = '1', urgent_time = now()"), anyMap());
        order.verify(support).notifyStore(eq(102L), anyString(), eq("有一份加急报名"), contains("前台换工"), eq("application"), eq(9L), anyString());
    }

    @Test
    void urgentNeedsEnoughPoints()
    {
        waitingApp();
        points(30, 0);
        ServiceException e = assertThrows(ServiceException.class, () -> service.urgent(7L, 9L));
        assertEquals(Integer.valueOf(422), e.getCode());
        assertEquals("积分不够，还差 20 分", e.getMessage());
        points(400, 380);
        assertEquals("积分不够，还差 30 分", assertThrows(ServiceException.class, () -> service.urgent(7L, 9L)).getMessage(), "看的是可用积分，不是累计获得");
        verify(db, never()).update(anyString(), anyMap());
    }

    @Test
    void urgentOnlyWhileWaitingAndOnlyOnceAndOnlyOwnApplication()
    {
        Map<String, Object> a = waitingApp();
        points(500, 0);
        a.put("status", "review_passed");
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class, () -> service.urgent(7L, 9L)).getCode());
        a.put("status", "submitted");
        a.put("urgentFlag", "1");
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class, () -> service.urgent(7L, 9L)).getCode());
        a.put("urgentFlag", "0");
        a.put("appUserId", 8L);
        assertEquals(Integer.valueOf(404), assertThrows(ServiceException.class, () -> service.urgent(7L, 9L)).getCode());
        verify(db, never()).update(anyString(), anyMap());
        a.put("appUserId", 7L);
        when(db.update(startsWith("insert into kg_point_entry"), anyMap())).thenThrow(new DuplicateKeyException("uk_point_biz"));
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class, () -> service.urgent(7L, 9L)).getCode(), "并发第二次撞唯一键");
        verify(db, never()).update(contains("set urgent_flag = '1'"), anyMap());
    }

    @Test
    void urgentInfoTellsHowManyPointsAreMissing()
    {
        when(db.one(contains("select app_id, app_user_id, status, urgent_flag"), anyMap())).thenReturn(p("appId", 9L, "appUserId", 7L, "status", "submitted",
                "urgentFlag", "0", "delFlag", "0"));
        points(20, 0);
        Map<String, Object> m = service.urgentInfo(7L, 9L);
        assertEquals(true, m.get("canUrgent"));
        assertEquals(30L, m.get("shortBy"));
        assertEquals("加急投递（50 积分）", m.get("label"));
    }

    @Test
    void staffListMarksOnlyWaitingUrgentRows()
    {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(new HashMap<String, Object>(p("appId", 1L)));
        rows.add(new HashMap<String, Object>(p("appId", 2L)));
        when(db.list(contains("urgent_flag = '1' and status = :s"), anyMap())).thenReturn(Collections.singletonList(p("appId", 2L)));
        service.markUrgent(rows);
        assertEquals(false, rows.get(0).get("urgent"));
        assertEquals(true, rows.get(1).get("urgent"));
    }

    @Test
    void staffDetailOffersVolunteerConfirmationOnlyForOnboardedUnfinishedStints()
    {
        Map<String, Object> a = new HashMap<String, Object>(p("status", "onboarded", "arrivalDate", today.minusDays(4).toString(), "stayDays", 30, "urgentFlag", "0"));
        when(db.one(contains("volunteer_done_by"), anyMap())).thenReturn(a);
        Map<String, Object> m = service.staffExtras(9L, "volunteer", true);
        assertEquals(true, m.get("canFinishVolunteer"));
        assertEquals(5, m.get("volunteerMaxDays"));
        assertEquals(10, m.get("volunteerPerDay"));
        assertTrue(String.valueOf(m.get("volunteerFinishTip")).contains("系统按每天 10 积分发给 TA"), String.valueOf(m.get("volunteerFinishTip")));
        assertEquals(false, service.staffExtras(9L, "volunteer", false).get("canFinishVolunteer"), "普通员工不能确认");
        assertEquals("", service.staffExtras(9L, "volunteer", false).get("volunteerFinishTip"));
        assertEquals(false, service.staffExtras(9L, "parttime", true).get("canFinishVolunteer"));
        a.put("volunteerDoneTime", "2026-10-10 10:00:00");
        a.put("volunteerDays", 12);
        a.put("volunteerDoneBy", "system");
        m = service.staffExtras(9L, "volunteer", true);
        assertEquals(false, m.get("canFinishVolunteer"));
        assertEquals(true, m.get("volunteerDone"));
        assertTrue(String.valueOf(m.get("volunteerDoneText")).contains("12 天"));
    }
}
