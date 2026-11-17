package com.ruoyi.kangaroo.config.schema;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import com.ruoyi.kangaroo.ext.KgJdbc;

/** V50：成果链接列不够 1600 字（3 个链接）时拒绝启动；关掉断言的开发库不查库；迁移与回退脚本按约定写（不连库，实跑见发布说明） */
class OnlineLinksSchemaTest
{
    private static KgJdbc db(long columns)
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.count(contains("table_name = 'kg_online_submission'"), anyMap())).thenReturn(columns);
        return db;
    }

    @Test
    void startsWhenColumnIsWidened()
    {
        KgJdbc db = db(1L);
        assertDoesNotThrow(() -> new OnlineLinksSchema(db, true).check());
        verify(db).count(contains("column_name = 'link' and data_type = 'varchar' and character_maximum_length >= 1600 and is_nullable = 'NO'"), anyMap());
    }

    @Test
    void columnStillAtFiveHundredRefusesStartupAndNamesTheMigration()
    {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new OnlineLinksSchema(db(0L), true).check());
        assertTrue(e.getMessage().contains("kg_online_submission.link") && e.getMessage().contains("V50__online_links.sql"), e.getMessage());
    }

    @Test
    void disabledAssertionSkipsDatabase()
    {
        KgJdbc db = mock(KgJdbc.class);
        assertDoesNotThrow(() -> new OnlineLinksSchema(db, false).check());
        verifyNoInteractions(db);
    }

    @Test
    void registersTheMigrationWithoutNewIndexes()
    {
        OnlineLinksSchema s = new OnlineLinksSchema(mock(KgJdbc.class), true);
        assertTrue(s.requiredIndexes().isEmpty());
        assertEquals("sql/kangaroo/V50__online_links.sql", s.migration());
        assertNotNull(OnlineLinksSchema.class.getAnnotation(org.springframework.stereotype.Component.class), "和其他核对一样登记成组件，启动时自动跑");
    }

    /** V47 的核对（≥ 500）放宽到 1600 之后照样通过：两条核对同时在，不会互相打架 */
    @Test
    void widerColumnStillSatisfiesTheV47Check()
    {
        KgJdbc db = db(1L);
        assertDoesNotThrow(() -> new OnlineLinkTextSchema(db, true).check());
        assertDoesNotThrow(() -> new OnlineLinksSchema(db, true).check());
    }

    /** 模块目录是 ruoyi-kangaroo（surefire 的 basedir）：迁移脚本在 ../sql/kangaroo */
    private static String sql(String name) throws Exception
    {
        Path p = Paths.get(System.getProperty("basedir", "."), "..", "sql", "kangaroo", name);
        assertTrue(Files.exists(p), p.toAbsolutePath().toString());
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    void migrationOnlyWidensTheColumnAfterV49() throws Exception
    {
        String m = sql("V50__online_links.sql");
        assertFalse(m.contains("\r\n"), "迁移脚本统一 LF");
        assertTrue(m.contains("call kg_assert_env('49');"));
        assertTrue(m.contains("alter table kg_online_submission modify column link varchar(1600) not null default ''"));
        // 只放宽不收窄：已经 ≥ 1600 的不动
        assertTrue(m.contains("and data_type = 'varchar' and character_maximum_length < 1600) then"));
        assertFalse(m.toLowerCase().contains("update kg_online_submission"), "不改任何已有数据");
        assertTrue(m.contains("values (50, 'online_links', now(), '"));
    }

    @Test
    void rollbackIsGuardedAndRefusesToTruncate() throws Exception
    {
        String r = sql("V50__rollback.sql");
        assertFalse(r.contains("\r\n"));
        assertTrue(r.contains("@kg_allow_online_links_rollback"), "回退要显式授权");
        assertTrue(r.contains("V50 rollback blocked by later migrations"));
        assertTrue(r.contains("if exists (select 1 from kg_online_submission where char_length(link) > 500) then"), "有超过 500 字的链接时拒绝回退");
        assertTrue(r.indexOf("char_length(link) > 500") < r.indexOf("modify column link varchar(500)"));
        assertTrue(r.contains("delete from kg_schema_version where version = 50;"));
        assertTrue(r.lastIndexOf("drop procedure if exists kg_assert_env;") > r.indexOf("call kg_rollback_online_links();"));
    }
}
