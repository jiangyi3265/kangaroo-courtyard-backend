package com.ruoyi.kangaroo.ext.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.domain.JobFilterFields;

/** 岗位关键词的规则拆词和「感觉」词表（KgJobKeyword）、技能词典新词（KgSkills）、筛选条件取值（JobFilterFields）（不连库） */
class KgJobKeywordTest
{
    @SuppressWarnings("unchecked")
    private static List<String> list(Map<String, Object> m, String k)
    {
        return (List<String>) m.get(k);
    }

    private static List<Map<String, Object>> terms(String k)
    {
        return KgJobKeyword.terms(k, null);
    }

    @Test
    void plainWordsAreSplitAndEachMustMatch()
    {
        List<Map<String, Object>> t = terms(" 大理，咖啡  民宿 ");
        assertEquals(3, t.size());
        assertEquals(Arrays.asList("%大理%"), list(t.get(0), "likes"));
        assertEquals(Arrays.asList("%咖啡%"), list(t.get(1), "likes"));
        assertEquals(Arrays.asList("%民宿%"), list(t.get(2), "likes"));
        // 普通词也认城市：岗位城市等于它或以它结尾（KgJobCity 口径，不是包含；mapper 里只对线下岗位）
        assertEquals(Arrays.asList("%大理"), list(t.get(0), "cityLikes"));
        assertEquals(Arrays.asList("%咖啡"), list(t.get(1), "cityLikes"));
        assertEquals(Arrays.asList(), list(t.get(0), "cityExacts"));
        // 「的」和连接词在词和词之间切开：大理的民宿 = 大理 + 民宿；大理附近 = 大理
        assertEquals(2, terms("大理的民宿").size());
        assertEquals(Arrays.asList("%大理%"), list(terms("大理附近").get(0), "likes"));
        // 开头的「去 / 找 / 想」去掉
        assertEquals(Arrays.asList("%大理%"), list(terms("去大理").get(0), "likes"));
        assertEquals(Arrays.asList("%咖啡%"), list(terms("找咖啡").get(0), "likes"));
        // 「美食」不会被当成「美」+「食」
        assertEquals(Arrays.asList("%美食%"), list(terms("美食").get(0), "likes"));
        // 只剩连接词 / 单字虚词：不筛
        assertTrue(terms("城市").isEmpty());
        assertTrue(terms(" 想去的地方 ").isEmpty());
        assertTrue(terms("很美").isEmpty());
        assertTrue(terms(null).isEmpty());
        assertTrue(terms("   ").isEmpty());
        // 重复的词只算一次
        assertEquals(1, terms("咖啡 咖啡").size());
    }

    /** 每组的文字写法（likes），按组排好 */
    private static List<List<String>> likes(String k)
    {
        List<List<String>> out = new ArrayList<List<String>>();
        for (Map<String, Object> m : terms(k))
        {
            out.add(list(m, "likes"));
        }
        return out;
    }

