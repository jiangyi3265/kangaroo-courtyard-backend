package com.ruoyi.kangaroo.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.config.schema.KgSchemaContributor;

/**
 * 启动时断言数据库里该有的唯一索引都在。
 *
 * 为什么需要这个：这套业务里"同一对象只能有一条进行中"的正确性全靠数据库唯一索引兜底 ——
 * 应用层的判重都有"查"和"写"之间的竞态窗口，并发下只有唯一索引挡得住。
 * 而索引缺了之后，应用照常启动、照常服务，任何页面都不会提示 ——
 * 只有等到并发重复报名、超额录用、两个人被约到同一个时段，才会发现。
 * 部署手册此前还漏掉过 V4（只列了 V1~V3），也就是说"漏执行"是大概率事件。
 *
 * 文档会被漏读，启动断言不会。缺索引就拒绝启动，并告诉运维该跑哪个迁移。
 *
 * 要断言哪些索引，由各工作包的 {@link KgSchemaContributor} 登记（存量的 V4 三条在 CoreSchema），
 * 这里汇总全部登记方：同一张表的多条索引合并、同一条索引被多方登记只查一次（WP01）。
 *
 * 可以用 kangaroo.schema.assert-indexes=false 关掉：只应发生在明知没有这些索引的开发库，
 * 以及"删唯一索引"类变异验证那一轮（R3 S02：那一轮要证明业务断言抓得住，而不是启动失败）。
 * 它属于 test-only 开关，生产环境下的判据由 WP03 的 KangarooProviderGuard 管。
 * 关掉时会打印一条醒目警告，不会静默放过。
 */
@Component
public class SchemaAssertion
{
    private static final Logger log = LoggerFactory.getLogger(SchemaAssertion.class);

    /** 查一条索引在不在。生产实现走 information_schema；单测里换成假的。 */
    interface IndexProbe
    {
        boolean exists(String table, String index) throws SQLException;
    }

    private final DataSource dataSource;

    private final List<KgSchemaContributor> contributors;

    private final boolean enabled;

