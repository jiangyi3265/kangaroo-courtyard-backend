package com.ruoyi.kangaroo.config.schema;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.kangaroo.ext.KgJdbc;

/** Checks only; migrations are explicitly applied during release. */
@Component
public class JobDisplayOrderSchema implements KgSchemaContributor
{
    private final KgJdbc db;
    private final boolean enabled;
    public JobDisplayOrderSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
    {
        this.db = db;
        this.enabled = enabled;
    }
    public Map<String, List<String>> requiredIndexes()
    {
        return Collections.singletonMap("kg_job", Collections.singletonList("idx_job_display_order"));
    }
    public String migration() { return "sql/kangaroo/V118__job_display_order.sql"; }
    @PostConstruct public void check()
    {
        if (enabled && (db.count("select count(*) from information_schema.columns where table_schema=database() "
                + "and table_name='kg_job' and column_name='sort_no' and data_type='int' "
                + "and is_nullable='NO' and column_default='100'", KgJdbc.p()) != 1
                || db.count("select count(*) from kg_schema_version where version=118", KgJdbc.p()) != 1))
            throw new IllegalStateException("岗位展示顺序字段或迁移记录缺失，请执行 " + migration());
    }
}
