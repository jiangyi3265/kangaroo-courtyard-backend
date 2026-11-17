package com.ruoyi.kangaroo.mapper;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ruoyi.kangaroo.domain.KgJob;

/** 解析真实的 KgJobMapper.xml：工作地点坐标（V42）查得出来、新增 / 局部更新只写传了的、后台全量更新一律写（保留与否由 Service 定）（不连库） */
class KgJobLocationMapperTest
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

    private BoundSql bound(String id, Object param)
    {
        return config.getMappedStatement("com.ruoyi.kangaroo.mapper.KgJobMapper." + id).getBoundSql(param);
    }

    private String sql(String id, Object param)
    {
        return bound(id, param).getSql().replaceAll("\\s+", " ");
    }

    private static KgJob job(BigDecimal lat, BigDecimal lng)
    {
        KgJob j = new KgJob();
        j.setJobId(7L); j.setStoreId(1L); j.setJobType("volunteer"); j.setTitle("t"); j.setCity("c"); j.setHeadcount(1); j.setSalaryType("none");
        j.setAddress("南舍庄园"); j.setLatitude(lat); j.setLongitude(lng);
        return j;
    }

    @Test
    void selectsAndMapsTheCoordinates()
    {
        for (String id : new String[] { "selectKgJobByJobId", "selectKgJobList", "selectAppJobList" })
        {
            assertTrue(sql(id, new KgJob()).contains("j.address, j.latitude, j.longitude,"), id);
        }
        List<String> props = config.getResultMap("com.ruoyi.kangaroo.mapper.KgJobMapper.KgJobResult").getResultMappings().stream()
                .map(ResultMapping::getProperty).collect(Collectors.toList());
        assertTrue(props.contains("latitude") && props.contains("longitude"), props.toString());
    }

    @Test
    void insertAndPartialUpdateWriteCoordinatesOnlyWhenGiven()
    {
        KgJob with = job(new BigDecimal("25.600000"), new BigDecimal("119.300000"));
        assertTrue(sql("insertKgJob", with).contains("latitude, longitude,"));
        assertTrue(sql("updateKgJob", with).contains("latitude = ?, longitude = ?,"));
        KgJob without = job(null, null);
        assertFalse(sql("insertKgJob", without).contains("latitude"));
        // 局部更新（上下架等）没带坐标时不能把库里的点清成 NULL
        assertFalse(sql("updateKgJob", without).contains("latitude"));
    }

    @Test
    void adminFullUpdateAlwaysWritesCoordinatesAsDecimal()
    {
        for (KgJob j : new KgJob[] { job(new BigDecimal("25.600000"), new BigDecimal("119.300000")), job(null, null) })
        {
            BoundSql b = bound("updateKgJobByAdmin", j);
            String q = b.getSql().replaceAll("\\s+", " ");
            assertTrue(q.contains("address = ?, latitude = ?, longitude = ?,"), q);
            for (String p : new String[] { "latitude", "longitude" })
            {
                ParameterMapping m = b.getParameterMappings().stream().filter(x -> p.equals(x.getProperty())).findFirst().orElse(null);
                assertNotNull(m, p);
                assertEquals(JdbcType.DECIMAL, m.getJdbcType(), "传 null 时要能写成 NULL");
            }
        }
    }
}
