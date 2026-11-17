package com.ruoyi.kangaroo.config.schema;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.KgJdbc;

/** 门店给报名人加的技能标签（V35）：缺列时拒绝启动，免得上线后人才库和报名详情才报 SQL 错误。 */
@Component
public class TalentStoreTagSchema implements KgSchemaContributor
{
    private final KgJdbc db;
    private final boolean enabled;

    public TalentStoreTagSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
    {
        this.db = db;
        this.enabled = enabled;
    }

    @Override
    public Map<String, List<String>> requiredIndexes()
    {
        return Collections.singletonMap("kg_talent_mark", Arrays.asList("PRIMARY", "uk_talent_mark"));
    }

    @Override
    public String migration()
    {
        return "sql/kangaroo/V35__talent_store_tags.sql";
    }

    @PostConstruct
    public void check()
    {
        if (!enabled)
        {
            return;
        }
        if (db.count("select count(*) from information_schema.columns where table_schema = database() and table_name = 'kg_talent_mark' and column_name = 'store_tags'",
                KgJdbc.p()) != 1)
        {
            throw new IllegalStateException("V35 kg_talent_mark.store_tags missing; run " + migration());
        }
    }
}
