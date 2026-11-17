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
 * V46（2026-10-16 私信放宽 + 门店会话发文件）：缺表、列没放宽时拒绝启动，免得上线后发长消息被数据库拒绝（Data too long）、
 * 发文件和打开聊天报 SQL 错误（消息列表的查询里就关联了 kg_chat_file）。
 * 新表 kg_chat_file 的主键和索引由 SchemaAssertion 汇总核对；kg_chat_msg.content 的长度在 {@link #check()} 里核对。
 */
@Component
public class ChatFileSchema implements KgSchemaContributor
{
    static final String MIGRATION = "sql/kangaroo/V46__chat_file.sql";

    private final KgJdbc db;
    private final boolean enabled;

    public ChatFileSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
    {
        this.db = db;
        this.enabled = enabled;
    }

    @Override
    public Map<String, List<String>> requiredIndexes()
    {
        return Collections.singletonMap("kg_chat_file", Arrays.asList("PRIMARY", "uk_chat_file_key", "idx_chat_file_sender"));
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
        // 每条文字最多 2000 字（KgChatService.TEXT_MAX）：content 必须至少是 varchar(2000)
        if (db.count("select count(*) from information_schema.columns where table_schema = database() and table_name = 'kg_chat_msg' "
                + "and column_name = 'content' and data_type = 'varchar' and character_maximum_length >= 2000", KgJdbc.p()) != 1)
        {
            throw new IllegalStateException("V46 kg_chat_msg.content is shorter than 2000; run " + MIGRATION);
        }
    }
}
