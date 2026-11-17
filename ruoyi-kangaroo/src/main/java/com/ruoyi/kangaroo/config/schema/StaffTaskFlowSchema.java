package com.ruoyi.kangaroo.config.schema;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * V74（2026-11-03 主人：「任务派发，需要有个流转过程……一键发送给所有全体员工……给某一个岗位，单独的发布任务」）：
 * 新表 kg_staff_task_batch（派发批次）、kg_staff_task_event（流转时间线），kg_staff_task 加 batch_id / role_code / requested_due_date / admin_attention，
 * 按钮权限 kangaroo:stafftask:handle。新代码每次派发、每一步流转都要写这两张表和这几列，没有就是 500。
 * 账本里没有 V74 时拒绝启动；两张新表的索引由 SchemaAssertion 汇总核对（缺了同样拒绝启动）。
 */
@Component
public class StaffTaskFlowSchema implements KgSchemaContributor
{
    static final String MIGRATION = "sql/kangaroo/V74__staff_task_flow.sql";
    static final String LEDGER_SQL = "select count(*) from kg_schema_version where version = 74 and name = 'staff_task_flow'";

    private final KgJdbc db;
    private final boolean enabled;

    public StaffTaskFlowSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
    {
        this.db = db;
        this.enabled = enabled;
    }

    @Override
    public Map<String, List<String>> requiredIndexes()
    {
        Map<String, List<String>> m = new LinkedHashMap<String, List<String>>();
        m.put("kg_staff_task_batch", Arrays.asList("uk_staff_task_batch_request"));
        m.put("kg_staff_task_event", Arrays.asList("idx_staff_task_event"));
        m.put("kg_staff_task", Arrays.asList("idx_staff_task_batch", "idx_staff_task_attention"));
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
        if (db.count(LEDGER_SQL, KgJdbc.p()) != 1)
        {
            throw new IllegalStateException("V74 staff task flow (kg_staff_task_batch / kg_staff_task_event, kangaroo:stafftask:handle) not migrated; run " + MIGRATION);
        }
    }
}
