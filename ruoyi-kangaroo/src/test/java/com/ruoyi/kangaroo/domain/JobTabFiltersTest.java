package com.ruoyi.kangaroo.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ruoyi.common.exception.ServiceException;

/**
 * 每个页签自己的筛选条件（2026-10-17 主人反馈）：兼职日薪三档、全职月薪三档、食宿四项、全职类型五类的口径（不连库）。
 * SQL 和这里的 Java 版是同一口径：KgJobTabFilterMapperTest 核对 KgJobMapper.xml 里的表达式和 DAILY_MIN_SQL / MONTHLY_MIN_SQL 一字不差
 */
class JobTabFiltersTest
{
    @Test
    void requestedMiddleBandIncludes4500AndExcludesTheGap()
    {
        for (String pay : Arrays.asList("3000", "4000", "4500"))
            assertTrue(JobTabFilters.payMatches("monthly", new BigDecimal(pay), null, 3000L, 4500L));
        for (String pay : Arrays.asList("2999.99", "4500.01", "4800", "5000"))
            assertFalse(JobTabFilters.payMatches("monthly", new BigDecimal(pay), null, 3000L, 4500L));
        assertFalse(JobTabFilters.payMatches("monthly", new BigDecimal("3000"), null, null, 3000L));
        assertTrue(JobTabFilters.payMatches("monthly", new BigDecimal("5000"), null, 5000L, null));
        Map<String, Object> params = new HashMap<>();
        JobTabFilters.putParams(params, null, 3000L, 4500L, Collections.emptyList());
        assertEquals(Boolean.TRUE, params.get("payMonthMaxInclusive"));
        StringBuilder sql = new StringBuilder();
        JobTabFilters.appendSql(sql, new HashMap<>(), null, 3000L, 4500L, Collections.emptyList());
        assertTrue(sql.toString().contains(" <= :pmx"));
    }
    private static BigDecimal d(String v)
    {
        return new BigDecimal(v);
    }

    @Test
    void payParsing()
    {
        assertNull(JobTabFilters.pay((Long) null));
        assertNull(JobTabFilters.pay(Long.valueOf(0)));
        assertNull(JobTabFilters.pay(Long.valueOf(-5)));
        assertEquals(Long.valueOf(80), JobTabFilters.pay(Long.valueOf(80)));
        assertEquals(Long.valueOf(JobTabFilters.PAY_MAX), JobTabFilters.pay(Long.valueOf(99999999999L)));
        // 全部筛选页的 JSON：数字、数字字符串、空串都认，别的 422
        assertEquals(Long.valueOf(100), JobTabFilters.pay((Object) Integer.valueOf(100)));
        assertEquals(Long.valueOf(3000), JobTabFilters.pay((Object) "3000"));
        assertNull(JobTabFilters.pay((Object) ""));
        assertNull(JobTabFilters.pay((Object) null));
        ServiceException e = assertThrows(ServiceException.class, () -> JobTabFilters.pay((Object) "80元"));
        assertEquals(Integer.valueOf(422), e.getCode());
        assertThrows(ServiceException.class, () -> JobTabFilters.pay((Object) "1e9"));
    }

    @Test
    void bandsAreWhatTheOwnerAskedFor()
    {
        assertEquals(Arrays.asList(80, 100, 120), JobTabFilters.PART_DAY_BANDS);
        assertEquals(Arrays.asList(3000, 4500, 5000), JobTabFilters.FULL_MONTH_EDGES);
    }

    @Test
    void dailyMinConvertsEveryUnitTheSameWayAsTheOldSalaryFilter()
    {
        assertEquals(0, d("150").compareTo(JobTabFilters.dailyMin("daily", d("150"))));
        assertEquals(0, d("80").compareTo(JobTabFilters.dailyMin("hourly", d("10"))));      // 10 元/时 × 8
        assertEquals(0, d("100").compareTo(JobTabFilters.dailyMin("weekly", d("500"))));    // 500 元/周 ÷ 5
        assertEquals(0, d("100").compareTo(JobTabFilters.dailyMin("monthly", d("2175"))));  // 2175 元/月 ÷ 21.75
        assertNull(JobTabFilters.dailyMin("piece", d("30")));
        assertNull(JobTabFilters.dailyMin("none", d("30")));
        assertNull(JobTabFilters.dailyMin("daily", null));
        assertNull(JobTabFilters.dailyMin(null, d("100")));
    }

