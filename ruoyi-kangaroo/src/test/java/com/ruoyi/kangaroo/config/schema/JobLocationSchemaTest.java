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

/** V42：kg_job 缺工作地点坐标列时拒绝启动；关掉断言的开发库不查库；迁移与回退脚本按约定写（不连库） */
class JobLocationSchemaTest
{
    private static KgJdbc db(long columns)
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.count(contains("table_name = 'kg_job'"), anyMap())).thenReturn(columns);
        return db;
    }

    @Test
    void startsWhenBothColumnsAreMigrated()
    {
        KgJdbc db = db(2L);
        assertDoesNotThrow(() -> new JobLocationSchema(db, true).check());
        verify(db).count(contains("column_name in ('latitude', 'longitude') and data_type = 'decimal' and numeric_scale >= 6 and is_nullable = 'YES'"), anyMap());
    }

    @Test
    void missingColumnsRefuseStartupAndNameTheMigration()
    {
        for (long found : new long[] { 0L, 1L })
        {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> new JobLocationSchema(db(found), true).check());
            assertTrue(e.getMessage().contains("kg_job.latitude") && e.getMessage().contains("V42__job_location.sql"), e.getMessage());
        }
    }

    @Test
    void disabledAssertionSkipsDatabase()
    {
        KgJdbc db = mock(KgJdbc.class);
        assertDoesNotThrow(() -> new JobLocationSchema(db, false).check());
        verifyNoInteractions(db);
    }

    @Test
    void registersTheMigrationWithoutNewIndexes()
    {
        JobLocationSchema s = new JobLocationSchema(mock(KgJdbc.class), true);
        assertTrue(s.requiredIndexes().isEmpty());
        assertEquals("sql/kangaroo/V42__job_location.sql", s.migration());
    }

    /** 模块目录是 ruoyi-kangaroo（surefire 的 basedir）：迁移脚本在 ../sql/kangaroo */
    private static String sql(String name) throws Exception
    {
        Path p = Paths.get(System.getProperty("basedir", "."), "..", "sql", "kangaroo", name);
        assertTrue(Files.exists(p), p.toAbsolutePath().toString());
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    void migrationAddsTwoNullableDecimalColumnsAfterAddressAndRequiresV41() throws Exception
    {
        String m = sql("V42__job_location.sql");
        assertFalse(m.contains("\r\n"), "迁移脚本统一 LF");
        assertTrue(m.contains("call kg_assert_env('41');"));
        assertTrue(m.contains("call kg_add_col_if_absent('kg_job', 'latitude', 'latitude decimal(9,6) null comment ''工作地点纬度（GCJ-02，空=未选点）'' after address');"));
        assertTrue(m.contains("call kg_add_col_if_absent('kg_job', 'longitude', 'longitude decimal(9,6) null comment ''工作地点经度（GCJ-02，空=未选点）'' after latitude');"));
        // 不回填、不改已有数据
        assertFalse(m.toLowerCase().matches("(?s).*\\bupdate kg_job\\b.*"));
        assertTrue(m.contains("values (42, 'job_location', now(), '"));
    }

    @Test
    void rollbackIsGuardedAndDropsOnlyTheTwoColumns() throws Exception
    {
        String r = sql("V42__rollback.sql");
        assertFalse(r.contains("\r\n"));
        assertTrue(r.contains("@kg_allow_job_location_rollback"), "回退要显式授权");
        assertTrue(r.contains("V42 rollback blocked by later migrations"));
        assertTrue(r.contains("select 1 from kg_job where latitude is not null"), "还有岗位存着坐标就拒绝回退");
        assertTrue(r.contains("call kg_drop_col_if_present('kg_job', 'latitude');"));
        assertTrue(r.contains("call kg_drop_col_if_present('kg_job', 'longitude');"));
        assertTrue(r.contains("delete from kg_schema_version where version = 42;"));
        // 用到的 kg_drop_col_if_present 来自头部标准片段，尾部片段再把它删掉
        assertTrue(r.indexOf("create procedure kg_drop_col_if_present") < r.indexOf("call kg_rollback_job_location();"));
        assertTrue(r.lastIndexOf("drop procedure if exists kg_drop_col_if_present;") > r.indexOf("call kg_rollback_job_location();"));
    }
}
