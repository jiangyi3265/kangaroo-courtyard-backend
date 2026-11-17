package com.ruoyi.kangaroo.config.schema;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.kangaroo.ext.KgJdbc;

/** 公开资料列只由显式迁移添加，应用启动不修改数据库。 */
@Component
public class CityCoordinatorSchema implements KgSchemaContributor
{
    private final KgJdbc db;
    private final boolean enabled;
    public CityCoordinatorSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled) { this.db = db; this.enabled = enabled; }
    public Map<String, List<String>> requiredIndexes() { return Collections.emptyMap(); }
    public String migration() { return "sql/kangaroo/V59__city_coordinator.sql"; }
    @PostConstruct public void check() {
        if (enabled && db.count("select count(*) from information_schema.columns where table_schema = database() and table_name = 'kg_city' "
                + "and data_type = 'varchar' and is_nullable = 'NO' and column_default = '' and "
                + "((column_name = 'coordinator_name' and character_maximum_length >= 32) or "
                + "(column_name = 'coordinator_avatar' and character_maximum_length >= 500) or "
                + "(column_name = 'coordinator_intro' and character_maximum_length >= 240))", KgJdbc.p()) != 3)
            throw new IllegalStateException("城市负责人字段缺失，请执行 " + migration());
    }
}
