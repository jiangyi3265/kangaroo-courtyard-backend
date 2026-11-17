package com.ruoyi.kangaroo.config.schema;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * V45（2026-10-16）：动态评论表按评论人查的索引 idx_pc_user。
 * 注销账号先按 app_user_id 读出本人评论的主键、再按主键软删（KgAccountService）；缺了这条索引对错不受影响，
 * 但按评论人查评论要扫全表，所以启动时一并核对（{@link com.ruoyi.kangaroo.config.SchemaAssertion} 汇总，缺了拒绝启动）。
 */
@Component
public class CommentUserIndexSchema implements KgSchemaContributor
{
    static final String MIGRATION = "sql/kangaroo/V45__post_comment_user_idx.sql";

    @Override
    public Map<String, List<String>> requiredIndexes()
    {
        return Collections.singletonMap("kg_post_comment", Arrays.asList("idx_pc_user"));
    }

    @Override
    public String migration()
    {
        return MIGRATION;
    }
}
