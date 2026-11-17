package com.ruoyi.kangaroo.config.schema;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.kangaroo.ext.KgJdbc;

/** City scope is deployed explicitly, never mutated during startup. */
@Component
public class CityStaffGrantSchema implements KgSchemaContributor {
    private final KgJdbc db;
    private final boolean enabled;
    public CityStaffGrantSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled) { this.db=db; this.enabled=enabled; }
    public Map<String,List<String>> requiredIndexes() { return Collections.singletonMap("kg_staff_grant", Arrays.asList("uk_grant_user_city")); }
    public String migration() { return "sql/kangaroo/V95__city_staff_grant.sql"; }
    @PostConstruct public void check() {
        if (enabled && (db.count("select count(*) from information_schema.columns where table_schema=database() and table_name='kg_staff_grant' and column_name in ('store_id','city_id') and data_type='bigint' and is_nullable='YES'", KgJdbc.p()) != 2
            || db.count("select count(*) from information_schema.statistics where table_schema=database() and table_name='kg_staff_grant' and index_name='uk_grant_user_city' and non_unique=0 and ((seq_in_index=1 and column_name='app_user_id') or (seq_in_index=2 and column_name='city_id'))", KgJdbc.p()) != 2))
            throw new IllegalStateException("城市员工授权字段或唯一索引缺失，请执行 " + migration());
    }
}
