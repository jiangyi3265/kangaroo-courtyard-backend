package com.ruoyi.kangaroo.config.schema;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * V49（内测反馈 2.0：岗位照片图集、活动集合地点坐标）：缺列或列定义不对时拒绝启动。
 *  · kg_job.images 至少 varchar(2000)、不可空、默认 ''：KgJobMapper 的查询列里就有它，缺了岗位列表、详情、收藏、后台岗位管理全部报 SQL 错误；
 *    可空或默认值不对，门店发布、后台保存不传照片时就落不成「没传」；
 *  · kg_activity.meet_lat / meet_lng decimal(9,6)、可空：活动发布时不选点就写 null，不可空时发不出活动。
 */
@Component
public class JobImagesActivityPlaceSchema implements KgSchemaContributor
{
    static final String MIGRATION = "sql/kangaroo/V49__job_images_activity_place.sql";

    private final KgJdbc db;
    private final boolean enabled;

    public JobImagesActivityPlaceSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
    {
        this.db = db;
        this.enabled = enabled;
    }

    /** 只加了列，没有新索引；列在 {@link #check()} 里核对 */
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
        if (db.count("select count(*) from information_schema.columns where table_schema = database() and ("
                + "(table_name = 'kg_job' and column_name = 'images' and data_type = 'varchar' and character_maximum_length >= 2000 "
                + "and is_nullable = 'NO' and column_default = '') "
                + "or (table_name = 'kg_activity' and column_name in ('meet_lat', 'meet_lng') and data_type = 'decimal' "
                + "and numeric_precision = 9 and numeric_scale = 6 and is_nullable = 'YES'))", KgJdbc.p()) != 3)
        {
            throw new IllegalStateException("V49 kg_job.images / kg_activity.meet_lat / meet_lng missing; run " + MIGRATION);
        }
    }
}
