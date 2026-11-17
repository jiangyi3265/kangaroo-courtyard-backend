package com.ruoyi.kangaroo.mapper;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ruoyi.kangaroo.domain.KgJob;

/** 解析真实的 KgJobMapper.xml：换工类型筛选（精确匹配，「其他」也包括没选类型的岗位）、新增 / 修改带上 work_kind（不连库） */
class KgJobWorkKindMapperTest
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
        BoundSql q = config.getMappedStatement("com.ruoyi.kangaroo.mapper.KgJobMapper." + id).getBoundSql(param);
        return q.getSql().replaceAll("\\s+", " ");
    }

    private static KgJob appQuery(String kind, Boolean untyped)
    {
        KgJob j = new KgJob();
        j.setJobType("volunteer");
        Map<String, Object> params = new HashMap<String, Object>();
        if (kind != null)
        {
            params.put("workKind", kind);
            if (untyped != null)
            {
                params.put("workKindUntyped", untyped);
            }
        }
        j.setParams(params);
        return j;
    }

    @Test
    void appListFiltersByKindExactlyWithoutGuessingFromTitles()
    {
        String q = sql("selectAppJobList", appQuery("photo", Boolean.FALSE));
        assertTrue(q.contains("and j.job_type = 'volunteer' and j.work_kind = ?"), q);
        // 之前没选类型的老岗位按类型名称 / 说明里的词模糊匹配标题和标签；现在不猜
        assertFalse(q.contains("j.work_kind = ''"), q);
        assertFalse(q.contains("like"), q);
        assertFalse(q.contains("photo"), "类型值要走参数绑定，不能拼进 SQL");
        // 没带 workKindUntyped 也一样只认精确值
        assertEquals(q, sql("selectAppJobList", appQuery("photo", null)));
    }

    @Test
    void otherAlsoListsVolunteerJobsWithoutAKind()
    {
        String q = sql("selectAppJobList", appQuery("other", Boolean.TRUE));
        assertTrue(q.contains("and j.job_type = 'volunteer' and (j.work_kind = ? or j.work_kind = '')"), q);
        assertFalse(q.contains("like"), q);
        assertFalse(q.contains("other"), "类型值要走参数绑定，不能拼进 SQL");
    }

    @Test
    void noKindLeavesTheListAlone()
    {
        String q = sql("selectAppJobList", appQuery(null, null));
        assertFalse(q.contains("j.work_kind ="), q);
        assertTrue(q.contains("j.work_kind"), "列表照样把 work_kind 查出来，卡片才能显示类型名");
    }

    @Test
    void adminListCanFilterByKind()
    {
        KgJob j = new KgJob();
        j.setWorkKind("daily");
        assertTrue(sql("selectKgJobList", j).contains("and j.work_kind = ?"));
        assertFalse(sql("selectKgJobList", new KgJob()).contains("and j.work_kind = ?"));
    }

    @Test
    void insertAndUpdatesCarryKindOnlyWhenGiven()
    {
        KgJob j = new KgJob();
        j.setJobId(7L); j.setStoreId(1L); j.setJobType("volunteer"); j.setTitle("t"); j.setCity("c"); j.setHeadcount(1); j.setSalaryType("none");
        j.setWorkKind("photo");
        assertTrue(sql("insertKgJob", j).contains("work_kind"));
        assertTrue(sql("updateKgJobByAdmin", j).contains("work_kind = ?"));
        assertTrue(sql("updateKgJob", j).contains("work_kind = ?"));
        // null = 老版本后台表单没带这个字段：不写这一列，保持原值
        j.setWorkKind(null);
        assertFalse(sql("insertKgJob", j).contains("work_kind"));
        assertFalse(sql("updateKgJobByAdmin", j).contains("work_kind"));
        // 空串 = 清空类型：照样写
        j.setWorkKind("");
        assertTrue(sql("updateKgJobByAdmin", j).contains("work_kind = ?"));
    }
}
