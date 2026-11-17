package com.ruoyi.kangaroo.config.schema;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * V65（2026-10-31 小院评论）：账本、kg_stay_comment 和它要用的列要在，缺了拒绝启动 —— 不然接口在、表不在，写评论和审核页都是 500。
 * 只核对账本和表，不核对菜单行：运营在后台「菜单管理」里自己停用菜单是允许的，不该因此起不来（DashboardMenuSchema 同一口径）。
 * 这张表没有唯一索引，requiredIndexes 留空。kangaroo.schema.assert-indexes=false（只有开发库）时跳过。
 */
@Component
public class StayCommentSchema implements KgSchemaContributor
{
    static final String MIGRATION = "sql/kangaroo/V65__stay_comment.sql";
    static final String LEDGER_SQL = "select count(*) from kg_schema_version where version = 65 and name = 'stay_comment'";
    static final String COLUMNS_SQL = "select count(*) from information_schema.columns where table_schema = database() and table_name = 'kg_stay_comment' "
            + "and column_name in ('comment_id', 'stay_id', 'stay_name', 'store_id', 'app_user_id', 'source_type', 'source_id', 'content', 'images', 'video_url', 'video_cover', "
            + "'portrait_confirmed', 'risk_flag', 'status', 'audit_reason', 'audit_by', 'audit_time', 'reported', 'anonymous', 'del_flag')";
    static final int COLUMNS = 20;

    private final KgJdbc db;
    private final boolean enabled;

    public StayCommentSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
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
            throw new IllegalStateException("V65 stay comment (kg_stay_comment) not migrated; run " + MIGRATION);
        }
    }
}
