package com.ruoyi.kangaroo.controller.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
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
import com.ruoyi.kangaroo.domain.KgStore;
import com.ruoyi.kangaroo.ext.user.KgCityService;
import com.ruoyi.kangaroo.ext.user.KgStoreFollowService;
import com.ruoyi.kangaroo.mapper.KgJobFavoriteMapper;
import com.ruoyi.kangaroo.service.IKgJobService;
import com.ruoyi.kangaroo.service.IKgStoreService;

/**
 * 岗位列表的筛选面板（2026-10-16 第二轮改版）：多城市、性别偏好、休息排班、住宿条件、技能多选、安心保障、规则关键词的参数校验，
 * 列表行 / 详情的新字段（性别偏好、排班和房型文案、各类岗位都下发认证与保险）（不连库）
 */
class AppJobFilterFieldsTest
{
    private final AppJobController controller = new AppJobController();
    private final IKgJobService jobs = mock(IKgJobService.class);
    private final KgCityService cities = mock(KgCityService.class);

    @BeforeEach
    void setUp()
    {
        ReflectionTestUtils.setField(controller, "kgJobService", jobs);
        ReflectionTestUtils.setField(controller, "kgJobFavoriteMapper", mock(KgJobFavoriteMapper.class));
        ReflectionTestUtils.setField(controller, "cityService", cities);
        when(cities.businessCity(anyString())).thenAnswer(inv -> {
            String n = ((String) inv.getArgument(0)).trim();
            return "平潭岛".equals(n) ? "平潭" : ("思明区".equals(n) ? "厦门" : n);
        });
        when(cities.jobCityTerms(anyString())).thenAnswer(inv -> "平潭".equals(inv.getArgument(0))
                ? new ArrayList<String>(Arrays.asList("平潭岛", "平潭县")) : new ArrayList<String>());
        when(jobs.selectAppJobList(any())).thenReturn(new ArrayList<KgJob>());
    }

    @AfterEach
    void tearDown()
    {
        PageHelper.clearPage();
    }

    /** 全部参数版的 list（和 @GetMapping 同一个方法） */
    private Map<String, Object> params(String type, String city, String keyword, String skill, Integer minStay,
            String cityList, String gender, String restModes, String roomTypes, String skills, String assured)
    {
        controller.list(type, city, keyword, null, null, null, null, skill, minStay, null, null,
                cityList, gender, restModes, roomTypes, skills, assured, 1, 20);
        ArgumentCaptor<KgJob> q = ArgumentCaptor.forClass(KgJob.class);
        verify(jobs, atLeastOnce()).selectAppJobList(q.capture());
        return q.getValue().getParams();
    }

    private Map<String, Object> panel(String gender, String restModes, String roomTypes, String skills, String assured)
    {
        return params("volunteer", null, null, null, null, null, gender, restModes, roomTypes, skills, assured);
    }

    private static int code(Runnable r)
    {
        ServiceException e = assertThrows(ServiceException.class, r::run);
        return e.getCode() == null ? 0 : e.getCode();
    }

    // ---------- 城市 ----------

    @Test
    void citiesAreNormalizedExpandedAndUnitedWithCity()
    {
        Map<String, Object> p = params("volunteer", "大理", null, null, null, " 平潭岛 ,思明区，大理,全部城市, ", null, null, null, null, null);
        // 大理（老参数）+ 平潭（平潭岛归一，带出别名）+ 厦门（思明区归一），去重，「全部城市」/ 空的跳过
        assertEquals(Arrays.asList("%大理", "%平潭", "%平潭岛", "%平潭县", "%厦门"), p.get("cityLikes"));
        assertEquals(new ArrayList<String>(), p.get("cityExacts"));
        // 只传 cities 也行
        Map<String, Object> only = params("volunteer", null, null, null, null, "厦门", null, null, null, null, null);
        assertEquals(Arrays.asList("%厦门"), only.get("cityLikes"));
        // 都是「全部城市」= 不限
        assertFalse(params("volunteer", null, null, null, null, "全部城市", null, null, null, null, null).containsKey("cityLikes"));
    }