    @Test
    void deAndJoinersOnlySplitBetweenWordsNeverInsideAWord()
    {
        // 「目的地」：切开只剩「目」「地」，不切，整词去找（之前变成「目」+「地」两组，带出「项目基地」这类岗位）
        assertEquals(Arrays.asList(Arrays.asList("%目的地%")), likes("目的地"));
        assertEquals(Arrays.asList("%目的地"), list(terms("目的地").get(0), "cityLikes"));
        // 「工作室」「工作日」：连接词「工作」切开只剩一个字，不切（之前变成「室」，带出「浴室保洁」）
        assertEquals(Arrays.asList(Arrays.asList("%工作室%")), likes("工作室"));
        assertEquals(Arrays.asList(Arrays.asList("%工作日%")), likes("工作日"));
        assertEquals(Arrays.asList(Arrays.asList("%陶艺工作室%")), likes("陶艺工作室"));
        // 整段就是连接词：不要
        assertTrue(terms("工作").isEmpty());
        assertTrue(terms("找工作").isEmpty(), "「找」是虚字，切开后丢掉");
        // 「的」在词和词之间才切
        assertEquals(Arrays.asList(Arrays.asList("%大理%"), Arrays.asList("%咖啡%")), likes("大理的咖啡"));
        assertEquals(Arrays.asList(Arrays.asList("%大理%"), Arrays.asList("%工作室%")), likes("大理的工作室"));
        assertEquals(Arrays.asList(Arrays.asList("%民宿%")), likes("我的民宿"), "「我」是虚字，切开后丢掉");
        assertEquals(Arrays.asList(Arrays.asList("%目的地%")), likes("目的地附近"), "连接词照切，词里的「的」不切");
        // 句子：我想 / 附近 / 的 都在词和词之间
        assertEquals(Arrays.asList(Arrays.asList("%大理%"), Arrays.asList("%民宿%")), likes("我想去大理附近的民宿"));
        assertEquals(Arrays.asList(Arrays.asList("%民宿%")), likes("民宿工作"));
        // 感觉词认过以后剩下的「的」「美的地方」：不要
        List<Map<String, Object>> quietSea = terms("安静的海边");
        assertEquals(1, quietSea.size(), quietSea.toString());
        assertEquals(Arrays.asList("%安静%"), list(quietSea.get(0), "likes"));
        assertEquals(1, terms("风景美的地方").size());
        // 「换工」按字面找（不当城市「义县」，也不拆）
        assertEquals(Arrays.asList(Arrays.asList("%换工%", "%义工%")), likes("换工"));
        assertEquals(Arrays.asList(Arrays.asList("%义工%", "%换工%")), likes("义工"));
        assertEquals(Arrays.asList(Arrays.asList("%摄影换工%", "%摄影义工%")), likes("摄影换工"));
        assertEquals(Arrays.asList("%换工"), list(terms("换工").get(0), "cityLikes"));
        // 「海边 小众 咖啡 民宿」= 感觉一组 + 咖啡 + 民宿
        assertEquals(Arrays.asList(Arrays.asList("%小众%"), Arrays.asList("%咖啡%"), Arrays.asList("%民宿%")), likes("海边 小众 咖啡 民宿"));
        // 切词本身
        assertEquals(Arrays.asList("目的地"), KgJobKeyword.cut("目的地"));
        assertEquals(Arrays.asList("工作室"), KgJobKeyword.cut("工作室"));
        assertEquals(Arrays.asList("大理", "咖啡"), KgJobKeyword.cut("大理的咖啡"));
        assertEquals(Arrays.asList(), KgJobKeyword.cut("想去的地方"));
        assertEquals(Arrays.asList(), KgJobKeyword.cut(""));
    }

    @Test
    void wildcardsAreLiteral()
    {
        assertEquals(Arrays.asList("%100\\%\\_\\\\%"), list(terms("100%_\\").get(0), "likes"));
    }

    @Test
    void lengthAndTermLimits()
    {
        ServiceException e = assertThrows(ServiceException.class, () -> terms(new String(new char[65]).replace('\0', 'a')));
        assertEquals(Integer.valueOf(422), e.getCode());
        assertDoesNotThrow(() -> terms(new String(new char[64]).replace('\0', 'a')));
        assertEquals(KgJobKeyword.MAX_TERMS, terms("一 二 三 四 五 六 七 八").size());
    }

    @Test
    void seasideMapsToCoastalCitiesOrTheWordItself()
    {
        List<Map<String, Object>> t = terms("海边");
        assertEquals(1, t.size());
        // 「海边」的文字不用 like：前一个字是洱 / 邛（洱海、邛海是湖）的不算
        assertEquals(Arrays.asList(), list(t.get(0), "likes"));
        assertEquals(Arrays.asList("(^|[^洱邛])海边"), list(t.get(0), "regexps"));
        List<String> cities = list(t.get(0), "cityLikes");
        for (String c : KgJobKeyword.COASTAL)
        {
            assertTrue(cities.contains("%" + c), c);
        }
        assertFalse(cities.contains("%大理"), "洱海不是海边城市");
        // 海景、看海、沙滩同一组：合成一组条件；「看海」「沙滩」前面不会是湖名，照旧 like
        t = terms("看海 沙滩");
        assertEquals(1, t.size(), t.toString());
        assertEquals(Arrays.asList("%看海%", "%沙滩%"), list(t.get(0), "likes"));
        assertNull(t.get(0).get("regexps"));
        t = terms("海景 海岛 海滨");
        assertEquals(Arrays.asList("(^|[^洱邛])海景", "(^|[^洱邛])海岛", "(^|[^洱邛])海滨"), list(t.get(0), "regexps"));
        // 「海边城市」：认了海边，「城市」是连接词
        assertEquals(1, terms("海边城市").size());
    }

