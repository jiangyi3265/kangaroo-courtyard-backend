package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.invocation.Invocation;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;

/**
 * 关注门店：「我的关注」列表、门店在招岗位、关注 / 取消关注与计数、门店上新给关注者的站内信（KgJdbc 全部打桩，不连库）。
 */
class KgStoreFollowServiceTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final KgHomeService home = mock(KgHomeService.class);
    private final StaffSupport support = mock(StaffSupport.class);
    private final KgStoreFollowService follows = new KgStoreFollowService();
    private final KgUserHubService hub = new KgUserHubService();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(follows, "db", db);
        ReflectionTestUtils.setField(follows, "home", home);
        ReflectionTestUtils.setField(hub, "db", db);
        ReflectionTestUtils.setField(hub, "support", support);
        ReflectionTestUtils.setField(hub, "home", home);
        KgCityService cities = mock(KgCityService.class);
        when(cities.businessCity(anyString())).thenAnswer(inv -> inv.getArgument(0));
        ReflectionTestUtils.setField(hub, "cityService", cities);
        when(db.list(anyString(), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        // KgJdbc.one 查不到返回 null；Mockito 对 Map 默认给空 Map，这里显式说明「默认查不到」（没有通知偏好 = 开启）
        when(db.one(anyString(), anyMap())).thenReturn(null);
        when(home.jobCard(anyMap())).thenAnswer(inv -> {
            Map<String, Object> j = inv.getArgument(0);
            return new HashMap<String, Object>(p("jobId", j.get("jobId"), "title", j.get("title")));
        });
    }

    private static Map<String, Object> row(Object... kv)
    {
        return new HashMap<String, Object>(p(kv));
    }

    // ---------------------------------------------------------------- 「我的关注」

    @Test
    void followListShowsOpenJobsOnlyForActiveStoresAndUsesFollowRows()
    {
        when(db.list(contains("from kg_job_subscribe s left join kg_store st"), anyMap())).thenReturn(Arrays.asList(
                row("subId", 3L, "storeId", 102L, "storeName", "平潭袋鼠小院", "city", "平潭", "status", "0", "delFlag", "0", "openJobs", 3L, "onlineJobs", 1L,
                        "freq", "instant", "createTime", "2026-10-08 10:00:00"),
                row("subId", 2L, "storeId", 103L, "storeName", "大理小院", "city", "大理", "status", "1", "delFlag", "0", "openJobs", 5L, "onlineJobs", 0L,
                        "freq", "daily", "createTime", "2026-10-01 09:00:00"),
                row("subId", 1L, "storeId", 104L, "storeName", null, "status", null, "delFlag", null, "openJobs", 0L, "onlineJobs", 0L)));
        Map<String, Object> out = follows.follows(9L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("rows");
        assertEquals(3, rows.size());
        assertEquals("在招 3 个岗位（含线上任务 1 个）", rows.get(0).get("jobsText"));
        assertEquals(true, rows.get(0).get("active"));
        assertEquals("2026-10-08", rows.get(0).get("followTime"));
        assertEquals("有新岗位马上通知", rows.get(0).get("freqText"));
        // 门店停用：不报在招数，提示暂停营业，但仍列出来方便取消关注
        assertEquals(false, rows.get(1).get("active"));
        assertEquals(0L, rows.get(1).get("openJobs"));
        assertTrue(String.valueOf(rows.get(1).get("jobsText")).contains("暂停营业"));
        assertEquals("每天 9:00 汇总通知", rows.get(1).get("freqText"));
        assertEquals("门店已下线", rows.get(2).get("storeName"));
        assertEquals(KgStoreFollowService.FOLLOW_MAX, out.get("max"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map> ps = ArgumentCaptor.forClass(Map.class);
        verify(db).list(sql.capture(), ps.capture());
        assertTrue(sql.getValue().contains(KgStoreFollowService.followRow("s.")), sql.getValue());
        assertTrue(sql.getValue().contains("j.status = 'published'"), sql.getValue());
        assertEquals(9L, ps.getValue().get("u"));
    }

    @Test
    void storeJobsListsPublishedJobsAndFollowingFlag()
    {
        when(db.one(contains("from kg_store where store_id"), anyMap())).thenReturn(row("storeId", 102L, "storeName", "平潭袋鼠小院", "city", "平潭",
                "status", "0", "delFlag", "0", "safeCert", "0", "rating", 4.8, "reviewCount", 12L));
        when(db.list(contains("from kg_job j join kg_store s"), anyMap())).thenReturn(Arrays.asList(row("jobId", 7L, "title", "咖啡师"), row("jobId", 8L, "title", "前台")));
        when(db.count(contains("from kg_job_subscribe"), anyMap())).thenReturn(1L);
        Map<String, Object> out = follows.storeJobs(9L, 102L);
        assertEquals(true, out.get("following"));
        assertEquals(2, out.get("total"));
        assertEquals(true, ((Map<?, ?>) out.get("store")).get("safe"));
        verify(home).markFavorites(eq(9L), anyList());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db).count(sql.capture(), anyMap());
        assertTrue(sql.getValue().contains(KgStoreFollowService.followRow("")), "已关注只认关注行：" + sql.getValue());
    }

    @Test
    void closedStoreIsNotFoundForGuestsAndPeopleWhoDoNotFollowIt()
    {
        // 之前门店删除 / 停用后谁来问都照样返回门店名，游客挨个试门店编号就能拿到下线门店的名字
        when(db.one(contains("from kg_store where store_id"), anyMap())).thenReturn(row("storeId", 102L, "storeName", "平潭袋鼠小院", "status", "0", "delFlag", "2"));
        ServiceException e = assertThrows(ServiceException.class, () -> follows.storeJobs(null, 102L));
        assertEquals(Integer.valueOf(404), e.getCode());
        assertEquals("门店不存在", e.getMessage());
        // 登录了但没关注这家店（关注行计数默认 0）
        assertEquals(Integer.valueOf(404), assertThrows(ServiceException.class, () -> follows.storeJobs(9L, 102L)).getCode());
        // 停用（status = 1、没删除）也一样
        when(db.one(contains("from kg_store where store_id"), anyMap())).thenReturn(row("storeId", 103L, "storeName", "大理小院", "status", "1", "delFlag", "0"));
        assertEquals(Integer.valueOf(404), assertThrows(ServiceException.class, () -> follows.storeJobs(null, 103L)).getCode());
        assertEquals(Integer.valueOf(404), assertThrows(ServiceException.class, () -> follows.storeJobs(9L, 103L)).getCode());
        verify(db, never()).list(contains("from kg_job j"), anyMap());
    }

    @Test
    void closedStoreStillShowsItsNameToFollowersSoTheyCanUnfollow()
    {
        when(db.one(contains("from kg_store where store_id"), anyMap())).thenReturn(row("storeId", 102L, "storeName", "平潭袋鼠小院", "city", "平潭",
                "intro", "海边小院", "status", "0", "delFlag", "2"));
        when(db.count(contains("from kg_job_subscribe"), anyMap())).thenReturn(1L);
        Map<String, Object> out = follows.storeJobs(9L, 102L);
        Map<?, ?> store = (Map<?, ?>) out.get("store");
        assertEquals(false, store.get("active"));
        assertEquals("平潭袋鼠小院", store.get("storeName"));
        assertNull(store.get("city"), "下线门店只给门店名，不给城市 / 介绍");
        assertNull(store.get("intro"));
        assertEquals(true, out.get("following"));
        assertEquals(0, out.get("total"));
        verify(db, never()).list(contains("from kg_job j"), anyMap());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db).count(sql.capture(), argThat(m -> Long.valueOf(9L).equals(m.get("u")) && Long.valueOf(102L).equals(m.get("s"))));
        assertTrue(sql.getValue().contains(KgStoreFollowService.followRow("")), "只认关注行：" + sql.getValue());
    }

    @Test
    void unknownStoreIs404()
    {
        assertEquals(Integer.valueOf(404), assertThrows(ServiceException.class, () -> follows.storeJobs(9L, 404L)).getCode());
    }

    // ---------------------------------------------------------------- 关注 / 取消关注

    @Test
    void unfollowDeletesOnlyFollowRowsAndWorksAfterStoreIsGone()
    {
        Map<String, Object> out = hub.followStore(9L, 102L, false);
        assertEquals(Boolean.FALSE, out.get("following"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db).update(sql.capture(), argThat(m -> Long.valueOf(9L).equals(m.get("u")) && Long.valueOf(102L).equals(m.get("s"))));
        assertTrue(sql.getValue().startsWith("delete from kg_job_subscribe where app_user_id = :u and store_id = :s and "), sql.getValue());
        assertTrue(sql.getValue().contains(KgStoreFollowService.followRow("")), "按城市 / 类型的订阅不能被取消关注顺带删掉：" + sql.getValue());
        // 之前取消关注先查门店，门店删了就报「门店不存在」
        verify(db, never()).must(anyString(), anyMap(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void followCountsOnlyFollowRowsSoSubscriptionsDoNotBlockIt()
    {
        when(db.must(contains("from kg_store"), anyMap(), anyString())).thenReturn(row("storeId", 102L, "storeName", "平潭袋鼠小院"));
        when(db.count(anyString(), anyMap())).thenReturn(9L);
        Map<String, Object> out = hub.followStore(9L, 102L, true);
        assertEquals(Boolean.TRUE, out.get("following"));
        assertTrue(String.valueOf(out.get("message")).contains("平潭袋鼠小院"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db).count(sql.capture(), anyMap());
        assertTrue(sql.getValue().contains(KgStoreFollowService.followRow("")), sql.getValue());
        ArgumentCaptor<Map> cols = ArgumentCaptor.forClass(Map.class);
        verify(db).insert(eq("kg_job_subscribe"), cols.capture());
        assertEquals("", cols.getValue().get("cities"));
        assertEquals("", cols.getValue().get("job_types"));
        assertEquals(102L, cols.getValue().get("store_id"));
    }

    @Test
    void fiftyFollowsIsTheLimit()
    {
        when(db.must(contains("from kg_store"), anyMap(), anyString())).thenReturn(row("storeId", 102L, "storeName", "平潭袋鼠小院"));
        when(db.count(anyString(), anyMap())).thenReturn((long) KgStoreFollowService.FOLLOW_MAX);
        ServiceException e = assertThrows(ServiceException.class, () -> hub.followStore(9L, 102L, true));
        assertEquals(Integer.valueOf(409), e.getCode());
        assertTrue(e.getMessage().contains("我的关注"), e.getMessage());
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    void alreadyFollowingIsIdempotent()
    {
        when(db.must(contains("from kg_store"), anyMap(), anyString())).thenReturn(row("storeId", 102L, "storeName", "平潭袋鼠小院"));
        when(db.one(contains("select sub_id from kg_job_subscribe"), anyMap())).thenReturn(row("subId", 5L));
        assertEquals(Boolean.TRUE, hub.followStore(9L, 102L, true).get("following"));
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    void subscriptionLimitNoLongerCountsFollows()
    {
        when(db.count(anyString(), anyMap())).thenReturn(3L);
        hub.saveSubscription(9L, null, new HashMap<String, Object>(p("cities", Collections.singletonList("大理"))));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db).count(sql.capture(), anyMap());
        assertTrue(sql.getValue().contains("not (" + KgStoreFollowService.followRow("") + ")"), sql.getValue());
        // 只带门店的订阅（岗位页「这家店缺人了提醒我」）就是关注，按关注计数
        reset(db);
        when(db.one(anyString(), anyMap())).thenReturn(null);
        when(db.one(contains("from kg_store where store_id = :s and del_flag = '0'"), anyMap())).thenReturn(row("storeId", 102L));
        when(db.count(anyString(), anyMap())).thenReturn(3L);
        hub.saveSubscription(9L, null, new HashMap<String, Object>(p("storeId", 102L)));
        verify(db).count(sql.capture(), anyMap());
        assertFalse(sql.getValue().contains("not ("), sql.getValue());
        assertTrue(sql.getValue().contains(KgStoreFollowService.followRow("")), sql.getValue());
    }

    // ---------------------------------------------------------------- 修改订阅（PUT /app/job-subscriptions/{id}）不能绕过名额

    private static final String OLD_ROW = "select sub_id, store_id, cities, job_types, keyword from kg_job_subscribe where sub_id = :s and app_user_id = :u";

    private static Map<String, Object> followRowOf(long subId, long storeId)
    {
        return row("subId", subId, "storeId", storeId, "cities", "", "jobTypes", "", "keyword", "");
    }

    @Test
    void editingCannotTurnASubscriptionIntoAFollow()
    {
        // 之前把订阅改成只带门店（= 关注）就腾出一个订阅名额，再新增、再改，反复下去能加出成千上万条
        when(db.one(contains(OLD_ROW), anyMap())).thenReturn(row("subId", 5L, "storeId", null, "cities", "大理", "jobTypes", "", "keyword", ""));
        when(db.one(contains("from kg_store where store_id"), anyMap())).thenReturn(row("storeId", 102L));
        ServiceException e = assertThrows(ServiceException.class, () -> hub.saveSubscription(9L, 5L, new HashMap<String, Object>(p("storeId", 102L))));
        assertEquals(Integer.valueOf(422), e.getCode());
        assertTrue(e.getMessage().contains("不能改成只关注门店"), e.getMessage());
        verify(db, never()).updateRow(anyString(), anyString(), any(), anyMap());
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    void editingCannotTurnAFollowIntoASubscription()
    {
        when(db.one(contains(OLD_ROW), anyMap())).thenReturn(followRowOf(6L, 102L));
        List<Map<String, Object>> bodies = Arrays.asList(
                new HashMap<String, Object>(p("storeId", 102L, "cities", Collections.singletonList("大理"))),
                new HashMap<String, Object>(p("storeId", 102L, "jobTypes", Collections.singletonList("volunteer"))),
                new HashMap<String, Object>(p("storeId", 102L, "keyword", "咖啡")),
                new HashMap<String, Object>(p("cities", Collections.singletonList("大理"))));
        for (Map<String, Object> b : bodies)
        {
            ServiceException e = assertThrows(ServiceException.class, () -> hub.saveSubscription(9L, 6L, b), String.valueOf(b));
            assertEquals(Integer.valueOf(422), e.getCode(), String.valueOf(b));
            assertTrue(e.getMessage().contains("另外新增一条"), e.getMessage());
        }
        verify(db, never()).updateRow(anyString(), anyString(), any(), anyMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    void editingAFollowCanStillChangeItsFrequencyEvenAfterTheStoreIsGone()
    {
        // 岗位列表「管理已有订阅」改推送频率：原字段原样带回，只改 freq。门店后来被删了（kg_store 查不到）也照样能改
        when(db.one(contains(OLD_ROW), anyMap())).thenReturn(followRowOf(6L, 102L));
        Map<String, Object> out = hub.saveSubscription(9L, 6L, new HashMap<String, Object>(p("cities", new ArrayList<String>(), "jobTypes", new ArrayList<String>(),
                "keyword", "", "storeId", 102L, "minMatch", 60, "freq", "daily")));
        assertEquals(6L, out.get("subId"));
        ArgumentCaptor<Map> cols = ArgumentCaptor.forClass(Map.class);
        verify(db).updateRow(eq("kg_job_subscribe"), eq("sub_id"), eq(6L), cols.capture());
        assertEquals("daily", cols.getValue().get("freq"));
        assertEquals(102L, cols.getValue().get("store_id"));
        verify(db, never()).one(contains("from kg_store"), anyMap());
        verify(db, never()).count(anyString(), anyMap());
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    void editingASubscriptionKeepsWorkingAsBefore()
    {
        when(db.one(contains(OLD_ROW), anyMap())).thenReturn(row("subId", 5L, "storeId", null, "cities", "大理", "jobTypes", "咖啡/茶饮", "keyword", ""));
        hub.saveSubscription(9L, 5L, new HashMap<String, Object>(p("cities", Arrays.asList("丽江", "大理"), "jobTypes", new ArrayList<String>(), "freq", "instant")));
        verify(db).updateRow(eq("kg_job_subscribe"), eq("sub_id"), eq(5L), anyMap());
        verify(db, never()).count(anyString(), anyMap());
    }

    @Test
    void editingSomeoneElsesOrAMissingSubscriptionIs404()
    {
        ServiceException e = assertThrows(ServiceException.class, () -> hub.saveSubscription(9L, 5L, new HashMap<String, Object>(p("cities", Collections.singletonList("丽江")))));
        assertEquals(Integer.valueOf(404), e.getCode());
        verify(db).one(contains(OLD_ROW), argThat(m -> Long.valueOf(5L).equals(m.get("s")) && Long.valueOf(9L).equals(m.get("u"))));
        verify(db, never()).updateRow(anyString(), anyString(), any(), anyMap());
    }

    @Test
    void storeMustExistAndNotBeDeleted()
    {
        // 之前门店编号不校验，随便填一个也能存。默认 kg_store 查不到 = 门店不存在或已删除
        ServiceException e = assertThrows(ServiceException.class, () -> hub.saveSubscription(9L, null, new HashMap<String, Object>(p("storeId", 999L))));
        assertEquals(Integer.valueOf(404), e.getCode());
        assertEquals("门店不存在", e.getMessage());
        verify(db).one(contains("from kg_store where store_id = :s and del_flag = '0'"), argThat(m -> Long.valueOf(999L).equals(m.get("s"))));
        verify(db, never()).insert(anyString(), anyMap());
        // 修改时把门店换成一家不存在的店，也一样拦下
        when(db.one(contains(OLD_ROW), anyMap())).thenReturn(row("subId", 5L, "storeId", 102L, "cities", "大理", "jobTypes", "", "keyword", ""));
        assertEquals(Integer.valueOf(404), assertThrows(ServiceException.class, () -> hub.saveSubscription(9L, 5L,
                new HashMap<String, Object>(p("storeId", 999L, "cities", Collections.singletonList("大理"))))).getCode());
        verify(db, never()).updateRow(anyString(), anyString(), any(), anyMap());
    }

    // ---------------------------------------------------------------- 并发：连点两下 / 网络重试只留一条

    private static final String LOCK = "select user_id from kg_app_user where user_id = :u for update";

    @Test
    void followLocksTheAccountBeforeLookingForAnExistingFollow() throws Exception
    {
        when(db.must(contains("from kg_store"), anyMap(), anyString())).thenReturn(row("storeId", 102L, "storeName", "平潭袋鼠小院"));
        hub.followStore(9L, 102L, true);
        // 第一条查询就是锁账号：可重复读下，锁之前不能有普通查询，否则看不到并发请求刚插入的那一条
        Invocation first = mockingDetails(db).getInvocations().iterator().next();
        assertEquals("one", first.getMethod().getName());
        assertEquals(LOCK, String.valueOf((Object) first.getArgument(0)));
        assertEquals(9L, ((Map<?, ?>) first.getArgument(1)).get("u"));
        InOrder order = inOrder(db);
        order.verify(db).one(eq(LOCK), anyMap());
        order.verify(db).must(contains("from kg_store"), anyMap(), anyString());
        order.verify(db).one(contains("select sub_id from kg_job_subscribe"), anyMap());
        order.verify(db).insert(eq("kg_job_subscribe"), anyMap());
        assertNotNull(KgUserHubService.class.getMethod("followStore", Long.class, Long.class, boolean.class).getAnnotation(Transactional.class),
                "关注要在事务里做，锁才能一直保持到插入完成");
    }

    @Test
    void doubleTapFollowLeavesOneRow()
    {
        when(db.must(contains("from kg_store"), anyMap(), anyString())).thenReturn(row("storeId", 102L, "storeName", "平潭袋鼠小院"));
        // 第二个请求要等第一个提交、放开锁才能往下走，这时能查到第一个刚插入的那一条
        when(db.one(contains("select sub_id from kg_job_subscribe"), anyMap())).thenReturn(null, row("subId", 31L));
        assertEquals(Boolean.TRUE, hub.followStore(9L, 102L, true).get("following"));
        assertEquals(Boolean.TRUE, hub.followStore(9L, 102L, true).get("following"));
        verify(db, times(2)).one(eq(LOCK), anyMap());
        verify(db, times(1)).insert(eq("kg_job_subscribe"), anyMap());
    }

    @Test
    void subscriptionWritesLockTheAccountFirstToo()
    {
        when(db.one(contains("from kg_store where store_id"), anyMap())).thenReturn(row("storeId", 102L));
        hub.saveSubscription(9L, null, new HashMap<String, Object>(p("storeId", 102L)));
        Invocation first = mockingDetails(db).getInvocations().iterator().next();
        assertEquals(LOCK, String.valueOf((Object) first.getArgument(0)));
        InOrder order = inOrder(db);
        order.verify(db).one(eq(LOCK), anyMap());
        order.verify(db).one(contains("select sub_id from kg_job_subscribe where app_user_id = :u and cities = :c"), anyMap());
        order.verify(db).insert(eq("kg_job_subscribe"), anyMap());
    }

    // ---------------------------------------------------------------- 门店上新推送

    private Map<String, Object> followSub()
    {
        return row("subId", 11L, "appUserId", 9L, "storeId", 102L, "cities", "", "jobTypes", "", "keyword", "", "freq", "instant",
                "lastNotifiedTime", "2026-10-09 08:00:00", "createTime", "2026-10-01 00:00:00");
    }

    @Test
    @SuppressWarnings("unchecked")
    void storeFollowTriggersFollowerMessageWithStoreNameAndJobLink()
    {
        when(db.list(startsWith("select * from kg_job_subscribe"), anyMap())).thenReturn(Collections.singletonList(followSub()));
        when(db.list(startsWith("select j.job_id, j.title, j.city from kg_job j"), anyMap())).thenReturn(Collections.singletonList(row("jobId", 77L, "title", "咖啡师", "city", "平潭")));
        when(db.one(contains("select store_name from kg_store"), anyMap())).thenReturn(row("storeName", "平潭袋鼠小院"));
        ReflectionTestUtils.invokeMethod(hub, "push", "instant");

        // 只按门店匹配：不拼城市 / 类型 / 关键词条件，新岗位只要是这家店发的就推
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map> ps = ArgumentCaptor.forClass(Map.class);
        verify(db, times(2)).list(sql.capture(), ps.capture());
        String jobSql = sql.getAllValues().get(1);
        assertTrue(jobSql.contains("j.store_id = :st"), jobSql);
        assertFalse(jobSql.contains("j.city like"), jobSql);
        assertFalse(jobSql.contains("j.job_type"), jobSql);
        assertFalse(jobSql.contains(":kw"), jobSql);
        assertEquals(102L, ps.getAllValues().get(1).get("st"));
        assertEquals("2026-10-09 08:00:00", ps.getAllValues().get(1).get("since"));

        verify(support).notifyUser(eq(9L), eq("system"), eq("你关注的门店有新岗位"), eq("你关注的「平潭袋鼠小院」发布了新岗位：咖啡师"),
                eq("job_subscribe"), eq(11L), eq("/pages/jobs/detail?jobId=77"));
        verify(db).update(contains("set last_notified_time = now()"), anyMap());
    }

    @Test
    void severalNewJobsLinkToTheStoresJobList()
    {
        when(db.list(startsWith("select * from kg_job_subscribe"), anyMap())).thenReturn(Collections.singletonList(followSub()));
        when(db.list(startsWith("select j.job_id, j.title, j.city from kg_job j"), anyMap())).thenReturn(Arrays.asList(
                row("jobId", 78L, "title", "摄影师", "city", "平潭"), row("jobId", 77L, "title", "咖啡师", "city", "平潭")));
        when(db.one(contains("select store_name from kg_store"), anyMap())).thenReturn(row("storeName", "平潭袋鼠小院"));
        ReflectionTestUtils.invokeMethod(hub, "push", "instant");
        verify(support).notifyUser(eq(9L), eq("system"), eq("你关注的门店有新岗位"), eq("你关注的「平潭袋鼠小院」发布了 2 个新岗位：摄影师 等"),
                eq("job_subscribe"), eq(11L), eq("/pages/profile/follows?storeId=102"));
    }

    @Test
    void ordinarySubscriptionKeepsItsOldWording()
    {
        Map<String, Object> sub = row("subId", 12L, "appUserId", 9L, "cities", "平潭", "jobTypes", "", "keyword", "", "createTime", "2026-10-01 00:00:00");
        when(db.list(startsWith("select * from kg_job_subscribe"), anyMap())).thenReturn(Collections.singletonList(sub));
        when(db.list(startsWith("select j.job_id, j.title, j.city from kg_job j"), anyMap())).thenReturn(Collections.singletonList(row("jobId", 77L, "title", "咖啡师", "city", "平潭")));
        ReflectionTestUtils.invokeMethod(hub, "push", "instant");
        verify(support).notifyUser(eq(9L), eq("system"), eq("有 1 个新机会符合你的订阅"), eq("咖啡师（平潭）"), eq("job_subscribe"), eq(12L), eq("/pages/jobs/detail?jobId=77"));
    }

    @Test
    void followerWhoTurnedOffNewJobAlertsGetsNothing()
    {
        when(db.list(startsWith("select * from kg_job_subscribe"), anyMap())).thenReturn(Collections.singletonList(followSub()));
        when(db.one(contains("from kg_notify_pref"), anyMap())).thenReturn(row("enabled", "0"));
        ReflectionTestUtils.invokeMethod(hub, "push", "instant");
        verifyNoInteractions(support);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pushPagesThroughAllSubscriptionsInsteadOfStoppingAtFiveHundred()
    {
        List<Map<String, Object>> first = new ArrayList<Map<String, Object>>();
        for (long i = 1; i <= KgUserHubService.PUSH_BATCH; i++)
        {
            first.add(row("subId", i, "appUserId", 9L, "storeId", 102L, "cities", "", "jobTypes", "", "keyword", "", "createTime", "2026-10-01 00:00:00"));
        }
        List<Map<String, Object>> second = Collections.singletonList(row("subId", 9001L, "appUserId", 9L, "storeId", 102L, "cities", "", "jobTypes", "", "keyword", "",
                "createTime", "2026-10-01 00:00:00"));
        when(db.list(startsWith("select * from kg_job_subscribe"), anyMap())).thenAnswer(inv -> {
            Map<String, Object> ps = inv.getArgument(1);
            return Long.valueOf(0L).equals(ps.get("after")) ? first : Long.valueOf(KgUserHubService.PUSH_BATCH).equals(ps.get("after")) ? second
                    : new ArrayList<Map<String, Object>>();
        });
        ReflectionTestUtils.invokeMethod(hub, "push", "instant");
        ArgumentCaptor<Map> ps = ArgumentCaptor.forClass(Map.class);
        verify(db, times(2)).list(startsWith("select * from kg_job_subscribe"), ps.capture());
        assertEquals(0L, ps.getAllValues().get(0).get("after"));
        assertEquals((long) KgUserHubService.PUSH_BATCH, ps.getAllValues().get(1).get("after"));
        // 每条订阅各跑一次岗位查询：500 + 1
        verify(db, times(KgUserHubService.PUSH_BATCH + 1)).list(startsWith("select j.job_id, j.title, j.city from kg_job j"), anyMap());
    }

    // ---------------------------------------------------------------- 文案 / 判定

    @Test
    void followRowDetection()
    {
        assertTrue(KgStoreFollowService.isFollowRow(row("storeId", 1L, "cities", "", "jobTypes", "", "keyword", "")));
        assertTrue(KgStoreFollowService.isFollowRow(row("storeId", 1L)));
        assertFalse(KgStoreFollowService.isFollowRow(row("storeId", 1L, "cities", "大理")));
        assertFalse(KgStoreFollowService.isFollowRow(row("storeId", 1L, "keyword", "咖啡")));
        assertFalse(KgStoreFollowService.isFollowRow(row("cities", "", "jobTypes", "")));
    }

    @Test
    void noticeFallsBackWhenStoreNameMissing()
    {
        String[] n = KgStoreFollowService.notice(5L, " ", Collections.singletonList(row("jobId", 1L, "title", "前台")));
        assertEquals("你关注的「门店」发布了新岗位：前台", n[1]);
    }
}
