package com.ruoyi.kangaroo.config.schema;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.KgJdbc;

/** V40（会员权益的店铺介绍）：缺列时拒绝启动，免得上线后后台保存权益、小程序打开权益详情才报 SQL 错误。 */
@Component
public class BenefitIntroSchema implements KgSchemaContributor
{
    private final KgJdbc db;
    private final boolean enabled;

    public BenefitIntroSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
    {
        this.db = db;
        this.enabled = enabled;
    }

    /** 只加了一列，没有新索引；列在 {@link #check()} 里核对 */
    @Override
    public Map<String, List<String>> requiredIndexes()
    {
        return Collections.emptyMap();
    }

    @Override
    public String migration()
    {
        return "sql/kangaroo/V40__benefit_intro.sql";
    }

    @PostConstruct
    public void check()
    {
        if (!enabled)
        {
            return;
        }
        if (db.count("select count(*) from information_schema.columns where table_schema = database() and table_name = 'kg_member_benefit' and column_name = 'intro'",
                KgJdbc.p()) != 1)
        {
            throw new IllegalStateException("V40 kg_member_benefit.intro missing; run " + migration());
        }
    }
}
