package com.ruoyi.kangaroo.config.schema;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.kangaroo.ext.KgJdbc;

@Component
public class StayCancelPolicySchema implements KgSchemaContributor {
    static final String MIGRATION = "sql/kangaroo/V91__stay_cancel_policy.sql";
    static final String SQL = "select count(*) from information_schema.columns where table_schema = database() and table_name = 'kg_stay' and column_name = 'cancel_policy_config' and data_type = 'varchar' and character_maximum_length >= 1000 and is_nullable = 'YES'";
    private final KgJdbc db;
    private final boolean enabled;
    public StayCancelPolicySchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled) { this.db = db; this.enabled = enabled; }
    @Override public Map<String, List<String>> requiredIndexes() { return Collections.emptyMap(); }
    @Override public String migration() { return MIGRATION; }
    @PostConstruct public void check() {
        if (enabled && db.count(SQL, KgJdbc.p()) != 1) throw new IllegalStateException("kg_stay.cancel_policy_config missing or incompatible; run " + MIGRATION);
    }
}