    @Test
    void monthlyMinConvertsEveryUnit()
    {
        assertEquals(0, d("6000").compareTo(JobTabFilters.monthlyMin("monthly", d("6000"))));
        assertEquals(0, d("3262.5").compareTo(JobTabFilters.monthlyMin("daily", d("150"))));  // 150 × 21.75
        assertEquals(0, d("3480").compareTo(JobTabFilters.monthlyMin("hourly", d("20"))));     // 20 × 8 × 21.75
        assertEquals(0, d("4350").compareTo(JobTabFilters.monthlyMin("weekly", d("1000"))));   // 1000 × 4.35
        assertNull(JobTabFilters.monthlyMin("piece", d("100")));
        assertNull(JobTabFilters.monthlyMin("monthly", null));
    }

    @Test
    void partTimeDailyBandsUseTheStartingPay()
    {
        // 「80元/天以上」= 起薪至少 80：正好 80 算，79.99 不算
        assertTrue(JobTabFilters.payMatches("daily", d("80"), 80L, null, null));
        assertFalse(JobTabFilters.payMatches("daily", d("79.99"), 80L, null, null));
        // 区间岗位按下限比：100-150 元/天选「120元/天以上」不出现，选「100元/天以上」出现
        assertFalse(JobTabFilters.payMatches("daily", d("100"), 120L, null, null));
        assertTrue(JobTabFilters.payMatches("daily", d("100"), 100L, null, null));
        // 时薪 15 × 8 = 120；周薪 450 ÷ 5 = 90；月薪 2000 ÷ 21.75 ≈ 91.95
        assertTrue(JobTabFilters.payMatches("hourly", d("15"), 120L, null, null));
        assertTrue(JobTabFilters.payMatches("weekly", d("450"), 80L, null, null));
        assertFalse(JobTabFilters.payMatches("weekly", d("450"), 100L, null, null));
        assertTrue(JobTabFilters.payMatches("monthly", d("2000"), 80L, null, null));
        assertFalse(JobTabFilters.payMatches("monthly", d("2000"), 100L, null, null));
        // 计件、面议、没写金额的岗位选了任何档都不出现；不选档时都在
        assertFalse(JobTabFilters.payMatches("piece", d("500"), 80L, null, null));
        assertFalse(JobTabFilters.payMatches("none", null, 80L, null, null));
        assertFalse(JobTabFilters.payMatches("daily", null, 80L, null, null));
        assertTrue(JobTabFilters.payMatches("none", null, null, null, null));
    }

    @Test
    void fullTimeMonthlyBandsAreContiguous()
    {
        // 3000以下 = [0, 3000)，3000–5000 = [3000, 5000)，5000以上 = [5000, ∞)：每个有月薪口径的岗位正好落在一档里
        long[][] bands = { { 0, 3000 }, { 3000, 5000 }, { 5000, 0 } };
        String[][] cases = { { "monthly", "2999.99", "0" }, { "monthly", "3000", "1" }, { "monthly", "4999", "1" }, { "monthly", "5000", "2" },
                { "monthly", "12000", "2" }, { "daily", "100", "0" }, { "daily", "150", "1" }, { "daily", "230", "2" },
                { "hourly", "20", "1" }, { "weekly", "1200", "2" } };
        for (String[] c : cases)
        {
            int hits = 0;
            for (int i = 0; i < bands.length; i++)
            {
                Long min = bands[i][0] > 0 ? bands[i][0] : null;
                Long max = bands[i][1] > 0 ? bands[i][1] : null;
                boolean m = JobTabFilters.payMatches(c[0], d(c[1]), null, min, max);
                assertEquals(Integer.parseInt(c[2]) == i, m, Arrays.toString(c) + " band " + i);
                hits += m ? 1 : 0;
            }
            assertEquals(1, hits, Arrays.toString(c));
        }
        assertFalse(JobTabFilters.payMatches("none", null, null, null, 3000L));
        assertFalse(JobTabFilters.payMatches("piece", d("100"), null, 5000L, null));
    }

    @Test
    void monthRangeMustBeOpenOnTheRight()
    {
        assertThrows(ServiceException.class, () -> JobTabFilters.checkMonthRange(5000L, 5000L));
        assertThrows(ServiceException.class, () -> JobTabFilters.checkMonthRange(5000L, 3000L));
        JobTabFilters.checkMonthRange(3000L, 5000L);
        JobTabFilters.checkMonthRange(null, 3000L);
        JobTabFilters.checkMonthRange(5000L, null);
    }