    @Test
    void lakeGuardRegexMatchesTheSeaButNotErhai()
    {
        // 和 MySQL 8 的 REGEXP（ICU，找到一处就算）同一写法：这里用 Java 的 find 核对正则本身
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(KgJobKeyword.notAfterLake("海边"));
        assertTrue(p.matcher("海边小屋").find());
        assertTrue(p.matcher("福建省平潭县筛验海边小屋").find());
        assertTrue(p.matcher("住在洱海旁，周末去北海海边").find(), "同一段里另有真正的海边照样算");
        assertFalse(p.matcher("洱海边花艺帮手").find());
        assertFalse(p.matcher("西昌邛海边民宿").find());
        assertFalse(p.matcher("喜洲古镇").find());
        // 正则里只有代码常量：用户输入的正则符号进的是普通词那一组（like，按字面）
        for (String k : new String[] { "海边.*", "海边[^a]", "海边(|)", "海边\\d" })
        {
            List<Map<String, Object>> t = terms(k);
            assertEquals(Arrays.asList("(^|[^洱邛])海边"), list(t.get(0), "regexps"), k);
            for (int i = 1; i < t.size(); i++)
            {
                assertNull(t.get(i).get("regexps"), k);
            }
        }
    }

    @Test
    void lakeNamesInTheKeywordStayPlainWordsInsteadOfTheSeasideVibe()
    {
        // 「洱海边」：关键词里的「海边」紧跟在「洱」后面，不认成海边，整词按字面找（之前剩一个「洱」，海边正则又排除了洱海边，一条都搜不到）
        List<Map<String, Object>> t = terms("洱海边");
        assertEquals(1, t.size(), t.toString());
        assertEquals(Arrays.asList("%洱海边%"), list(t.get(0), "likes"));
        assertNull(t.get(0).get("regexps"), "不带海边的正则（它会排除洱海边）");
        assertEquals(Arrays.asList("%洱海边"), list(t.get(0), "cityLikes"), "普通词只带它自己的城市写法，不带海边城市名单");
        assertNull(t.get(0).get("towns"));
        // 邛海边同理
        assertEquals(Arrays.asList(Arrays.asList("%邛海边%")), likes("邛海边"));
        assertNull(terms("邛海边").get(0).get("regexps"));
        // 洱海边的民宿 = 洱海边 + 民宿，两组「并且」，都按字面
        List<Map<String, Object>> stay = terms("洱海边的民宿");
        assertEquals(Arrays.asList(Arrays.asList("%洱海边%"), Arrays.asList("%民宿%")), likes("洱海边的民宿"));
        for (Map<String, Object> m : stay)
        {
            assertNull(m.get("regexps"), stay.toString());
        }
        // 整词照原样留下（岗位 10015「洱海边花艺帮手」）；海景前面是洱也一样
        assertEquals(Arrays.asList(Arrays.asList("%洱海边花艺%")), likes("洱海边花艺"));
        assertEquals(Arrays.asList(Arrays.asList("%洱海景观%")), likes("洱海景观"));
        // 「海边」单写照旧：海边城市 + 排除洱海边的正则，没有 like
        Map<String, Object> sea = terms("海边").get(0);
        assertEquals(Arrays.asList(), list(sea, "likes"));
        assertEquals(Arrays.asList("(^|[^洱邛])海边"), list(sea, "regexps"));
        assertTrue(list(sea, "cityLikes").contains("%厦门"));
        // 「安静的海边」照旧一组（安静 或者 海边）
        List<Map<String, Object>> quietSea = terms("安静的海边");
        assertEquals(1, quietSea.size(), quietSea.toString());
        assertEquals(Arrays.asList("(^|[^洱邛])海边"), list(quietSea.get(0), "regexps"));
        // 同一句里另有真正的海边：海边那一组 + 洱海边一组「并且」
        List<Map<String, Object>> both = terms("洱海边，也想去海边");
        assertEquals(2, both.size(), both.toString());
        assertEquals(Arrays.asList("(^|[^洱邛])海边"), list(both.get(0), "regexps"));
        assertEquals(Arrays.asList("%洱海边%"), list(both.get(1), "likes"));
        // 前面隔了空格 / 别的字就不算湖名：照旧认海边
        assertNotNull(terms("洱 海边").get(0).get("regexps"));
        assertNotNull(terms("洱源海边").get(0).get("regexps"));
        // takeVibe 本身：只换掉不跟在湖名后面的那几处；一处都没认到返回 null
        KgJobKeyword.Vibe seaVibe = KgJobKeyword.VIBES.get(0);
        assertEquals("海边", seaVibe.word);
        assertNull(KgJobKeyword.takeVibe("洱海边", seaVibe));
        assertNull(KgJobKeyword.takeVibe("邛海边民宿", seaVibe));
        assertEquals("洱海边 ", KgJobKeyword.takeVibe("洱海边海边", seaVibe));
        assertEquals(" 洱海边", KgJobKeyword.takeVibe("海边洱海边", seaVibe));
        assertEquals("安静的 ", KgJobKeyword.takeVibe("安静的海边", seaVibe));
        // 不防湖名的说法（看海）照旧全换
        KgJobKeyword.Vibe look = null;
        for (KgJobKeyword.Vibe v : KgJobKeyword.VIBES)
        {
            look = "看海".equals(v.word) ? v : look;
        }
        assertEquals("洱 ", KgJobKeyword.takeVibe("洱看海", look));
    }

