package com.ruoyi.kangaroo.ext.staff;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.AppLoginUser;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.and;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 人才档案四个页签都有真实数据：经历按类型计数（培训不再算全职）、培训考核、本店面试评价、档案动态。 */
class KgTalentProfileTabsTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final KgTalentService service = new KgTalentService();
    private final StaffSupport.Ctx ctx = new StaffSupport.Ctx();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "support", mock(StaffSupport.class));
        ctx.storeId = 102L; ctx.storeName = "平潭测试门店"; ctx.role = "manager";
        ctx.me = new AppLoginUser(); ctx.me.setUserId(1L);
        // 人才池里只有 7 号用户（每行都会被 pool() 追加 tags / kinds，必须是可变 Map）
        when(db.list(contains("from kg_app_user u"), anyMap())).thenReturn(Collections.singletonList(p("userId", 7L, "name", "张三", "skillText", "咖啡 拉花")));
        when(db.list(contains("select * from kg_resume_exp"), anyMap())).thenReturn(Arrays.asList(
                p("kind", "volunteer", "title", "前台换工", "startDate", "2026-03-01"),
                p("kind", "parttime", "title", "周末咖啡师"),
                p("kind", "training", "title", "咖啡基础课", "passed", "1", "level", "初级"),
                p("kind", "other", "title", "门店店长")));
        when(db.list(contains("from kg_cert"), anyMap())).thenReturn(Collections.singletonList(
                p("name", "咖啡师认证", "level", "中级", "issuer", "袋鼠小院", "issuedDate", "2026-05-01", "score", 88)));
        when(db.list(contains("from kg_interview i"), anyMap())).thenReturn(Collections.singletonList(
                p("staffRating", 4, "staffNote", "沟通顺畅", "startTime", "2026-09-05 10:00:00", "title", "咖啡师")));
        when(db.list(contains("from kg_talent_invite i"), anyMap())).thenReturn(Collections.singletonList(
                p("createTime", "2026-09-10 09:00:00", "title", "咖啡师", "applied", 0L)));
        when(db.list(contains("order by a.submit_time desc limit 10"), anyMap())).thenReturn(Collections.singletonList(
                p("submitTime", "2026-09-01 08:00:00", "status", "onboarded", "title", "咖啡师")));
        when(db.one(contains("from kg_pii_access"), anyMap())).thenReturn(p("n", 3L, "lastTime", "2026-09-20 18:30:00"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void experienceCountsExcludeTrainingAndCarryKind()
    {
        Map<String, Object> m = service.profile(ctx, 7L);
        assertEquals(Arrays.asList(3, 1, 1, 1), m.get("expCounts"));
        List<Map<String, Object>> exps = (List<Map<String, Object>>) m.get("experiences");
        assertEquals(Arrays.asList("volunteer", "parttime", "fulltime"), exps.stream().map(e -> e.get("kind")).collect(java.util.stream.Collectors.toList()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void trainingTabListsCertificatesThenTrainingExperience()
    {
        Map<String, Object> m = service.profile(ctx, 7L);
        List<Map<String, Object>> trainings = (List<Map<String, Object>>) m.get("trainings");
        assertEquals(2, trainings.size());
        assertEquals(2, m.get("certs"));
        assertEquals("咖啡师认证（中级）", trainings.get(0).get("title"));
        assertTrue(trainings.get(0).get("desc").toString().contains("88 分"));
        assertEquals("考核通过 · 等级 初级", trainings.get(1).get("desc"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reviewsComeOnlyFromThisStoresInterviewRatings()
    {
        Map<String, Object> m = service.profile(ctx, 7L);
        List<Map<String, Object>> reviews = (List<Map<String, Object>>) m.get("reviews");
        assertEquals(1, m.get("reviewCount"));
        assertEquals("本店面试评价 4 星（咖啡师）", reviews.get(0).get("title"));
        assertEquals("2026-09-05", reviews.get(0).get("date"));
        verify(db).list(and(contains("from kg_interview i"), contains("a.store_id = :s")), eq(p("u", 7L, "s", 102L)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void activityIsNewestFirstAndScopedToThisStore()
    {
        Map<String, Object> m = service.profile(ctx, 7L);
        List<Map<String, Object>> activity = (List<Map<String, Object>>) m.get("activity");
        assertEquals(3, activity.size());
        assertEquals("本店员工查看档案 3 次", activity.get(0).get("title"));
        assertEquals("2026-09-20", activity.get(0).get("date"));
        assertEquals("本店邀请报名「咖啡师」", activity.get(1).get("title"));
        assertEquals("报名「咖啡师」", activity.get(2).get("title"));
        assertEquals("当前状态：已入职", activity.get(2).get("desc"));
        assertFalse(activity.get(0).containsKey("at"), "排序用的完整时间不下发");
        verify(db).one(contains("biz_id = :s"), eq(p("u", 7L, "s", 102L)));
    }
}
