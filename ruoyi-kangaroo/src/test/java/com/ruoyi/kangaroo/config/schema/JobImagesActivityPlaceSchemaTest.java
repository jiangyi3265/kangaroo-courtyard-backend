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

/** V49：缺岗位照片列、活动集合地点坐标列时拒绝启动；关掉断言的开发库不查库；迁移与回退脚本按约定写（不连库，实跑见 scratchpad v49_scratch.py） */
class JobImagesActivityPlaceSchemaTest
{
    private static KgJdbc db(long columns)
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.count(contains("information_schema.columns"), anyMap())).thenReturn(columns);
        return db;
    }

    @Test
    void startsWhenAllThreeColumnsAreMigrated()
    {
        KgJdbc db = db(3L);
        assertDoesNotThrow(() -> new JobImagesActivityPlaceSchema(db, true).check());
        verify(db).count(argThat((String s) -> s.contains("table_name = 'kg_job' and column_name = 'images' and data_type = 'varchar' and character_maximum_length >= 2000")
                && s.contains("is_nullable = 'NO' and column_default = ''")
                && s.contains("table_name = 'kg_activity' and column_name in ('meet_lat', 'meet_lng') and data_type = 'decimal'")
                && s.contains("numeric_precision = 9 and numeric_scale = 6 and is_nullable = 'YES'")), anyMap());
    }

    @Test
    void missingOrWrongColumnRefusesStartupAndNamesTheMigration()
    {
        for (long n : new long[] { 0L, 1L, 2L })
        {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> new JobImagesActivityPlaceSchema(db(n), true).check());
            assertTrue(e.getMessage().contains("kg_job.images") && e.getMessage().contains("meet_lat")
                    && e.getMessage().contains("V49__job_images_activity_place.sql"), e.getMessage());
        }
    }

    @Test
    void disabledAssertionSkipsDatabase()
    {
        KgJdbc db = mock(KgJdbc.class);
        assertDoesNotThrow(() -> new JobImagesActivityPlaceSchema(db, false).check());
        verifyNoInteractions(db);
    }

    @Test
    void registersTheMigrationWithoutNewIndexes()
    {
        JobImagesActivityPlaceSchema s = new JobImagesActivityPlaceSchema(mock(KgJdbc.class), true);
        assertTrue(s.requiredIndexes().isEmpty());
        assertEquals("sql/kangaroo/V49__job_images_activity_place.sql", s.migration());
    }

    /** 模块目录是 ruoyi-kangaroo（surefire 的 basedir）：迁移脚本在 ../sql/kangaroo */
    private static String sql(String name) throws Exception
    {
        Path p = Paths.get(System.getProperty("basedir", "."), "..", "sql", "kangaroo", name);
        assertTrue(Files.exists(p), p.toAbsolutePath().toString());
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    void migrationAddsTheThreeColumnsAfterV48AndTouchesNoData() throws Exception
    {
        String m = sql("V49__job_images_activity_place.sql");
        assertFalse(m.contains("\r\n"), "迁移脚本统一 LF");
        assertTrue(m.contains("call kg_assert_env('48');"));
        assertTrue(m.contains("call kg_add_col_if_absent('kg_job', 'images', 'images varchar(2000) not null default '''' comment "));
        assertTrue(m.contains("call kg_add_col_if_absent('kg_activity', 'meet_lat', 'meet_lat decimal(9,6) null comment "));
        assertTrue(m.contains("call kg_add_col_if_absent('kg_activity', 'meet_lng', 'meet_lng decimal(9,6) null comment "));
        // 只加列：老岗位不回填照片，不改、不删任何数据
        String lower = m.toLowerCase();
        assertFalse(lower.contains("update kg_job") || lower.contains("update kg_activity"));
        assertFalse(lower.contains("delete from kg_job") || lower.contains("delete from kg_activity"));
        assertTrue(m.contains("values (49, 'job_images_activity_place', now(), '"));
    }

    @Test
    void rollbackIsGuardedAndDropsOnlyTheThreeColumns() throws Exception
    {
        String r = sql("V49__rollback.sql");
        assertFalse(r.contains("\r\n"));
        assertTrue(r.contains("@kg_allow_job_images_activity_place_rollback"), "回退要显式授权");
        assertTrue(r.contains("@kg_allow_job_images_activity_place_discard"), "还有照片 / 坐标时要再确认一次");
        assertTrue(r.contains("V49 rollback blocked by later migrations"));
        assertTrue(r.contains("select 1 from kg_job where images <> ''"));
        assertTrue(r.contains("select 1 from kg_activity where meet_lat is not null or meet_lng is not null"));
        assertTrue(r.contains("call kg_drop_col_if_present('kg_job', 'images');"));
        assertTrue(r.contains("call kg_drop_col_if_present('kg_activity', 'meet_lat');"));
        assertTrue(r.contains("call kg_drop_col_if_present('kg_activity', 'meet_lng');"));
        assertFalse(r.contains("'kg_job', 'cover'"), "封面是老列，不动");
        String lower = r.toLowerCase();
        assertFalse(lower.contains("update kg_job") || lower.contains("delete from kg_job") || lower.contains("delete from kg_activity"), "不改数据");
        assertTrue(r.contains("delete from kg_schema_version where version = 49;"));
        assertTrue(r.indexOf("create procedure kg_drop_col_if_present") < r.indexOf("call kg_rollback_job_images_activity_place();"));
        assertTrue(r.lastIndexOf("drop procedure if exists kg_drop_col_if_present;") > r.indexOf("call kg_rollback_job_images_activity_place();"));
    }
}