    @Test
    void cityTiersAreSeparatedAndOneTierAloneIsNotGuessed()
    {
        List<String> big = list(terms("一二线城市").get(0), "cityLikes");
        assertTrue(big.containsAll(Arrays.asList("%北京", "%上海", "%成都", "%杭州", "%昆明", "%厦门")), big.toString());
        assertEquals(1, terms("一二线城市").size());
        assertEquals(big, list(terms("大城市").get(0), "cityLikes"));
        List<String> newFirst = list(terms("新一线城市").get(0), "cityLikes");
        assertTrue(newFirst.contains("%成都") && !newFirst.contains("%北京") && !newFirst.contains("%昆明"), newFirst.toString());
        assertEquals(1, terms("新一线城市").size(), "「新一线城市」不会再被当成「一线城市」");
        List<String> first = list(terms("一线城市").get(0), "cityLikes");
        assertEquals(Arrays.asList("%北京", "%上海", "%广州", "%深圳"), first);
        // 单写「一线」（一线海景）不当城市等级：海景是感觉那一组，「一线」是普通词，两组「并且」
        List<Map<String, Object>> sea = terms("一线海景");
        assertEquals(2, sea.size(), sea.toString());
        assertTrue(list(sea.get(0), "cityLikes").contains("%三亚"));
        assertEquals(Arrays.asList("(^|[^洱邛])海景"), list(sea.get(0), "regexps"));
        assertEquals(Arrays.asList("%一线%"), list(sea.get(1), "likes"));
        assertEquals(Arrays.asList("%一线"), list(sea.get(1), "cityLikes"), "普通词只带它自己的城市写法，不带城市等级名单");
    }

    @Test
    void quietAndScenicWordsAndCombinations()
    {
        List<Map<String, Object>> t = terms("小众安静");
        assertEquals(1, t.size(), "同一组的说法合成一组：" + t);
        assertEquals(Arrays.asList("%小众%", "%安静%"), list(t.get(0), "likes"));
        List<String> quiet = list(t.get(0), "cityLikes");
        assertTrue(quiet.containsAll(Arrays.asList("%腾冲", "%香格里拉", "%安吉", "%阳朔", "%平潭")), quiet.toString());
        assertFalse(quiet.contains("%大理") || quiet.contains("%北京"), quiet.toString());
        // 风景美：认「风景」，剩下的「美」不要
        List<Map<String, Object>> scenic = terms("风景美");
        assertEquals(1, scenic.size(), scenic.toString());
        assertTrue(list(scenic.get(0), "cityLikes").containsAll(Arrays.asList("%大理", "%丽江", "%阳朔")));
        assertNull(scenic.get(0).get("towns"), "古镇只跟小众安静");
    }

