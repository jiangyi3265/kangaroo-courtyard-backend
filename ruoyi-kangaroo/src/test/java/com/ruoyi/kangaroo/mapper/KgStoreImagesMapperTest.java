package com.ruoyi.kangaroo.mapper;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStream;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import com.ruoyi.kangaroo.domain.KgStore;

class KgStoreImagesMapperTest
{
    @Test void realMapperReadsAndWritesOrderedGalleryIncludingClear() throws Exception {
        Configuration config = new Configuration(); config.getTypeAliasRegistry().registerAlias("KgStore", KgStore.class);
        String resource = "mapper/kangaroo/KgStoreMapper.xml", ns = "com.ruoyi.kangaroo.mapper.KgStoreMapper.";
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(stream, config, resource, config.getSqlFragments()).parse();
        }
        assertTrue(config.getResultMap(ns + "KgStoreResult").getResultMappings().stream().anyMatch(m -> "images".equals(m.getProperty()) && "images".equals(m.getColumn())));
        assertTrue(config.getMappedStatement(ns + "selectKgStoreByStoreId").getBoundSql(77L).getSql().contains("cover, images,"));
        KgStore store = new KgStore(); store.setStoreId(77L);
        assertFalse(config.getMappedStatement(ns + "updateKgStore").getBoundSql(store).getSql().contains("images ="));
        store.setImages("[]"); store.setCover("");
        for (String id : new String[] {"insertKgStore", "updateKgStore"}) {
            assertTrue(config.getMappedStatement(ns + id).getBoundSql(store).getParameterMappings().stream().anyMatch(m -> "images".equals(m.getProperty())));
            assertTrue(config.getMappedStatement(ns + id).getBoundSql(store).getParameterMappings().stream().anyMatch(m -> "cover".equals(m.getProperty())));
        }
    }
}
