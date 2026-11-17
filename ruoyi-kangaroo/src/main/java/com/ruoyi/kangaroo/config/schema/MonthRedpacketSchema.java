package com.ruoyi.kangaroo.config.schema;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * V44（岗位「做满一个月有红包」）：kg_job 缺 month_redpacket 时拒绝启动，
 * 免得上线后岗位列表、详情、筛选、后台保存、门店发布岗位全部报 SQL 错误（KgJobMapper 的查询列里就有这一列）。
 */
@Component
public class MonthRedpacketSchema implements KgSchemaContributor
{
    static final String MIGRATION = "sql/kangaroo/V44__job_month_redpacket.sql";

    private final KgJdbc db;
    private final boolean enabled;

    public MonthRedpacketSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
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
        return MIGRATION;
    }

    @PostConstruct
    public void check()
    {
        if (!enabled)
        {
            return;
        }
        // 必须是不可空、默认 '1'（没有）的 char：后台、门店发布不勾时不写这一列，靠默认值落成「没有」
        if (db.count("select count(*) from information_schema.columns where table_schema = database() and table_name = 'kg_job' "
                + "and column_name = 'month_redpacket' and data_type = 'char' and is_nullable = 'NO' and column_default = '1'", KgJdbc.p()) != 1)
        {
            throw new IllegalStateException("V44 kg_job.month_redpacket missing; run " + MIGRATION);
        }
    }
}
