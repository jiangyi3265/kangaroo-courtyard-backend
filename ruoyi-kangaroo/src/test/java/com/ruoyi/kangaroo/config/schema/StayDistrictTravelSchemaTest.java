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

/** V51：缺所在区县 / 主理人 / 出行参考这八列时拒绝启动；关掉断言的开发库不查库；迁移与回退脚本按约定写（不连库，实跑见 acceptance/20261017-r2/local_migrate_v51.py） */
class StayDistrictTravelSchemaTest
{
    private static KgJdbc db(long columns)
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.count(contains("information_schema.columns"), anyMap())).thenReturn(columns);
        return db;
    }

    @Test
    void startsWhenAllEightColumnsAreMigrated()
    {
        KgJdbc db = db(8L);
        assertDoesNotThrow(() -> new StayDistrictTravelSchema(db, true).check());
        verify(db).count(argThat((String s) -> s.contains("table_name = 'kg_stay' and data_type = 'varchar' and is_nullable = 'NO' and column_default = ''")
                && s.contains("(column_name = 'district' and character_maximum_length >= 32)")
                && s.contains("(column_name = 'host_age' and character_maximum_length >= 16)")
                && s.contains("(column_name = 'host_hometown' and character_maximum_length >= 32)")
                && s.contains("(column_name = 'host_skills' and character_maximum_length >= 128)")
                && s.contains("(column_name = 'travel_pros' and character_maximum_length >= 255)")
                && s.contains("(column_name = 'travel_cons' and character_maximum_length >= 255)")
                && s.contains("(column_name = 'travel_airport' and character_maximum_length >= 255)")
                && s.contains("(column_name = 'travel_rail' and character_maximum_length >= 255)")), anyMap());
    }

    @Test
    void missingOrWrongColumnRefusesStartupAndNamesTheMigration()
    {
        for (long n : new long[] { 0L, 1L, 7L })
        {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> new StayDistrictTravelSchema(db(n), true).check());
            assertTrue(e.getMessage().contains("kg_stay.district") && e.getMessage().contains("travel_")
                    && e.getMessage().contains("V51__stay_district_travel.sql"), e.getMessage());
        }
    }

    @Test
    void disabledAssertionSkipsDatabase()
    {
        KgJdbc db = mock(KgJdbc.class);
        assertDoesNotThrow(() -> new StayDistrictTravelSchema(db, false).check());
        verifyNoInteractions(db);
    }

    @Test
    void registersTheMigrationWithoutNewUniqueIndexes()
    {
        StayDistrictTravelSchema s = new StayDistrictTravelSchema(mock(KgJdbc.class), true);
        assertTrue(s.requiredIndexes().isEmpty());
        assertEquals("sql/kangaroo/V51__stay_district_travel.sql", s.migration());
        assertNotNull(StayDistrictTravelSchema.class.getAnnotation(org.springframework.stereotype.Component.class), "和其他核对一样登记成组件，启动时自动跑");
    }

    /** 模块目录是 ruoyi-kangaroo（surefire 的 basedir）：迁移脚本在 ../sql/kangaroo */
    private static String sql(String name) throws Exception
    {
        Path p = Paths.get(System.getProperty("basedir", "."), "..", "sql", "kangaroo", name);
        assertTrue(Files.exists(p), p.toAbsolutePath().toString());
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    void migrationAddsTheColumnsAndFacilitiesAfterV50AndTouchesNoData() throws Exception
    {
        String m = sql("V51__stay_district_travel.sql");
        assertFalse(m.contains("\r\n"), "迁移脚本统一 LF");
        assertTrue(m.contains("call kg_assert_env('50');"));
        assertTrue(m.contains("call kg_add_col_if_absent('kg_stay', 'district', 'district varchar(32) not null default '''' comment "));
        for (String c : new String[] { "host_age varchar(16)", "host_hometown varchar(32)", "host_skills varchar(128)", "travel_pros varchar(255)",
                "travel_cons varchar(255)", "travel_airport varchar(255)", "travel_rail varchar(255)" })
        {
            assertTrue(m.contains("'" + c + " not null default '''' comment "), c);
        }
        assertTrue(m.contains("call kg_add_idx_if_absent('kg_stay', 'idx_stay_district', 'key idx_stay_district (city, district)');"));
        // 字典项按 dict_type + dict_value 判重，重复执行不会插两条
        assertTrue(m.contains("select 8, '学习室', 'study', 'kg_stay_facility'"));
        assertTrue(m.contains("from dual where not exists (select 1 from sys_dict_data where dict_type = 'kg_stay_facility' and dict_value = 'study');"));
        assertTrue(m.contains("select 9, '健身房', 'gym', 'kg_stay_facility'"));
        assertTrue(m.contains("from dual where not exists (select 1 from sys_dict_data where dict_type = 'kg_stay_facility' and dict_value = 'gym');"));
        String lower = m.toLowerCase();
        assertFalse(lower.contains("update kg_stay") || lower.contains("delete from kg_stay") || lower.contains("delete from sys_dict_data"), "只加不改");
        assertFalse(m.contains("AI"), "出行参考是员工手填的，不叫 AI");
        assertTrue(m.contains("values (51, 'stay_district_travel', now(), '"));
    }

    @Test
    void rollbackIsGuardedAndDropsOnlyWhatV51Added() throws Exception
    {
        String r = sql("V51__rollback.sql");
        assertFalse(r.contains("\r\n"));
        assertTrue(r.contains("@kg_allow_stay_district_travel_rollback"), "回退要显式授权");
        assertTrue(r.contains("@kg_allow_stay_district_travel_discard"), "还有填过的区县 / 主理人 / 出行参考时要再确认一次");
        assertTrue(r.contains("V51 rollback blocked by later migrations"));
        for (String c : new String[] { "district", "host_age", "host_hometown", "host_skills", "travel_pros", "travel_cons", "travel_airport", "travel_rail" })
        {
            assertTrue(r.contains("select 1 from kg_stay where " + c + " <> ''"), c);
            assertTrue(r.contains("call kg_drop_col_if_present('kg_stay', '" + c + "');"), c);
        }
        assertTrue(r.contains("call kg_drop_idx_if_present('kg_stay', 'idx_stay_district');"));
        assertTrue(r.indexOf("kg_drop_idx_if_present('kg_stay', 'idx_stay_district')") < r.indexOf("kg_drop_col_if_present('kg_stay', 'district')"), "先删索引再删列");
        assertTrue(r.contains("delete from sys_dict_data where dict_type = 'kg_stay_facility' and dict_value in ('study', 'gym');"));
        assertFalse(r.contains("'kg_stay', 'area'") || r.contains("'kg_stay', 'host_name'"), "老列不动");
        assertFalse(r.toLowerCase().contains("update kg_stay"), "不改房源数据（设施里勾过的 study / gym 原样保留）");
        assertTrue(r.contains("delete from kg_schema_version where version = 51;"));
        assertTrue(r.indexOf("create procedure kg_drop_col_if_present") < r.indexOf("call kg_rollback_stay_district_travel();"));
        assertTrue(r.lastIndexOf("drop procedure if exists kg_drop_col_if_present;") > r.indexOf("call kg_rollback_stay_district_travel();"));
    }
}