    @Test
    void boardCodesAndLabels()
    {
        assertNull(JobTabFilters.board(null));
        assertNull(JobTabFilters.board(""));
        assertNull(JobTabFilters.board("none"));   // 老版本小程序 / 全部筛选页的「不限」
        assertNull(JobTabFilters.board("不限"));
        for (String c : Arrays.asList("all", "room", "meal", "self"))
        {
            assertEquals(c, JobTabFilters.board(c));
        }
        assertEquals("all", JobTabFilters.board("包吃包住"));
        assertEquals("room", JobTabFilters.board("包住"));
        assertEquals("meal", JobTabFilters.board("包吃"));
        assertEquals("self", JobTabFilters.board(" 不包食宿 "));
        for (String bad : Arrays.asList("part", "ALL", "包三餐", "x' or 1=1"))
        {
            ServiceException e = assertThrows(ServiceException.class, () -> JobTabFilters.board(bad), bad);
            assertEquals("食宿条件不合法", e.getMessage());
            assertEquals(Integer.valueOf(422), e.getCode());
        }
        assertEquals(Arrays.asList("包吃包住", "包住", "包吃", "不包食宿"), Arrays.asList(JobTabFilters.BOARDS.values().toArray()));
    }

    @Test
    void boardMatching()
    {
        // 包吃包住只认 all；包住 = all / room；包吃 = all / meal；不包食宿 = none / 没填；「包部分」part 哪个都不算
        String[] types = { "all", "room", "meal", "part", "none", "", null };
        Map<String, List<Boolean>> expect = new LinkedHashMap<String, List<Boolean>>();
        expect.put("all", Arrays.asList(true, false, false, false, false, false, false));
        expect.put("room", Arrays.asList(true, true, false, false, false, false, false));
        expect.put("meal", Arrays.asList(true, false, true, false, false, false, false));
        expect.put("self", Arrays.asList(false, false, false, false, true, true, true));
        for (Map.Entry<String, List<Boolean>> e : expect.entrySet())
        {
            for (int i = 0; i < types.length; i++)
            {
                assertEquals(e.getValue().get(i), JobTabFilters.boardMatches(types[i], e.getKey()), e.getKey() + " vs " + types[i]);
            }
        }
        assertTrue(JobTabFilters.boardMatches("part", null));
        assertEquals(" and coalesce(j.board_type, 'none') in ('none','')", JobTabFilters.boardSql("self"));
        assertEquals(" and j.board_type in ('all','meal')", JobTabFilters.boardSql("meal"));
        assertEquals("", JobTabFilters.boardSql(null));
    }

    @Test
    void categoryCodesAndLabels()
    {
        assertEquals(Arrays.asList("民宿", "酒店", "咖啡餐饮", "酒吧", "其他"), Arrays.asList(JobTabFilters.CATEGORIES.values().toArray()));
        assertEquals(Collections.emptyList(), JobTabFilters.categories(null));
        assertEquals(Collections.emptyList(), JobTabFilters.categories(" , "));
        assertEquals(Arrays.asList("homestay", "hotel"), JobTabFilters.categories("homestay,酒店,homestay"));
        assertEquals(Arrays.asList("food", "other"), JobTabFilters.categories(Arrays.asList("咖啡餐饮", "other")));
        assertEquals(Arrays.asList("bar"), JobTabFilters.categories("酒吧，"));
        for (String bad : Arrays.asList("cafe", "民宿酒店", "%"))
        {
            ServiceException e = assertThrows(ServiceException.class, () -> JobTabFilters.categories(bad), bad);
            assertEquals("岗位类型不合法", e.getMessage());
            assertEquals(Integer.valueOf(422), e.getCode());
        }
    }

