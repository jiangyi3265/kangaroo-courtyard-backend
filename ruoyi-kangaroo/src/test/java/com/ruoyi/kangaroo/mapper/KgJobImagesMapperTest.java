package com.ruoyi.kangaroo.mapper;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ruoyi.kangaroo.domain.KgJob;

/** 解析真实的 KgJobMapper.xml：岗位照片（V49）查得出（连门店封面一起）、新增和两种修改都能写，修改时不带就不动（不连库） */
class KgJobImagesMapperTest
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

    private List<String> props(String id, Object param)
    {
        return config.getMappedStatement("com.ruoyi.kangaroo.mapper.KgJobMapper." + id).getBoundSql(param).getParameterMappings().stream()
                .map(ParameterMapping::getProperty).collect(Collectors.toList());
    }

    private static KgJob job(String images)
    {
        KgJob j = new KgJob();
        j.setJobId(7L); j.setStoreId(1L); j.setJobType("volunteer"); j.setTitle("t"); j.setCity("c"); j.setHeadcount(1); j.setSalaryType("none");
        j.setImages(images);
        return j;
    }

    @Test
    void selectsImagesAndTheStoreCover()
    {
        List<String> cols = config.getResultMap("com.ruoyi.kangaroo.mapper.KgJobMapper.KgJobResult").getResultMappings().stream()
                .map(ResultMapping::getColumn).collect(Collectors.toList());
        assertTrue(cols.contains("images") && cols.contains("store_cover"), cols.toString());
        for (String id : new String[] { "selectKgJobByJobId", "selectKgJobList", "selectAppJobList" })
        {
            String s = sql(id, id.equals("selectKgJobByJobId") ? (Object) 7L : job(null));
            assertTrue(s.contains("j.cover, j.images, j.city"), id + ": " + s);
            assertTrue(s.contains("s.store_name, s.safe_cert, s.cover store_cover,"), id);
        }
    }

    @Test
    void insertAndUpdatesWriteImagesOnlyWhenGiven()
    {
        assertTrue(sql("insertKgJob", job("[\"/profile/upload/a.jpg\"]")).contains("images,"));
        assertTrue(props("insertKgJob", job("[\"/profile/upload/a.jpg\"]")).contains("images"));
        assertFalse(sql("insertKgJob", job(null)).contains("images"), "不带 = 列默认值 ''");
        assertTrue(sql("updateKgJob", job("")).contains("images = ?"));
        assertFalse(sql("updateKgJob", job(null)).contains("images"));
        assertTrue(sql("updateKgJobByAdmin", job("")).contains("images = ?"), "清空照片也要写");
        assertFalse(sql("updateKgJobByAdmin", job(null)).contains("images"), "老版本后台表单不带：保持原值");
    }
}