    @Autowired
    public SchemaAssertion(DataSource dataSource, ObjectProvider<KgSchemaContributor> contributors,
            @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
    {
        this.dataSource = dataSource;
        this.contributors = contributors.orderedStream().collect(Collectors.toList());
        this.enabled = enabled;
    }

    /** 单测用：不连库，由调用方给 IndexProbe */
    SchemaAssertion(List<KgSchemaContributor> contributors, boolean enabled)
    {
        this(null, contributors, enabled);
    }

    /** 单测用：给一个假的 DataSource，走真实启动路径 {@link #check()} */
    SchemaAssertion(DataSource dataSource, List<KgSchemaContributor> contributors, boolean enabled)
    {
        this.dataSource = dataSource;
        this.contributors = new ArrayList<KgSchemaContributor>(contributors);
        this.enabled = enabled;
    }

    /** 一次查询的结果：缺了哪些（表.索引）、总共查了几条 */
    static final class Probed
    {
        final List<String> missing = new ArrayList<String>();

        int total;
    }

    /**
     * 汇总全部登记方：表 → 索引集合（保持登记顺序、去重）。
     * 同一张表被多个登记方登记时合并，任何一方的任何一条都不能丢。
     */
    static Map<String, Set<String>> merge(List<KgSchemaContributor> list)
    {
        Map<String, Set<String>> merged = new LinkedHashMap<String, Set<String>>();
        for (KgSchemaContributor c : list)
        {
            for (Map.Entry<String, List<String>> e : c.requiredIndexes().entrySet())
            {
                Set<String> idx = merged.get(e.getKey());
                if (idx == null)
                {
                    idx = new LinkedHashSet<String>();
                    merged.put(e.getKey(), idx);
                }
                idx.addAll(e.getValue());
            }
        }
        return merged;
    }

    /** 表.索引 → 登记它的迁移（可能不止一个），用于报错时指路 */
    private static Map<String, Set<String>> sources(List<KgSchemaContributor> list)
    {
        Map<String, Set<String>> src = new LinkedHashMap<String, Set<String>>();
        for (KgSchemaContributor c : list)
        {
            for (Map.Entry<String, List<String>> e : c.requiredIndexes().entrySet())
            {
                for (String index : e.getValue())
                {
                    String key = e.getKey() + "." + index;
                    Set<String> s = src.get(key);
                    if (s == null)
                    {
                        s = new LinkedHashSet<String>();
                        src.put(key, s);
                    }
                    s.add(c.migration());
                }
            }
        }
        return src;
    }

    /**
     * 真实启动路径（@PostConstruct 里抛出即拒绝启动）。
     *
     * 连接或查询失败时没有完整结果，必须拒绝启动并保留原 SQLException 作为原因。
     * catch 只接 SQLException；代码缺陷与缺失索引异常照常往外抛。
     * 已完整收集结果后仅关闭连接失败，仍使用完整结果判定，不能抵消缺失索引。
     */
    @PostConstruct
    public void check()
    {
        if (!enabled)
        {
            warnDisabled();
            return;
        }
        // 零登记方与库无关，连库之前就判，也就不可能被下面的 catch 吞掉
        requireContributors();
        Probed probed = null;
        try (Connection conn = dataSource.getConnection())
        {
            probed = collect((table, index) -> indexExists(conn, table, index));
        }
        catch (SQLException e)
        {
            if (probed == null)
            {
                throw new IllegalStateException("唯一索引启动断言未能完成，拒绝启动：无法取得连接或查询索引元数据", e);
            }
            // 查完了、只是归还连接时出错：结果已经在手里，照样按结果判，不能让它把"缺索引"抵消掉
            log.warn("唯一索引启动断言查询完成后关闭数据库连接失败：{}", e.getMessage());
        }
        failIfMissing(probed);
    }

    /** 逐条查汇总后的索引，缺任何一条就抛 IllegalStateException。单测用它绕开连库，判定逻辑与 {@link #check()} 同一套 */
    void assertIndexes(IndexProbe probe) throws SQLException
    {
        if (!enabled)
        {
            warnDisabled();
            return;
        }
        requireContributors();
        failIfMissing(collect(probe));
    }

    private void requireContributors()
    {
        if (contributors.isEmpty())
        {
            // 至少有 CoreSchema。一个登记方都没有只能是装配坏了（包没被扫描到之类），
            // 这时"断言通过"是空转 —— 失败关闭，而不是放行。
            throw new IllegalStateException("唯一索引启动断言没有任何登记方（KgSchemaContributor），"
                    + "至少应有 CoreSchema：检查 com.ruoyi.kangaroo.config.schema 是否被组件扫描");
        }
    }

    /** 只查不判：逐条问 probe，收集缺了哪些。probe 抛出的异常原样往外抛 */
    private Probed collect(IndexProbe probe) throws SQLException
    {
        Probed probed = new Probed();
        for (Map.Entry<String, Set<String>> e : merge(contributors).entrySet())
        {
            for (String index : e.getValue())
            {
                probed.total++;
                if (!probe.exists(e.getKey(), index))
                {
                    probed.missing.add(e.getKey() + "." + index);
                }
            }
        }
        return probed;
    }

    /** 缺任何一条就抛 IllegalStateException，并列出缺的索引与只属于它们的迁移 */
    private void failIfMissing(Probed probed)
    {
        if (!probed.missing.isEmpty())
        {
            Map<String, Set<String>> from = sources(contributors);
            Set<String> migrations = new LinkedHashSet<String>();
            for (String key : probed.missing)
            {
                Set<String> m = from.get(key);
                migrations.addAll(m == null ? Collections.<String>emptySet() : m);
            }
            throw new IllegalStateException(
                    "数据库缺少必需的唯一索引：" + probed.missing
                    + "。这些索引是并发下重复数据（重复报名 / 超额录用 / 撞车时段……）的唯一兜底，"
                    + "缺了应用照常服务但数据会出错。请按 INTEGRATION.md 第五节执行：" + migrations
                    + "。确实要在没有这些索引的开发库上启动，用 kangaroo.schema.assert-indexes=false 显式关闭。");
        }
        log.info("唯一索引启动断言通过：{} 个登记方、{} 条索引", contributors.size(), probed.total);
    }

    private static void warnDisabled()
    {
        log.warn("==========================================================");
        log.warn("  已关闭唯一索引启动断言（kangaroo.schema.assert-indexes=false）");
        log.warn("  缺少唯一索引时，并发重复报名 / 超额录用 / 撞车面试等");
        log.warn("  将不再有任何兜底。仅限开发库与删索引类变异验证。");
        log.warn("==========================================================");
    }

    private boolean indexExists(Connection conn, String table, String index) throws SQLException
    {
        String sql = "select count(*) from information_schema.statistics "
                + "where table_schema = database() and table_name = ? and index_name = ?";
        // 登记的主键与 uk_ 索引承担唯一性约束；普通 idx_ 查询索引仍允许非唯一。
        if ("PRIMARY".equals(index) || index.startsWith("uk_"))
        {
            sql += " and non_unique = 0";
        }
        try (PreparedStatement ps = conn.prepareStatement(sql))
        {
            ps.setString(1, table);
            ps.setString(2, index);
            try (ResultSet rs = ps.executeQuery())
            {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }
}
