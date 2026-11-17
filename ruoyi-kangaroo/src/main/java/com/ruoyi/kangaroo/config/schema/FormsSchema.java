package com.ruoyi.kangaroo.config.schema;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * V66（包 20261031-forms）：账本、kg_job 的 7 个新列要在，缺了拒绝启动 —— KgJobMapper 的查询列里就有它们，不然岗位列表、详情、后台岗位管理全部报 SQL 错误。
 * 只核对账本和列；kangaroo.schema.assert-indexes=false（只有开发库）时跳过。这几列没有唯一索引，requiredIndexes 留空。
 */
@Component
public class FormsSchema implements KgSchemaContributor
{
    static final String MIGRATION = "sql/kangaroo/V66__forms.sql";
    static final String LEDGER_SQL = "select count(*) from kg_schema_version where version = 66 and name = 'forms'";
    static final String COLUMNS_SQL = "select count(*) from information_schema.columns where table_schema = database() and table_name = 'kg_job' "
            + "and column_name in ('meal_allowance', 'meal_allowance_unit', 'pet_status', 'pet_images', 'lodging_images', 'lodging_video', 'lodging_video_cover')";
    static final int COLUMNS = 7;

    private final KgJdbc db;
    private final boolean enabled;

    public FormsSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
    {
        this.db = db;
        this.enabled = enabled;
    }

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
        if (db.count(LEDGER_SQL, KgJdbc.p()) != 1 || db.count(COLUMNS_SQL, KgJdbc.p()) != COLUMNS)
        {
            throw new IllegalStateException("V66 forms (kg_job meal / pet / lodging columns) not migrated; run " + MIGRATION);
        }
    }
}
