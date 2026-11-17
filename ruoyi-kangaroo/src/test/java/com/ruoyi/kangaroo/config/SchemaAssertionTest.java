package com.ruoyi.kangaroo.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.ruoyi.kangaroo.config.schema.CoreSchema;
import com.ruoyi.kangaroo.config.schema.KgSchemaContributor;

/**
 * 唯一索引启动断言汇总全部登记方（WP01；R3 S02）。
 *
 * 为什么要测汇总：以前 SchemaAssertion 是一张写死的"表 → 1 条索引"的表，
 * 后面每个包都要往里加索引（kg_pay_order.uk_pay_biz、kg_message.dedup_uk……），
 * 同一张表还会有多条。汇总逻辑只要有一处"只取第一条"（同表只取第一个索引、
 * 同表只认第一个登记方），漏掉的那条索引缺了应用照常启动 —— 正是这道断言要防的事。
 *
 * 前半（assertIndexes + FakeProbe）测"该查哪些、缺了是否拒绝、报错是否指到正确的迁移"；
 * 后半（check + FakeDb）走真实启动路径 @PostConstruct check()：假 DataSource 一路给到
 * Connection → PreparedStatement → ResultSet，测"缺索引真的拒绝启动"不会被连库那层的 catch 吞掉
 * （WP01 复核第 1 轮：把 rethrow 删掉，旧的 7 例全绿，缺索引只剩一条 WARN、应用照常启动）。
 */
class SchemaAssertionTest
{
    /** 测试用登记方：固定的表 → 索引清单与迁移说明 */
    private static KgSchemaContributor contributor(final String migration, final Object... tableIndexPairs)
    {
        final Map<String, List<String>> m = new LinkedHashMap<String, List<String>>();
        for (int i = 0; i < tableIndexPairs.length; i += 2)
        {
            String table = (String) tableIndexPairs[i];
            @SuppressWarnings("unchecked")
            List<String> idx = (List<String>) tableIndexPairs[i + 1];
            m.put(table, idx);
        }
        return new KgSchemaContributor()
        {
            @Override
            public Map<String, List<String>> requiredIndexes()
            {
                return m;
            }

            @Override
            public String migration()
            {
                return migration;
            }
        };
    }

    /** A：kg_a 两条、kg_b 一条；B：kg_a 再加两条（与 A 同表）、kg_c 一条 */
    private static List<KgSchemaContributor> twoContributors()
    {
        return Arrays.asList(
                contributor("V4__a.sql", "kg_a", Arrays.asList("uk_a1", "uk_a2"),
                        "kg_b", Collections.singletonList("uk_b1")),
                contributor("V16__b.sql", "kg_a", Arrays.asList("uk_a3", "uk_a4"),
                        "kg_c", Collections.singletonList("uk_c1")));
    }

    /** 记录被问到的 表.索引，并按给定集合回答"在不在" */
    private static final class FakeProbe implements SchemaAssertion.IndexProbe
    {
        final Set<String> present;
        final List<String> asked = new ArrayList<String>();

        FakeProbe(String... present)
        {
            this.present = new HashSet<String>(Arrays.asList(present));
        }

        @Override
        public boolean exists(String table, String index)
        {
            asked.add(table + "." + index);
            return present.contains(table + "." + index);
        }
    }

    private static final String[] ALL = { "kg_a.uk_a1", "kg_a.uk_a2", "kg_a.uk_a3", "kg_a.uk_a4",
            "kg_b.uk_b1", "kg_c.uk_c1" };

    @Test
    @DisplayName("两个登记方汇总：同一张表的索引合并、一条不漏，每条都被查到")
    void mergesAllContributors()
    {
        Map<String, Set<String>> merged = SchemaAssertion.merge(twoContributors());
        assertEquals(new HashSet<String>(Arrays.asList("kg_a", "kg_b", "kg_c")), merged.keySet());
        assertEquals(new HashSet<String>(Arrays.asList("uk_a1", "uk_a2", "uk_a3", "uk_a4")), merged.get("kg_a"));
        assertEquals(Collections.singleton("uk_b1"), merged.get("kg_b"));
        assertEquals(Collections.singleton("uk_c1"), merged.get("kg_c"));

        FakeProbe probe = new FakeProbe(ALL);
        assertDoesNotThrow(() -> new SchemaAssertion(twoContributors(), true).assertIndexes(probe));
        assertEquals(new HashSet<String>(Arrays.asList(ALL)), new HashSet<String>(probe.asked));
    }

