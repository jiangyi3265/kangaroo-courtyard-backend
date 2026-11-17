package com.ruoyi.kangaroo.config.schema;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;

import org.junit.jupiter.api.Test;

import com.ruoyi.kangaroo.ext.KgJdbc;

/** V71（2026-11-03 活动费用三选一：免费 / 收费 / AA制）：账本或 aa_fen 列不在时拒绝启动；迁移与回退脚本按约定写（不连库） */
class ActivityFeeModeSchemaTest
{
    private static KgJdbc db(long ledger, long column)
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.count(contains("from kg_schema_version where version = 71 and name = 'activity_fee_mode'"), anyMap())).thenReturn(ledger);
        when(db.count(contains("column_name = 'aa_fen'"), anyMap())).thenReturn(column);
        return db;
    }

    @Test
    void startsWhenV71AndTheColumnArePresent()
    {
        assertDoesNotThrow(() -> new ActivityFeeModeSchema(db(1L, 1L), true).check());
    }

    @Test
    void missingLedgerOrColumnRefusesStartupAndNamesTheMigration()
    {
        for (long[] c : new long[][] { { 0L, 1L }, { 1L, 0L } })
        {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> new ActivityFeeModeSchema(db(c[0], c[1]), true).check());
            assertTrue(e.getMessage().contains("V71__activity_fee_mode.sql"), e.getMessage());
        }
    }

    @Test
    void disabledAssertionSkipsDatabase()
    {
        KgJdbc db = mock(KgJdbc.class);
        assertDoesNotThrow(() -> new ActivityFeeModeSchema(db, false).check());
        verifyNoInteractions(db);
    }

    @Test
    void registersTheMigrationWithoutIndexes()
    {
        ActivityFeeModeSchema s = new ActivityFeeModeSchema(mock(KgJdbc.class), true);
        assertTrue(s.requiredIndexes().isEmpty());
        assertEquals("sql/kangaroo/V71__activity_fee_mode.sql", s.migration());
        assertNotNull(ActivityFeeModeSchema.class.getAnnotation(org.springframework.stereotype.Component.class));
    }

    private static String sql(String name) throws Exception
    {
        Path p = Paths.get(System.getProperty("basedir", "."), "..", "sql", "kangaroo", name);
        assertTrue(Files.exists(p), p.toAbsolutePath().toString());
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    void migrationOnlyAddsTheNullableColumnAfterV67() throws Exception
    {
        String m = sql("V71__activity_fee_mode.sql");
        assertFalse(m.contains("\r\n"), "迁移脚本统一 LF");
        assertTrue(m.contains("call kg_assert_env('67');"), "不依赖 V68–V70（别的工作包）");
        assertTrue(m.contains("call kg_add_col_if_absent('kg_activity', 'aa_fen', 'aa_fen int null default null comment"), "可重复执行、可空");
        String lower = m.toLowerCase();
        assertFalse(lower.contains("update kg_") || lower.contains("delete from") || lower.contains("create table"), "不回填、不改已有数据");
        assertTrue(m.contains("values (71, 'activity_fee_mode', now(), '"));
        int at = m.indexOf("-- @kg-ledger");
        byte[] d = MessageDigest.getInstance("SHA-256").digest(m.substring(0, at).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : d)
        {
            hex.append(String.format("%02x", b));
        }
        assertTrue(m.substring(at).contains("'" + hex + "'"), "账本 checksum 要等于标记行之前内容的 sha256（用 make_migration.py 生成，别手改）");
    }

    @Test
    void rollbackIsGuardedAndRefusesWhileAaActivitiesExist() throws Exception
    {
        String r = sql("V71__rollback.sql");
        assertFalse(r.contains("\r\n"));
        assertTrue(r.contains("@kg_allow_activity_fee_mode_rollback"));
        assertTrue(r.contains("V71 rollback requires stopped application and explicit authorization"));
        assertTrue(r.contains("V71 rollback blocked by later migrations"));
        assertTrue(r.contains("V71 rollback blocked: kg_activity has AA activities"));
        assertTrue(r.indexOf("kg_activity has AA activities") < r.indexOf("call kg_drop_col_if_present('kg_activity', 'aa_fen');"), "先拦再删列");
        assertTrue(r.contains("delete from kg_schema_version where version = 71;"));
        assertTrue(r.lastIndexOf("drop procedure if exists kg_drop_col_if_present;") > r.indexOf("call kg_rollback_activity_fee_mode();"));
    }
}
