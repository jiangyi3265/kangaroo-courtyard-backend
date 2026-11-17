package com.ruoyi.kangaroo.config.schema;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.kangaroo.ext.KgJdbc;

/** 群码配置列由显式迁移添加，启动只检查。 */
@Component
public class RegionGroupSchema implements KgSchemaContributor
{
    private final KgJdbc db;
    private final boolean enabled;
    public RegionGroupSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled) { this.db = db; this.enabled = enabled; }
    public Map<String, List<String>> requiredIndexes() { return Collections.emptyMap(); }
    public String migration() { return "sql/kangaroo/V94__region_group.sql"; }
    @PostConstruct public void check() {
        if (enabled && db.count("select count(*) from information_schema.columns where table_schema=database() and table_name='kg_city' and ("
                + "(data_type='varchar' and is_nullable='NO' and column_default='' and ((column_name='region_group_name' and character_maximum_length>=64) "
                + "or (column_name='region_group_qr' and character_maximum_length>=500) or (column_name='region_group_note' and character_maximum_length>=240))) "
                + "or (column_name='region_group_expire' and data_type='date' and is_nullable='YES'))", KgJdbc.p()) != 4)
            throw new IllegalStateException("地区换工群字段缺失，请执行 " + migration());
    }
}