    @Test
    @SuppressWarnings("unchecked")
    void quietTownsMatchTheAddressInsideTheirCityNotTheCityColumn()
    {
        Map<String, Object> t = terms("安静").get(0);
        List<String> quiet = list(t, "cityLikes");
        // 古镇不当城市比（岗位城市写的是「云南大理」，永远不会以「双廊」结尾）
        assertFalse(quiet.contains("%双廊") || quiet.contains("%喜洲") || quiet.contains("%沙溪"), quiet.toString());
        List<Map<String, Object>> towns = (List<Map<String, Object>>) t.get("towns");
        assertEquals(3, towns.size(), towns.toString());
        assertEquals("%双廊%", towns.get(0).get("addressLike"));
        assertEquals(Arrays.asList("%大理"), list(towns.get(0), "cityLikes"));
        assertEquals("%喜洲%", towns.get(1).get("addressLike"));
        assertEquals("%沙溪%", towns.get(2).get("addressLike"));
        assertEquals(Arrays.asList("%大理", "%剑川"), list(towns.get(2), "cityLikes"));
        // 有城市字典时，所属城市带上别名（大理州…）
        KgCityService cities = mock(KgCityService.class);
        when(cities.jobCityTerms(anyString())).thenAnswer(inv -> "大理".equals(inv.getArgument(0))
                ? new ArrayList<String>(Arrays.asList("大理白族自治州")) : new ArrayList<String>());
        when(cities.businessCity(anyString())).thenAnswer(inv -> inv.getArgument(0));
        Map<String, Object> d = KgJobKeyword.terms("小众", cities).get(0);
        assertEquals(Arrays.asList("%大理", "%大理白族自治州"), list(((List<Map<String, Object>>) d.get("towns")).get(0), "cityLikes"));
    }

    @Test
    void differentVibesAreOredIntoOneGroupWhilePlainWordsStayAnded()
    {
        // 安静的海边 = 安静的 或者 海边的（一组）
        List<Map<String, Object>> both = terms("安静的海边");
        assertEquals(1, both.size(), both.toString());
        assertEquals(Arrays.asList("%安静%"), list(both.get(0), "likes"));
        assertEquals(Arrays.asList("(^|[^洱邛])海边"), list(both.get(0), "regexps"));
        List<String> c = list(both.get(0), "cityLikes");
        assertTrue(c.containsAll(Arrays.asList("%腾冲", "%三亚", "%平潭")), c.toString());
        assertEquals(new java.util.HashSet<String>(c).size(), c.size(), "城市不重复：" + c);
        assertNotNull(both.get(0).get("towns"));
        // 换宿页搜索框的占位例子整句：风景、海边、一二线、小众安静合成一组「或者」，不是四组「并且」（之前整句一个都搜不到）
        List<Map<String, Object>> sentence = terms("风景美，海边，一二线城市，小众安静等等");
        assertEquals(1, sentence.size(), sentence.toString());
        List<String> all = list(sentence.get(0), "cityLikes");
        assertTrue(all.containsAll(Arrays.asList("%大理", "%三亚", "%北京", "%成都", "%昆明", "%腾冲")), all.toString());
        assertEquals(Arrays.asList("%一二线%", "%小众%", "%安静%", "%风景%"), list(sentence.get(0), "likes"));
        assertEquals(Arrays.asList("(^|[^洱邛])海边"), list(sentence.get(0), "regexps"));
        // 占位例子原句（不带「等等」）也是一组；「美」「城市」切开后丢掉，不会多出一组「并且」
        List<Map<String, Object>> placeholder = terms("风景美，海边，一二线城市，小众安静");
        assertEquals(1, placeholder.size(), placeholder.toString());
        assertEquals(list(sentence.get(0), "cityLikes"), list(placeholder.get(0), "cityLikes"));
        // 感觉 + 普通词：感觉那一组和每个词「并且」
        List<Map<String, Object>> mixed = terms("海边 小众 咖啡 民宿");
        assertEquals(3, mixed.size(), mixed.toString());
        assertEquals(Arrays.asList("%小众%"), list(mixed.get(0), "likes"));
        assertEquals(Arrays.asList("%咖啡%"), list(mixed.get(1), "likes"));
        assertEquals(Arrays.asList("%民宿%"), list(mixed.get(2), "likes"));
        assertEquals(Arrays.asList("%咖啡"), list(mixed.get(1), "cityLikes"), "普通词只带它自己的城市写法");
        assertNull(mixed.get(1).get("towns"));
        assertNull(mixed.get(1).get("regexps"));
        // 感觉那一组只算一组：后面还能放 5 个词
        assertEquals(KgJobKeyword.MAX_TERMS, terms("海边 小众 风景 一 二 三 四 五 六 七").size());
    }

