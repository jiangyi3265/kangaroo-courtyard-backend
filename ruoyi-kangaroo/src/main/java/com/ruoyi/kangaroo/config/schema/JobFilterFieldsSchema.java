package com.ruoyi.kangaroo.config.schema;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * V48（岗位筛选条件：性别偏好、休息排班、住宿房型）：kg_job 缺这三列、或不是「不可空、默认空串」时拒绝启动。
 * KgJobMapper 的查询列里就有它们，缺了岗位列表、详情、收藏、后台岗位管理、门店发布岗位全部报 SQL 错误；
 * 门店发布、后台保存不选时靠默认值 '' 落成「没填」，可空或默认值不对，筛选口径就乱了。
 */
@Component
public class JobFilterFieldsSchema implements KgSchemaContributor
{
    static final String MIGRATION = "sql/kangaroo/V48__job_filter_fields.sql";

    private final KgJdbc db;
    private final boolean enabled;

    public JobFilterFieldsSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
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
        // 三列都要在：gender_pref char(1)、rest_mode 至少 varchar(8)、room_type 至少 varchar(16)，都不可空、默认 ''
        if (db.count("select count(*) from information_schema.columns where table_schema = database() and table_name = 'kg_job' "
                + "and is_nullable = 'NO' and column_default = '' and ("
                + "(column_name = 'gender_pref' and data_type = 'char' and character_maximum_length = 1) "
                + "or (column_name = 'rest_mode' and data_type = 'varchar' and character_maximum_length >= 8) "
                + "or (column_name = 'room_type' and data_type = 'varchar' and character_maximum_length >= 16))", KgJdbc.p()) != 3)
        {
            throw new IllegalStateException("V48 kg_job.gender_pref / rest_mode / room_type missing; run " + MIGRATION);
        }
    }
}
