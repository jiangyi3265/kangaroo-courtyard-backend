package com.ruoyi.kangaroo.config.schema;

import java.util.List;
import java.util.Map;

/**
 * 唯一性兜底的登记方：一个工作包一个实现（{@code K/config/schema/WPxxSchema.java}），声明为 Spring 组件，
 * {@link com.ruoyi.kangaroo.config.SchemaAssertion} 启动时汇总全部登记方，缺任何一条就拒绝启动。
 *
 * 为什么按包登记：并发下"同一对象只能有一条进行中"只有数据库唯一索引挡得住（存储生成列 + 唯一索引，
 * 00 §1.0.1），而索引缺了应用照常启动、照常服务，直到出现重复数据才发现。
 * 以前 SchemaAssertion 是一张写死的"表 → 1 条索引"，每个包都去改同一个文件，同一张表也登记不了第二条。
 */
public interface KgSchemaContributor
{
    /**
     * 本包必须存在的唯一索引：表名 → 索引名列表。同一张表可以有多条，
     * 也可以和别的登记方登记在同一张表上（汇总时合并、去重）。
     */
    Map<String, List<String>> requiredIndexes();

    /**
     * 缺索引时告诉运维该执行哪个迁移，例如 {@code sql/kangaroo/V16__payment_core.sql}。
     * 会原样出现在拒绝启动的报错里。
     */
    String migration();
}
