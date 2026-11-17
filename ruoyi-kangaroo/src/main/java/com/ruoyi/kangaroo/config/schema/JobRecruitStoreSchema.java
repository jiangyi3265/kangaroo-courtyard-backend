package com.ruoyi.kangaroo.config.schema;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.kangaroo.ext.KgJdbc;

/** 只检查，DDL 由发布迁移显式执行。 */
@Component
public class JobRecruitStoreSchema implements KgSchemaContributor
{
    private final KgJdbc db;
    private final boolean enabled;
    public JobRecruitStoreSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled) { this.db = db; this.enabled = enabled; }
    public Map<String, List<String>> requiredIndexes() { return Collections.singletonMap("kg_job", Collections.singletonList("idx_job_recruit_store")); }
    public String migration() { return "sql/kangaroo/V60__job_recruit_store.sql"; }
    @PostConstruct public void check() {
        if (enabled && db.count("select count(*) from information_schema.columns where table_schema=database() and table_name='kg_job' "
                + "and column_name='recruit_store_id' and data_type='bigint' and is_nullable='YES'", KgJdbc.p()) != 1)
            throw new IllegalStateException("实际招聘门店字段缺失，请执行 " + migration());
    }
}
