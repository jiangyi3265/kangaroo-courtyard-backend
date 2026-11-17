package com.ruoyi.kangaroo.config.schema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * 存量（V1–V5）的唯一性兜底：V4 建的三条唯一索引。
 *
 *   uk_app_active_user_job  同一个人对同一个岗位只能有一条活跃报名
 *   uk_store_name_city      同城不能有两家同名门店
 *   uk_slot_store_range     同门店同一时间区间只能有一个面试时段
 *
 * 应用层的判重都有"查"和"写"之间的竞态窗口（发布前实测 8 并发落库 6 条），并发下只有唯一索引挡得住。
 * 原先写死在 SchemaAssertion 里，WP01 搬到这里，与后续各包的 WPxxSchema 同一种登记方式。
 */
@Component
public class CoreSchema implements KgSchemaContributor
{
    @Override
    public Map<String, List<String>> requiredIndexes()
    {
        Map<String, List<String>> m = new LinkedHashMap<String, List<String>>();
        m.put("kg_application", Collections.singletonList("uk_app_active_user_job"));
        m.put("kg_store", Collections.singletonList("uk_store_name_city"));
        m.put("kg_interview_slot", Collections.singletonList("uk_slot_store_range"));
        return m;
    }

    @Override
    public String migration()
    {
        return "sql/kangaroo/V4__uniqueness_and_counter_fix.sql"
                + "（执行前先跑只读的 V4__duplicates-report.sql 看有没有需要先处理的重复数据）";
    }
}