    @Test
    void categoryMappingUsesStoreNameAndTitle()
    {
        // 本地验收库里真实的全职岗位：门店名决定大类
        assertEquals(set("homestay"), JobTabFilters.categoriesOf("双廊听海民宿", "民宿运营店长"));
        assertEquals(set("food"), JobTabFilters.categoriesOf("古城拾光咖啡馆", "咖啡品牌主理人"));
        assertEquals(set("other"), JobTabFilters.categoriesOf("喜洲花间手作", "内容策划专员"));
        // 标题里写「提供食宿」「包住宿」不算民宿（不收单个「宿」字）
        assertEquals(set("other"), JobTabFilters.categoriesOf("苍山书店", "店员（提供食宿）"));
        assertEquals(set("hotel"), JobTabFilters.categoriesOf("洱海度假村", "前台"));
        assertEquals(set("hotel"), JobTabFilters.categoriesOf(null, "酒店前台接待"));
        assertEquals(set("bar"), JobTabFilters.categoriesOf("海边清吧", "调酒师"));
        assertEquals(set("bar"), JobTabFilters.categoriesOf("LiveHouse 现场", "音响助理"));
        assertEquals(set("homestay"), JobTabFilters.categoriesOf("沙溪客栈", "管家"));
        // 「酒店」里的「酒」不会被当成酒吧；民宿里的咖啡岗两类都算
        assertFalse(JobTabFilters.categoriesOf("大理酒店", "前台").contains("bar"));
        assertEquals(set("homestay", "food"), JobTabFilters.categoriesOf("听海民宿", "咖啡师"));
        assertEquals(set("food"), JobTabFilters.categoriesOf("小院烘焙", "面包师"));
        assertEquals(set("other"), JobTabFilters.categoriesOf("", ""));
        // 2026-10-18：全职岗位第一个标签是商家选的门店类型，名字里看不出来也能归对
        assertEquals(set("homestay"), JobTabFilters.categoriesOf("洱海边的院子", "店长", "民宿,管理"));
        assertEquals(set("bar"), JobTabFilters.categoriesOf("海边小屋", "调酒师", "酒吧"));
        assertEquals(set("other"), JobTabFilters.categoriesOf("苍山书店", "店员", "其他"));
        assertEquals(set("food"), JobTabFilters.categoriesOf("古城拾光", "店员", "咖啡餐饮"));
    }

    @Test
    void putParamsBuildsMapperParams()
    {
        Map<String, Object> p = new HashMap<String, Object>();
        JobTabFilters.putParams(p, null, null, null, null);
        assertTrue(p.isEmpty());
        JobTabFilters.putParams(p, 100L, 3000L, 5000L, Arrays.asList("homestay", "other"));
        assertEquals(100L, p.get("payDay"));
        assertEquals(3000L, p.get("payMonthMin"));
        assertEquals(5000L, p.get("payMonthMax"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> terms = (List<Map<String, Object>>) p.get("categoryTerms");
        assertEquals(2, terms.size());
        assertEquals(Boolean.FALSE, terms.get(0).get("other"));
        assertTrue(((List<?>) terms.get(0).get("likes")).contains("%民宿%"));
        assertEquals(Boolean.TRUE, terms.get(1).get("other"));
        assertEquals(JobTabFilters.allCategoryWords().size(), ((List<?>) p.get("categoryAllLikes")).size());
        assertThrows(ServiceException.class, () -> JobTabFilters.putParams(new HashMap<String, Object>(), null, 5000L, 3000L, null));
    }

    @Test
    void appendSqlForTheSearchPage()
    {
        StringBuilder w = new StringBuilder();
        Map<String, Object> ps = new HashMap<String, Object>();
        JobTabFilters.appendSql(w, ps, 120L, null, null, null);
        assertTrue(w.toString().contains(JobTabFilters.DAILY_MIN_SQL + " >= :pd"), w.toString());
        assertTrue(w.toString().contains("j.salary_min is not null"), w.toString());
        assertEquals(120L, ps.get("pd"));

        w = new StringBuilder();
        ps = new HashMap<String, Object>();
        JobTabFilters.appendSql(w, ps, null, 3000L, 5000L, Arrays.asList("bar", "other"));
        String s = w.toString();
        assertTrue(s.contains(JobTabFilters.MONTHLY_MIN_SQL + " >= :pmn"), s);
        assertTrue(s.contains(JobTabFilters.MONTHLY_MIN_SQL + " < :pmx"), s);
        assertTrue(s.contains(" or not ("), s);
        assertTrue(s.contains("coalesce(s.store_name, '') like :cw"), s);
        assertTrue(s.contains(" or coalesce(j.tags, '') like :cw"), s);
        assertEquals("%酒吧%", ps.get("cw" + JobTabFilters.allCategoryWords().indexOf("酒吧")));
        // 所有 like 值都走参数绑定，SQL 里没有词本身
        for (String word : JobTabFilters.allCategoryWords())
        {
            assertFalse(s.contains(word), word);
        }
    }

    private static LinkedHashSet<String> set(String... v)
    {
        return new LinkedHashSet<String>(Arrays.asList(v));
    }
}
