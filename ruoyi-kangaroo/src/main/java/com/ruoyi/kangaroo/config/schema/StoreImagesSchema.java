package com.ruoyi.kangaroo.config.schema;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.kangaroo.ext.KgJdbc;

/** DDL 由发布迁移执行，启动时只检查列是否可读写。 */
@Component
public class StoreImagesSchema implements KgSchemaContributor
{
    private final KgJdbc db;
    private final boolean enabled;
    public StoreImagesSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled) { this.db = db; this.enabled = enabled; }
    public Map<String, List<String>> requiredIndexes() { return Collections.emptyMap(); }
    public String migration() { return "sql/kangaroo/V61__store_images.sql"; }
    @PostConstruct public void check() {
        if (enabled && db.count("select count(*) from information_schema.columns where table_schema=database() and table_name='kg_store' "
                + "and column_name='images' and data_type='varchar' and character_maximum_length>=3000 and is_nullable='NO'", KgJdbc.p()) != 1)
            throw new IllegalStateException("门店图片字段缺失或长度不足，请执行 " + migration());
    }
}
