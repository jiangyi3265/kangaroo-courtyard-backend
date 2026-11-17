package com.ruoyi.kangaroo.config.schema;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * V69（2026-11-03 主人：「订票以后让他提交这个身份证号」）：出行证件表 kg_user_travel_id。
 * 账本和表的列要在，缺了拒绝启动 —— 不然报名人提交证件号、换工主理人导出名单都是 500。
 * 表的主键就是 user_id（一人一行），没有另外的唯一索引，requiredIndexes 留空。kangaroo.schema.assert-indexes=false（只有开发库）时跳过。
 */
@Component
public class TravelIdSchema implements KgSchemaContributor
{
    static final String MIGRATION = "sql/kangaroo/V69__travel_id.sql";
    static final String LEDGER_SQL = "select count(*) from kg_schema_version where version = 69 and name = 'travel_id'";
    static final String COLUMNS_SQL = "select count(*) from information_schema.columns where table_schema = database() and table_name = 'kg_user_travel_id' "
            + "and column_name in ('user_id', 'id_name', 'id_no_enc', 'id_last4', 'id_hash', 'create_time', 'update_time')";
    static final int COLUMNS = 7;

    private final KgJdbc db;
    private final boolean enabled;

    public TravelIdSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
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
            throw new IllegalStateException("V69 travel id (kg_user_travel_id) not migrated; run " + MIGRATION);
        }
    }
}
