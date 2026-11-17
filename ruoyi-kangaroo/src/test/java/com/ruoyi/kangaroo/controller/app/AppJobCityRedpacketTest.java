package com.ruoyi.kangaroo.controller.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.github.pagehelper.PageHelper;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.domain.KgJob;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.KgCityService;
import com.ruoyi.kangaroo.mapper.KgJobFavoriteMapper;
import com.ruoyi.kangaroo.service.IKgJobService;

/** 岗位列表的城市（归一成业务城市名，平潭岛 → 平潭）、有岗位的城市接口、满月红包筛选与出参（不连库） */
class AppJobCityRedpacketTest
{
    private final AppJobController controller = new AppJobController();
    private final IKgJobService jobs = mock(IKgJobService.class);
    private final KgCityService cities = mock(KgCityService.class);
    private final KgJdbc db = mock(KgJdbc.class);

    @BeforeEach
    void setUp()
    {
        ReflectionTestUtils.setField(controller, "kgJobService", jobs);
        ReflectionTestUtils.setField(controller, "kgJobFavoriteMapper", mock(KgJobFavoriteMapper.class));
        ReflectionTestUtils.setField(controller, "cityService", cities);
        ReflectionTestUtils.setField(controller, "db", db);
        when(cities.businessCity(anyString())).thenAnswer(inv -> {
            String n = ((String) inv.getArgument(0)).trim();
            return "平潭岛".equals(n) ? "平潭" : ("思明区".equals(n) ? "厦门" : n);
        });
        // 字典里只属于平潭的别名（KgCityService.jobCityTerms）
        when(cities.jobCityTerms(anyString())).thenAnswer(inv -> "平潭".equals(inv.getArgument(0))
                ? new ArrayList<String>(Arrays.asList("平潭岛", "平潭县")) : new ArrayList<String>());
        when(jobs.selectAppJobList(any())).thenReturn(new ArrayList<KgJob>());
    }

    @AfterEach
    void tearDown()
    {
        PageHelper.clearPage();
    }

    private KgJob query(String city, String monthBonus)
    {
        controller.list("volunteer", city, null, null, null, null, null, null, null, null, monthBonus, 1, 20);
        ArgumentCaptor<KgJob> q = ArgumentCaptor.forClass(KgJob.class);
        verify(jobs, atLeastOnce()).selectAppJobList(q.capture());
        return q.getValue();
    }

    @Test
    void cityIsNormalizedToTheBusinessCity()
    {
        assertEquals("平潭", query(" 平潭岛 ", null).getCity());
        assertEquals("厦门", query("思明区", null).getCity());
        assertEquals("平潭", query("平潭", null).getCity());
        assertNull(query("", null).getCity());
        assertNull(query("全部城市", null).getCity());
        // like 通配符去掉，不能用「%」查出所有城市
        assertNull(query("%", null).getCity());
        assertEquals("大理", query("大%理_", null).getCity());
        String tooLong = "很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长";
        assertThrows(ServiceException.class, () -> controller.list("volunteer", tooLong, null, null, 1, 20));
    }

