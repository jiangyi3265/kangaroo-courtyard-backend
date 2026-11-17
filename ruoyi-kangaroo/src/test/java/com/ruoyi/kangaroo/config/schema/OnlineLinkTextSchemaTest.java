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

/** V47：成果链接列不够 500 字时拒绝启动；关掉断言的开发库不查库；迁移与回退脚本按约定写（不连库，实跑见发布说明） */
class OnlineLinkTextSchemaTest
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
        assertDoesNotThrow(() -> new OnlineLinkTextSchema(db, true).check());
        verify(db).count(contains("column_name = 'link' and data_type = 'varchar' and character_maximum_length >= 500 and is_nullable = 'NO'"), anyMap());
    }

    @Test
    void narrowColumnRefusesStartupAndNamesTheMigration()
    {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new OnlineLinkTextSchema(db(0L), true).check());
        assertTrue(e.getMessage().contains("kg_online_submission.link") && e.getMessage().contains("V47__online_link_text.sql"), e.getMessage());
    }

    @Test
    void disabledAssertionSkipsDatabase()
    {
        KgJdbc db = mock(KgJdbc.class);
        assertDoesNotThrow(() -> new OnlineLinkTextSchema(db, false).check());
        verifyNoInteractions(db);
    }

    @Test
    void registersTheMigrationWithoutNewIndexes()
    {
        OnlineLinkTextSchema s = new OnlineLinkTextSchema(mock(KgJdbc.class), true);
        assertTrue(s.requiredIndexes().isEmpty());
        assertEquals("sql/kangaroo/V47__online_link_text.sql", s.migration());
    }

    /** 模块目录是 ruoyi-kangaroo（surefire 的 basedir）：迁移脚本在 ../sql/kangaroo */
    private static String sql(String name) throws Exception
    {
        Path p = Paths.get(System.getProperty("basedir", "."), "..", "sql", "kangaroo", name);
        assertTrue(Files.exists(p), p.toAbsolutePath().toString());
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    void migrationOnlyWidensTheColumnAfterV46() throws Exception
    {
        String m = sql("V47__online_link_text.sql");
        assertFalse(m.contains("\r\n"), "迁移脚本统一 LF");
        assertTrue(m.contains("call kg_assert_env('46');"));
        assertTrue(m.contains("alter table kg_online_submission modify column link varchar(500) not null default ''"));
        // 只放宽不收窄：已经 ≥ 500 的不动
        assertTrue(m.contains("and data_type = 'varchar' and character_maximum_length < 500) then"));
        assertFalse(m.toLowerCase().contains("update kg_online_submission"), "不改任何已有数据");
        assertTrue(m.contains("values (47, 'online_link_text', now(), '"));
    }

    @Test
    void rollbackIsGuardedAndRefusesToTruncate() throws Exception
    {
        String r = sql("V47__rollback.sql");
        assertFalse(r.contains("\r\n"));
        assertTrue(r.contains("@kg_allow_online_link_text_rollback"), "回退要显式授权");
        assertTrue(r.contains("V47 rollback blocked by later migrations"));
        assertTrue(r.contains("if exists (select 1 from kg_online_submission where char_length(link) > 255) then"), "有超过 255 字的链接时拒绝回退");
        assertTrue(r.indexOf("char_length(link) > 255") < r.indexOf("modify column link varchar(255)"));
        assertTrue(r.contains("delete from kg_schema_version where version = 47;"));
        assertTrue(r.lastIndexOf("drop procedure if exists kg_assert_env;") > r.indexOf("call kg_rollback_online_link_text();"));
    }
}
