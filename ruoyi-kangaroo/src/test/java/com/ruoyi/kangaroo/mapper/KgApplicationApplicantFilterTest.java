package com.ruoyi.kangaroo.mapper;
import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStream;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.kangaroo.domain.KgApplication;

/** Parse the real shared list/export mapper, without a Mock SQL replacement. */
class KgApplicationApplicantFilterTest {
    Configuration config;
    @BeforeEach void setup() throws Exception {
        config=new Configuration();config.getTypeAliasRegistry().registerAlias("KgApplication",KgApplication.class);
        String resource="mapper/kangaroo/KgApplicationMapper.xml";
        try(InputStream in=getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in);new XMLMapperBuilder(in,config,resource,config.getSqlFragments()).parse();
        }
    }
    BoundSql query(KgApplication filter) {
        return config.getMappedStatement("com.ruoyi.kangaroo.mapper.KgApplicationMapper.selectKgApplicationList").getBoundSql(filter);
    }
    @Test void applicantIdIsBoundRatherThanIgnoredOrInterpolated() {
        KgApplication f=new KgApplication();f.setAppUserId(4689L);BoundSql q=query(f);
        assertTrue(q.getSql().contains("and a.app_user_id = ?"));assertFalse(q.getSql().contains("4689"));
        assertEquals(1,q.getParameterMappings().stream().filter(m->m.getProperty().equals("appUserId")).count());
        assertEquals(4689L,config.newMetaObject(q.getParameterObject()).getValue("appUserId"));
    }
    @Test void applicantAndStoreFiltersAreConjunctive() {
        KgApplication f=new KgApplication();f.setAppUserId(7L);f.setStoreId(12L);String q=query(f).getSql();
        assertTrue(q.contains("and a.store_id = ?"));assertTrue(q.contains("and a.app_user_id = ?"));assertFalse(q.contains("or a.app_user_id"));
    }
    @Test void noApplicantFilterPreservesAuthorizedAllUserListing() {
        assertFalse(query(new KgApplication()).getSql().contains("and a.app_user_id = ?"));
    }
    @Test void existingSoftDeletionFilterIsStillRequired() {
        KgApplication f=new KgApplication();f.setAppUserId(7L);
        assertTrue(query(f).getSql().contains("a.del_flag = '0'"));
    }
}
