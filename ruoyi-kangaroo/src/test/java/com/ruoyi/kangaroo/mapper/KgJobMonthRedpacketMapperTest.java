package com.ruoyi.kangaroo.mapper;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ruoyi.kangaroo.domain.KgJob;

/** 解析真实的 KgJobMapper.xml：满月红包（V44）查得出、能写、能筛；小程序列表的城市按「等于或以城市 / 别名结尾」匹配（KgJobCity，平潭 ↔ 平潭岛）（不连库） */
class KgJobMonthRedpacketMapperTest
{
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

    private String sql(String id, Object param)
    {
        return config.getMappedStatement("com.ruoyi.kangaroo.mapper.KgJobMapper." + id).getBoundSql(param).getSql().replaceAll("\\s+", " ");
    }

    private static KgJob job(String flag)
    {
        KgJob j = new KgJob();
        j.setJobId(7L); j.setStoreId(1L); j.setJobType("volunteer"); j.setTitle("t"); j.setCity("c"); j.setHeadcount(1); j.setSalaryType("none");
        j.setMonthRedpacket(flag);
        return j;
    }

    @Test
    void selectsAndMapsTheFlag()
    {
        for (String id : new String[] { "selectKgJobByJobId", "selectKgJobList", "selectAppJobList" })
        {
            assertTrue(sql(id, job(null)).contains("j.insurance_flag, j.month_redpacket,"), id);
        }
        boolean mapped = false;
        for (ResultMapping m : config.getResultMap("com.ruoyi.kangaroo.mapper.KgJobMapper.KgJobResult").getResultMappings())
        {
            mapped |= "monthRedpacket".equals(m.getProperty()) && "month_redpacket".equals(m.getColumn());
        }
        assertTrue(mapped);
    }

    @Test
    void writesTheFlagOnlyWhenGiven()
    {
        assertTrue(sql("insertKgJob", job("0")).contains("month_redpacket"));
        assertFalse(sql("insertKgJob", job(null)).contains("month_redpacket"), "没传按库默认「没有」");
        assertTrue(sql("updateKgJob", job("1")).contains("month_redpacket = ?"));
        assertFalse(sql("updateKgJob", job(null)).contains("month_redpacket"));
        // 后台整表单保存：老版本表单不带这个字段时保持原值
        assertTrue(sql("updateKgJobByAdmin", job("0")).contains("month_redpacket = ?"));
        assertFalse(sql("updateKgJobByAdmin", job("")).contains("month_redpacket"));
    }

    @Test
    void appListFiltersMonthBonusAndMatchesCityBySuffixTerms()
    {
        KgJob q = job(null);
        q.setCity("平潭");
        Map<String, Object> params = new HashMap<String, Object>();
        q.setParams(params);
        // 只传 city、没带写法列表：按完全相等兜底，不放开
        assertTrue(sql("selectAppJobList", q).contains("and (trim(j.city) = ? or j.job_type = 'online')"), sql("selectAppJobList", q));
        com.ruoyi.kangaroo.ext.user.KgJobCity.putMapperParams(params, java.util.Arrays.asList("平潭", "平潭岛", "京"));
        String s = sql("selectAppJobList", q);
        assertTrue(s.contains("and (j.job_type = 'online' or trim(j.city) like ? or trim(j.city) like ? or trim(j.city) = ? )"), s);
        assertFalse(s.contains("concat('%', ?, '%') or j.job_type"), "不再包含匹配：大连 ⊂ 五大连池");
        assertEquals(java.util.Arrays.asList("%平潭", "%平潭岛"), params.get("cityLikes"));
        assertFalse(s.contains("month_redpacket = '0'"), s);
        params.put("monthBonus", Boolean.TRUE);
        assertTrue(sql("selectAppJobList", q).contains("and j.month_redpacket = '0'"));
        // 后台列表仍按原城市精确管理
        assertTrue(sql("selectKgJobList", q).contains("j.city = ?"));
    }
}
