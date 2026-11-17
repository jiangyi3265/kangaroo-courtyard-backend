package com.ruoyi.kangaroo.config.schema;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ruoyi.kangaroo.ext.KgJdbc;

/** V54（2026-10-18 门店页）：kg_store 缺采访 / 店长 / 亮点 / 交通 / 执照 / 日常这些列时拒绝启动；迁移与回退脚本按约定写（不连库，实跑见 acceptance/20261018-store/local_migrate_v54.py） */
class StoreProfileSchemaTest
{
    private static KgJdbc db(long text, long seconds)
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.count(contains("column_default = '' and ("), anyMap())).thenReturn(text);
        when(db.count(contains("column_name = 'interview_seconds'"), anyMap())).thenReturn(seconds);
        return db;
    }

    @Test
    void startsWhenAllColumnsAreMigrated()
    {
        assertDoesNotThrow(() -> new StoreProfileSchema(db(15L, 1L), true).check());
        String sql = StoreProfileSchema.textSql();
        assertTrue(sql.contains("table_name = 'kg_store' and data_type = 'varchar' and is_nullable = 'NO' and column_default = ''"));
        for (String c : new String[] { "(column_name = 'interview_mode' and character_maximum_length >= 16)",
                "(column_name = 'interview_note' and character_maximum_length >= 100)", "(column_name = 'manager_quote' and character_maximum_length >= 200)",
                "(column_name = 'travel_rail' and character_maximum_length >= 500)", "(column_name = 'daily_images' and character_maximum_length >= 2000)" })
        {
            assertTrue(sql.contains(c), c);
        }
        assertEquals(15, StoreProfileSchema.TEXT_COLUMNS.size());
        assertTrue(StoreProfileSchema.SECONDS_SQL.contains("data_type = 'int' and is_nullable = 'YES'"));
    }

    @Test
    void missingColumnsRefuseStartupAndNameTheMigration()
    {
        for (long[] n : new long[][] { { 0, 1 }, { 14, 1 }, { 15, 0 } })
        {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> new StoreProfileSchema(db(n[0], n[1]), true).check());
            assertTrue(e.getMessage().contains("V54__store_profile.sql"), e.getMessage());
        }
    }

    @Test
    void disabledAssertionSkipsDatabase()
    {
        KgJdbc db = mock(KgJdbc.class);
        assertDoesNotThrow(() -> new StoreProfileSchema(db, false).check());
        verifyNoInteractions(db);
    }

    @Test
    void registersTheMigrationWithoutIndexes()
    {
        StoreProfileSchema s = new StoreProfileSchema(mock(KgJdbc.class), true);
        assertTrue(s.requiredIndexes().isEmpty());
        assertEquals("sql/kangaroo/V54__store_profile.sql", s.migration());
        assertNotNull(StoreProfileSchema.class.getAnnotation(org.springframework.stereotype.Component.class));
    }

    private static String sql(String name) throws Exception
    {
        Path p = Paths.get(System.getProperty("basedir", "."), "..", "sql", "kangaroo", name);
        assertTrue(Files.exists(p), p.toAbsolutePath().toString());
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    void migrationAddsTheColumnsAfterV53AndOnlyBackfillsInterviewMode() throws Exception
    {
        String m = sql("V54__store_profile.sql");
        assertFalse(m.contains("\r\n"), "迁移脚本统一 LF");
        assertTrue(m.contains("call kg_assert_env('53');"));
        for (Map.Entry<String, Integer> e : StoreProfileSchema.TEXT_COLUMNS.entrySet())
        {
            String c = "call kg_add_col_if_absent('kg_store', '" + e.getKey() + "', '" + e.getKey() + " varchar(" + e.getValue() + ") not null default '''' comment ";
            assertTrue(m.contains(c), c);
        }
        assertTrue(m.contains("call kg_add_col_if_absent('kg_store', 'interview_seconds', 'interview_seconds int default null comment "));
        assertTrue(m.contains("update kg_store set interview_mode = 'onsite' where safe_cert = '0' and interview_mode = '';"), "已认证 = 实地探访，可重复执行");
        assertEquals(1, m.toLowerCase().split("update kg_store", -1).length - 1, "只有这一条回填");
        assertFalse(m.contains("AI"), "交通参考是员工手填的，不叫 AI");
        assertTrue(m.contains("values (54, 'store_profile', now(), '"));
    }

    @Test
    void rollbackIsGuardedAndRefusesToDropStoredContent() throws Exception
    {
        String r = sql("V54__rollback.sql");
        assertFalse(r.contains("\r\n"));
        assertTrue(r.contains("@kg_allow_store_profile_rollback"));
        assertTrue(r.contains("V54 rollback blocked by later migrations"));
        assertTrue(r.contains("@kg_allow_store_profile_loss"));
        assertTrue(r.contains("from kg_store where interview_mode = ''online''"), "选过线上采访的回退会丢");
        for (String c : StoreProfileSchema.TEXT_COLUMNS.keySet())
        {
            assertTrue(r.contains("call kg_drop_col_if_present('kg_store', '" + c + "');"), c);
        }
        assertTrue(r.contains("call kg_drop_col_if_present('kg_store', 'interview_seconds');"));
        assertFalse(r.contains("'kg_store', 'safe_cert'") || r.toLowerCase().contains("update kg_store"), "认证不动");
        assertTrue(r.contains("delete from kg_schema_version where version = 54;"));
        assertTrue(r.indexOf("V54 rollback blocked: stores still have") < r.indexOf("call kg_drop_col_if_present('kg_store', 'daily_images');"));
        assertTrue(r.lastIndexOf("drop procedure if exists kg_drop_col_if_present;") > r.indexOf("call kg_rollback_store_profile();"));
    }
}
