package com.ruoyi.kangaroo.config.schema;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * V42（岗位工作地点的地图坐标）：kg_job 缺 latitude / longitude 时拒绝启动，
 * 免得上线后岗位列表、详情、后台保存、门店发布岗位全部报 SQL 错误（KgJobMapper 的查询列里就有这两列）。
 */
@Component
public class JobLocationSchema implements KgSchemaContributor
{
    static final String MIGRATION = "sql/kangaroo/V42__job_location.sql";

    private final KgJdbc db;
    private final boolean enabled;

    public JobLocationSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
    {
        this.db = db;
        this.enabled = enabled;
    }

    /** 只加了两列，没有新索引；列在 {@link #check()} 里核对 */
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
        // 两列都要是可空的 decimal：空 = 没选过点；精度不够（如被手工建成 int）会把坐标截成整数，地图点偏出几十公里
        if (db.count("select count(*) from information_schema.columns where table_schema = database() and table_name = 'kg_job' "
                + "and column_name in ('latitude', 'longitude') and data_type = 'decimal' and numeric_scale >= 6 and is_nullable = 'YES'", KgJdbc.p()) != 2)
        {
            throw new IllegalStateException("V42 kg_job.latitude / longitude missing; run " + MIGRATION);
        }
    }
}
