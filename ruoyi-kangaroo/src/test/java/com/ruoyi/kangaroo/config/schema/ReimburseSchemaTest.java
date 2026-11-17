package com.ruoyi.kangaroo.config.schema;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import com.ruoyi.kangaroo.ext.KgJdbc;

class ReimburseSchemaTest
{
    private static KgJdbc db(long ledger, long columns)
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.count(eq(ReimburseSchema.LEDGER_SQL), anyMap())).thenReturn(ledger);
        when(db.count(eq(ReimburseSchema.COLUMNS_SQL), anyMap())).thenReturn(columns);
        return db;
    }

    @Test
    void refusesToStartWithoutTheMigration()
    {
        new ReimburseSchema(db(1, ReimburseSchema.COLUMNS), true).check();
        assertTrue(assertThrows(IllegalStateException.class, () -> new ReimburseSchema(db(0, ReimburseSchema.COLUMNS), true).check()).getMessage().contains("V66__forms.sql"));
        assertThrows(IllegalStateException.class, () -> new ReimburseSchema(db(1, 3), true).check());
    }

    @Test
    void devDatabaseSkips()
    {
        new ReimburseSchema(db(0, 0), false).check();
    }

    @Test
    void columnListIsTheCount()
    {
        assertEquals(ReimburseSchema.COLUMNS, ReimburseSchema.COLUMNS_SQL.split("column_name in \\(")[1].split(",").length);
    }

    @Test
    void migrationGuards() throws Exception
    {
        String up = new String(Files.readAllBytes(Paths.get("../sql/kangaroo/V66__forms.sql")), StandardCharsets.UTF_8);
        String down = new String(Files.readAllBytes(Paths.get("../sql/kangaroo/V66__rollback.sql")), StandardCharsets.UTF_8);
        assertTrue(up.contains("create table if not exists kg_reimbursement ("));
        assertTrue(up.contains("amount          decimal(10,2) not null"), "金额是 decimal，100000.00 放得下");
        assertTrue(up.contains("call kg_assert_same_collation('kg_reimbursement')"));
        for (int id : new int[] { 2288, 2289 })
        {
            assertTrue(up.contains("when exists (select 1 from sys_menu x where x.menu_id = " + id + ") then null"), "编号被占时退让 " + id);
        }
        assertTrue(up.contains("m.perms = 'kangaroo:stayvlog:list'"), "放在 Vlog 审核同一个上级（旅居）");
        assertTrue(up.contains("select 1, n.menu_id from sys_menu n where n.perms like 'kangaroo:reimburse:%'"), "只给角色 1");
        assertTrue(up.contains("'kangaroo/reimburse/index'") && up.contains("'KgReimburse'"), "菜单组件和页面对得上");
        assertTrue(down.contains("V66 rollback blocked: kg_reimbursement has data"), "财务记录不能跟着回滚一起没了");
        assertTrue(down.contains("if exists (select 1 from kg_reimbursement) then"), "撤回的、注销转匿名的行也算有数据：不看 del_flag");
        assertTrue(down.contains("drop table if exists kg_reimbursement"));
        assertTrue(down.indexOf("kg_reimbursement has data") < down.indexOf("update sys_dict_data"), "先拒绝，再动数据");
        assertEquals("sql/kangaroo/V66__forms.sql", new ReimburseSchema(mock(KgJdbc.class), true).migration());
    }
}
