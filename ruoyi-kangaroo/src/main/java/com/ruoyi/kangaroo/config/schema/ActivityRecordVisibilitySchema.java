package com.ruoyi.kangaroo.config.schema;

import java.util.*;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.kangaroo.ext.KgJdbc;

@Component
public class ActivityRecordVisibilitySchema implements KgSchemaContributor {
    private final KgJdbc db;
    private final boolean enabled;
    public ActivityRecordVisibilitySchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled) {
        this.db = db; this.enabled = enabled;
    }
    public String migration() { return "sql/kangaroo/V98__activity_record_visibility.sql"; }
    public Map<String,List<String>> requiredIndexes() { return Collections.emptyMap(); }
    @PostConstruct public void check() {
        if (enabled && (db.count("select count(*) from kg_schema_version where version=98 and name='activity_record_visibility'", KgJdbc.p()) != 1
                || db.count("select count(*) from information_schema.columns where table_schema=database() and table_name='kg_activity_signup' and column_name='hidden_by_user'", KgJdbc.p()) != 1))
            throw new IllegalStateException("Run " + migration());
    }
}
