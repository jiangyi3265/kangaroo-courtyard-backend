package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.KgCityService;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.StaffGrantView;
import com.ruoyi.kangaroo.service.IKgMessageService;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;
import com.ruoyi.kangaroo.security.AppTokenService;

/** 招募周报按店汇总、业务页入口文字、城市负责人能看到招募日报 */
class KgVolunteerWeekShopsTest
{
    private KgVolunteerDailyService service(KgJdbc db)
    {
        KgCityService cities = mock(KgCityService.class);
        when(cities.jobCity(anyString())).thenAnswer(i -> i.getArgument(0));
        KgVolunteerDailyService s = new KgVolunteerDailyService();
        ReflectionTestUtils.setField(s, "db", db);
        ReflectionTestUtils.setField(s, "cities", cities);
        return s;
    }

    private static Map<String, Object> job(long jobId, long shopId, String shop, String status, long head, long occupied, String city)
    {
        return p("jobId", jobId, "shopId", shopId, "shopName", shop, "shopCover", "c" + shopId, "status", status, "headcount", head, "occupied", occupied, "city", city);
    }

    @Test
    void groupsByRecruitingShopWithinTheCity()
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.list(contains("from kg_job j"), anyMap())).thenReturn(Arrays.asList(
                job(1, 10, "甲店", "published", 3, 1, "丽江"),
                job(2, 10, "甲店", "closed", 5, 0, "丽江"),
                job(3, 20, "乙店", "published", 1, 2, "丽江"),
                job(4, 30, "外城店", "published", 9, 0, "大理")));
        when(db.list(contains("coalesce(sum(a.store_confirm_time"), anyMap())).thenReturn(Arrays.asList(
                p("jobId", 1L, "recruited", 2L, "onboarded", 1L, "recruitedLast", 1L, "onboardedLast", 0L, "applied", 5L, "appliedLast", 4L),
                p("jobId", 2L, "recruited", 1L, "onboarded", 1L, "recruitedLast", 0L, "onboardedLast", 1L, "applied", 0L, "appliedLast", 0L),
                p("jobId", 3L, "recruited", 7L, "onboarded", 0L, "recruitedLast", 0L, "onboardedLast", 0L, "applied", 0L, "appliedLast", 0L)));
        when(db.list(contains("date_add(a.arrival_date"), anyMap())).thenReturn(Collections.singletonList(p("jobId", 3L, "n", 2L)));
        List<Map<String, Object>> shops = service(db).weekShops("丽江", LocalDate.of(2026, 10, 26));
        assertEquals(2, shops.size(), "外城的店不进来");
        Map<String, Object> a = shops.get(0);
        assertEquals(10L, a.get("storeId"));
        assertEquals("甲店", a.get("name"));
        assertEquals("c10", a.get("cover"));
        assertEquals(3L, a.get("recruited"), "同一家店两个岗位加起来");
        assertEquals(2L, a.get("onboarded"));
        assertEquals(1L, a.get("recruitedLast"));
        assertEquals(1L, a.get("onboardedLast"));
        assertEquals(5L, a.get("applied"));
        assertEquals(2L, a.get("shortfall"), "只算招聘中岗位的剩余名额，已关闭的不算");
        assertEquals(0L, a.get("leaving"));
        Map<String, Object> b = shops.get(1);
        assertEquals(0L, b.get("shortfall"), "超招不算负缺口");
        assertEquals(2L, b.get("leaving"));
        verify(db).list(argThat(s -> s.contains("date_add(a.arrival_date") && s.contains("a.status in ('store_confirmed','contract_signed','onboarded')")), anyMap());
    }

    @Test
    void weekWindowIsMondayToNextMonday()
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.list(contains("from kg_job j"), anyMap())).thenReturn(Collections.singletonList(job(1, 10, "甲店", "published", 3, 1, "丽江")));
        when(db.list(contains("coalesce(sum(a.store_confirm_time"), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        when(db.list(contains("date_add(a.arrival_date"), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        service(db).weekShops("丽江", LocalDate.of(2026, 10, 26));
        org.mockito.ArgumentCaptor<Map<String, Object>> ps = org.mockito.ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
        verify(db).list(contains("coalesce(sum(a.store_confirm_time"), ps.capture());
        assertEquals("2026-10-19", ps.getValue().get("lf"));
        assertEquals("2026-10-26", ps.getValue().get("f"));
        assertEquals("2026-11-02", ps.getValue().get("t"));
    }

    @Test
    void noVolunteerShopsMeansNoApplicationQueries()
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.list(contains("from kg_job j"), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        assertTrue(service(db).weekShops("丽江", LocalDate.of(2026, 10, 26)).isEmpty());
        verify(db, times(1)).list(anyString(), anyMap());
    }

    // ------------------------------------------------------------------ 业务页入口文字

    private static StaffSupport.Ctx ctx(String role)
    {
        StaffSupport.Ctx c = new StaffSupport.Ctx();
        AppLoginUser me = new AppLoginUser();
        me.setUserId(7L);
        me.setStaffGrants(Collections.singletonList(new StaffGrantView()));
        c.me = me;
        c.storeId = 1L;
        c.storeName = "店";
        c.role = role;
        return c;
    }

    @Test
    void hubGivesWeeklyEntryLabelByRole()
    {
        KgStaffTeamService t = new KgStaffTeamService();
        ReflectionTestUtils.setField(t, "db", mock(KgJdbc.class));
        ReflectionTestUtils.setField(t, "support", mock(StaffSupport.class));
        ReflectionTestUtils.setField(t, "grants", mock(IKgStaffGrantService.class));
        ReflectionTestUtils.setField(t, "tokens", mock(AppTokenService.class));
        ReflectionTestUtils.setField(t, "messages", mock(IKgMessageService.class));
        assertEquals("招募周报", t.hub(ctx(StaffRole.MANAGER)).get("weeklyEntryLabel"));
        assertEquals("周报管理", t.hub(ctx(StaffRole.KEEPER)).get("weeklyEntryLabel"));
        assertEquals("周报管理", t.hub(ctx(StaffRole.STAFF)).get("weeklyEntryLabel"));
    }

    // ------------------------------------------------------------------ 消息可见范围

    @Test
    void cityLeadOnlyAccountsCanSeeRecruitDailyMessages() throws Exception
    {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("mapper/kangaroo/KgMessageMapper.xml"))
        {
            String xml = org.springframework.util.StreamUtils.copyToString(in, StandardCharsets.UTF_8);
            assertTrue(xml.matches("(?s).*biz_type in \\([^)]*'recruit_daily'[^)]*\\).*"));
        }
    }
}
