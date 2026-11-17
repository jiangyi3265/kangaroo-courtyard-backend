package com.ruoyi.kangaroo.config.schema;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import com.ruoyi.kangaroo.ext.KgJdbc;

class FormsSchemaTest
{
    private static KgJdbc db(long ledger, long columns)
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.count(eq(FormsSchema.LEDGER_SQL), anyMap())).thenReturn(ledger);
        when(db.count(eq(FormsSchema.COLUMNS_SQL), anyMap())).thenReturn(columns);
        return db;
    }

    @Test
    void refusesToStartWithoutTheMigration()
    {
        new FormsSchema(db(1, FormsSchema.COLUMNS), true).check();
        assertTrue(assertThrows(IllegalStateException.class, () -> new FormsSchema(db(0, FormsSchema.COLUMNS), true).check()).getMessage().contains("V66__forms.sql"));
        assertThrows(IllegalStateException.class, () -> new FormsSchema(db(1, 3), true).check());
    }

    @Test
    void devDatabaseSkips()
    {
        new FormsSchema(db(0, 0), false).check();
    }

    @Test
    void migrationGuards() throws Exception
    {
        String up = new String(Files.readAllBytes(Paths.get("../sql/kangaroo/V66__forms.sql")), StandardCharsets.UTF_8);
        String down = new String(Files.readAllBytes(Paths.get("../sql/kangaroo/V66__rollback.sql")), StandardCharsets.UTF_8);
        assertTrue(up.contains("call kg_assert_env('60,62,63,64,65')"), "不依赖 V61");
        for (String col : new String[] { "meal_allowance", "meal_allowance_unit", "pet_status", "pet_images", "lodging_images", "lodging_video", "lodging_video_cover" })
        {
            assertTrue(up.contains("call kg_add_col_if_absent('kg_job', '" + col + "', '" + col + " varchar("), col);
            assertTrue(FormsSchema.COLUMNS_SQL.contains("'" + col + "'"), col);
            assertTrue(down.contains("call kg_drop_col_if_present('kg_job', '" + col + "')"), col);
        }
        // 只加可空列：线上 4bd5 jar 不写这几列，插入不能因为它们报错；老行也不用回填（报销的新表另说，这里只看 kg_job）
        for (String line : up.split("\n"))
        {
            if (line.startsWith("call kg_add_col_if_absent('kg_job'"))
            {
                assertTrue(line.contains(" null default null"), line);
                assertFalse(line.contains("not null"), line);
            }
        }
        assertTrue(up.contains("update kg_merchant_request set title = left(replace(title, '云连锁', '抖音卖房'), 64)"));
        assertTrue(up.contains("where request_type = 'cloud_chain' and title like '%云连锁%'"), "只改这个类型的默认标题");
        assertFalse(up.contains("update kg_staff_op_log") || up.contains("set content = replace"), "操作日志、商家写的说明不动");
        assertTrue(up.contains("update sys_dict_data set remark = replace(remark, '云连锁', '抖音卖房')"));
        assertTrue(up.contains("update sys_menu set remark = replace(remark, '云连锁', '抖音卖房')"));
        assertTrue(up.contains("update kg_message set title = left(replace(title, '云连锁', '抖音卖房'), 128), content = left(replace(content, '云连锁', '抖音卖房'), 500)"));
        assertTrue(down.contains("@kg_allow_forms_rollback"));
        assertTrue(down.contains("later migrations"));
        assertTrue(down.contains("kg_job has meal, pet or lodging data"), "门店填的内容不能跟着回滚一起没了");
        assertTrue(down.contains("replace(title, '抖音卖房', '云连锁')"));
        assertEquals("sql/kangaroo/V66__forms.sql", new FormsSchema(mock(KgJdbc.class), true).migration());
    }
}
