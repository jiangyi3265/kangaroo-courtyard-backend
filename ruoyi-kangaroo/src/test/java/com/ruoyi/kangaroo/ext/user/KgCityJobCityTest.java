package com.ruoyi.kangaroo.ext.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * 「有岗位的城市」下拉的城市名归一（KgCityService.jobCity，不连库：kg_city 打桩）。
 * 生产 10-16：岗位城市写成「云南大理」，businessCity 认不出来，下拉里和「大理」分成两行。
 */
class KgCityJobCityTest
{
    private final KgCityService service = new KgCityService();

    private static Map<String, Object> city(String name, String fullName, String parent, String level, String createBy, String aliases)
    {
        return KgJdbc.p("name", name, "fullName", fullName, "parentName", parent, "level", level, "createBy", createBy, "searchAliases", aliases);
    }

    @BeforeEach
    void setUp()
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.list(anyString(), anyMap())).thenReturn(Arrays.asList(
                city("大理", "大理市", "", "destination", "V11", "大理,大理市,dl"),
                city("大理白族自治州", "大理白族自治州", "云南省", "city", "V25", "大理白族自治州,dlbzzzz"),
                city("平潭", "平潭县", "", "destination", "V25", "平潭,平潭县,pt,平潭岛,平潭综合实验区"),
                city("厦门", "厦门市", "福建省", "city", "V11", "厦门市,xm"),
                city("思明区", "思明区", "厦门市", "district", "V25", ""),
                city("古城区", "古城区", "丽江市", "district", "V25", ""),
                city("大连", "大连市", "辽宁省", "city", "V11", "大连市,dl"),
                city("中山", "中山市", "广东省", "city", "V11", "中山市,zs"),
                city("五大连池", "五大连池市", "黑河市", "district", "V25", "")));
        ReflectionTestUtils.setField(service, "db", db);
    }

    @Test
    void provincePrefixAndAliasSuffixFallToTheDictionaryCity()
    {
        assertEquals("大理", service.jobCity("云南大理"));
        assertEquals("大理", service.jobCity(" 大理 "));
        assertEquals("平潭", service.jobCity("平潭岛"));
        assertEquals("平潭", service.jobCity("福建平潭岛"));
        assertEquals("厦门", service.jobCity("厦门市"));
        // 包含多个字典名时取最长的那个
        assertEquals("大理白族自治州", service.jobCity("云南省大理白族自治州"));
    }

    @Test
    void districtsAndUnknownNamesKeepTheBusinessCityRule()
    {
        assertEquals("厦门", service.jobCity("思明区"));
        // 区县名不拿来做包含匹配，也不认一个字的名字；认不出来原样返回
        assertEquals("福建思明区某村", service.jobCity("福建思明区某村"));
        assertEquals("不限", service.jobCity("不限"));
        assertEquals("海岛营地", service.jobCity("海岛营地"));
        assertNull(service.jobCity(null));
        assertEquals("", service.jobCity("  "));
    }

    @Test
    void jobCityIsSuffixBasedLikeTheList()
    {
        // 和列表同一口径（以城市 / 别名结尾）：包含「大连」的「五大连池」不归到大连，「中山区」不归到中山
        assertEquals("五大连池", service.jobCity("五大连池"));
        assertEquals("中山区", service.jobCity("中山区"));
        assertEquals("大连", service.jobCity("辽宁大连"));
        assertEquals("大连", service.jobCity("辽宁省大连市"));
    }

    @Test
    void jobCityTermsAreTheChineseAliasesOfThatCityOnly()
    {
        // 拼音缩写（pt）、和字典名重复的写法（平潭）不进来；区县不算
        assertEquals(Arrays.asList("平潭县", "平潭岛", "平潭综合实验区"), service.jobCityTerms("平潭"));
        assertEquals(Arrays.asList("厦门市"), service.jobCityTerms(" 厦门 "));
        assertTrue(service.jobCityTerms("思明区").isEmpty());
        assertTrue(service.jobCityTerms("没有这个城市").isEmpty());
        assertTrue(service.jobCityTerms(null).isEmpty());

        java.util.List<String> pt = KgJobCity.terms(service, "平潭");
        assertTrue(KgJobCity.matches("平潭岛", pt));
        assertTrue(KgJobCity.matches("福建平潭", pt));
        assertTrue(KgJobCity.matches(" 福建平潭岛 ", pt));
        assertFalse(KgJobCity.matches("平潭岛西", pt));
        java.util.List<String> dl = KgJobCity.terms(service, "大连");
        assertTrue(KgJobCity.matches("大连", dl));
        assertTrue(KgJobCity.matches("辽宁大连市", dl));
        assertFalse(KgJobCity.matches("五大连池", dl));
        assertFalse(KgJobCity.matches("大连区", dl));
        assertFalse(KgJobCity.matches("中山区", KgJobCity.terms(service, "中山")));
    }
}