    @Test
    void atMostFiveCities()
    {
        assertDoesNotThrow(() -> params("volunteer", null, null, null, null, "大理,丽江,厦门,平潭,腾冲,大理", null, null, null, null, null));
        assertEquals(422, code(() -> params("volunteer", null, null, null, null, "大理,丽江,厦门,平潭,腾冲,昆明", null, null, null, null, null)));
        String tooLong = "很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长";
        assertEquals(422, code(() -> params("volunteer", null, null, null, null, "大理," + tooLong, null, null, null, null, null)));
        verify(jobs, times(1)).selectAppJobList(any());
    }

    // ---------- 性别 / 排班 / 房型 / 保障 ----------

    @Test
    void genderIsWhitelisted()
    {
        assertEquals("F", panel("F", null, null, null, null).get("gender"));
        assertEquals("M", panel("男", null, null, null, null).get("gender"));
        for (String none : new String[] { null, "", " ", "不限", "男女不限" })
        {
            assertFalse(panel(none, null, null, null, null).containsKey("gender"), String.valueOf(none));
        }
        for (String bad : new String[] { "X", "f", "' or 1=1", "FM" })
        {
            assertEquals(422, code(() -> panel(bad, null, null, null, null)), bad);
        }
    }

    @Test
    void restModesAndRoomTypesAreWhitelistedAndDeduplicated()
    {
        Map<String, Object> p = panel(null, "5-2, 6-1,5-2,,做三休一", "single,双人间,single", null, null);
        assertEquals(Arrays.asList("5-2", "6-1", "3-1"), p.get("restModes"));
        assertEquals(Arrays.asList("single", "double"), p.get("roomTypes"));
        assertFalse(panel(null, "", " , ", null, null).containsKey("restModes"));
        assertFalse(panel(null, null, "", null, null).containsKey("roomTypes"));
        assertEquals(422, code(() -> panel(null, "5-2,7-0", null, null, null)));
        assertEquals(422, code(() -> panel(null, "5-2') or ('1", null, null, null)));
        assertEquals(422, code(() -> panel(null, null, "suite", null, null)));
    }

    @Test
    void assuredOnlyWhenAsked()
    {
        assertEquals(Boolean.TRUE, panel(null, null, null, null, "1").get("assured"));
        assertEquals(Boolean.TRUE, panel(null, null, null, null, "true").get("assured"));
        assertFalse(panel(null, null, null, null, "0").containsKey("assured"));
        assertFalse(panel(null, null, null, null, null).containsKey("assured"));
    }

    @Test
    void minStayAcceptsTheNewOptions()
    {
        for (int d : new int[] { 7, 15, 20, 30 })
        {
            assertEquals(Integer.valueOf(d), params("volunteer", null, null, null, d, null, null, null, null, null, null).get("minStay"));
        }
    }

    // ---------- 技能多选 ----------

    @Test
    @SuppressWarnings("unchecked")
    void skillsExpandSynonymsAndAnyOneMatches()
    {
        List<String> w = (List<String>) panel(null, null, null, "做饭,拍摄,开车,玩游戏", null).get("skillWords");
        assertTrue(w.containsAll(Arrays.asList("做饭", "厨", "烹饪", "下厨")), w.toString());
        // 「拍摄」是摄影的同义词：也带出写着摄影、拍照的岗位
        assertTrue(w.containsAll(Arrays.asList("拍摄", "摄影", "拍照")), w.toString());
        assertTrue(w.containsAll(Arrays.asList("开车", "驾照", "驾驶", "司机")), w.toString());
        assertTrue(w.containsAll(Arrays.asList("玩游戏", "游戏", "剧本杀", "桌游")), w.toString());
        assertEquals(w.size(), new java.util.HashSet<String>(w).size(), "去重");
        // 和单选的 skill 合在一起
        List<String> both = (List<String>) params("parttime", null, null, "咖啡制作", null, null, null, null, null, "做饭", null).get("skillWords");
        assertTrue(both.containsAll(Arrays.asList("咖啡制作", "手冲", "做饭", "下厨")), both.toString());
        assertFalse(panel(null, null, null, " , ", null).containsKey("skillWords"));
        assertEquals(422, code(() -> panel(null, null, null, "做饭,%' or '1", null)));
        assertEquals(422, code(() -> panel(null, null, null, "a,b,c,d,e,f,g,h,i", null)));
    }

