package com.ruoyi.kangaroo.config.schema;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * V52（2026-10-17 主人要求：经验分享也能自己选关联）：kg_post.link_type / link_id 缺了或定义不对时拒绝启动。
 *  · link_type 至少 varchar(16)、不可空、默认 ''：新代码发帖时写它，'' = 没选；可空或默认值不对，老版本程序（不写这一列）发的帖子就落不成「没选」；
 *  · link_id bigint、可空：没选关联写 null。
 * 列没加就上线的话，发帖直接报 SQL 错误。索引 idx_post_link（以后按关联对象查帖子）由 SchemaAssertion 一起核对。
 */
@Component
public class PostLinkSchema implements KgSchemaContributor
{
    static final String MIGRATION = "sql/kangaroo/V52__post_link.sql";

    private final KgJdbc db;
    private final boolean enabled;

    public PostLinkSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
    {
        this.db = db;
        this.enabled = enabled;
    }

    /** 普通查询索引（idx_ 开头，不要求唯一） */
    @Override
    public Map<String, List<String>> requiredIndexes()
    {
        return Collections.singletonMap("kg_post", Collections.singletonList("idx_post_link"));
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
        if (db.count("select count(*) from information_schema.columns where table_schema = database() and table_name = 'kg_post' and ("
                + "(column_name = 'link_type' and data_type = 'varchar' and character_maximum_length >= 16 and is_nullable = 'NO' and column_default = '') "
                + "or (column_name = 'link_id' and data_type = 'bigint' and is_nullable = 'YES'))", KgJdbc.p()) != 2)
        {
            throw new IllegalStateException("V52 kg_post.link_type / link_id missing; run " + MIGRATION);
        }
    }
}