    @Test
    void vibeCitiesUseTheDictionaryAliasesAndDistrictsMapToTheirCity()
    {
        KgCityService cities = mock(KgCityService.class);
        when(cities.jobCityTerms(anyString())).thenAnswer(inv -> "平潭".equals(inv.getArgument(0))
                ? new ArrayList<String>(Arrays.asList("平潭岛", "平潭县")) : new ArrayList<String>());
        when(cities.businessCity(anyString())).thenAnswer(inv -> "思明区".equals(inv.getArgument(0)) ? "厦门" : inv.getArgument(0));
        List<String> sea = list(KgJobKeyword.terms("海边", cities).get(0), "cityLikes");
        assertTrue(sea.containsAll(Arrays.asList("%平潭", "%平潭岛", "%平潭县")), sea.toString());
        Map<String, Object> d = KgJobKeyword.terms("思明区", cities).get(0);
        assertEquals(Arrays.asList("%思明区%"), list(d, "likes"));
        assertEquals(Arrays.asList("%思明区", "%厦门"), list(d, "cityLikes"));
        // 普通词本身是城市：带上字典别名（平潭 → 平潭岛、平潭县）
        assertEquals(Arrays.asList("%平潭", "%平潭岛", "%平潭县"), list(KgJobKeyword.terms("平潭", cities).get(0), "cityLikes"));
        // 普通词归不到别的城市：只有它自己（以它结尾）
        assertEquals(Arrays.asList("%咖啡"), list(KgJobKeyword.terms("咖啡", cities).get(0), "cityLikes"));
    }

    @Test
    void plainWordCityUsesTheCityFilterRuleNotContains()
    {
        // 「大连」：岗位城市等于大连或以大连结尾（辽宁大连）算，「五大连池」不算（之前 j.city like %大连%）
        Map<String, Object> t = terms("大连").get(0);
        assertEquals(Arrays.asList("%大连"), list(t, "cityLikes"));
        assertFalse(list(t, "cityLikes").contains("%大连%"));
        List<String> dalian = KgJobCity.terms(null, "大连");
        assertTrue(KgJobCity.matches("大连", dalian));
        assertTrue(KgJobCity.matches(" 辽宁大连 ", dalian));
        assertFalse(KgJobCity.matches("五大连池", dalian));
        // 一个字只认相等（KgJobCity 同一口径）
        Map<String, Object> one = terms("义").get(0);
        assertEquals(Arrays.asList(), list(one, "cityLikes"));
        assertEquals(Arrays.asList("义"), list(one, "cityExacts"));
        // 城市写法里的 % _ \ 也按字面找
        assertEquals(Arrays.asList("%a\\%b"), list(terms("a%b").get(0), "cityLikes"));
    }

    // ---------- 技能词典 ----------

