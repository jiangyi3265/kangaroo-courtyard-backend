package com.ruoyi.kangaroo.config.schema;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * V56（2026-10-18「成为商家」）：新表 kg_merchant_apply + 后台菜单「袋鼠小院 → 商家入驻申请」。
 * 账本里没有 V56、或者表不在时拒绝启动 —— 不然小程序里点「提交申请」才报 500，后台列表也打不开，而且没人知道是少跑了迁移。
 * 只核对账本和表、不核对菜单行：运营在后台「菜单管理」里自己停用 / 删掉这个菜单是允许的，不该因此起不来。
 */
@Component
public class MerchantApplySchema implements KgSchemaContributor
{
    static final String MIGRATION = "sql/kangaroo/V56__merchant_apply.sql";
    static final String LEDGER_SQL = "select count(*) from kg_schema_version where version = 56 and name = 'merchant_apply'";
    static final String TABLE_SQL = "select count(*) from information_schema.tables where table_schema = database() and table_name = 'kg_merchant_apply'";

    private final KgJdbc db;
    private final boolean enabled;

    public MerchantApplySchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
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
        if (db.count(LEDGER_SQL, KgJdbc.p()) != 1 || db.count(TABLE_SQL, KgJdbc.p()) != 1)
        {
            throw new IllegalStateException("V56 merchant apply (kg_merchant_apply, kangaroo:merchantapply:*) not migrated; run " + MIGRATION);
        }
    }
}
