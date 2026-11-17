package com.ruoyi.kangaroo.config.schema;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/** V38（10-11 复核修复）：积分流水按人查询的索引。缺了不影响对错，但按人合计积分会扫全表、加急时锁得更久，所以启动时一并核对。 */
@Component
public class ReviewFixSchema implements KgSchemaContributor
{
    @Override
    public Map<String, List<String>> requiredIndexes()
    {
        Map<String, List<String>> m = new LinkedHashMap<String, List<String>>();
        m.put("kg_point_entry", Arrays.asList("idx_point_user"));
        return m;
    }

    @Override
    public String migration()
    {
        return "sql/kangaroo/V38__review_fixes.sql";
    }
}
