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

/** V48：kg_job 缺岗位筛选条件三列时拒绝启动；关掉断言的开发库不查库；迁移与回退脚本按约定写（不连库，实跑见发布说明） */
class JobFilterFieldsSchemaTest
{
    private static KgJdbc db(long columns)
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.count(contains("table_name = 'kg_job'"), anyMap())).thenReturn(columns);
        return db;
    }

    @Test
    void startsWhenAllThreeColumnsAreMigrated()
    {
        KgJdbc db = db(3L);
        assertDoesNotThrow(() -> new JobFilterFieldsSchema(db, true).check());
        verify(db).count(argThat((String s) -> s.contains("is_nullable = 'NO' and column_default = ''")
                && s.contains("column_name = 'gender_pref' and data_type = 'char' and character_maximum_length = 1")
                && s.contains("column_name = 'rest_mode' and data_type = 'varchar' and character_maximum_length >= 8")
                && s.contains("column_name = 'room_type' and data_type = 'varchar' and character_maximum_length >= 16")), anyMap());
    }

    @Test
    void missingOrWrongColumnRefusesStartupAndNamesTheMigration()
    {
        for (long n : new long[] { 0L, 1L, 2L })
        {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> new JobFilterFieldsSchema(db(n), true).check());
            assertTrue(e.getMessage().contains("kg_job.gender_pref") && e.getMessage().contains("V48__job_filter_fields.sql"), e.getMessage());
        }
    }

    @Test
    void disabledAssertionSkipsDatabase()
    {
        KgJdbc db = mock(KgJdbc.class);
        assertDoesNotThrow(() -> new JobFilterFieldsSchema(db, false).check());
        verifyNoInteractions(db);
    }

    @Test
    void registersTheMigrationWithoutNewIndexes()
    {
        JobFilterFieldsSchema s = new JobFilterFieldsSchema(mock(KgJdbc.class), true);
        assertTrue(s.requiredIndexes().isEmpty());
        assertEquals("sql/kangaroo/V48__job_filter_fields.sql", s.migration());
    }

    /** 模块目录是 ruoyi-kangaroo（surefire 的 basedir）：迁移脚本在 ../sql/kangaroo */
    private static String sql(String name) throws Exception
    {
        Path p = Paths.get(System.getProperty("basedir", "."), "..", "sql", "kangaroo", name);
        assertTrue(Files.exists(p), p.toAbsolutePath().toString());
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    void migrationAddsThreeNotNullColumnsAfterV47AndBackfillsOnce() throws Exception
    {
        String m = sql("V48__job_filter_fields.sql");
        assertFalse(m.contains("\r\n"), "迁移脚本统一 LF");
        assertTrue(m.contains("call kg_assert_env('47');"));
        assertTrue(m.contains("call kg_add_col_if_absent('kg_job', 'gender_pref', 'gender_pref char(1) not null default '''' comment "));
        assertTrue(m.contains("call kg_add_col_if_absent('kg_job', 'rest_mode', 'rest_mode varchar(8) not null default '''' comment "));
        assertTrue(m.contains("call kg_add_col_if_absent('kg_job', 'room_type', 'room_type varchar(16) not null default '''' comment "));
        // 老岗位按文字自动填：只在第一次执行（账本里还没有 V48）时；排班只填线下岗位，房型只填包住的岗位，只写了一种才填
        assertTrue(m.contains("where not exists (select 1 from kg_schema_version v where v.version = 48)"));
        assertTrue(m.contains("and t.job_type <> 'online'"));
        assertTrue(m.contains("and (t.job_type = 'volunteer' or t.board_type in ('all', 'room'))"));
        assertTrue(m.contains("then '5-2'") && m.contains("then 'single'"));
        assertTrue(m.contains("'%做6休1%') = 1;") && m.contains("'%八人间%') = 1;"), "写了两种的不猜");
        // 性别不自动填；不删任何数据
        assertFalse(m.contains("set j.gender_pref"));
        assertFalse(m.toLowerCase().contains("delete from kg_job"));
        assertTrue(m.contains("drop temporary table kg_v48_text;"));
        assertTrue(m.contains("values (48, 'job_filter_fields', now(), '"));
    }

    @Test
    void rollbackIsGuardedAndDropsOnlyTheThreeColumns() throws Exception
    {
        String r = sql("V48__rollback.sql");
        assertFalse(r.contains("\r\n"));
        assertTrue(r.contains("@kg_allow_job_filter_fields_rollback"), "回退要显式授权");
        assertTrue(r.contains("@kg_allow_job_filter_fields_discard"), "还有岗位填着这几项时要再确认一次");
        assertTrue(r.contains("V48 rollback blocked by later migrations"));
        for (String c : new String[] { "gender_pref", "rest_mode", "room_type" })
        {
            assertTrue(r.contains("call kg_drop_col_if_present('kg_job', '" + c + "');"), c);
            assertTrue(r.contains("select 1 from kg_job where " + c + " <> ''"), c);
        }
        assertFalse(r.contains("'kg_job', 'min_days'"), "最短在店天数是老列，不动");
        assertFalse(r.toLowerCase().contains("update kg_job") || r.toLowerCase().contains("delete from kg_job"), "不改岗位数据");
        assertTrue(r.contains("delete from kg_schema_version where version = 48;"));
        assertTrue(r.indexOf("create procedure kg_drop_col_if_present") < r.indexOf("call kg_rollback_job_filter_fields();"));
        assertTrue(r.lastIndexOf("drop procedure if exists kg_drop_col_if_present;") > r.indexOf("call kg_rollback_job_filter_fields();"));
    }
}
