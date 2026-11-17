package com.ruoyi.kangaroo.ext.community;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.ExtEntity;
import com.ruoyi.kangaroo.ext.ExtField;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.KgCityService;

/**
 * 社区改版（2026-10-16）：「换工日记」改名「经验分享」（代码仍是 diary），兼职 / 全职经历也能发；
 * 信息流 jobType 只在 diary 下按关联经历的岗位类型筛；卡片「话题｜标题」的 topicLabel 一页一次查齐；搜索关键词按字面找。
 * Real KgPostService bodies, Mock JDBC (no real SQL).
 */
class KgPostFeedTopicTest
{
    static final String FEED_ROWS = "select p.* from kg_post p";
    static final String DIARY_JOBS = "select a.app_id, j.job_type from kg_application a join kg_job j on j.job_id = a.job_id where a.app_id in (:ids)";
    static final String JOB_EXISTS = " and p.ref_type = 'application' and exists (select 1 from kg_application ja join kg_job jj on jj.job_id = ja.job_id "
            + "where ja.app_id = p.ref_id and jj.job_type = :jt)";

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
    }

    static Map<String, Object> row(Object... kv)
    {
        Map<String, Object> r = p("postId", 5L, "kind", "daily", "appUserId", 9L, "city", "大理", "title", "洱海日落", "status", "online");
        r.putAll(p(kv));
        return r;
    }

    String feedSql()
    {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db, atLeastOnce()).list(sql.capture(), anyMap());
        for (String s : sql.getAllValues())
        {
            if (s.startsWith(FEED_ROWS))
            {
                return s;
            }
        }
        return fail("信息流没有查帖子");
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> feedParams()
    {
        ArgumentCaptor<Map> ps = ArgumentCaptor.forClass(Map.class);
        verify(db).list(startsWith(FEED_ROWS), ps.capture());
        return ps.getValue();
    }

    // ---------------------------------------------------------------- 名字

    @Test
    void diaryIsRenamedOnlyItsLabel()
    {
        assertEquals(Arrays.asList("daily", "buddy", "diary"), KgPostService.KINDS, "代码不变，老帖子不用迁移");
        assertEquals("经验分享", KgPostService.KIND_LABEL.get("diary"));
        assertEquals("找搭子", KgPostService.KIND_LABEL.get("buddy"));
        // 2026-10-17 主人拍板：「旅居日常」改名「旅居攻略」，代码还是 daily
        assertEquals("旅居攻略", KgPostService.KIND_LABEL.get("daily"));
        assertEquals(Arrays.asList("volunteer", "parttime", "fulltime"), new ArrayList<>(KgPostService.DIARY_JOB_LABEL.keySet()));
        assertEquals(Arrays.asList("换宿旅行", "线下兼职", "全职"), new ArrayList<>(KgPostService.DIARY_JOB_LABEL.values()));
        assertFalse(KgPostService.DIARY_JOB_LABEL.containsKey("online"), "线上任务不算旅居经验");
    }

    @Test
    void adminKindOptionUsesTheNewName()
    {
        KgCommunityModule module = new KgCommunityModule();
        ReflectionTestUtils.setField(module, "db", db);
        ReflectionTestUtils.setField(module, "activities", mock(KgActivityService.class));
        ExtEntity post = module.entities().get(0);
        ExtField kind = post.fields.stream().filter(f -> "kind".equals(f.name)).findFirst().orElseThrow(AssertionError::new);
        assertEquals("daily:旅居攻略,buddy:找搭子,diary:经验分享", kind.options);
    }

    // ---------------------------------------------------------------- jobType 筛选

    @Test
    void diaryJobTypeAddsExistsJoinOnTheLinkedApplication()
    {
        service.feed(7L, "diary", "大理", "new", 1, null, "parttime");
        assertTrue(feedSql().contains(" and p.kind = :k" + JOB_EXISTS), feedSql());
        Map<String, Object> ps = feedParams();
        assertEquals("diary", ps.get("k"));
        assertEquals("parttime", ps.get("jt"));
    }

    @Test
    void jobTypeIsIgnoredOutsideDiaryOrWhenUnknown()
    {
        String[][] cases = { { "daily", "parttime" }, { "buddy", "volunteer" }, { null, "fulltime" }, { "diary", "online" }, { "diary", "PARTTIME" },
                { "diary", "" }, { "diary", null }, { "diary", "volunteer' or 1=1 --" } };
        for (String[] c : cases)
        {
            service.feed(7L, c[0], "大理", "new", 1, null, c[1]);
        }
        verify(db, times(cases.length)).list(argThat((String sql) -> sql.startsWith(FEED_ROWS) && !sql.contains("job_type")), argThat(m -> !m.containsKey("jt")));
    }

    @Test
    void oldSignatureStillWorksWithoutJobType()
    {
        service.feed(7L, "diary", "大理", "new", 1, null);
        assertFalse(feedSql().contains("job_type"));
    }

    @Test
    void keywordWildcardsAreMatchedLiterally()
    {
        service.feed(7L, null, "大理", "new", 1, " 100%_好 ", null);
        assertTrue(feedSql().contains("(p.title like :kw or p.content like :kw or p.tags like :kw)"));
        assertEquals("%100\\%\\_好%", feedParams().get("kw"));
    }

    @Test
    void buddiesAcceptAKeywordWithTheSameRules()
    {
        service.buddies(7L, "大理", null, null, null, false, null, "徒步%");
        verify(db).list(argThat((String sql) -> sql.contains(" and (p.title like :kw or p.content like :kw or p.tags like :kw)")), argThat(m -> "%徒步\\%%".equals(m.get("kw"))));
        service.buddies(7L, "大理", null, null, null, false, null);
        service.buddies(7L, "大理", null, null, null, false, null, "  ");
        verify(db, times(2)).list(argThat((String sql) -> sql.contains("p.kind = 'buddy'") && !sql.contains(":kw")), argThat(m -> !m.containsKey("kw")));
    }

    // ---------------------------------------------------------------- 话题（「话题｜标题」）

    @SuppressWarnings("unchecked")
    @Test
    void topicLabelsComeFromJobTypeCategoryOrFirstTagAndDiaryLookupIsBatched()
    {
        when(db.list(startsWith(FEED_ROWS), anyMap())).thenReturn(Arrays.asList(
                row("postId", 1L, "kind", "diary", "refType", "application", "refId", 31L),
                row("postId", 2L, "kind", "diary", "refType", "application", "refId", 32L),
                row("postId", 3L, "kind", "diary", "refType", "application", "refId", 31L),
                row("postId", 4L, "kind", "diary"),
                row("postId", 5L, "kind", "buddy", "category", "hike"),
                row("postId", 6L, "kind", "buddy", "category", "nonsense"),
                row("postId", 10L, "kind", "buddy", "category", "other"),
                row("postId", 7L, "kind", "daily", "tags", "#骑行,洱海"),
                row("postId", 8L, "kind", "daily", "tags", ""),
                row("postId", 9L, "kind", "daily", "tags", "这是一个特别特别长的标签名字啊")));
        when(db.list(startsWith(DIARY_JOBS), anyMap())).thenReturn(Arrays.asList(p("appId", 31L, "jobType", "parttime"), p("appId", 32L, "jobType", "online")));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) service.feed(7L, null, "大理", "new", 1, null, null).get("rows");
        List<Object> topics = new ArrayList<>();
        for (Map<String, Object> r : rows)
        {
            topics.add(r.get("topicLabel"));
        }
        // 2026-10-17：找搭子分类是「其他」时不当话题（之前卡片上成了「其他｜酒吧」）
        assertEquals(Arrays.asList("线下兼职", "", "线下兼职", "", "徒步登山", "", "", "骑行", "", "这是一个特别特别长的"), topics);
        assertEquals("经验分享", rows.get(0).get("kindLabel"));
        // 一页只查一次岗位类型，报名编号去重
        verify(db, times(1)).list(startsWith(DIARY_JOBS), anyMap());
        verify(db).list(eq(DIARY_JOBS), eq(p("ids", Arrays.asList(31L, 32L))));
    }

    @Test
    void pagesWithoutDiaryPostsDoNotQueryJobTypes()
    {
        when(db.list(startsWith(FEED_ROWS), anyMap())).thenReturn(Arrays.asList(row("kind", "daily"), row("postId", 6L, "kind", "buddy", "category", "run")));
        service.feed(7L, null, "大理", "new", 1, null, null);
        verify(db, never()).list(startsWith(DIARY_JOBS), anyMap());
    }

    @Test
    void detailCarriesTheTopicToo()
    {
        when(db.one(startsWith("select p.* from kg_post p where p.post_id"), anyMap())).thenReturn(row("kind", "diary", "refType", "application", "refId", 40L));
        when(db.list(startsWith(DIARY_JOBS), anyMap())).thenReturn(Collections.singletonList(p("appId", 40L, "jobType", "volunteer")));
        Map<String, Object> d = service.detail(5L, null);
        assertEquals("换宿旅行", d.get("topicLabel"));
        assertEquals("经验分享", d.get("kindLabel"));
    }

    // ---------------------------------------------------------------- 已完成的经历：换宿 / 兼职 / 全职

    @SuppressWarnings("unchecked")
    @Test
    void experiencesCoverVolunteerParttimeAndFulltimeWithTypeLabels()
    {
        when(db.list(startsWith("select a.app_id, a.arrival_date"), anyMap())).thenReturn(Arrays.asList(
                p("appId", 1L, "arrivalDate", "2026-01-01", "stayDays", 30L, "title", "前台换工", "city", "大理", "jobType", "volunteer"),
                p("appId", 2L, "arrivalDate", "2026-02-01", "stayDays", 10L, "title", "咖啡店兼职", "city", "大理", "jobType", "parttime"),
                p("appId", 3L, "arrivalDate", "2026-03-01", "stayDays", 60L, "title", "民宿管家", "city", "丽江", "jobType", "fulltime"),
                p("appId", 4L, "arrivalDate", "2099-01-01", "stayDays", 10L, "title", "还没做完", "city", "大理", "jobType", "parttime")));
        List<Map<String, Object>> list = service.experiences(7L);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db).list(sql.capture(), eq(p("u", 7L)));
        assertTrue(sql.getValue().contains("j.job_type in ('volunteer','parttime','fulltime')"), sql.getValue());
        assertFalse(sql.getValue().contains("j.job_type = 'volunteer'"));
        // 2026-10-16 内测反馈 2.0：已到岗、还没做完的也算（和简历「平台记录」同一口径），带 ongoing 让页面显示「进行中」
        assertEquals(4, list.size(), "已到岗还没结束的也算");
        assertEquals(Arrays.asList("volunteer", "parttime", "fulltime", "parttime"), Arrays.asList(list.get(0).get("jobType"), list.get(1).get("jobType"), list.get(2).get("jobType"), list.get(3).get("jobType")));
        assertEquals(Arrays.asList("换宿旅行", "线下兼职", "全职"), Arrays.asList(list.get(0).get("jobTypeLabel"), list.get(1).get("jobTypeLabel"), list.get(2).get("jobTypeLabel")));
        assertEquals(Arrays.asList(false, false, false, true), Arrays.asList(list.get(0).get("ongoing"), list.get(1).get("ongoing"), list.get(2).get("ongoing"), list.get(3).get("ongoing")));
        assertEquals("2099.01.01–2099.01.10", list.get(3).get("date"));
    }

    @Test
    void diaryWithAFinishedParttimeExperiencePublishes()
    {
        when(db.list(startsWith("select a.app_id, a.arrival_date"), anyMap())).thenReturn(Collections.singletonList(
                p("appId", 2L, "arrivalDate", "2026-02-01", "stayDays", 10L, "title", "咖啡店兼职", "city", "大理", "jobType", "parttime")));
        when(db.insert(eq("kg_post"), anyMap())).thenReturn(42L);
        Map<String, Object> res = service.publish(7L, p("kind", "diary", "title", "兼职一个月的收获", "content", "", "city", "大理", "refType", "application", "refId", 2L));
        assertEquals(42L, res.get("postId"));
        verify(db).insert(eq("kg_post"), argThat(r -> "diary".equals(r.get("kind")) && "application".equals(r.get("ref_type")) && Long.valueOf(2L).equals(r.get("ref_id"))));
    }

    @Test
    void diaryWithoutAFinishedExperienceIsRejectedWithTheNewName()
    {
        when(db.list(startsWith("select a.app_id, a.arrival_date"), anyMap())).thenReturn(new ArrayList<>());
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.publish(7L, p("kind", "diary", "title", "随便写写", "content", "", "city", "大理")));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("经验分享"), e.getMessage());
        assertFalse(e.getMessage().contains("换工日记"));
        verify(db, never()).insert(anyString(), anyMap());
    }
}
