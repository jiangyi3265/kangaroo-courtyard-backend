package com.ruoyi.kangaroo.config.schema;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * V41（线上任务素材附件 + 退回修改附截图）：缺表、缺列时拒绝启动，免得上线后门店发素材、退回修改、接单人打开交付页才报 SQL 错误。
 * 新表 kg_online_material_file 的主键和索引由 SchemaAssertion 汇总核对；kg_online_submission 的两列在 {@link #check()} 里核对。
 */
@Component
public class OnlineFilesSchema implements KgSchemaContributor
{
    static final String MIGRATION = "sql/kangaroo/V41__online_files.sql";

    private final KgJdbc db;
    private final boolean enabled;

    public OnlineFilesSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
    {
        this.db = db;
        this.enabled = enabled;
    }

    @Override
    public Map<String, List<String>> requiredIndexes()
    {
        return Collections.singletonMap("kg_online_material_file",
                Arrays.asList("PRIMARY", "uk_mfile_key", "idx_mfile_material", "idx_mfile_app", "idx_mfile_store"));
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
        // 退回截图：逗号拼接的平台图片路径，最多 9 张、2000 字符
        if (db.count("select count(*) from information_schema.columns where table_schema = database() and table_name = 'kg_online_submission' "
                + "and column_name = 'review_images' and data_type = 'varchar' and character_maximum_length >= 2000 and is_nullable = 'NO'", KgJdbc.p()) != 1)
        {
            throw new IllegalStateException("V41 kg_online_submission.review_images missing; run " + MIGRATION);
        }
        // 退回说明放宽到 5000 字：review_comment 必须已经是 text（varchar(200) 时写长说明会被数据库拒绝）
        if (db.count("select count(*) from information_schema.columns where table_schema = database() and table_name = 'kg_online_submission' "
                + "and column_name = 'review_comment' and data_type in ('text', 'mediumtext', 'longtext')", KgJdbc.p()) != 1)
        {
            throw new IllegalStateException("V41 kg_online_submission.review_comment is not text; run " + MIGRATION);
        }
    }
}
