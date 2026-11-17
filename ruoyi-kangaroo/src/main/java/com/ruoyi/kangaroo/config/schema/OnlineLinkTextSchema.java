package com.ruoyi.kangaroo.config.schema;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * V47（线上任务成果链接放宽到 500 字）：kg_online_submission.link 还是 varchar(255) 时拒绝启动。
 * 新代码允许粘贴小红书、抖音的整段分享口令（KgOnlineTaskService.LINK_MAX = 500），
 * 列没放宽就上线的话，接单人粘贴长口令提交成果会直接报 SQL 错误。
 */
@Component
public class OnlineLinkTextSchema implements KgSchemaContributor
{
    static final String MIGRATION = "sql/kangaroo/V47__online_link_text.sql";

    private final KgJdbc db;
    private final boolean enabled;

    public OnlineLinkTextSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
    {
        this.db = db;
        this.enabled = enabled;
    }

    /** 只放宽了一列，没有新索引；列在 {@link #check()} 里核对 */
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
        // 必须是不可空的 varchar，至少 500 个字符（以后再放宽也认）
        if (db.count("select count(*) from information_schema.columns where table_schema = database() and table_name = 'kg_online_submission' "
                + "and column_name = 'link' and data_type = 'varchar' and character_maximum_length >= 500 and is_nullable = 'NO'", KgJdbc.p()) != 1)
        {
            throw new IllegalStateException("V47 kg_online_submission.link is not varchar(500); run " + MIGRATION);
        }
    }
}
