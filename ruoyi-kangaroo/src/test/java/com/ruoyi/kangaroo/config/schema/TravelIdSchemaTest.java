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

/** V69（2026-11-03 出行证件）：账本或表不全时拒绝启动；迁移与回退脚本按约定写（不连库） */
class TravelIdSchemaTest
{
    private static KgJdbc db(long ledger, long columns)
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.count(contains("from kg_schema_version where version = 69 and name = 'travel_id'"), anyMap())).thenReturn(ledger);
        when(db.count(contains("table_name = 'kg_user_travel_id'"), anyMap())).thenReturn(columns);
        return db;
    }

    @Test
    void startsWhenLedgerAndColumnsArePresent()
    {
        assertDoesNotThrow(() -> new TravelIdSchema(db(1L, 7L), true).check());
    }

    @Test
    void missingLedgerOrColumnsRefusesStartup()
    {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new TravelIdSchema(db(0L, 7L), true).check());
        assertTrue(e.getMessage().contains("V69__travel_id.sql"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> new TravelIdSchema(db(1L, 6L), true).check());
    }

    @Test
    void disabledAssertionSkipsDatabase()
    {
        KgJdbc db = mock(KgJdbc.class);
        assertDoesNotThrow(() -> new TravelIdSchema(db, false).check());
        verifyNoInteractions(db);
    }

    private static String sql(String name) throws Exception
    {
        Path p = Paths.get(System.getProperty("basedir", "."), "..", "sql", "kangaroo", name);
        assertTrue(Files.exists(p), p.toAbsolutePath().toString());
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    void migrationCreatesTheTableIdempotentlyWithAValidChecksum() throws Exception
    {
        String m = sql("V69__travel_id.sql");
        assertFalse(m.contains("\r\n"), "迁移脚本统一 LF");
        assertTrue(m.contains("call kg_assert_env('14');"));
        assertTrue(m.contains("create table if not exists kg_user_travel_id ("));
        assertTrue(m.contains("call kg_assert_same_collation('kg_user_travel_id');"));
        assertFalse(m.toLowerCase().contains("alter table kg_app_user"), "不改 kg_app_user");
        int ledger = m.indexOf("-- @kg-ledger");
        assertTrue(ledger > 0);
        byte[] h = MessageDigest.getInstance("SHA-256").digest(m.substring(0, ledger).getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : h)
        {
            sb.append(String.format("%02x", b));
        }
        assertTrue(m.substring(ledger).contains("values (69, 'travel_id', now(), '" + sb + "')"), "账本 checksum 和正文对得上");
    }

    @Test
    void rollbackRefusesWhenUsersHaveSubmittedIds() throws Exception
    {
        String r = sql("V69__rollback.sql");
        assertFalse(r.contains("\r\n"));
        assertTrue(r.contains("coalesce(@kg_allow_travel_id_rollback, 0) <> 1"));
        assertTrue(r.contains("if exists (select 1 from kg_user_travel_id) then"));
        assertTrue(r.contains("delete from kg_schema_version where version = 69;"));
        assertFalse(r.contains("kg_schema_version (version"), "回退脚本不写账本");
    }
}
