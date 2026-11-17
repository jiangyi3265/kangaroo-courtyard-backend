package com.ruoyi.kangaroo.config.schema;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.kangaroo.ext.KgJdbc;

@Component
public class JobGenderCountsSchema implements KgSchemaContributor
{
    private final KgJdbc db;
    private final boolean enabled;
    public JobGenderCountsSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
    { this.db = db; this.enabled = enabled; }
    public String migration() { return "sql/kangaroo/V78__job_gender_counts.sql"; }
    public Map<String, List<String>> requiredIndexes() { return Collections.emptyMap(); }
    @PostConstruct public void check()
    {
        if (enabled && db.count("select count(*) from information_schema.columns where table_schema=database() "
                + "and table_name='kg_job' and column_name in ('gender_male_count','gender_female_count') "
                + "and data_type='int' and is_nullable='YES'", KgJdbc.p()) != 2)
            throw new IllegalStateException("Gender recruitment plan columns missing; run " + migration());
    }
}