    // ---------- 关键词 ----------

    @Test
    @SuppressWarnings("unchecked")
    void keywordIsSplitIntoAndedTermsWithVibeCities()
    {
        List<Map<String, Object>> t = (List<Map<String, Object>>) params("volunteer", null, "大理 咖啡", null, null, null, null, null, null, null, null).get("keywordTerms");
        assertEquals(2, t.size());
        assertEquals(Arrays.asList("%大理%"), t.get(0).get("likes"));
        assertEquals(Arrays.asList("%咖啡%"), t.get(1).get("likes"));
        // 普通词的城市：等于或以它结尾（KgJobCity 口径，不是包含；mapper 里只对线下岗位）
        assertEquals(Arrays.asList("%大理"), t.get(0).get("cityLikes"));
        // 「的」只在词和词之间切：目的地、工作室整词去找
        t = (List<Map<String, Object>>) params("volunteer", null, "目的地 工作室", null, null, null, null, null, null, null, null).get("keywordTerms");
        assertEquals(2, t.size(), t.toString());
        assertEquals(Arrays.asList("%目的地%"), t.get(0).get("likes"));
        assertEquals(Arrays.asList("%工作室%"), t.get(1).get("likes"));
        // 海边：文字写了海边（前一个字不是洱 / 邛，洱海边不算），或者在海边城市（平潭带上字典别名）
        t = (List<Map<String, Object>>) params("volunteer", null, "想去海边", null, null, null, null, null, null, null, null).get("keywordTerms");
        assertEquals(1, t.size(), t.toString());
        assertEquals(Arrays.asList(), t.get(0).get("likes"));
        assertEquals(Arrays.asList("(^|[^洱邛])海边"), t.get(0).get("regexps"));
        List<String> likes = (List<String>) t.get(0).get("cityLikes");
        assertTrue(likes.containsAll(Arrays.asList("%厦门", "%青岛", "%三亚", "%平潭", "%平潭岛")), likes.toString());
        assertFalse(likes.contains("%大理"));
        // 写区县：按城市筛选同一套归一（思明区 → 厦门）
        t = (List<Map<String, Object>>) params("volunteer", null, "思明区", null, null, null, null, null, null, null, null).get("keywordTerms");
        assertEquals(Arrays.asList("%思明区%"), t.get(0).get("likes"));
        assertEquals(Arrays.asList("%思明区", "%厦门"), t.get(0).get("cityLikes"));
        // 写城市别名：归到平潭，连同平潭的字典别名
        t = (List<Map<String, Object>>) params("volunteer", null, "平潭岛", null, null, null, null, null, null, null, null).get("keywordTerms");
        assertEquals(Arrays.asList("%平潭岛", "%平潭", "%平潭县"), t.get(0).get("cityLikes"));
        // 城市筛选和关键词各管各的：城市参数照旧，关键词的城市写法在 keywordTerms 里
        Map<String, Object> both = params("online", "平潭", "海边", null, null, null, null, null, null, null, null);
        assertEquals(Arrays.asList("%平潭", "%平潭岛", "%平潭县"), both.get("cityLikes"));
        assertEquals(1, ((List<Map<String, Object>>) both.get("keywordTerms")).size());
        // 只有连接词 = 不按关键词筛
        assertFalse(params("volunteer", null, " 城市 的 ", null, null, null, null, null, null, null, null).containsKey("keywordTerms"));
        assertFalse(params("volunteer", null, null, null, null, null, null, null, null, null, null).containsKey("keywordTerms"));
        String tooLong = new String(new char[65]).replace('\0', '海');
        assertEquals(422, code(() -> params("volunteer", null, tooLong, null, null, null, null, null, null, null, null)));
    }

    // ---------- 出参 ----------