    @Test
    void newSkillsExpandToTheirSynonyms()
    {
        assertEquals(Arrays.asList("做饭", "厨", "烹饪", "下厨"), KgSkills.wordsOf("做饭"));
        assertEquals(Arrays.asList("开车", "驾照", "驾驶", "司机"), KgSkills.wordsOf("开车"));
        assertEquals(Arrays.asList("玩游戏", "游戏", "剧本杀", "桌游"), KgSkills.wordsOf("玩游戏"));
        // 「拍摄」是摄影的关键词：按摄影展开，它自己排最前
        List<String> shoot = KgSkills.wordsOf("拍摄");
        assertEquals("拍摄", shoot.get(0));
        assertTrue(shoot.containsAll(Arrays.asList("摄影", "拍照", "相机")), shoot.toString());
        assertTrue(KgSkills.wordsOf("摄影").contains("拍摄"));
        // 简历技能画像也认这些词
        assertTrue(KgSkills.tagsOf("会下厨，有驾照，喜欢桌游和拍摄").containsAll(Arrays.asList("做饭", "开车", "玩游戏", "摄影")));
        // 多选合并去重
        List<String> all = KgSkills.wordsOfAll(Arrays.asList("拍摄", "摄影", "做饭"));
        assertEquals(all.size(), new java.util.HashSet<String>(all).size());
        assertTrue(all.containsAll(Arrays.asList("拍摄", "摄影", "做饭", "下厨")));
        // 老行为不变：词典外的按斜杠拆、原样用
        assertEquals(Arrays.asList("店员", "助手"), KgSkills.wordsOf("店员/助手"));
        assertEquals(Arrays.asList("内容写作"), KgSkills.wordsOf("内容写作"));
    }

    // ---------- 筛选条件取值 ----------

    @Test
    void filterFieldCodesAndLabels()
    {
        assertEquals(Arrays.asList("1-1", "4-3", "2-1", "5-2", "3-2", "3-1", "6-1"), new ArrayList<String>(JobFilterFields.REST_MODES.keySet()));
        assertEquals(Arrays.asList("做一休一", "做四休三", "做二休一", "做五休二", "做三休二", "做三休一", "做六休一"), new ArrayList<String>(JobFilterFields.REST_MODES.values()));
        assertEquals(Arrays.asList("single", "double", "triple", "multi"), new ArrayList<String>(JobFilterFields.ROOM_TYPES.keySet()));
        assertEquals(Arrays.asList("单人间", "双人间", "三人间", "多人间"), new ArrayList<String>(JobFilterFields.ROOM_TYPES.values()));
        assertEquals("做五休二", JobFilterFields.restModeLabel("5-2"));
        assertEquals("", JobFilterFields.restModeLabel(""));
        assertEquals("", JobFilterFields.restModeLabel(null));
        assertEquals("", JobFilterFields.roomTypeLabel("x"));
        assertNull(JobFilterFields.gender(null), "没传 = null，交给调用方决定保持原值还是写空");
        assertEquals("", JobFilterFields.gender(" "));
        assertEquals("F", JobFilterFields.gender("女"));
        // 包不包住
        assertTrue(JobFilterFields.housing("volunteer", "none"));
        assertTrue(JobFilterFields.housing("parttime", "room"));
        assertTrue(JobFilterFields.housing("fulltime", "all"));
        assertFalse(JobFilterFields.housing("parttime", "meal"));
        assertFalse(JobFilterFields.housing("parttime", "part"));
        assertFalse(JobFilterFields.housing("online", "all"));
        // 收口
        assertEquals("", JobFilterFields.genderFor("parttime", "F"));
        assertEquals("F", JobFilterFields.genderFor("volunteer", "F"));
        assertEquals("", JobFilterFields.restModeFor("online", "5-2"));
        assertEquals("", JobFilterFields.roomTypeFor("parttime", "none", "single"));
        // 最短在店
        assertNull(JobFilterFields.minDays(null));
        assertNull(JobFilterFields.minDays(""));
        assertNull(JobFilterFields.minDays(0));
        assertEquals(Integer.valueOf(15), JobFilterFields.minDays("15"));
        assertEquals(Integer.valueOf(20), JobFilterFields.minDays(20L));
        assertEquals(Integer.valueOf(365), JobFilterFields.minDays(365));
        for (Object bad : new Object[] { 366, -1, "abc", "15天", "12345", 15.5d, Boolean.TRUE })
        {
            assertThrows(ServiceException.class, () -> JobFilterFields.minDays(bad), String.valueOf(bad));
        }
    }
}
