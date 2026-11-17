package com.ruoyi.kangaroo.config.schema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * V51（2026-10-17 旅居改版 R2：所在区县、主理人年龄 / 籍贯 / 擅长、出行参考）：kg_stay 缺这八列或定义不对时拒绝启动。
 * 房源列表按 s.district 筛选、计数，员工端 / 后台保存房源直接写这八列，缺了列表、详情、员工端编辑全部报 SQL 错误；
 * 可空或默认值不对时，老房源、后台留空保存就落不成「没填」（''）。
 * 设施字典里的学习室 / 健身房不在这里查：少了只是选项里不出现，不影响启动。
 */
@Component
public class StayDistrictTravelSchema implements KgSchemaContributor
{
    static final String MIGRATION = "sql/kangaroo/V51__stay_district_travel.sql";

    /** 列名 → 最少字符数（和 V51 一致；以后再放宽也认） */
    static final Map<String, Integer> COLUMNS = new LinkedHashMap<String, Integer>();
    static
    {
        COLUMNS.put("district", 32);
        COLUMNS.put("host_age", 16);
        COLUMNS.put("host_hometown", 32);
        COLUMNS.put("host_skills", 128);
        COLUMNS.put("travel_pros", 255);
        COLUMNS.put("travel_cons", 255);
        COLUMNS.put("travel_airport", 255);
        COLUMNS.put("travel_rail", 255);
    }

    private final KgJdbc db;
    private final boolean enabled;

    public StayDistrictTravelSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
    {
        this.db = db;
        this.enabled = enabled;
    }

    /** 只加了列和一条普通索引（不是唯一索引），列在 {@link #check()} 里核对 */
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

    static String sql()
    {
        StringBuilder or = new StringBuilder();
        for (Map.Entry<String, Integer> e : COLUMNS.entrySet())
        {
            or.append(or.length() == 0 ? "" : " or ").append("(column_name = '").append(e.getKey())
                    .append("' and character_maximum_length >= ").append(e.getValue()).append(")");
        }
        return "select count(*) from information_schema.columns where table_schema = database() and table_name = 'kg_stay' "
                + "and data_type = 'varchar' and is_nullable = 'NO' and column_default = '' and (" + or + ")";
    }

    @PostConstruct
    public void check()
    {
        if (!enabled)
        {
            return;
        }
        if (db.count(sql(), KgJdbc.p()) != COLUMNS.size())
        {
            throw new IllegalStateException("V51 kg_stay.district / host_age / host_hometown / host_skills / travel_* missing; run " + MIGRATION);
        }
    }
}