    @Test
    @DisplayName("缺第二个登记方在同一张表上的第二条索引：拒绝启动，并指到那个登记方的迁移")
    void missingLaterIndexOnSharedTableFailsStartup()
    {
        FakeProbe probe = new FakeProbe("kg_a.uk_a1", "kg_a.uk_a2", "kg_a.uk_a3", "kg_b.uk_b1", "kg_c.uk_c1");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new SchemaAssertion(twoContributors(), true).assertIndexes(probe));
        assertTrue(e.getMessage().contains("kg_a.uk_a4"), e.getMessage());
        assertTrue(e.getMessage().contains("V16__b.sql"), e.getMessage());
        assertFalse(e.getMessage().contains("kg_a.uk_a1"), "不缺的索引不该出现在报错里：" + e.getMessage());
    }

    @Test
    @DisplayName("缺第一个登记方的索引：报错指到第一个登记方的迁移，而不是后一个")
    void missingFirstContributorIndexNamesItsMigration()
    {
        FakeProbe probe = new FakeProbe("kg_a.uk_a1", "kg_a.uk_a3", "kg_a.uk_a4", "kg_b.uk_b1", "kg_c.uk_c1");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new SchemaAssertion(twoContributors(), true).assertIndexes(probe));
        assertTrue(e.getMessage().contains("kg_a.uk_a2"), e.getMessage());
        assertTrue(e.getMessage().contains("V4__a.sql"), e.getMessage());
        assertFalse(e.getMessage().contains("V16__b.sql"), e.getMessage());
    }

    @Test
    @DisplayName("两个登记方登记了同一条索引：只查一次、缺了只报一次，两个迁移都点名")
    void duplicateRegistrationIsProbedOnce()
    {
        List<KgSchemaContributor> list = Arrays.asList(
                contributor("V4__a.sql", "kg_a", Collections.singletonList("uk_x")),
                contributor("V16__b.sql", "kg_a", Collections.singletonList("uk_x")));
        FakeProbe probe = new FakeProbe();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new SchemaAssertion(list, true).assertIndexes(probe));
        assertEquals(Collections.singletonList("kg_a.uk_x"), probe.asked);
        assertEquals(e.getMessage().indexOf("kg_a.uk_x"), e.getMessage().lastIndexOf("kg_a.uk_x"), e.getMessage());
        assertTrue(e.getMessage().contains("V4__a.sql") && e.getMessage().contains("V16__b.sql"), e.getMessage());
    }

    @Test
    @DisplayName("显式关闭（kangaroo.schema.assert-indexes=false）：不查库、不拒绝启动")
    void disabledSkipsProbing()
    {
        FakeProbe probe = new FakeProbe();
        assertDoesNotThrow(() -> new SchemaAssertion(twoContributors(), false).assertIndexes(probe));
        assertTrue(probe.asked.isEmpty(), "关闭时不该查库：" + probe.asked);
    }

    @Test
    @DisplayName("一个登记方都没有（装配坏了）：拒绝启动，而不是空转通过")
    void noContributorFailsClosed()
    {
        FakeProbe probe = new FakeProbe();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new SchemaAssertion(Collections.<KgSchemaContributor>emptyList(), true).assertIndexes(probe));
        assertTrue(e.getMessage().contains("CoreSchema"), e.getMessage());
    }

    @Test
    @DisplayName("CoreSchema 登记的正好是 V4 建出来的三条唯一索引")
    void coreSchemaRegistersV4Indexes()
    {
        Map<String, Set<String>> merged = SchemaAssertion.merge(
                Collections.<KgSchemaContributor>singletonList(new CoreSchema()));
        Map<String, Set<String>> want = new LinkedHashMap<String, Set<String>>();
        want.put("kg_application", Collections.singleton("uk_app_active_user_job"));
        want.put("kg_store", Collections.singleton("uk_store_name_city"));
        want.put("kg_interview_slot", Collections.singleton("uk_slot_store_range"));
        assertEquals(want, merged);
        assertTrue(new CoreSchema().migration().contains("V4__uniqueness_and_counter_fix.sql"));
    }

    // ================================================================ 真实启动路径 check()

    @SuppressWarnings("unchecked")
    private static <T> T proxy(final Class<T> type, final InvocationHandler h)
    {
        return (T) Proxy.newProxyInstance(SchemaAssertionTest.class.getClassLoader(), new Class<?>[] { type },
                (p, m, a) -> {
                    if (m.getDeclaringClass() == Object.class)
                    {
                        if ("equals".equals(m.getName()))
                        {
                            return p == a[0];
                        }
                        if ("hashCode".equals(m.getName()))
                        {
                            return System.identityHashCode(p);
                        }
                        return "Fake" + type.getSimpleName();
                    }
                    return h.invoke(p, m, a);
                });
    }

    /**
     * 假数据库：information_schema.statistics 里只有 present 这些 表.索引。
     * 按 prepareStatement 的 SQL 与 setString(1 = 表, 2 = 索引) 回答 count(*)；
     * 记下被问到的 表.索引、执行过的 SQL、取了几次连接、还有几个连接/语句/结果集没关。
     * 用到清单之外的 JDBC 方法直接抛 UnsupportedOperationException —— 它不是 SQLException，
     * 不会被当成"连不上库"放过，测试会当场红。
     */
    private static final class FakeDb
    {
        final Set<String> present;
        final List<String> asked = new ArrayList<String>();
        final List<String> sqls = new ArrayList<String>();
        int connections;
        int openResources;
        SQLException failOnConnect;
        SQLException failOnClose;
        RuntimeException failOnQuery;

        FakeDb(String... present)
        {
            this.present = new HashSet<String>(Arrays.asList(present));
        }

        DataSource dataSource()
        {
            return proxy(DataSource.class, (p, m, a) -> {
                if ("getConnection".equals(m.getName()) && (a == null || a.length == 0))
                {
                    connections++;
                    if (failOnConnect != null)
                    {
                        throw failOnConnect;
                    }
                    openResources++;
                    return connection();
                }
                throw new UnsupportedOperationException("DataSource." + m.getName());
            });
        }

        private Connection connection()
        {
            return proxy(Connection.class, (p, m, a) -> {
                if ("prepareStatement".equals(m.getName()) && a != null && a.length == 1)
                {
                    sqls.add((String) a[0]);
                    openResources++;
                    return statement();
                }
                if ("close".equals(m.getName()))
                {
                    openResources--;
                    if (failOnClose != null)
                    {
                        throw failOnClose;
                    }
                    return null;
                }
                throw new UnsupportedOperationException("Connection." + m.getName());
            });
        }

        private PreparedStatement statement()
        {
            final Map<Integer, String> params = new LinkedHashMap<Integer, String>();
            return proxy(PreparedStatement.class, (p, m, a) -> {
                if ("setString".equals(m.getName()))
                {
                    params.put((Integer) a[0], (String) a[1]);
                    return null;
                }
                if ("executeQuery".equals(m.getName()) && (a == null || a.length == 0))
                {
                    if (failOnQuery != null)
                    {
                        throw failOnQuery;
                    }
                    String key = params.get(1) + "." + params.get(2);
                    asked.add(key);
                    openResources++;
                    return resultSet(present.contains(key) ? 1 : 0);
                }
                if ("close".equals(m.getName()))
                {
                    openResources--;
                    return null;
                }
                throw new UnsupportedOperationException("PreparedStatement." + m.getName());
            });
        }

        private ResultSet resultSet(final int count)
        {
            final boolean[] consumed = { false };
            return proxy(ResultSet.class, (p, m, a) -> {
                if ("next".equals(m.getName()))
                {
                    boolean first = !consumed[0];
                    consumed[0] = true;
                    return first;
                }
                if ("getInt".equals(m.getName()) && consumed[0] && Integer.valueOf(1).equals(a[0]))
                {
                    return count;
                }
                if ("close".equals(m.getName()))
                {
                    openResources--;
                    return null;
                }
                throw new UnsupportedOperationException("ResultSet." + m.getName());
            });
        }
    }

    /** 在 SchemaAssertion 的 logger 上临时挂一个 ListAppender，跑完摘掉，返回期间记下的日志 */
    private static List<ILoggingEvent> logsOf(Executable body) throws Throwable
    {
        Logger logger = (Logger) LoggerFactory.getLogger(SchemaAssertion.class);
        Level old = logger.getLevel();
        ListAppender<ILoggingEvent> app = new ListAppender<ILoggingEvent>();
        app.start();
        logger.setLevel(Level.INFO);
        logger.addAppender(app);
        try
        {
            body.execute();
        }
        finally
        {
            logger.detachAppender(app);
            logger.setLevel(old);
            app.stop();
        }
        return app.list;
    }

    private static boolean logged(List<ILoggingEvent> logs, Level level, String text)
    {
        for (ILoggingEvent ev : logs)
        {
            if (ev.getLevel() == level && ev.getFormattedMessage().contains(text))
            {
                return true;
            }
        }
        return false;
    }

    private static final String[] MISSING_A4 = { "kg_a.uk_a1", "kg_a.uk_a2", "kg_a.uk_a3", "kg_b.uk_b1",
            "kg_c.uk_c1" };

    @Test
    @DisplayName("check()：库里缺一条索引 → 抛 IllegalStateException 拒绝启动（不能被连库那层的 catch 吞成一条 WARN）")
    void checkRefusesStartupWhenIndexMissing()
    {
        FakeDb db = new FakeDb(MISSING_A4);
        SchemaAssertion sa = new SchemaAssertion(db.dataSource(), twoContributors(), true);
        IllegalStateException e = assertThrows(IllegalStateException.class, sa::check);
        assertTrue(e.getMessage().contains("kg_a.uk_a4") && e.getMessage().contains("V16__b.sql"), e.getMessage());
        assertEquals(new HashSet<String>(Arrays.asList(ALL)), new HashSet<String>(db.asked));
        assertEquals(0, db.openResources, "连接/语句/结果集都要关");
    }

    @Test
    @DisplayName("check()：索引齐全 → 放行；每条只查一次、只认当前库、连接语句结果集都关，并留下通过日志")
    void checkPassesWhenAllIndexesPresent() throws Throwable
    {
        FakeDb db = new FakeDb(ALL);
        SchemaAssertion sa = new SchemaAssertion(db.dataSource(), twoContributors(), true);
        List<ILoggingEvent> logs = logsOf(() -> assertDoesNotThrow(sa::check));
        assertEquals(1, db.connections);
        assertEquals(ALL.length, db.asked.size(), "每条只查一次：" + db.asked);
        assertEquals(new HashSet<String>(Arrays.asList(ALL)), new HashSet<String>(db.asked));
        assertEquals(ALL.length, db.sqls.size());
        for (String sql : db.sqls)
        {
            // 不限定 table_schema 的话，别的库（例如 C17 的隔离库）里的同名索引也会被当成"在"
            assertTrue(sql.contains("information_schema.statistics") && sql.contains("table_schema = database()"),
                    "只能认当前库里的索引：" + sql);
        }
        assertEquals(0, db.openResources, "连接/语句/结果集都要关");
        assertTrue(logged(logs, Level.INFO, "唯一索引启动断言通过：2 个登记方、6 条索引"), String.valueOf(logs));
    }

    @Test
    @DisplayName("check()：取不到数据库连接 → 拒绝启动并保留原因，不能记成\"通过\"")
    void checkRefusesStartupWhenDatabaseUnreachable() throws Throwable
    {
        FakeDb db = new FakeDb(ALL);
        db.failOnConnect = new SQLException("Communications link failure");
        SchemaAssertion sa = new SchemaAssertion(db.dataSource(), twoContributors(), true);
        List<ILoggingEvent> logs = logsOf(() -> {
            IllegalStateException failure = assertThrows(IllegalStateException.class, sa::check);
            assertEquals(db.failOnConnect, failure.getCause());
        });
        assertFalse(logged(logs, Level.INFO, "唯一索引启动断言通过"), "没查过不能记成通过：" + logs);
        assertTrue(db.asked.isEmpty(), String.valueOf(db.asked));
    }

    @Test
    @DisplayName("check()：查完之后归还连接失败 → 结果已在手，缺索引照样拒绝启动")
    void checkRefusesStartupEvenIfClosingConnectionFails()
    {
        FakeDb db = new FakeDb(MISSING_A4);
        db.failOnClose = new SQLException("connection reset while returning to pool");
        SchemaAssertion sa = new SchemaAssertion(db.dataSource(), twoContributors(), true);
        IllegalStateException e = assertThrows(IllegalStateException.class, sa::check);
        assertTrue(e.getMessage().contains("kg_a.uk_a4"), e.getMessage());
    }

    @Test
    @DisplayName("check()：查询时出的不是 SQLException（代码缺陷之类）→ 原样抛出拒绝启动，不当成\"库没连上\"放过")
    void checkDoesNotSwallowNonDatabaseErrors()
    {
        FakeDb db = new FakeDb(ALL);
        db.failOnQuery = new IllegalArgumentException("probe bug");
        SchemaAssertion sa = new SchemaAssertion(db.dataSource(), twoContributors(), true);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, sa::check);
        assertEquals("probe bug", e.getMessage());
    }

    @Test
    @DisplayName("check()：一个登记方都没有 → 拒绝启动（真实启动路径上也失败关闭）")
    void checkRefusesStartupWithoutContributors()
    {
        FakeDb db = new FakeDb(ALL);
        SchemaAssertion sa = new SchemaAssertion(db.dataSource(), Collections.<KgSchemaContributor>emptyList(), true);
        IllegalStateException e = assertThrows(IllegalStateException.class, sa::check);
        assertTrue(e.getMessage().contains("CoreSchema"), e.getMessage());
    }

    @Test
    @DisplayName("check()：显式关闭 → 不取连接、不拒绝启动，但打醒目 WARN")
    void checkDisabledDoesNotTouchDatabase() throws Throwable
    {
        FakeDb db = new FakeDb();
        SchemaAssertion sa = new SchemaAssertion(db.dataSource(), twoContributors(), false);
        List<ILoggingEvent> logs = logsOf(() -> assertDoesNotThrow(sa::check));
        assertEquals(0, db.connections, "关闭时不该取连接");
        assertTrue(logged(logs, Level.WARN, "已关闭唯一索引启动断言"), String.valueOf(logs));
    }
}
