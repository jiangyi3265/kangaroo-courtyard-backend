package com.ruoyi.kangaroo.config.schema;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * V53（2026-10-17 旅居改版 R3：小院视频、房型视频、生活碎片、评价视频）：缺列、缺表或定义不对时拒绝启动。
 *  · kg_stay 五列、kg_stay_room_type 两列：varchar、不可空、默认 ''（老版本程序新增房源 / 房型时不写它们，要落成「没传」）；
 *    详情页、员工端「视频与生活碎片」、后台房源管理直接读写，缺了全部报 SQL 错误；
 *  · kg_review.videos / video_covers：varchar(600)、可空（null = 没有视频；老版本程序写评价不带这两列）；
 *  · 新表 kg_stay_diary 的主键和列表索引 idx_stay_diary_list 由 SchemaAssertion 汇总核对（表不在时同样拒绝启动）。
 */
@Component
public class StayMediaSchema implements KgSchemaContributor
{
    static final String MIGRATION = "sql/kangaroo/V53__stay_media.sql";

    /** 表.列 → 最少字符数（和 V53 一致；以后再放宽也认） */
    static final Map<String, Integer> TEXT_COLUMNS = new LinkedHashMap<String, Integer>();
    static
    {
        TEXT_COLUMNS.put("kg_stay.intro_video", 255);
        TEXT_COLUMNS.put("kg_stay.intro_video_cover", 255);
        TEXT_COLUMNS.put("kg_stay.diary_bg", 255);
        TEXT_COLUMNS.put("kg_stay.diary_title", 32);
        TEXT_COLUMNS.put("kg_stay.diary_intro", 255);
        TEXT_COLUMNS.put("kg_stay_room_type.video_url", 255);
        TEXT_COLUMNS.put("kg_stay_room_type.video_cover", 255);
    }

    private final KgJdbc db;
    private final boolean enabled;

    public StayMediaSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
    {
        this.db = db;
        this.enabled = enabled;
    }

    /** 新表的主键和「按房源取生活碎片」的普通索引 */
    @Override
    public Map<String, List<String>> requiredIndexes()
    {
        return Collections.singletonMap("kg_stay_diary", Arrays.asList("PRIMARY", "idx_stay_diary_list"));
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
            String[] tc = e.getKey().split("\\.");
            or.append(or.length() == 0 ? "" : " or ").append("(table_name = '").append(tc[0]).append("' and column_name = '").append(tc[1])
                    .append("' and character_maximum_length >= ").append(e.getValue()).append(")");
        }
        return "select count(*) from information_schema.columns where table_schema = database() "
                + "and data_type = 'varchar' and is_nullable = 'NO' and column_default = '' and (" + or + ")";
    }

    static final String REVIEW_SQL = "select count(*) from information_schema.columns where table_schema = database() and table_name = 'kg_review' "
            + "and data_type = 'varchar' and is_nullable = 'YES' and character_maximum_length >= 600 and column_name in ('videos', 'video_covers')";

    static final String DIARY_SQL = "select count(*) from information_schema.columns where table_schema = database() and table_name = 'kg_stay_diary' "
            + "and column_name in ('diary_id', 'stay_id', 'store_id', 'media_type', 'media_url', 'cover_url', 'caption', 'shot_date', 'sort', 'status', 'del_flag')";

    @PostConstruct
    public void check()
    {
        if (!enabled)
        {
            return;
        }
        if (db.count(textSql(), KgJdbc.p()) != TEXT_COLUMNS.size())
        {
            throw new IllegalStateException("V53 kg_stay.intro_video / diary_* or kg_stay_room_type.video_url / video_cover missing; run " + MIGRATION);
        }
        if (db.count(REVIEW_SQL, KgJdbc.p()) != 2)
        {
            throw new IllegalStateException("V53 kg_review.videos / video_covers missing; run " + MIGRATION);
        }
        if (db.count(DIARY_SQL, KgJdbc.p()) != 11)
        {
            throw new IllegalStateException("V53 table kg_stay_diary missing or incomplete; run " + MIGRATION);
        }
    }
}
