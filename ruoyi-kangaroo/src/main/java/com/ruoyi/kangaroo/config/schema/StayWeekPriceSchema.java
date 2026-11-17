package com.ruoyi.kangaroo.config.schema;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * V70（2026-11-03 主人：房型「必须7天，填写7天的价格」、来源类型加「合作」、设施加「地暖」）：
 * kg_stay_room_type.week_price_fen 一列 + 两个字典项。没有这一列时拒绝启动 —— 员工端存 7 天价会直接报 SQL 错；
 * 账本里没有 V70 也拒绝（字典项缺了「合作」存不进门店、「地暖」选不到）。只核对列和账本，不核对字典行：运营停用字典项是允许的。
 */
@Component
public class StayWeekPriceSchema implements KgSchemaContributor
{
    static final String MIGRATION = "sql/kangaroo/V70__stay_week_price_options.sql";
    static final String COLUMN_SQL = "select count(*) from information_schema.columns where table_schema = database() "
            + "and table_name = 'kg_stay_room_type' and column_name = 'week_price_fen' and data_type = 'varchar' "
            + "and character_maximum_length >= 80 and is_nullable = 'YES'";
    static final String LEDGER_SQL = "select count(*) from kg_schema_version where version = 70 and name = 'stay_week_price_options'";

    private final KgJdbc db;
    private final boolean enabled;

    public StayWeekPriceSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
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
        if (db.count(COLUMN_SQL, KgJdbc.p()) != 1)
        {
            throw new IllegalStateException("kg_stay_room_type.week_price_fen missing or incompatible; run " + MIGRATION);
        }
        if (db.count(LEDGER_SQL, KgJdbc.p()) != 1)
        {
            throw new IllegalStateException("V70 stay week price / partner / floorheat not migrated; run " + MIGRATION);
        }
    }
}
