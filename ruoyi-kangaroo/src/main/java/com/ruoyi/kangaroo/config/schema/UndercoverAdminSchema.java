package com.ruoyi.kangaroo.config.schema;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.kangaroo.ext.KgJdbc;

@Component
public class UndercoverAdminSchema implements KgSchemaContributor
{
    private final KgJdbc db;
    private final boolean enabled;

    public UndercoverAdminSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
    {
        this.db = db;
        this.enabled = enabled;
    }

    public String migration() { return "sql/kangaroo/V89__undercover_admin.sql"; }

    public Map<String, List<String>> requiredIndexes()
    {
        return Collections.singletonMap("kg_undercover_word", Arrays.asList("PRIMARY", "uk_undercover_pair"));
    }

    @PostConstruct
    public void check()
    {
        if (enabled && db.count("select count(*) from kg_schema_version where version=89 and name='undercover_admin'", KgJdbc.p()) != 1)
            throw new IllegalStateException("Run " + migration() + " before deploying undercover admin");
    }
}
