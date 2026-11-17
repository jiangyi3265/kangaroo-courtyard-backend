package com.ruoyi.kangaroo.config.schema;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * V67（2026-11-03 主人：「年龄选择年龄段，在后台管理配置」「加一个性格」）：两个字典 kg_job_age / kg_job_personality 和它们的种子选项，
 * 菜单「袋鼠小院 → 岗位要求选项」（kangaroo:jobopt:*），没有新表新列。
 * 账本里没有 V67 时拒绝启动 —— 不然新接口在、菜单不在，运营没地方改选项；字典是空的，发布页的年龄、性格也就没得选。
 * 只核对账本、不核对字典行和菜单行：运营停用、删掉其中的选项或菜单是允许的，不该因此起不来。
 */
@Component
public class JobRequirementOptionsSchema implements KgSchemaContributor
{
    static final String MIGRATION = "sql/kangaroo/V67__job_requirement_options.sql";
    static final String LEDGER_SQL = "select count(*) from kg_schema_version where version = 67 and name = 'job_requirement_options'";

    private final KgJdbc db;
    private final boolean enabled;

    public JobRequirementOptionsSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
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
            throw new IllegalStateException("V67 job requirement options (kg_job_age / kg_job_personality, kangaroo:jobopt) not migrated; run " + MIGRATION);
        }
    }
}
