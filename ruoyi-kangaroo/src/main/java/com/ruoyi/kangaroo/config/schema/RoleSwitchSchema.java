package com.ruoyi.kangaroo.config.schema;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.RolePhase;

/**
 * V64（2026-10-30 角色两阶段切换）：账本、三张表要在，阶段要读得出来，读不到拒绝启动——猜一个阶段就是猜谁有资金权。
 * 启动时把库里的阶段放进 {@link RolePhase}。kangaroo.schema.assert-indexes=false（只有开发库）时读不到就按 legacy，打一条警告。
 */
@Component
public class RoleSwitchSchema implements KgSchemaContributor
{
    private static final Logger log = LoggerFactory.getLogger(RoleSwitchSchema.class);

    static final String MIGRATION = "sql/kangaroo/V64__role_switch.sql";
    static final String LEDGER_SQL = "select count(*) from kg_schema_version where version = 64 and name = 'role_switch'";
    static final String TABLES_SQL = "select count(*) from information_schema.tables where table_schema = database() "
            + "and table_name in ('kg_role_switch', 'kg_role_switch_backup', 'kg_staff_client_seen')";
    static final int TABLES = 3;
    static final String PHASE_SQL = "select phase from kg_role_switch where id = 1";

    private final KgJdbc db;
    private final boolean enabled;

    public RoleSwitchSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
    {
        this.db = db;
        this.enabled = enabled;
    }

    @Override
    public Map<String, List<String>> requiredIndexes()
    {
        Map<String, List<String>> m = new LinkedHashMap<String, List<String>>();
        m.put("kg_role_switch_backup", Arrays.asList("uk_rsb_grant", "uk_rsb_dict"));
        return m;
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
            try
            {
                load();
            }
            catch (RuntimeException e)
            {
                RolePhase.set(RolePhase.LEGACY);
                log.warn("读不到角色切换阶段，按 legacy 运行（kangaroo.schema.assert-indexes=false，只应出现在开发库）：{}", e.getMessage());
            }
            return;
        }
        if (db.count(LEDGER_SQL, KgJdbc.p()) != 1 || db.count(TABLES_SQL, KgJdbc.p()) != TABLES)
        {
            throw new IllegalStateException("V64 role switch (kg_role_switch, kg_role_switch_backup, kg_staff_client_seen) not migrated; run " + MIGRATION);
        }
        load();
    }

    private void load()
    {
        Map<String, Object> row = db.one(PHASE_SQL, KgJdbc.p());
        if (row == null)
        {
            throw new IllegalStateException("kg_role_switch has no phase row; run " + MIGRATION);
        }
        RolePhase.set(String.valueOf(row.get("phase")));
        log.info("角色切换阶段：{}", RolePhase.code());
    }
}
