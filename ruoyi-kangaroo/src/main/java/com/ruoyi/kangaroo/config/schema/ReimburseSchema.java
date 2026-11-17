package com.ruoyi.kangaroo.config.schema;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * V66（包 20261031-forms）的采购报销：账本、kg_reimbursement 和它要用的列要在，缺了拒绝启动 —— 不然接口在、表不在，提交和后台表格都是 500。
 * 账本和 FormsSchema 是同一条（同一个迁移）。只核对账本和表，不核对菜单行：运营在后台「菜单管理」里自己停用菜单是允许的，不该因此起不来（StayCommentSchema 同一口径）。
 * 这张表没有唯一索引，requiredIndexes 留空。kangaroo.schema.assert-indexes=false（只有开发库）时跳过。
 */
@Component
public class ReimburseSchema implements KgSchemaContributor
{
    static final String MIGRATION = "sql/kangaroo/V66__forms.sql";
    static final String LEDGER_SQL = "select count(*) from kg_schema_version where version = 66 and name = 'forms'";
    static final String COLUMNS_SQL = "select count(*) from information_schema.columns where table_schema = database() and table_name = 'kg_reimbursement' "
            + "and column_name in ('reimburse_id', 'store_id', 'store_name', 'app_user_id', 'submitter_name', 'item_name', 'quantity', 'amount', 'purchase_date', 'receipts', 'remark', "
            + "'status', 'revision', 'reject_reason', 'audit_by', 'audit_time', 'paid_by', 'paid_time', 'submit_time', 'anonymous', 'del_flag')";
    static final int COLUMNS = 21;

    private final KgJdbc db;
    private final boolean enabled;

    public ReimburseSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
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
            throw new IllegalStateException("V66 reimbursement (kg_reimbursement) not migrated; run " + MIGRATION);
        }
    }
}
