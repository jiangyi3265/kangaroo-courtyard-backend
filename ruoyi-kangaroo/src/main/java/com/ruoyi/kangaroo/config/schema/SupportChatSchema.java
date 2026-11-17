package com.ruoyi.kangaroo.config.schema;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * V72（2026-11-03 主人：「历史聊天记录，永久保存，不能删除」）：账本、kg_support_chat 和它要用的列要在，缺了拒绝启动 ——
 * 不然 AI袋鼠一问就 500（问答和写聊天记录在同一个事务里），聊天记录页也打不开。
 * 只核对账本和表，不核对菜单行：运营在后台「菜单管理」里自己停用菜单是允许的，不该因此起不来（StayCommentSchema 同一口径）。
 * 这张表没有唯一索引，requiredIndexes 留空。kangaroo.schema.assert-indexes=false（只有开发库）时跳过。
 */
@Component
public class SupportChatSchema implements KgSchemaContributor
{
    static final String MIGRATION = "sql/kangaroo/V72__support_chat.sql";
    static final String LEDGER_SQL = "select count(*) from kg_schema_version where version = 72 and name = 'support_chat'";
    static final String COLUMNS_SQL = "select count(*) from information_schema.columns where table_schema = database() and table_name = 'kg_support_chat' "
            + "and column_name in ('chat_id', 'app_user_id', 'role', 'content', 'source', 'intent', 'context', 'extra', 'create_time')";
    static final int COLUMNS = 9;

    private final KgJdbc db;
    private final boolean enabled;

    public SupportChatSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
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
        if (db.count(LEDGER_SQL, KgJdbc.p()) != 1 || db.count(COLUMNS_SQL, KgJdbc.p()) != COLUMNS)
        {
            throw new IllegalStateException("V72 support chat history (kg_support_chat) not migrated; run " + MIGRATION);
        }
    }
}
