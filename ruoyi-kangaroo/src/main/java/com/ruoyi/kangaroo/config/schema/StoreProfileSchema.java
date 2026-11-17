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
 * V54（2026-10-18 岗位详情改版「门店页」：采访方式、采访视频、店长介绍、该店亮点、交通参考、营业执照、店里日常）：kg_store 缺列或定义不对时拒绝启动。
 *  · 十五个文字列：varchar、不可空、默认 ''（老版本程序新增门店时不写它们，要落成「没填」）；后台「门店资料」、岗位详情直接读写，缺了全部报 SQL 错误；
 *  · interview_seconds：int、可空（null = 不知道时长）。
 * 只加了列，没有新索引。
 */
@Component
public class StoreProfileSchema implements KgSchemaContributor
{
    static final String MIGRATION = "sql/kangaroo/V54__store_profile.sql";

    /** 列名 → 最少字符数（和 V54 一致；以后再放宽也认） */
    static final Map<String, Integer> TEXT_COLUMNS = new LinkedHashMap<String, Integer>();
    static
    {
        TEXT_COLUMNS.put("interview_mode", 16);
        TEXT_COLUMNS.put("interview_video", 255);
        TEXT_COLUMNS.put("interview_cover", 255);
        TEXT_COLUMNS.put("interview_note", 100);
        TEXT_COLUMNS.put("manager_name", 32);
        TEXT_COLUMNS.put("manager_avatar", 255);
        TEXT_COLUMNS.put("manager_years", 32);
        TEXT_COLUMNS.put("manager_skills", 100);
        TEXT_COLUMNS.put("manager_quote", 200);
        TEXT_COLUMNS.put("facilities", 255);
        TEXT_COLUMNS.put("travel_rail", 500);
        TEXT_COLUMNS.put("travel_airport", 500);
        TEXT_COLUMNS.put("travel_cost", 32);
        TEXT_COLUMNS.put("license_image", 255);
        TEXT_COLUMNS.put("daily_images", 2000);
    }

    private final KgJdbc db;
    private final boolean enabled;

    public StoreProfileSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
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

    static String textSql()
    {
        StringBuilder or = new StringBuilder();
        for (Map.Entry<String, Integer> e : TEXT_COLUMNS.entrySet())
        {
            or.append(or.length() == 0 ? "" : " or ").append("(column_name = '").append(e.getKey())
                    .append("' and character_maximum_length >= ").append(e.getValue()).append(")");
        }
        return "select count(*) from information_schema.columns where table_schema = database() and table_name = 'kg_store' "
                + "and data_type = 'varchar' and is_nullable = 'NO' and column_default = '' and (" + or + ")";
    }

    static final String SECONDS_SQL = "select count(*) from information_schema.columns where table_schema = database() and table_name = 'kg_store' "
            + "and column_name = 'interview_seconds' and data_type = 'int' and is_nullable = 'YES'";

    @PostConstruct
    public void check()
    {
        if (!enabled)
        {
            return;
        }
        if (db.count(textSql(), KgJdbc.p()) != TEXT_COLUMNS.size() || db.count(SECONDS_SQL, KgJdbc.p()) != 1)
        {
            throw new IllegalStateException("V54 kg_store.interview_* / manager_* / facilities / travel_* / license_image / daily_images missing; run " + MIGRATION);
        }
    }
}
