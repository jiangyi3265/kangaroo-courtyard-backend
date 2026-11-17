package com.ruoyi.kangaroo.config.schema;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * V55（2026-10-18 管理后台「数据看板」）：只加了菜单「袋鼠小院 → 数据看板」和授权（kangaroo:dashboard:view），没有新表新列。
 * 账本里没有 V55 时拒绝启动 —— 不然新接口在、菜单不在，谁都进不去看板，首页也不出看板，而且没有任何报错能看出来。
 * 只核对账本、不核对菜单行：运营在后台「菜单管理」里自己停用 / 删掉这个菜单是允许的，不该因此起不来。
 */
@Component
public class DashboardMenuSchema implements KgSchemaContributor
{
    static final String MIGRATION = "sql/kangaroo/V55__dashboard_menu.sql";
    static final String LEDGER_SQL = "select count(*) from kg_schema_version where version = 55 and name = 'dashboard_menu'";

    private final KgJdbc db;
    private final boolean enabled;

    public DashboardMenuSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
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
        if (db.count(LEDGER_SQL, KgJdbc.p()) != 1)
        {
            throw new IllegalStateException("V55 dashboard menu (kangaroo:dashboard:view) not migrated; run " + MIGRATION);
        }
    }
}
