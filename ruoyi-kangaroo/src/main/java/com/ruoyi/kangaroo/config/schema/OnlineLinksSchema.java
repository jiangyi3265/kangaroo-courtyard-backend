package com.ruoyi.kangaroo.config.schema;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * V50（2026-10-17 主人反馈：一轮成果最多 3 个链接或口令）：kg_online_submission.link 还是 varchar(500) 时拒绝启动。
 * 新代码把几个链接用换行连起来存在这一列里（KgOnlineTaskService.LINKS_MAX = 3，每个 LINK_MAX = 500 字），
 * 列没放宽就上线的话，接单人交两三个长口令提交成果会直接报 SQL 错误。
 */
@Component
public class OnlineLinksSchema implements KgSchemaContributor
{
    static final String MIGRATION = "sql/kangaroo/V50__online_links.sql";

    private final KgJdbc db;
    private final boolean enabled;

    public OnlineLinksSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
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
        // 必须是不可空的 varchar，至少 1600 个字符（3 × 500 + 分隔的换行；以后再放宽也认）
        if (db.count("select count(*) from information_schema.columns where table_schema = database() and table_name = 'kg_online_submission' "
                + "and column_name = 'link' and data_type = 'varchar' and character_maximum_length >= 1600 and is_nullable = 'NO'", KgJdbc.p()) != 1)
        {
            throw new IllegalStateException("V50 kg_online_submission.link is not varchar(1600); run " + MIGRATION);
        }
    }
}
