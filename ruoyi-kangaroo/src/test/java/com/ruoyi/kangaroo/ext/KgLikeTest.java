package com.ruoyi.kangaroo.ext;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ruoyi.kangaroo.ext.user.KgJobCity;

/** like 通配符转义（KgLike）与岗位城市条件（KgJobCity.sql）的拼法，不连库 */
class KgLikeTest
{
    @Test
    void escapesBackslashPercentAndUnderscore()
    {
        assertEquals("100\\%", KgLike.escape("100%"));
        assertEquals("a\\_b", KgLike.escape("a_b"));
        // 反斜杠先转义，不会把后面的 \% 吃掉
        assertEquals("c:\\\\x\\%", KgLike.escape("c:\\x%"));
        assertEquals("咖啡师", KgLike.escape("咖啡师"));
        assertNull(KgLike.escape(null));
        assertEquals("%\\%%", KgLike.contains("%"));
        assertEquals("%%", KgLike.contains(null));
        assertEquals("%平潭\\_", KgLike.endsWith("平潭_"));
    }

    @Test
    void jobCitySqlBindsEveryTermAndNeverOpensUp()
    {
        Map<String, Object> ps = new LinkedHashMap<String, Object>();
        String sql = KgJobCity.sql("j.city", "jc", Arrays.asList("平潭", "平潭岛", "京"), ps);
        assertEquals("(trim(j.city) like :jc0 or trim(j.city) like :jc1 or trim(j.city) = :jc2)", sql);
        assertEquals("%平潭", ps.get("jc0"));
        assertEquals("%平潭岛", ps.get("jc1"));
        // 一个字只认相等
        assertEquals("京", ps.get("jc2"));
        // 写法里的通配符按字面
        ps.clear();
        KgJobCity.sql("j.city", "c0_", Arrays.asList("大%理"), ps);
        assertEquals("%大\\%理", ps.get("c0_0"));
        // 没有写法：恒假，不会变成不限城市
        assertEquals("(1 = 0)", KgJobCity.sql("j.city", "jc", Arrays.<String>asList(), new LinkedHashMap<String, Object>()));
        assertTrue(KgJobCity.terms(null, "  ").isEmpty());
        assertEquals(Arrays.asList("大理"), KgJobCity.terms(null, " 大理 "));
    }

    @Test
    void jobCityMatchesIsSuffixOrExact()
    {
        assertTrue(KgJobCity.matches("云南大理", Arrays.asList("大理")));
        assertTrue(KgJobCity.matches("大理", Arrays.asList("大理")));
        assertFalse(KgJobCity.matches("大理白族自治州", Arrays.asList("大理")));
        assertFalse(KgJobCity.matches("大同区", Arrays.asList("大同")));
        assertFalse(KgJobCity.matches("北京", Arrays.asList("京")));
        assertTrue(KgJobCity.matches("京", Arrays.asList("京")));
        assertFalse(KgJobCity.matches(null, Arrays.asList("大理")));
        assertFalse(KgJobCity.matches("", Arrays.asList("大理")));
    }
}
