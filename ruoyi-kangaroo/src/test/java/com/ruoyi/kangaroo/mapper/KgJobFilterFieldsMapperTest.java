package com.ruoyi.kangaroo.mapper;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ruoyi.kangaroo.domain.KgJob;
import com.ruoyi.kangaroo.ext.user.KgJobKeyword;

/**
 * 解析真实的 KgJobMapper.xml：V48 岗位筛选条件（性别偏好 / 休息排班 / 住宿房型）查得出、能写；
 * 小程序列表的筛选面板条件（性别、排班、房型、安心保障）和规则关键词拼出的 SQL 对、值都走参数绑定（不连库）
 */
class KgJobFilterFieldsMapperTest
{
    @Test
    void genderPlanColumnsAreSelectedInsertedAndExplicitlyCleared()
    {
        KgJob j=job("volunteer"); j.setHeadcount(2); j.setGenderCountsEnabled(true);
        j.setGenderMaleCount(1);j.setGenderFemaleCount(1);
        String select=sql("selectKgJobList",j),insert=sql("insertKgJob",j),update=sql("updateKgJobByAdmin",j);
        for(String column:Arrays.asList("gender_male_count","gender_female_count")) {
            assertTrue(select.contains(column));assertTrue(insert.contains(column));assertTrue(update.contains(column+" = ?"));
        }
        j.setGenderCountsEnabled(false);j.setGenderMaleCount(null);j.setGenderFemaleCount(null);
        assertTrue(sql("updateKgJobByAdmin",j).contains("gender_male_count = ?"));
        assertEquals(2,config.getResultMap("com.ruoyi.kangaroo.mapper.KgJobMapper.KgJobResult").getResultMappings().stream()
            .filter(m->Arrays.asList("genderMaleCount","genderFemaleCount").contains(m.getProperty())).count());
    }

