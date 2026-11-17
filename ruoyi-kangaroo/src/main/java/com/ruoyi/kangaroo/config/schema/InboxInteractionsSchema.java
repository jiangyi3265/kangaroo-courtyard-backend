package com.ruoyi.kangaroo.config.schema;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * V43（消息页的「评论」「赞」）：kg_message 缺 actor_id 时拒绝启动。
 * 消息列表、未读红点、评论和点赞通知都读写这一列，缺了就是整个消息中心报 SQL 错误、评论发不出去。
 */
@Component
public class InboxInteractionsSchema implements KgSchemaContributor
{
    static final String MIGRATION = "sql/kangaroo/V43__inbox_interactions.sql";

    private final KgJdbc db;
    private final boolean enabled;

    public InboxInteractionsSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
    {
        this.db = db;
        this.enabled = enabled;
    }

    /** 只加了一列，没有新索引；列在 {@link #check()} 里核对 */
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
        // 必须是可空的 bigint：系统通知没有触发人，写空；被手工建成 int 会把用户 ID 截断
        if (db.count("select count(*) from information_schema.columns where table_schema = database() and table_name = 'kg_message' "
                + "and column_name = 'actor_id' and data_type = 'bigint' and is_nullable = 'YES'", KgJdbc.p()) != 1)
        {
            throw new IllegalStateException("V43 kg_message.actor_id missing; run " + MIGRATION);
        }
    }
}
