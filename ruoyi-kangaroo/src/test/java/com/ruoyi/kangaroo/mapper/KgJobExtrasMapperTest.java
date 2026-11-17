package com.ruoyi.kangaroo.mapper;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ruoyi.kangaroo.domain.KgJob;

/** 解析真实的 KgJobMapper.xml：V66 的 7 列查得出；新增和两种修改都是「带了才写」，老版本后台表单不带时一个字都不碰（不连库） */
class KgJobExtrasMapperTest
{
    private static final List<String> COLUMNS = Arrays.asList("meal_allowance", "meal_allowance_unit", "pet_status", "pet_images", "lodging_images", "lodging_video", "lodging_video_cover");

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

    private static KgJob job()
    {
        KgJob j = new KgJob();
        j.setJobId(7L);
        j.setStoreId(1L);
        j.setJobType("volunteer");
        j.setTitle("t");
        j.setCity("c");
        j.setHeadcount(1);
        j.setSalaryType("none");
        return j;
    }

    private static KgJob full()
    {
        KgJob j = job();
        j.setMealAllowance("30");
        j.setMealAllowanceUnit("day");
        j.setPetStatus("yes");
        j.setPetImages("[]");
        j.setLodgingImages("[]");
        j.setLodgingVideo("v");
        j.setLodgingVideoCover("c");
        return j;
    }

    @Test
    void everyReadCarriesTheNewColumns()
    {
        List<String> mapped = config.getResultMap("com.ruoyi.kangaroo.mapper.KgJobMapper.KgJobResult").getResultMappings().stream()
                .map(ResultMapping::getColumn).collect(Collectors.toList());
        for (String col : COLUMNS)
        {
            assertTrue(mapped.contains(col), col);
            assertTrue(sql("selectKgJobByJobId", 7L).contains("j." + col), col);
            assertTrue(sql("selectKgJobList", job()).contains("j." + col), col);
            assertTrue(sql("selectAppJobList", job()).contains("j." + col), col);
        }
    }

    @Test
    void writesOnlyWhatIsGiven()
    {
        for (String id : Arrays.asList("insertKgJob", "updateKgJob", "updateKgJobByAdmin"))
        {
            String none = sql(id, job());
            String all = sql(id, full());
            for (String col : COLUMNS)
            {
                assertFalse(none.contains(col), id + " 不带 " + col + " 时不能写它: " + none);
                assertTrue(all.contains(col), id + " 带了 " + col + " 要写: " + all);
            }
        }
    }

    @Test
    void emptyStringClearsInsteadOfBeingSkipped()
    {
        KgJob j = job();
        j.setPetStatus("");
        j.setLodgingVideo("");
        String s = sql("updateKgJobByAdmin", j);
        assertTrue(s.contains("pet_status = ?"), "'' 是显式清空，要写");
        assertTrue(s.contains("lodging_video = ?"));
    }
}