    Configuration config;

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
    }

    private BoundSql bound(String id, Object param)
    {
        return config.getMappedStatement("com.ruoyi.kangaroo.mapper.KgJobMapper." + id).getBoundSql(param);
    }

    private String sql(String id, Object param)
    {
        return bound(id, param).getSql().replaceAll("\\s+", " ");
    }

    /** 参数绑定的实际值（foreach 的项在 additionalParameter 里，其余按属性取） */
    private static List<Object> values(BoundSql b, KgJob j)
    {
        List<Object> out = new ArrayList<Object>();
        for (ParameterMapping pm : b.getParameterMappings())
        {
            String p = pm.getProperty();
            if (b.hasAdditionalParameter(p))
            {
                out.add(b.getAdditionalParameter(p));
            }
            else if (p.startsWith("params."))
            {
                out.add(j.getParams().get(p.substring("params.".length())));
            }
            else
            {
                out.add(p);
            }
        }
        return out;
    }

    private static KgJob job(String type)
    {
        KgJob j = new KgJob();
        j.setJobId(7L); j.setStoreId(1L); j.setJobType(type); j.setTitle("t"); j.setCity("c"); j.setHeadcount(1); j.setSalaryType("none");
        j.setParams(new HashMap<String, Object>());
        return j;
    }

    @Test
    void selectsAndMapsTheThreeColumns()
    {
        for (String id : new String[] { "selectKgJobByJobId", "selectKgJobList", "selectAppJobList" })
        {
            assertTrue(sql(id, job("volunteer")).contains("j.gender_pref, j.rest_mode, j.room_type,"), id);
        }
        Map<String, String> mapped = new HashMap<String, String>();
        for (ResultMapping m : config.getResultMap("com.ruoyi.kangaroo.mapper.KgJobMapper.KgJobResult").getResultMappings())
        {
            mapped.put(m.getProperty(), m.getColumn());
        }
        assertEquals("gender_pref", mapped.get("genderPref"));
        assertEquals("rest_mode", mapped.get("restMode"));
        assertEquals("room_type", mapped.get("roomType"));
    }

    @Test
    void writesTheColumnsOnlyWhenGiven()
    {
        KgJob j = job("volunteer");
        j.setGenderPref("F"); j.setRestMode("5-2"); j.setRoomType("single");
        String ins = sql("insertKgJob", j);
        assertTrue(ins.contains("gender_pref, rest_mode, room_type,"), ins);
        String upd = sql("updateKgJob", j);
        assertTrue(upd.contains("gender_pref = ?, rest_mode = ?, room_type = ?,"), upd);
        String admin = sql("updateKgJobByAdmin", j);
        assertTrue(admin.contains("gender_pref = ?, rest_mode = ?, room_type = ?,"), admin);
        // 空串（没填）也照写：后台把「做五休二」改回不填要真的清掉
        j.setGenderPref(""); j.setRestMode(""); j.setRoomType("");
        assertTrue(sql("updateKgJobByAdmin", j).contains("gender_pref = ?, rest_mode = ?, room_type = ?,"));
        assertTrue(sql("insertKgJob", j).contains("gender_pref, rest_mode, room_type,"));
        // 没带（老版本表单 / 内部局部更新）：不碰这几列，新增按库默认 ''
        KgJob old = job("volunteer");
        for (String id : new String[] { "insertKgJob", "updateKgJob", "updateKgJobByAdmin" })
        {
            String s = sql(id, old);
            assertFalse(s.contains("gender_pref") || s.contains("rest_mode") || s.contains("room_type"), id + ": " + s);
        }
    }

    @Test
    void noPanelFiltersLeavesTheListAlone()
    {
        String q = sql("selectAppJobList", job("volunteer"));
        assertFalse(q.contains("j.gender_pref in"), q);
        assertFalse(q.contains("j.rest_mode in"), q);
        assertFalse(q.contains("j.room_type in"), q);
        assertFalse(q.contains("(case when j.recruit_store_id is null then s.safe_cert else rs.safe_cert end) = '0'"), q);
        assertFalse(q.contains(" like "), q);
    }

    @Test
    void genderMatchesTheSameGenderOrNotLimited()
    {
        KgJob j = job("volunteer");
        j.getParams().put("gender", "F");
        BoundSql b = bound("selectAppJobList", j);
        String q = b.getSql().replaceAll("\\s+", " ");
        assertTrue(q.contains("and j.gender_pref in ('', ?)"), q);
        assertTrue(values(b, j).contains("F"));
        assertFalse(q.contains("'F'"), "性别走参数绑定");
        // 空串 = 男女不限，不加条件
        j.getParams().put("gender", "");
        assertFalse(sql("selectAppJobList", j).contains("gender_pref in"));
    }

    @Test
    void restModesAndRoomTypesAreBoundInLists()
    {
        KgJob j = job("parttime");
        j.getParams().put("restModes", Arrays.asList("5-2", "6-1"));
        j.getParams().put("roomTypes", Arrays.asList("single"));
        BoundSql b = bound("selectAppJobList", j);
        String q = b.getSql().replaceAll("\\s+", " ");
        assertTrue(q.contains("and j.rest_mode in ( ? , ? )") || q.contains("and j.rest_mode in (?,?)") || q.matches(".*and j\\.rest_mode in \\(\\s*\\?\\s*,\\s*\\?\\s*\\).*"), q);
        assertTrue(q.matches(".*and j\\.room_type in \\(\\s*\\?\\s*\\).*"), q);
        List<Object> v = values(b, j);
        assertTrue(v.containsAll(Arrays.asList("5-2", "6-1", "single")), v.toString());
        assertFalse(q.contains("'5-2'") || q.contains("'single'"), "取值走参数绑定");
        // 空列表不加条件（没选 = 不筛）
        j.getParams().put("restModes", new ArrayList<String>());
        j.getParams().put("roomTypes", new ArrayList<String>());
        String none = sql("selectAppJobList", j);
        assertFalse(none.contains("rest_mode in") || none.contains("room_type in"), none);
    }

    @Test
    void assuredNeedsCertifiedStoreInsuredOfflineJob()
    {
        KgJob j = job("volunteer");
        j.getParams().put("assured", Boolean.TRUE);
        String q = sql("selectAppJobList", j);
        assertTrue(q.contains("and j.job_type <> 'online' and j.insurance_flag = '0' and (case when j.recruit_store_id is null then s.safe_cert else rs.safe_cert end) = '0'"), q);
        // s 是门店的 left join（选门店名、认证状态的那一个）
        assertTrue(q.contains("left join kg_store s on s.store_id = j.store_id and s.del_flag = '0'"), q);
        j.getParams().put("assured", Boolean.FALSE);
        assertFalse(sql("selectAppJobList", j).contains("(case when j.recruit_store_id is null then s.safe_cert else rs.safe_cert end) = '0'"));
    }

    private static final String OFFLINE_ONLY = "(j.job_type <> 'online' and (";

    /** 只带关键词：不带城市筛选（城市筛选自己也有 trim(j.city)，会混进下面「城市条件都在线下块里」的检查） */
    private static KgJob keywordJob(String type)
    {
        KgJob j = job(type);
        j.setCity(null);
        return j;
    }

    /** 去掉所有「只对线下岗位」的块 or (j.job_type <> 'online' and (...))（按括号配对；参数都是 ?，SQL 里没有带括号的字面量） */
    private static String withoutOfflineOnly(String q)
    {
        StringBuilder out = new StringBuilder(q);
        int i;
        while ((i = out.indexOf("or " + OFFLINE_ONLY)) >= 0)
        {
            int depth = 0;
            int end = i + 3;
            for (; end < out.length(); end++)
            {
                char ch = out.charAt(end);
                depth += ch == '(' ? 1 : ch == ')' ? -1 : 0;
                if (depth == 0)
                {
                    break;
                }
            }
            out.delete(i, end + 1);
        }
        return out.toString().replaceAll("\\s+", " ");
    }

    @Test
    void keywordTermsAreAndedAndEachTermMatchesTextOrVibeCities()
    {
        KgJob j = keywordJob("volunteer");
        List<Map<String, Object>> terms = KgJobKeyword.terms("海边 咖啡%", null);
        assertEquals(2, terms.size(), terms.toString());
        j.getParams().put("keywordTerms", terms);
        BoundSql b = bound("selectAppJobList", j);
        String q = b.getSql().replaceAll("\\s+", " ");
        // 两组「并且」：第一组是海边（文字用正则排除「洱海边」+ 海边城市），第二组是普通词（文字 + 城市等于或以它结尾）
        assertTrue(q.contains("and ( j.title regexp ? or j.tags regexp ? or j.address regexp ? or j.content regexp ?"), q);
        assertTrue(q.contains("and ( j.title like ? or j.tags like ? or j.address like ? or j.content like ?"), q);
        int groups = q.split("and \\( j\\.title (like|regexp) \\?", -1).length - 1;
        assertEquals(2, groups, q);
        assertFalse(q.contains("( or") || q.contains("(or "), "每组开头多出来的 or 去掉了：" + q);
        // 城市列不再做包含匹配 / 正则，只按「等于或以它结尾」（KgJobCity）
        assertFalse(q.contains("j.city like") || q.contains("j.city regexp"), q);
        assertTrue(q.contains("or trim(j.city) like ?"), "海边城市按「以它结尾」匹配：" + q);
        List<Object> v = values(b, j);
        assertTrue(v.contains("(^|[^洱邛])海边"), v.toString());
        assertFalse(v.contains("%海边%"), "海边不再按 like 找（会带出洱海边）：" + v);
        assertTrue(v.contains("%厦门") && v.contains("%青岛") && v.contains("%平潭"), v.toString());
        assertTrue(v.contains("%咖啡\\%%"), "用户写的 % 按字面找：" + v);
        assertTrue(v.contains("%咖啡\\%"), "普通词的城市写法（以它结尾），% 也按字面：" + v);
        assertFalse(q.contains("咖啡") || q.contains("厦门") || q.contains("洱"), "写法和正则都走参数绑定，不拼进 SQL");
        // 两组都各有一个「只对线下岗位」的城市块；线上任务全国可接，不按城市找、也不按城市排除
        assertEquals(2, q.split(java.util.regex.Pattern.quote("or " + OFFLINE_ONLY), -1).length - 1, q);
        String textOnly = withoutOfflineOnly(q);
        assertFalse(textOnly.contains("trim(j.city)"), "所有按城市的条件都在 job_type <> 'online' 里：" + textOnly);
        assertTrue(textOnly.contains("and ( j.title regexp ? or j.tags regexp ? or j.address regexp ? or j.content regexp ? )"), textOnly);
        assertTrue(textOnly.contains("and ( j.title like ? or j.tags like ? or j.address like ? or j.content like ? )"), textOnly);
        String second = q.substring(q.lastIndexOf("and ( j.title like ?"));
        assertTrue(second.startsWith("and ( j.title like ? or j.tags like ? or j.address like ? or j.content like ? or "
                + OFFLINE_ONLY + " trim(j.city) like ? ))"), second);
        // 列表的 total 是 PageHelper 按同一条 SQL 数的：带 regexp 和线下块也能解析成普通的 count，不会退回子查询，条件一字不差
        String count = new com.github.pagehelper.parser.CountSqlParser().getSmartCountSql(b.getSql()).replaceAll("\\s+", " ");
        assertTrue(count.toLowerCase().startsWith("select count(0) from kg_job j"), count);
        assertTrue(count.contains("REGEXP ?") || count.contains("regexp ?"), count);
        assertEquals(q.split("\\?", -1).length, count.split("\\?", -1).length, "count 和列表绑定同样多的参数：" + count);
        assertTrue(count.contains("j.job_type <> 'online' AND (") || count.contains(OFFLINE_ONLY), count);
    }

    @Test
    void plainWordCityIsSuffixNotContains()
    {
        // 「大连」：文字包含 %大连%，城市只认等于或以大连结尾（%大连），「五大连池」不再算进来
        KgJob j = keywordJob("volunteer");
        j.getParams().put("keywordTerms", KgJobKeyword.terms("大连", null));
        BoundSql b = bound("selectAppJobList", j);
        String q = b.getSql().replaceAll("\\s+", " ");
        assertTrue(q.contains("and ( j.title like ? or j.tags like ? or j.address like ? or j.content like ? or "
                + OFFLINE_ONLY + " trim(j.city) like ? ))"), q);
        List<Object> v = values(b, j);
        // 包含只比四列文字（标题 / 标签 / 地址 / 介绍，之前还有 j.city 共五列），城市只有一处「以它结尾」
        assertEquals(4, java.util.Collections.frequency(v, "%大连%"), v.toString());
        assertEquals(1, java.util.Collections.frequency(v, "%大连"), v.toString());
        // 线上页签：类型条件照常，城市块里的 job_type <> 'online' 让线上任务只能靠文字命中
        KgJob online = keywordJob("online");
        online.getParams().put("keywordTerms", KgJobKeyword.terms("平潭", null));
        String o = sql("selectAppJobList", online);
        assertTrue(o.contains("and j.job_type = ?"), o);
        assertFalse(withoutOfflineOnly(o).contains("trim(j.city)"), o);
    }

    @Test
    @SuppressWarnings("unchecked")
    void vibesAreOredInOneGroupAndQuietTownsNeedTheirCityAndTheAddress()
    {
        KgJob j = keywordJob("volunteer");
        // 搜索框的占位例子整句：只有一组（几条感觉「或者」）
        List<Map<String, Object>> terms = KgJobKeyword.terms("风景美，海边，一二线城市，小众安静", null);
        assertEquals(1, terms.size(), terms.toString());
        j.getParams().put("keywordTerms", terms);
        BoundSql b = bound("selectAppJobList", j);
        String q = b.getSql().replaceAll("\\s+", " ");
        assertEquals(1, q.split("and \\( j\\.title (like|regexp) \\?", -1).length - 1, q);
        // 古镇：地址里有古镇名，并且岗位城市是所属城市（双廊 / 喜洲 / 沙溪 → 大理，沙溪另认剑川）
        assertTrue(q.contains("or (j.address like ? and ( trim(j.city) like ? ))"), q);
        assertTrue(q.contains("or (j.address like ? and ( trim(j.city) like ? or trim(j.city) like ? ))"), q);
        // 城市名单和古镇都只对线下岗位（线上任务的城市 / 地址是「不限 / 远程」或发布门店的，不代表工作地点）
        assertEquals(1, q.split(java.util.regex.Pattern.quote("or " + OFFLINE_ONLY), -1).length - 1, q);
        String textOnly = withoutOfflineOnly(q);
        assertFalse(textOnly.contains("trim(j.city)") || textOnly.contains("j.address like ? and"), textOnly);
        assertTrue(textOnly.contains("j.title regexp ?") && textOnly.contains("j.title like ?"), "文字匹配对所有类型：" + textOnly);
        List<Object> v = values(b, j);
        assertTrue(v.containsAll(Arrays.asList("%双廊%", "%喜洲%", "%沙溪%", "%大理", "%剑川", "%三亚", "%北京", "%腾冲")), v.toString());
        assertFalse(v.contains("%双廊"), "古镇不再拿来比岗位城市：" + v);
        // 只有正则、没有 like 的感觉组照样拼得对
        j.getParams().put("keywordTerms", KgJobKeyword.terms("海边", null));
        String sea = sql("selectAppJobList", j);
        assertTrue(sea.contains("and ( j.title regexp ?"), sea);
        assertFalse(sea.contains("j.title like ?"), sea);
    }

    @Test
    void erhaiInTheKeywordIsAPlainWordWithoutTheSeasideRegexp()
    {
        // 「洱海边」不认成海边：没有正则（会排除洱海边），四列文字按 %洱海边% 包含找，城市只有它自己的写法
        KgJob j = keywordJob("volunteer");
        j.getParams().put("keywordTerms", KgJobKeyword.terms("洱海边", null));
        BoundSql b = bound("selectAppJobList", j);
        String q = b.getSql().replaceAll("\\s+", " ");
        assertFalse(q.contains("regexp"), q);
        assertTrue(q.contains("and ( j.title like ? or j.tags like ? or j.address like ? or j.content like ? or "
                + OFFLINE_ONLY + " trim(j.city) like ? ))"), q);
        List<Object> v = values(b, j);
        assertEquals(4, java.util.Collections.frequency(v, "%洱海边%"), v.toString());
        assertFalse(v.contains("%厦门") || v.contains("(^|[^洱邛])海边"), v.toString());
    }

    @Test
    void legacyKeywordParamNoLongerFiltersByTitleOnly()
    {
        KgJob j = job("volunteer");
        j.getParams().put("keyword", "咖啡");
        assertFalse(sql("selectAppJobList", j).contains(" like "), "只认 keywordTerms");
    }
}