    @Test
    void cityIsMatchedBySuffixOfTheCityAndItsAliases()
    {
        // 选「平潭」：平潭 / 平潭岛 / 平潭县 都按「等于或以它结尾」匹配（KgJobCity），福建平潭、福建平潭岛都能出来
        KgJob q = query("平潭", null);
        assertEquals(Arrays.asList("%平潭", "%平潭岛", "%平潭县"), q.getParams().get("cityLikes"));
        assertEquals(new ArrayList<String>(), q.getParams().get("cityExacts"));
        // 不选城市不带条件
        assertFalse(query(null, null).getParams().containsKey("cityLikes"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void keywordWildcardsAreEscaped()
    {
        controller.list("volunteer", null, " 100%_\\ ", null, 1, 20);
        ArgumentCaptor<KgJob> q = ArgumentCaptor.forClass(KgJob.class);
        verify(jobs).selectAppJobList(q.capture());
        // 关键词按规则拆成条件组（KgJobKeyword）：% _ \ 按字面找，拼好前后的 % 走参数绑定
        List<Map<String, Object>> terms = (List<Map<String, Object>>) q.getValue().getParams().get("keywordTerms");
        assertEquals(1, terms.size());
        assertEquals(Arrays.asList("%100\\%\\_\\\\%"), terms.get(0).get("likes"));
        assertFalse(q.getValue().getParams().containsKey("keyword"), "老的只按标题 like 的参数不再用");
    }

    @Test
    void monthBonusOnlyWhenAsked()
    {
        assertFalse(query(null, null).getParams().containsKey("monthBonus"));
        assertFalse(query(null, "0").getParams().containsKey("monthBonus"));
        assertEquals(Boolean.TRUE, query(null, "1").getParams().get("monthBonus"));
        assertEquals(Boolean.TRUE, query(null, "true").getParams().get("monthBonus"));
    }

    @Test
    void rowsCarryTheMonthRedpacketTag()
    {
        KgJob a = new KgJob(); a.setJobId(1L); a.setJobType("volunteer"); a.setMonthRedpacket("0");
        KgJob b = new KgJob(); b.setJobId(2L); b.setJobType("volunteer"); b.setMonthRedpacket("1");
        KgJob c = new KgJob(); c.setJobId(3L); c.setJobType("volunteer");
        when(jobs.selectAppJobList(any())).thenReturn(new ArrayList<KgJob>(Arrays.asList(a, b, c)));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) controller.list("volunteer", null, null, null, 1, 20).get("rows");
        assertEquals(Boolean.TRUE, rows.get(0).get("monthRedpacket"));
        assertEquals(Boolean.FALSE, rows.get(1).get("monthRedpacket"));
        assertEquals(Boolean.FALSE, rows.get(2).get("monthRedpacket"));
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void citiesWithJobsMergeAliasesAndSortByCount()
    {
        when(db.list(anyString(), anyMap())).thenReturn(Arrays.asList(
                KgJdbc.p("city", "大理", "cnt", 2L), KgJdbc.p("city", "平潭岛", "cnt", 2L), KgJdbc.p("city", "平潭", "cnt", 1L),
                KgJdbc.p("city", "不限", "cnt", 5L), KgJdbc.p("city", "", "cnt", 4L)));
        AjaxResult r = controller.cities("volunteer");
        List<Map<String, Object>> list = (List<Map<String, Object>>) r.get(AjaxResult.DATA_TAG);
        assertEquals(2, list.size());
        assertEquals("平潭", list.get(0).get("name"));
        assertEquals(3L, list.get(0).get("count"));
        assertEquals("大理", list.get(1).get("name"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map> ps = ArgumentCaptor.forClass(Map.class);
        verify(db).list(sql.capture(), ps.capture());
        // 和列表同一口径：已上架 / 暂停、门店启用；线上任务不算城市
        assertTrue(sql.getValue().contains("j.status in ('published', 'paused')"), sql.getValue());
        assertTrue(sql.getValue().contains("st.status = '0' and st.del_flag = '0'"), sql.getValue());
        assertTrue(sql.getValue().contains("j.job_type <> 'online'"), sql.getValue());
        assertTrue(sql.getValue().contains("j.job_type = :t"), sql.getValue());
        assertEquals("volunteer", ps.getValue().get("t"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void provincePrefixedCityMergesIntoTheCityAndCountsMatchTheListLike()
    {
        // 生产（10-16）：下拉里「大理 221」「云南大理 3」两行，选「大理」列表按 like %大理% 出 224 个
        when(cities.jobCity(anyString())).thenAnswer(inv -> {
            String n = ((String) inv.getArgument(0)).trim();
            return "云南大理".equals(n) ? "大理" : ("平潭岛".equals(n) ? "平潭" : n);
        });
        when(db.list(anyString(), anyMap())).thenReturn(Arrays.asList(
                KgJdbc.p("city", "大理", "cnt", 221L), KgJdbc.p("city", "云南大理", "cnt", 3L),
                KgJdbc.p("city", "平潭岛", "cnt", 4L), KgJdbc.p("city", "平潭", "cnt", 2L),
                KgJdbc.p("city", "思明区", "cnt", 6L), KgJdbc.p("city", "不限", "cnt", 9L),
                KgJdbc.p("city", "大连", "cnt", 2L), KgJdbc.p("city", "五大连池", "cnt", 1L), KgJdbc.p("city", "福建平潭", "cnt", 1L)));
        List<Map<String, Object>> all = (List<Map<String, Object>>) controller.cities(null).get(AjaxResult.DATA_TAG);
        // 「大连」只数大连自己（2），不再把「五大连池」算进去；「五大连池」自己单列一行
        assertTrue(all.toString().contains("name=大连, count=2"), all.toString());
        assertTrue(all.toString().contains("name=五大连池, count=1"), all.toString());
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> m : all)
        {
            if ("大理".equals(m.get("name")) || "平潭".equals(m.get("name")) || "厦门".equals(m.get("name")))
            {
                list.add(m);
            }
        }
        assertEquals(2, list.size(), list.toString());
        assertEquals("大理", list.get(0).get("name"));
        assertEquals(224L, list.get(0).get("count"));
        assertEquals("平潭", list.get(1).get("name"));
        // 平潭 4 + 平潭岛 2 + 福建平潭 1（以「平潭」或别名「平潭岛」结尾）
        assertEquals(7L, list.get(1).get("count"));
        // 下拉里的名字传回列表接口时原样查它自己（以大理结尾）
        assertEquals("大理", query("大理", null).getCity());
        // 只写了区县、列表查不出来的不给一个点进去为空的城市（思明区 → 厦门，以「厦门」结尾的是 0）
        assertFalse(list.toString().contains("厦门"), list.toString());
    }

    @Test
    void citiesRejectUnknownTypeAndSkipOnline()
    {
        assertThrows(ServiceException.class, () -> controller.cities("' or 1=1"));
        AjaxResult r = controller.cities("online");
        assertTrue(((List<?>) r.get(AjaxResult.DATA_TAG)).isEmpty());
        verifyNoInteractions(db);
    }
}
