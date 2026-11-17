package com.ruoyi.kangaroo.config.schema;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.kangaroo.ext.KgJdbc;

/** V57：生活碎片副标题由门店维护；先执行迁移，再发布应用。 */
@Component
public class StayDiaryHeaderSchema implements KgSchemaContributor
{
    static final String MIGRATION = "sql/kangaroo/V57__stay_diary_subtitle.sql";
    static final String SQL = "select count(*) from information_schema.columns where table_schema = database() "
            + "and table_name = 'kg_stay' and column_name = 'diary_subtitle' and data_type = 'varchar' "
            + "and character_maximum_length >= 80 and is_nullable = 'NO' and column_default = ''";
    private final KgJdbc db;
    private final boolean enabled;

    public StayDiaryHeaderSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
    {
        this.db = db;
        this.enabled = enabled;
    }

    @Override
    public Map<String, List<String>> requiredIndexes() { return Collections.emptyMap(); }

    @Override
    public String migration() { return MIGRATION; }

    @PostConstruct
    public void check()
    {
        if (enabled && db.count(SQL, KgJdbc.p()) != 1)
        {
            throw new IllegalStateException("kg_stay.diary_subtitle missing or incompatible; run " + MIGRATION);
        }
    }
}