    private static KgJob row(long id, String type, String gender, String rest, String room, String cert, String ins)
    {
        KgJob j = new KgJob();
        j.setJobId(id); j.setStoreId(1L); j.setJobType(type); j.setTitle("t" + id); j.setCity("大理");
        j.setGenderPref(gender); j.setRestMode(rest); j.setRoomType(room); j.setSafeCert(cert); j.setInsuranceFlag(ins); j.setMinDays(15);
        j.setSalaryType("none");
        return j;
    }

    @Test
    @SuppressWarnings("unchecked")
    void rowsCarryTheNewFieldsForEveryType()
    {
        when(jobs.selectAppJobList(any())).thenReturn(new ArrayList<KgJob>(Arrays.asList(
                row(1, "volunteer", "F", "5-2", "single", "0", "0"),
                row(2, "parttime", "F", "6-1", "double", "0", "0"),
                row(3, "fulltime", "", "", "", "0", "1"),
                row(4, "online", null, null, null, "0", "0"),
                row(5, "volunteer", null, null, null, null, null))));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) controller.list("volunteer", null, null, null, 1, 20).get("rows");
        Map<String, Object> v = rows.get(0);
        assertEquals("F", v.get("genderPref"));
        assertEquals("5-2", v.get("restMode"));
        assertEquals("做五休二", v.get("restModeLabel"));
        assertEquals("single", v.get("roomType"));
        assertEquals("单人间", v.get("roomTypeLabel"));
        assertEquals(15, v.get("minDays"));
        assertEquals(Boolean.TRUE, v.get("assured"));
        // 兼职、全职也下发认证与保险（列表卡片的「经袋鼠实地拍摄并含保险」）；性别偏好只对换工换宿
        Map<String, Object> pt = rows.get(1);
        assertEquals("0", pt.get("safeCert"));
        assertEquals("0", pt.get("insuranceFlag"));
        assertEquals(Boolean.TRUE, pt.get("assured"));
        assertEquals("", pt.get("genderPref"));
        assertEquals("做六休一", pt.get("restModeLabel"));
        assertEquals("双人间", pt.get("roomTypeLabel"));
        Map<String, Object> ft = rows.get(2);
        assertEquals("0", ft.get("safeCert"));
        assertEquals("1", ft.get("insuranceFlag"));
        assertEquals(Boolean.FALSE, ft.get("assured"));
        assertEquals("", ft.get("restModeLabel"));
        // 线上任务永远不挂
        assertEquals(Boolean.FALSE, rows.get(3).get("assured"));
        // 老数据（null）按没填下发空串
        Map<String, Object> old = rows.get(4);
        assertEquals("", old.get("genderPref"));
        assertEquals("", old.get("restMode"));
        assertEquals("", old.get("restModeLabel"));
        assertEquals("", old.get("roomType"));
        assertEquals("", old.get("roomTypeLabel"));
        assertEquals(Boolean.FALSE, old.get("assured"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void detailCarriesTheNewFields()
    {
        IKgStoreService stores = mock(IKgStoreService.class);
        KgStoreFollowService follows = mock(KgStoreFollowService.class);
        ReflectionTestUtils.setField(controller, "kgStoreService", stores);
        ReflectionTestUtils.setField(controller, "storeFollows", follows);
        KgJob j = row(9, "volunteer", "M", "1-1", "multi", "0", "0");
        j.setStatus("published"); j.setHeadcount(2); j.setHiredCount(0); j.setSalaryMin(new BigDecimal("0"));
        when(jobs.requireJob(9L)).thenReturn(j);
        KgStore s = new KgStore(); s.setStoreId(1L); s.setStatus("0"); s.setSafeCert("0");
        when(stores.selectKgStoreByStoreId(1L)).thenReturn(s);
        Map<String, Object> d = (Map<String, Object>) controller.detail(9L).get(AjaxResult.DATA_TAG);
        assertEquals("M", d.get("genderPref"));
        assertEquals("做一休一", d.get("restModeLabel"));
        assertEquals("多人间", d.get("roomTypeLabel"));
        assertEquals(15, d.get("minDays"));
        assertEquals("0", d.get("safeCert"));
        assertEquals("0", d.get("insuranceFlag"));
        assertEquals(Boolean.TRUE, d.get("assured"));
    }
}
