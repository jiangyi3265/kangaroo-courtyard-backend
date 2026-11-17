package com.ruoyi.kangaroo.config.schema;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * V63（2026-10-30 商家端 + 新角色）：kg_merchant_request、入驻申请的 store_id / grant_id、日报 ledger、周报 report_type、授权的 also_service（小院主理人兼任商家服务人）。
 * 账本或列不在时拒绝启动：不然商家发布岗位、换工主理人记账时才报 500，而且没人知道是少跑了迁移；also_service 不在时员工端所有接口都读不出授权。
 * 周报唯一键也登记：同店同周两种周报要各占一行，索引还是旧的时第二份周报会插入失败。
 * 不核对菜单和字典：运营可以在后台自己停用。
 */
@Component
public class MerchantRolesSchema implements KgSchemaContributor
{
    static final String MIGRATION = "sql/kangaroo/V63__merchant_roles.sql";
    static final String LEDGER_SQL = "select count(*) from kg_schema_version where version = 63 and name = 'merchant_roles'";
    static final String COLUMNS_SQL = "select count(*) from information_schema.columns where table_schema = database() and "
            + "((table_name = 'kg_merchant_request' and column_name = 'request_id') or (table_name = 'kg_merchant_apply' and column_name in ('store_id', 'grant_id')) "
            + "or (table_name = 'kg_daily_entry' and column_name = 'ledger') or (table_name = 'kg_weekly_report' and column_name = 'report_type') "
            + "or (table_name = 'kg_staff_grant' and column_name = 'also_service'))";
    static final int COLUMNS = 6;

    private final KgJdbc db;
    private final boolean enabled;

    public MerchantRolesSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
    {
        this.db = db;
        this.enabled = enabled;
    }

    @Override
    public Map<String, List<String>> requiredIndexes()
    {
        Map<String, List<String>> m = new LinkedHashMap<String, List<String>>();
        m.put("kg_merchant_request", Arrays.asList("uk_mreq_job", "idx_mreq_city_status", "idx_mreq_store"));
        m.put("kg_weekly_report", Collections.singletonList("uk_weekly_store_week_type"));
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
            return;
        }
        if (db.count(LEDGER_SQL, KgJdbc.p()) != 1 || db.count(COLUMNS_SQL, KgJdbc.p()) != COLUMNS)
        {
            throw new IllegalStateException("V63 merchant roles (kg_merchant_request, ledger, report_type) not migrated; run " + MIGRATION);
        }
    }
}
