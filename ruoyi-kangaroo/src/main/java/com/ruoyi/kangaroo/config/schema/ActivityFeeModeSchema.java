package com.ruoyi.kangaroo.config.schema;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * V71（2026-11-03 主人：「价格，免费，aa制三种付费方式」）：活动费用多一种 AA制，预估人均存 kg_activity.aa_fen。
 * 账本里没有 V71 或这一列不在时拒绝启动 —— 员工端 / 用户发活动、后台新增活动都会写 aa_fen，列不在就全部 SQL 报错、活动发不出去。
 * 只核对账本和列；kangaroo.schema.assert-indexes=false（只有开发库）时跳过。没有唯一索引，requiredIndexes 留空。
 */
@Component
public class ActivityFeeModeSchema implements KgSchemaContributor
{
    static final String MIGRATION = "sql/kangaroo/V71__activity_fee_mode.sql";
    static final String LEDGER_SQL = "select count(*) from kg_schema_version where version = 71 and name = 'activity_fee_mode'";
    static final String COLUMN_SQL = "select count(*) from information_schema.columns where table_schema = database() and table_name = 'kg_activity' "
            + "and column_name = 'aa_fen'";

    private final KgJdbc db;
    private final boolean enabled;

    public ActivityFeeModeSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
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
        if (db.count(LEDGER_SQL, KgJdbc.p()) != 1 || db.count(COLUMN_SQL, KgJdbc.p()) != 1)
        {
            throw new IllegalStateException("V71 activity fee mode (kg_activity.aa_fen, fee_mode aa) not migrated; run " + MIGRATION);
        }
    }
}
