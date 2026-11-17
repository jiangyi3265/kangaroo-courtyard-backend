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

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.stay.KgStayBrowseService;
import com.ruoyi.kangaroo.ext.user.KgCityService;

/**
 * 发布页改版（2026-10-17）：帖子「关联」小院 / 门店 / 岗位 / 活动（主人拍板，不关联别人的帖子）。
 * 不加列，存在 ref_type / ref_id；发布时校验对象存在且现在能选；信息流 / 详情按类型一页一次查齐「关联：XXX」；
 * 经验分享的 ref 仍是经历那条报名，关联自动指向报名的岗位（岗位下架退到门店）；关联选择页的搜索。
 * 2026-10-17 r2（V52）：关联单独存 link_type / link_id，经验分享也能自己选；读的时候先 link_* 再 ref_*；
 * 老版本小程序（只传 refType / refId）照常能发，找搭子 / 旅居攻略的 ref_* 照旧写给老版本程序看。
 * Real KgPostService bodies, Mock JDBC (no real SQL).
 */
class KgPostLinkTest
{
    static final String FEED_ROWS = "select p.* from kg_post p";

    final KgJdbc db = mock(KgJdbc.class);
    final KgCommunityGuard guard = mock(KgCommunityGuard.class);
    final KgCityService cities = mock(KgCityService.class);
    final KgPostService service = new KgPostService();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "guard", guard);
        ReflectionTestUtils.setField(service, "cities", cities);
        ReflectionTestUtils.setField(service, "activities", mock(KgActivityService.class));
        when(cities.businessCity(any())).thenAnswer(i -> i.getArgument(0));
        when(db.one(startsWith("select status, del_flag from kg_app_user"), anyMap())).thenReturn(p("status", "0", "delFlag", "0"));
        when(guard.check(any(String[].class))).thenReturn("pass");
        when(guard.initialStatus(any())).thenReturn("online");
        when(guard.mode()).thenReturn("mock");
        when(db.insert(eq("kg_post"), anyMap())).thenReturn(42L);
    }

    static Map<String, Object> row(Object... kv)
    {
        Map<String, Object> r = p("postId", 5L, "kind", "daily", "appUserId", 9L, "city", "大理", "title", "洱海日落", "status", "online");
        r.putAll(p(kv));
        return r;
    }

    Map<String, Object> daily(Object... kv)
    {
        Map<String, Object> b = p("kind", "daily", "title", "沙溪三天攻略", "content", "古镇很安静", "city", "大理");
        b.putAll(p(kv));
        return b;
    }

    // ---------------------------------------------------------------- 名字 / 类型

    @Test
    void linkTypesFitTheOldRefColumns()
    {
        assertEquals(Arrays.asList("stay", "store", "job", "activity"), new java.util.ArrayList<>(KgPostService.LINK_LABEL.keySet()));
        assertEquals(Arrays.asList("小院", "门店", "岗位", "活动"), new java.util.ArrayList<>(KgPostService.LINK_LABEL.values()));
        for (String t : KgPostService.LINK_LABEL.keySet())
        {
            assertTrue(t.length() <= 16, "kg_post.ref_type 是 varchar(16)，不用加列：" + t);
        }
        assertEquals("旅居攻略", KgPostService.KIND_LABEL.get("daily"));
        assertTrue(KgPostService.KINDS.contains("daily"), "代码还是 daily，老帖子自动变成旅居攻略");
    }

    @Test
    void otherCategoryIsNotATopic()
    {
        assertEquals("", KgPostService.topicLabel(row("kind", "buddy", "category", "other"), null), "不再出现「其他｜」");
        assertEquals("拼饭", KgPostService.topicLabel(row("kind", "buddy", "category", "meal"), null));
        assertEquals("其他", KgPostService.CATEGORY_LABEL.get("other"), "找搭子列表的分类小标签照旧");
    }

    // ---------------------------------------------------------------- 发布校验

    @Test
    void publishStoresAPickableStayInTheRefColumns()
    {
        when(db.one(startsWith(KgPostService.LINK_STAY_SQL), anyMap())).thenReturn(p("id", 3L, "title", "平潭袋鼠小院", "cover", "/profile/a.jpg", "city", "平潭", "sub", "龙凤头"));
        when(db.list(startsWith(KgPostService.LINK_STAY_SQL), anyMap())).thenReturn(Collections.singletonList(p("id", 3L, "title", "平潭袋鼠小院", "cover", "/profile/a.jpg", "city", "平潭", "sub", "龙凤头")));
        Map<String, Object> res = service.publish(7L, daily("refType", "stay", "refId", 3L));
        verify(db).one(eq(KgPostService.LINK_STAY_SQL + "s.stay_id = :id and " + KgStayBrowseService.ONLINE), eq(p("id", 3L)));
        verify(db).insert(eq("kg_post"), argThat(r -> "stay".equals(r.get("ref_type")) && Long.valueOf(3L).equals(r.get("ref_id"))
                && "stay".equals(r.get("link_type")) && Long.valueOf(3L).equals(r.get("link_id"))));
        @SuppressWarnings("unchecked")
        Map<String, Object> link = (Map<String, Object>) res.get("link");
        assertNotNull(link, "发布成功页要显示「已关联到：XXX」");
        assertEquals("stay", link.get("type"));
        assertEquals(3L, link.get("id"));
        assertEquals("平潭袋鼠小院", link.get("title"));
        assertEquals("小院", link.get("label"));
        // 不能用我的报名 / 订单列表去校验公开对象
        verify(db, never()).list(startsWith("select a.app_id, j.title, j.city from kg_application"), anyMap());
    }

    @Test
    void offlineOrMissingTargetsAreRejectedAndNothingIsInserted()
    {
        for (String t : new String[] { "stay", "store", "job", "activity" })
        {
            ServiceException e = assertThrows(ServiceException.class, () -> service.publish(7L, daily("refType", t, "refId", 99L)));
            assertEquals(422, e.getCode());
            assertTrue(e.getMessage().contains(KgPostService.LINK_LABEL.get(t)), e.getMessage());
        }
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    void pickConditionsOnlyOfferLiveTargets()
    {
        service.pickableLink("store", 4L);
        service.pickableLink("job", 5L);
        service.pickableLink("activity", 6L);
        verify(db).one(eq(KgPostService.LINK_STORE_SQL + "st.store_id = :id and " + KgPostService.STORE_PICK), eq(p("id", 4L)));
        verify(db).one(eq(KgPostService.LINK_JOB_SQL + "j.job_id = :id and " + KgPostService.JOB_PICK), eq(p("id", 5L)));
        verify(db).one(eq(KgPostService.LINK_ACT_SQL + "a.activity_id = :id and " + KgPostService.ACT_PICK), eq(p("id", 6L)));
        assertTrue(KgPostService.STORE_PICK.contains("sj.status = 'published'"), "门店要有在招岗位");
        assertTrue(KgPostService.JOB_PICK.contains("j.status = 'published'") && KgPostService.JOB_PICK.contains("st.status = '0'"));
        assertTrue(KgPostService.ACT_PICK.contains("a.status in ('open','paused','closed')") && KgPostService.ACT_PICK.contains("> now()"), "结束的活动不能再选");
        assertNull(service.pickableLink("post", 1L), "不关联别人的帖子");
        assertNull(service.pickableLink("job", null));
    }

    @Test
    void legacyPrivateRefsAreStillCheckedAgainstMyOwnList()
    {
        // 老版本小程序还会传自己的报名 / 住宿订单：照旧只认我自己的
        ServiceException e = assertThrows(ServiceException.class, () -> service.publish(7L, daily("refType", "stay_order", "refId", 8L)));
        assertEquals(403, e.getCode());
        verify(db, never()).one(startsWith(KgPostService.LINK_STAY_SQL), anyMap());
    }

    @Test
    void diaryKeepsItsApplicationRefAndLinksToThatJob()
    {
        when(db.list(startsWith("select a.app_id, a.arrival_date"), anyMap())).thenReturn(Collections.singletonList(
                p("appId", 2L, "arrivalDate", "2026-02-01", "stayDays", 10L, "title", "咖啡店兼职", "city", "大理", "jobType", "parttime")));
        when(db.list(startsWith("select a.app_id, a.job_id, a.store_id from kg_application a"), anyMap())).thenReturn(Collections.singletonList(p("appId", 2L, "jobId", 70L, "storeId", 4L)));
        when(db.list(startsWith(KgPostService.LINK_JOB_SQL), anyMap())).thenReturn(Collections.singletonList(p("id", 70L, "title", "咖啡店兼职", "jobType", "parttime", "city", "大理", "sub", "洱海咖啡")));
        Map<String, Object> res = service.publish(7L, p("kind", "diary", "title", "兼职一个月", "content", "", "city", "大理", "refType", "application", "refId", 2L));
        verify(db).insert(eq("kg_post"), argThat(r -> "application".equals(r.get("ref_type")) && Long.valueOf(2L).equals(r.get("ref_id"))
                && !r.containsKey("link_type") && !r.containsKey("link_id")));
        @SuppressWarnings("unchecked")
        Map<String, Object> link = (Map<String, Object>) res.get("link");
        assertEquals("job", link.get("type"));
        assertEquals(70L, link.get("id"));
        assertEquals("线下兼职", link.get("label"), "岗位的小标签是岗位类型");
        verify(db).list(eq(KgPostService.LINK_JOB_SQL + "j.job_id in (:ids) and " + KgPostService.JOB_SHOWN), eq(p("ids", Collections.singletonList(70L))));
    }

    @Test
    void diaryFallsBackToTheStoreWhenTheJobIsGone()
    {
        when(db.list(startsWith(FEED_ROWS), anyMap())).thenReturn(Collections.singletonList(row("kind", "diary", "refType", "application", "refId", 2L)));
        when(db.list(startsWith("select a.app_id, a.job_id, a.store_id from kg_application a"), anyMap())).thenReturn(Collections.singletonList(p("appId", 2L, "jobId", 70L, "storeId", 4L)));
        when(db.list(startsWith(KgPostService.LINK_STORE_SQL), anyMap())).thenReturn(Collections.singletonList(p("id", 4L, "title", "洱海咖啡", "city", "大理")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) service.feed(7L, null, "大理", "new", 1, null, null).get("rows");
        @SuppressWarnings("unchecked")
        Map<String, Object> link = (Map<String, Object>) rows.get(0).get("link");
        assertEquals("store", link.get("type"));
        assertEquals("门店", link.get("label"));
        verify(db).list(eq(KgPostService.LINK_STORE_SQL + "st.store_id in (:ids) and " + KgPostService.STORE_SHOWN), eq(p("ids", Collections.singletonList(4L))));
    }

    // ---------------------------------------------------------------- 信息流 / 详情的「关联：XXX」

    @SuppressWarnings("unchecked")
    @Test
    void feedRowsCarryLinksLookedUpOncePerType()
    {
        when(db.list(startsWith(FEED_ROWS), anyMap())).thenReturn(Arrays.asList(
                row("postId", 1L, "refType", "stay", "refId", 3L),
                row("postId", 2L, "kind", "buddy", "refType", "activity", "refId", 8L),
                row("postId", 3L, "refType", "stay", "refId", 3L),
                row("postId", 4L, "refType", "job", "refId", 9L),
                row("postId", 5L, "refType", "stay_order", "refId", 11L),
                row("postId", 6L)));
        when(db.list(startsWith(KgPostService.LINK_STAY_SQL), anyMap())).thenReturn(Collections.singletonList(p("id", 3L, "title", "平潭袋鼠小院", "city", "平潭")));
        when(db.list(startsWith(KgPostService.LINK_ACT_SQL), anyMap())).thenReturn(Collections.singletonList(p("id", 8L, "title", "洱海骑行", "startTime", "2026-10-20 08:00:00")));
        // 岗位 9 已下架：查不到 → 不显示关联
        List<Map<String, Object>> rows = (List<Map<String, Object>>) service.feed(7L, null, "大理", "new", 1, null, null).get("rows");
        assertEquals("平潭袋鼠小院", ((Map<String, Object>) rows.get(0).get("link")).get("title"));
        Map<String, Object> act = (Map<String, Object>) rows.get(1).get("link");
        assertEquals("activity", act.get("type"));
        assertEquals("活动", act.get("label"));
        assertEquals("10月20日 08:00", act.get("sub"));
        assertEquals(3L, ((Map<String, Object>) rows.get(2).get("link")).get("id"));
        assertNull(rows.get(3).get("link"), "下线的不给点进去打不开的入口");
        assertNull(rows.get(4).get("link"), "自己的住宿订单不公开");
        assertNull(rows.get(5).get("link"));
        verify(db, times(1)).list(startsWith(KgPostService.LINK_STAY_SQL), anyMap());
        verify(db).list(eq(KgPostService.LINK_STAY_SQL + "s.stay_id in (:ids) and " + KgStayBrowseService.ONLINE), eq(p("ids", Collections.singletonList(3L))));
        verify(db).list(eq(KgPostService.LINK_ACT_SQL + "a.activity_id in (:ids) and " + KgPostService.ACT_SHOWN), eq(p("ids", Collections.singletonList(8L))));
        assertTrue(KgPostService.ACT_SHOWN.contains("'ended'"), "结束的活动详情还看得到，关联照常显示");
        verify(db, never()).list(startsWith(KgPostService.LINK_STORE_SQL), anyMap());
        verify(db, never()).list(startsWith("select a.app_id, a.job_id"), anyMap());
    }

    @Test
    void pagesWithoutLinksDoNoExtraQueries()
    {
        when(db.list(startsWith(FEED_ROWS), anyMap())).thenReturn(Arrays.asList(row("postId", 1L), row("postId", 2L, "kind", "buddy")));
        service.feed(7L, null, "大理", "new", 1, null, null);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db, atLeastOnce()).list(sql.capture(), anyMap());
        for (String s : sql.getAllValues())
        {
            assertFalse(s.startsWith(KgPostService.LINK_STAY_SQL) || s.startsWith(KgPostService.LINK_JOB_SQL) || s.startsWith(KgPostService.LINK_ACT_SQL)
                    || s.startsWith(KgPostService.LINK_STORE_SQL), s);
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void detailCarriesTheLink()
    {
        when(db.one(startsWith("select p.* from kg_post p where p.post_id"), anyMap())).thenReturn(row("refType", "job", "refId", 9L));
        when(db.list(startsWith(KgPostService.LINK_JOB_SQL), anyMap())).thenReturn(Collections.singletonList(p("id", 9L, "title", "前台换宿", "jobType", "volunteer")));
        Map<String, Object> d = service.detail(5L, null);
        Map<String, Object> link = (Map<String, Object>) d.get("link");
        assertEquals("前台换宿", link.get("title"));
        assertEquals("换宿旅行", link.get("label"));
    }

    // ---------------------------------------------------------------- 关联选择页的搜索

    @SuppressWarnings("unchecked")
    @Test
    void placeSearchUnionsStaysAndStoresSameCityFirst()
    {
        when(db.list(startsWith("select * from (select 'stay' kind"), anyMap())).thenReturn(Arrays.asList(
                p("kind", "stay", "id", 3L, "title", "平潭袋鼠小院", "city", "平潭", "sub", "龙凤头"),
                p("kind", "store", "id", 4L, "title", "洱海咖啡", "city", "大理")));
        Map<String, Object> res = service.linkables("place", " 100%_ ", "平潭", 2);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map> ps = ArgumentCaptor.forClass(Map.class);
        verify(db).list(sql.capture(), ps.capture());
        String s = sql.getValue();
        assertTrue(s.contains(KgStayBrowseService.ONLINE) && s.contains(KgPostService.STORE_PICK), s);
        assertTrue(s.contains(" union all "), s);
        assertTrue(s.contains("order by (x.city = :c) desc, (x.kind = 'stay') desc"), s);
        assertTrue(s.contains("s.stay_name like :kw") && s.contains("st.store_name like :kw"), s);
        assertEquals("%100\\%\\_%", ps.getValue().get("kw"), "通配符按字面找");
        assertEquals("平潭", ps.getValue().get("c"));
        assertEquals(20, ps.getValue().get("off"));
        assertEquals(21, ps.getValue().get("size"), "多取一条判断还有没有下一页");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) res.get("rows");
        assertEquals(Arrays.asList("stay", "store"), Arrays.asList(rows.get(0).get("type"), rows.get(1).get("type")));
        assertEquals(Arrays.asList("小院", "门店"), Arrays.asList(rows.get(0).get("label"), rows.get(1).get("label")));
        assertEquals(false, res.get("hasMore"));
        // 卡片只要几个字段
        assertEquals(new java.util.LinkedHashSet<>(Arrays.asList("type", "id", "title", "cover", "city", "label", "sub")), rows.get(0).keySet());
    }

    @SuppressWarnings("unchecked")
    @Test
    void jobAndActivitySearchUseThePickConditionsAndPage()
    {
        java.util.List<Map<String, Object>> many = new java.util.ArrayList<>();
        for (long i = 1; i <= 21; i++)
        {
            many.add(p("id", i, "title", "岗位" + i, "jobType", "fulltime"));
        }
        when(db.list(startsWith("select * from (" + KgPostService.LINK_JOB_SQL), anyMap())).thenReturn(many);
        Map<String, Object> res = service.linkables("job", null, "全部城市", 1);
        assertEquals(20, ((List<?>) res.get("rows")).size());
        assertEquals(true, res.get("hasMore"));
        assertEquals("全职", ((List<Map<String, Object>>) res.get("rows")).get(0).get("label"));
        verify(db).list(eq("select * from (" + KgPostService.LINK_JOB_SQL + KgPostService.JOB_PICK + ") x order by x.id desc limit :off, :size"),
                argThat(m -> !m.containsKey("kw") && !m.containsKey("c")));
        service.linkables("activity", "骑行", null, 1);
        verify(db).list(eq("select * from (" + KgPostService.LINK_ACT_SQL + KgPostService.ACT_PICK + " and (a.title like :kw or a.city like :kw)) x order by x.start_time, x.id limit :off, :size"),
                argThat(m -> "%骑行%".equals(m.get("kw"))));
    }

    @Test
    void unknownSearchTypeIsRejected()
    {
        for (String t : new String[] { null, "", "post", "stay" })
        {
            ServiceException e = assertThrows(ServiceException.class, () -> service.linkables(t, null, null, 1));
            assertEquals(422, e.getCode());
        }
        verify(db, never()).list(anyString(), anyMap());
    }

    // ---------------------------------------------------------------- 2026-10-17 r2：link_type / link_id（V52），经验分享也能选

    private void myExperience()
    {
        when(db.list(startsWith("select a.app_id, a.arrival_date"), anyMap())).thenReturn(Collections.singletonList(
                p("appId", 2L, "arrivalDate", "2026-02-01", "stayDays", 10L, "title", "咖啡店兼职", "city", "大理", "jobType", "parttime")));
        when(db.list(startsWith("select a.app_id, a.job_id, a.store_id from kg_application a"), anyMap())).thenReturn(Collections.singletonList(p("appId", 2L, "jobId", 70L, "storeId", 4L)));
    }

    private Map<String, Object> diary(Object... kv)
    {
        Map<String, Object> b = p("kind", "diary", "title", "兼职一个月", "content", "", "city", "大理", "refType", "application", "refId", 2L);
        b.putAll(p(kv));
        return b;
    }

    @SuppressWarnings("unchecked")
    @Test
    void diaryWithAChosenLinkKeepsTheApplicationRefAndStoresTheLink()
    {
        myExperience();
        Map<String, Object> act = p("id", 8L, "title", "洱海骑行", "city", "大理", "startTime", "2026-10-20 08:00:00");
        when(db.one(startsWith(KgPostService.LINK_ACT_SQL), anyMap())).thenReturn(act);
        when(db.list(startsWith(KgPostService.LINK_ACT_SQL), anyMap())).thenReturn(Collections.singletonList(act));
        when(db.list(startsWith(KgPostService.LINK_JOB_SQL), anyMap())).thenReturn(Collections.singletonList(p("id", 70L, "title", "咖啡店兼职", "jobType", "parttime")));
        Map<String, Object> res = service.publish(7L, diary("linkType", "activity", "linkId", 8L));
        // 经历校验照旧：ref 是那条报名；自己选的关联只进 link_*
        verify(db).insert(eq("kg_post"), argThat(r -> "application".equals(r.get("ref_type")) && Long.valueOf(2L).equals(r.get("ref_id"))
                && "activity".equals(r.get("link_type")) && Long.valueOf(8L).equals(r.get("link_id"))));
        verify(db).one(eq(KgPostService.LINK_ACT_SQL + "a.activity_id = :id and " + KgPostService.ACT_PICK), eq(p("id", 8L)));
        Map<String, Object> link = (Map<String, Object>) res.get("link");
        assertEquals("activity", link.get("type"), "选了就用选的，不再自动关联岗位");
        assertEquals("洱海骑行", link.get("title"));
    }

    @Test
    void diaryChosenLinkIsValidatedLikeTheOtherKinds()
    {
        myExperience();
        ServiceException e = assertThrows(ServiceException.class, () -> service.publish(7L, diary("linkType", "stay", "linkId", 99L)));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("小院"), e.getMessage());
        ServiceException bad = assertThrows(ServiceException.class, () -> service.publish(7L, diary("linkType", "post", "linkId", 3L)));
        assertEquals(422, bad.getCode(), "不关联别人的帖子");
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    void diaryStillNeedsItsOwnExperienceEvenWithALink()
    {
        // 只选了关联、没选经历（或 ref 不是报名）：照旧拒绝
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.publish(7L, p("kind", "diary", "title", "t", "content", "", "city", "大理", "linkType", "stay", "linkId", 3L)));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("经历"), e.getMessage());
        verify(db, never()).insert(anyString(), anyMap());
    }

    @SuppressWarnings("unchecked")
    @Test
    void diaryWithoutAChosenLinkStillAutoLinksAndFallsBackWhenTheChosenOneIsGone()
    {
        when(db.list(startsWith(FEED_ROWS), anyMap())).thenReturn(Arrays.asList(
                row("postId", 1L, "kind", "diary", "refType", "application", "refId", 2L, "linkType", "", "linkId", null),
                row("postId", 2L, "kind", "diary", "refType", "application", "refId", 2L, "linkType", "stay", "linkId", 3L),
                row("postId", 3L, "kind", "diary", "refType", "application", "refId", 2L, "linkType", "activity", "linkId", 8L)));
        when(db.list(startsWith("select a.app_id, a.job_id, a.store_id from kg_application a"), anyMap())).thenReturn(Collections.singletonList(p("appId", 2L, "jobId", 70L, "storeId", 4L)));
        when(db.list(startsWith(KgPostService.LINK_JOB_SQL), anyMap())).thenReturn(Collections.singletonList(p("id", 70L, "title", "咖啡店兼职", "jobType", "parttime")));
        when(db.list(startsWith(KgPostService.LINK_STAY_SQL), anyMap())).thenReturn(Collections.singletonList(p("id", 3L, "title", "平潭袋鼠小院")));
        // 活动 8 已经下线：查不到
        List<Map<String, Object>> rows = (List<Map<String, Object>>) service.feed(7L, null, "大理", "new", 1, null, null).get("rows");
        assertEquals("咖啡店兼职", ((Map<String, Object>) rows.get(0).get("link")).get("title"), "没选：照旧自动关联经历的岗位");
        assertEquals("平潭袋鼠小院", ((Map<String, Object>) rows.get(1).get("link")).get("title"), "选了：用选的");
        assertEquals("job", ((Map<String, Object>) rows.get(2).get("link")).get("type"), "选的下线了：退回经历的岗位");
        verify(db, times(1)).list(startsWith("select a.app_id, a.job_id, a.store_id from kg_application a"), anyMap());
        // 话题（岗位类型）照旧按 ref 那条报名算
        verify(db).list(startsWith("select a.app_id, j.job_type from kg_application a"), anyMap());
    }

    @SuppressWarnings("unchecked")
    @Test
    void readPrefersLinkColumnsAndFallsBackToRefForRowsWithoutThem()
    {
        when(db.list(startsWith(FEED_ROWS), anyMap())).thenReturn(Arrays.asList(
                // V52 回填过 / 新代码发的：两边都有
                row("postId", 1L, "refType", "stay", "refId", 3L, "linkType", "stay", "linkId", 3L),
                // V52 执行后老版本程序发的：只有 ref_*（link_type 默认 ''、link_id null）
                row("postId", 2L, "kind", "buddy", "refType", "job", "refId", 9L, "linkType", "", "linkId", null),
                // 只有 link_*
                row("postId", 3L, "linkType", "activity", "linkId", 8L),
                // 两边不一样：以 link_* 为准
                row("postId", 4L, "refType", "job", "refId", 9L, "linkType", "stay", "linkId", 3L),
                // 自己的住宿订单（不公开）、不认识的 link_type：都不显示
                row("postId", 5L, "refType", "stay_order", "refId", 11L, "linkType", "", "linkId", null),
                row("postId", 6L, "linkType", "post", "linkId", 1L)));
        when(db.list(startsWith(KgPostService.LINK_STAY_SQL), anyMap())).thenReturn(Collections.singletonList(p("id", 3L, "title", "平潭袋鼠小院")));
        when(db.list(startsWith(KgPostService.LINK_JOB_SQL), anyMap())).thenReturn(Collections.singletonList(p("id", 9L, "title", "前台换宿", "jobType", "volunteer")));
        when(db.list(startsWith(KgPostService.LINK_ACT_SQL), anyMap())).thenReturn(Collections.singletonList(p("id", 8L, "title", "洱海骑行")));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) service.feed(7L, null, "大理", "new", 1, null, null).get("rows");
        assertEquals("平潭袋鼠小院", ((Map<String, Object>) rows.get(0).get("link")).get("title"));
        assertEquals("前台换宿", ((Map<String, Object>) rows.get(1).get("link")).get("title"), "没回填的行退回读 ref_*");
        assertEquals("洱海骑行", ((Map<String, Object>) rows.get(2).get("link")).get("title"));
        assertEquals("stay", ((Map<String, Object>) rows.get(3).get("link")).get("type"));
        assertNull(rows.get(4).get("link"));
        assertNull(rows.get(5).get("link"));
        // 还是一种类型查一次；同一个对象只查一遍
        verify(db, times(1)).list(startsWith(KgPostService.LINK_STAY_SQL), anyMap());
        verify(db).list(eq(KgPostService.LINK_STAY_SQL + "s.stay_id in (:ids) and " + KgStayBrowseService.ONLINE), eq(p("ids", Collections.singletonList(3L))));
        verify(db).list(eq(KgPostService.LINK_JOB_SQL + "j.job_id in (:ids) and " + KgPostService.JOB_SHOWN), eq(p("ids", Collections.singletonList(9L))));
        // 卡片的字段形状不变
        assertEquals(new java.util.LinkedHashSet<>(Arrays.asList("type", "id", "title", "cover", "city", "label", "sub")), ((Map<String, Object>) rows.get(0).get("link")).keySet());
    }

    @Test
    void storedLinkReadsLinkColumnsFirst()
    {
        assertArrayEquals(new Object[] { "stay", 3L }, KgPostService.storedLink(row("linkType", "stay", "linkId", 3L, "refType", "job", "refId", 9L)));
        assertArrayEquals(new Object[] { "job", 9L }, KgPostService.storedLink(row("linkType", "", "linkId", null, "refType", "job", "refId", 9L)));
        assertNull(KgPostService.storedLink(row("kind", "diary", "refType", "application", "refId", 2L)), "经验分享的 ref 是报名，不算关联对象");
        assertNull(KgPostService.storedLink(row("kind", "diary", "refType", "stay", "refId", 3L)), "经验分享的 ref 不当关联读");
        assertArrayEquals(new Object[] { "activity", 8L }, KgPostService.storedLink(row("kind", "diary", "refType", "application", "refId", 2L, "linkType", "activity", "linkId", 8L)));
        assertNull(KgPostService.storedLink(row()));
    }

    private void stayThree()
    {
        Map<String, Object> stay = p("id", 3L, "title", "平潭袋鼠小院", "city", "平潭");
        when(db.one(startsWith(KgPostService.LINK_STAY_SQL), anyMap())).thenReturn(stay);
        when(db.list(startsWith(KgPostService.LINK_STAY_SQL), anyMap())).thenReturn(Collections.singletonList(stay));
    }

    @Test
    void oldMiniProgramPayloadStillWorks()
    {
        // r1.1 小程序：找搭子 / 旅居攻略只传 refType / refId → ref_* 和 link_* 都写
        stayThree();
        Map<String, Object> res = service.publish(7L, daily("refType", "stay", "refId", 3L));
        verify(db).insert(eq("kg_post"), argThat(r -> "stay".equals(r.get("ref_type")) && Long.valueOf(3L).equals(r.get("ref_id"))
                && "stay".equals(r.get("link_type")) && Long.valueOf(3L).equals(r.get("link_id"))));
        assertNotNull(res.get("link"));
        // r1.1 经验分享只传那条报名：不写 link_*，照旧自动关联岗位（见 diaryKeepsItsApplicationRefAndLinksToThatJob）
    }

    @Test
    void newPayloadWithBothFieldsAndLinkOnlyPayloadStillWriteTheRefForOldJars()
    {
        stayThree();
        service.publish(7L, daily("refType", "stay", "refId", 3L, "linkType", "stay", "linkId", 3L));
        service.publish(7L, daily("linkType", "stay", "linkId", "3"));
        // 两次都把 ref_* 写成同一个关联：老版本程序（只读 ref_*）照样显示「关联：XXX」
        verify(db, times(2)).insert(eq("kg_post"), argThat(r -> "stay".equals(r.get("ref_type")) && Long.valueOf(3L).equals(r.get("ref_id"))
                && "stay".equals(r.get("link_type")) && Long.valueOf(3L).equals(r.get("link_id"))));
        // 只校验一次「现在能选」（不会因为 ref 和 link 各校验一遍）
        verify(db, times(2)).one(startsWith(KgPostService.LINK_STAY_SQL), anyMap());
    }

    @Test
    void unknownLinkTypeIsRejectedAndEmptyLinkIsNoLink()
    {
        ServiceException e = assertThrows(ServiceException.class, () -> service.publish(7L, daily("linkType", "post", "linkId", 1L)));
        assertEquals(422, e.getCode());
        verify(db, never()).insert(anyString(), anyMap());
        Map<String, Object> res = service.publish(7L, daily("linkType", "", "linkId", ""));
        verify(db).insert(eq("kg_post"), argThat(r -> !r.containsKey("link_type") && !r.containsKey("ref_type")));
        assertNull(res.get("link"));
    }

    @Test
    void legacyPrivateRefKeepsItsRefAndALinkGoesToTheLinkColumnsOnly()
    {
        stayThree();
        when(db.list(startsWith("select o.order_id, o.check_in, s.stay_name from kg_stay_order"), anyMap())).thenReturn(Collections.singletonList(
                p("orderId", 11L, "checkIn", "2026-10-01", "stayName", "平潭袋鼠小院")));
        service.publish(7L, daily("refType", "stay_order", "refId", 11L, "linkType", "stay", "linkId", 3L));
        verify(db).insert(eq("kg_post"), argThat(r -> "stay_order".equals(r.get("ref_type")) && Long.valueOf(11L).equals(r.get("ref_id"))
                && "stay".equals(r.get("link_type")) && Long.valueOf(3L).equals(r.get("link_id"))));
    }
}
