package com.ruoyi.kangaroo.mapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.github.pagehelper.PageHelper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.controller.app.AppJobController;
import com.ruoyi.kangaroo.domain.JobTabFilters;
import com.ruoyi.kangaroo.domain.KgJob;
import com.ruoyi.kangaroo.ext.user.KgCityService;
import com.ruoyi.kangaroo.service.IKgJobService;

/**
 * 每个页签自己的筛选条件（2026-10-17）：GET /app/job/list 的 payDay / payMonthMin / payMonthMax / categories / board=self
 * → 参数校验 → 解析真实 KgJobMapper.xml 拼出的条件（不连库；真库上的结果见 e2e/test_job_tab_filters.py）
 */
class KgJobTabFilterMapperTest
{
    private Configuration config;
    private final AppJobController controller = new AppJobController();
    private final IKgJobService jobs = mock(IKgJobService.class);

    @BeforeEach
    void setup() throws Exception
    {
        config = new Configuration();
        config.getTypeAliasRegistry().registerAlias("KgJob", KgJob.class);
        String resource = "mapper/kangaroo/KgJobMapper.xml";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource))
        {
            assertNotNull(in);
            new XMLMapperBuilder(in, config, resource, config.getSqlFragments()).parse();
        }
        KgCityService cities = mock(KgCityService.class);
        when(cities.businessCity(anyString())).thenAnswer(inv -> ((String) inv.getArgument(0)).trim());
        when(cities.jobCityTerms(anyString())).thenReturn(new ArrayList<String>());
        ReflectionTestUtils.setField(controller, "kgJobService", jobs);
        ReflectionTestUtils.setField(controller, "kgJobFavoriteMapper", mock(KgJobFavoriteMapper.class));
        ReflectionTestUtils.setField(controller, "cityService", cities);
        when(jobs.selectAppJobList(any())).thenReturn(new ArrayList<KgJob>());
    }

    @AfterEach
    void tearDown()
    {
        PageHelper.clearPage();
    }

    /** 走一遍对外的 list（和 @GetMapping 同一个方法），拿到交给 mapper 的 KgJob */
    private KgJob call(String type, String board, Long payDay, Long payMonthMin, Long payMonthMax, String categories)
    {
        controller.list(type, null, null, null, null, null, board, null, null, null, null,
                null, null, null, null, null, null, payDay, payMonthMin, payMonthMax, categories, 1, 20);
        ArgumentCaptor<KgJob> q = ArgumentCaptor.forClass(KgJob.class);
        verify(jobs, atLeastOnce()).selectAppJobList(q.capture());
        return q.getValue();
    }

    private BoundSql bound(KgJob j)
    {
        return config.getMappedStatement("com.ruoyi.kangaroo.mapper.KgJobMapper.selectAppJobList").getBoundSql(j);
    }

    private static String flat(String s)
    {
        return s.replaceAll("\\s+", " ").trim();
    }

    /** 绑定参数按顺序取出来的值 */
    private List<Object> values(KgJob j, BoundSql b)
    {
        List<Object> out = new ArrayList<Object>();
        MetaObject meta = config.newMetaObject(j);
        for (ParameterMapping pm : b.getParameterMappings())
        {
            String prop = pm.getProperty();
            out.add(b.hasAdditionalParameter(prop) ? b.getAdditionalParameter(prop) : meta.getValue(prop));
        }
        return out;
    }

    @Test
    void newMonthlyBandIncludes4500() {
        String sql=flat(bound(call("fulltime",null,null,3000L,4500L,null)).getSql());
        assertTrue(sql.contains(flat(JobTabFilters.MONTHLY_MIN_SQL) + " <= ?"),sql);
        assertFalse(sql.contains(flat(JobTabFilters.MONTHLY_MIN_SQL) + " < ?"),sql);
    }

    @Test
    void noTabConditionsLeaveTheListAlone()
    {
        String q = flat(bound(call("parttime", null, null, null, null, null)).getSql());
        assertFalse(q.contains("salary_min is not null"), q);
        assertFalse(q.contains("store_name, '') like"), q);
        assertFalse(q.contains("coalesce(j.board_type, 'none')"), q);
    }

    @Test
    void partTimeDailyBandComparesTheStartingDailyPay()
    {
        KgJob j = call("parttime", null, 100L, null, null, null);
        assertEquals(100L, j.getParams().get("payDay"));
        BoundSql b = bound(j);
        String q = flat(b.getSql());
        // 和 JobTabFilters.DAILY_MIN_SQL 一字不差（Java 版 dailyMin 的单测守着换算口径）
        assertTrue(q.contains(flat(JobTabFilters.DAILY_MIN_SQL) + " >= ?"), q);
        assertTrue(q.contains("j.salary_type in ('hourly','daily','weekly','monthly') and j.salary_min is not null"), q);
        // 和老的 salaryMin（取 salary_max 比，区间重叠就算）不同：这里只看起薪
        assertFalse(q.contains("coalesce(j.salary_max, j.salary_min)"), q);
        assertTrue(values(j, b).contains(100L));
    }

    @Test
    void fullTimeMonthlyBandsAreLeftClosedRightOpen()
    {
        String month = flat(JobTabFilters.MONTHLY_MIN_SQL);
        String below = flat(bound(call("fulltime", null, null, null, 3000L, null)).getSql());
        assertTrue(below.contains(month + " < ?"), below);
        assertFalse(below.contains(month + " >= ?"), below);
        KgJob mid = call("fulltime", null, null, 3000L, 5000L, null);
        BoundSql mb = bound(mid);
        String q = flat(mb.getSql());
        assertTrue(q.contains(month + " >= ?") && q.contains(month + " < ?"), q);
        assertTrue(values(mid, mb).containsAll(Arrays.asList(3000L, 5000L)));
        String above = flat(bound(call("fulltime", null, null, 5000L, null, null)).getSql());
        assertTrue(above.contains(month + " >= ?"), above);
        assertFalse(above.contains(month + " < ?"), above);
    }

    @Test
    void foodAndLodgingOptions()
    {
        assertTrue(flat(bound(call("parttime", "all", null, null, null, null)).getSql()).contains("and j.board_type = 'all'"));
        assertTrue(flat(bound(call("parttime", "room", null, null, null, null)).getSql()).contains("and j.board_type in ('all', 'room')"));
        assertTrue(flat(bound(call("fulltime", "meal", null, null, null, null)).getSql()).contains("and j.board_type in ('all', 'meal')"));
        String self = flat(bound(call("fulltime", "self", null, null, null, null)).getSql());
        assertTrue(self.contains("and coalesce(j.board_type, 'none') in ('none', '')"), self);
        assertFalse(self.contains("board_type in ('all'"), self);
        // 老版本小程序的 none = 不限
        String none = flat(bound(call("parttime", "none", null, null, null, null)).getSql());
        assertFalse(none.contains("and j.board_type") || none.contains("coalesce(j.board_type"), none);
        ServiceException e = assertThrows(ServiceException.class, () -> call("parttime", "part", null, null, null, null));
        assertEquals(Integer.valueOf(422), e.getCode());
    }

    @Test
    void fullTimeTypeMatchesStoreNameOrTitle()
    {
        KgJob j = call("fulltime", null, null, null, null, "homestay,酒吧");
        BoundSql b = bound(j);
        String q = flat(b.getSql());
        assertTrue(q.contains("coalesce(s.store_name, '') like ? or j.title like ?"), q);
        // 2026-10-18：商家端把门店类型存成岗位第一个标签，标签里有这类词也算
        assertTrue(q.contains("coalesce(s.store_name, '') like ? or j.title like ? or coalesce(j.tags, '') like ?"), q);
        assertFalse(q.contains("not ("), q);
        List<Object> v = values(j, b);
        assertTrue(v.contains("%民宿%") && v.contains("%客栈%") && v.contains("%酒吧%") && v.contains("%清吧%"), v.toString());
        assertFalse(v.contains("%酒店%"), v.toString());
        // 类型之间是「或者」：民宿 or 酒吧
        assertTrue(q.contains(") or ("), q);
    }

    @Test
    void otherMeansNoneOfTheTypeWords()
    {
        KgJob j = call("fulltime", null, null, null, null, "other");
        BoundSql b = bound(j);
        String q = flat(b.getSql());
        assertTrue(q.contains("not (coalesce(s.store_name, '') like ?"), q);
        assertTrue(q.contains("or coalesce(j.tags, '') like ?"), q);
        List<Object> v = values(j, b);
        for (String w : JobTabFilters.allCategoryWords())
        {
            assertTrue(v.contains("%" + w + "%"), w);
        }
    }

    @Test
    void badValuesAreRejected()
    {
        for (String bad : Arrays.asList("cafe", "民宿,unknown"))
        {
            ServiceException e = assertThrows(ServiceException.class, () -> call("fulltime", null, null, null, null, bad), bad);
            assertEquals("岗位类型不合法", e.getMessage());
        }
        ServiceException e = assertThrows(ServiceException.class, () -> call("fulltime", null, null, 5000L, 3000L, null));
        assertEquals("薪资区间不合法", e.getMessage());
        // 0 / 负数 = 不筛
        assertNull(call("parttime", null, 0L, null, null, null).getParams().get("payDay"));
    }

    @Test
    void oldCallersStillWork()
    {
        // 老版本小程序的 salaryMin / salaryMax / board 照旧（19 个参数的老签名，不带页签条件）
        controller.list("parttime", null, null, null, 80L, 150L, "room", null, null, null, null,
                null, null, null, null, null, null, 1, 20);
        ArgumentCaptor<KgJob> q = ArgumentCaptor.forClass(KgJob.class);
        verify(jobs, atLeastOnce()).selectAppJobList(q.capture());
        Map<String, Object> p = q.getValue().getParams();
        assertEquals(80L, p.get("salaryMin"));
        assertEquals(150L, p.get("salaryMax"));
        assertEquals("room", p.get("board"));
        assertNull(p.get("payDay"));
        assertNull(p.get("categoryTerms"));
        Map<String, Object> none = new HashMap<String, Object>();
        String sql = flat(bound(withParams(none)).getSql());
        assertFalse(sql.contains("salary_min is not null"), sql);
    }

    private static KgJob withParams(Map<String, Object> p)
    {
        KgJob j = new KgJob();
        j.setJobType("fulltime");
        j.setParams(p);
        return j;
    }
}
