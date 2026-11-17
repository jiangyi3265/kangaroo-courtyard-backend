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

/** V56（2026-10-18「成为商家」）：账本里没有 V56 或没有表时拒绝启动；迁移与回退脚本按约定写（不连库，实跑见 acceptance/20261018-store/local_migrate_v56.py） */
class MerchantApplySchemaTest
{
    private static KgJdbc db(long ledger, long table)
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.count(contains("from kg_schema_version where version = 56 and name = 'merchant_apply'"), anyMap())).thenReturn(ledger);
        when(db.count(contains("table_name = 'kg_merchant_apply'"), anyMap())).thenReturn(table);
        return db;
    }

    @Test
    void startsWhenV56IsInTheLedgerAndTheTableExists()
    {
        assertDoesNotThrow(() -> new MerchantApplySchema(db(1L, 1L), true).check());
    }

    @Test
    void missingLedgerOrTableRefusesStartupAndNamesTheMigration()
    {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new MerchantApplySchema(db(0L, 1L), true).check());
        assertTrue(e.getMessage().contains("V56__merchant_apply.sql"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> new MerchantApplySchema(db(1L, 0L), true).check());
    }

    @Test
    void disabledAssertionSkipsDatabase()
    {
        KgJdbc db = mock(KgJdbc.class);
        assertDoesNotThrow(() -> new MerchantApplySchema(db, false).check());
        verifyNoInteractions(db);
    }

    @Test
    void registersTheMigrationWithoutUniqueIndexes()
    {
        MerchantApplySchema s = new MerchantApplySchema(mock(KgJdbc.class), true);
        assertTrue(s.requiredIndexes().isEmpty());
        assertEquals("sql/kangaroo/V56__merchant_apply.sql", s.migration());
        assertNotNull(MerchantApplySchema.class.getAnnotation(org.springframework.stereotype.Component.class));
    }

    private static String sql(String name) throws Exception
    {
        Path p = Paths.get(System.getProperty("basedir", "."), "..", "sql", "kangaroo", name);
        assertTrue(Files.exists(p), p.toAbsolutePath().toString());
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    void migrationCreatesTheTableAndTheMenuUnderKangarooAfterV55() throws Exception
    {
        String m = sql("V56__merchant_apply.sql");
        assertFalse(m.contains("\r\n"), "迁移脚本统一 LF");
        assertTrue(m.contains("call kg_assert_env('55');"));
        assertTrue(m.contains("create table if not exists kg_merchant_apply ("), "可重复执行：表已在不重建");
        for (String col : new String[] { "app_user_id", "contact_name", "phone", "city", "store_type", "store_name", "intro", "want_types", "status",
                "follow_note", "create_time", "update_time", "del_flag" })
        {
            assertTrue(m.contains("\n  " + col + " "), "缺列 " + col);
        }
        assertTrue(m.contains("call kg_assert_same_collation('kg_merchant_apply');"));
        int byStore = m.indexOf("select m.parent_id from sys_menu m where m.perms = 'kangaroo:store:list' and m.menu_type = 'C'");
        int byPath = m.indexOf("select m.menu_id from sys_menu m where m.parent_id = 0 and m.path = 'kangaroo' and m.menu_type = 'M'");
        int fail = m.indexOf("signal sqlstate '45000' set message_text = '[V56] 找不到「袋鼠小院」目录");
        assertTrue(byStore > 0 && byPath > byStore && fail > byPath, "先按门店管理的上级找，再按根目录 path 找，都找不到报错中止");
        assertTrue(fail < m.indexOf("insert into sys_menu"), "找不到上级时不插菜单");
        assertTrue(m.contains("if not exists (select 1 from sys_menu where perms = 'kangaroo:merchantapply:list') then"), "可重复执行：菜单已在就跳过");
        assertTrue(m.contains("'商家入驻申请', v_parent, 3960, 'merchantapply', 'kangaroo/ext/merchantapply/index', '', 'KgExtMerchantApply', 1, 0, 'C', '0', '0', 'kangaroo:merchantapply:list'"));
        assertTrue(m.contains("'kangaroo:merchantapply:query'") && m.contains("'kangaroo:merchantapply:edit'"));
        assertFalse(m.contains("kangaroo:merchantapply:add") || m.contains("kangaroo:merchantapply:remove"), "不能新增、不能删除");
        assertTrue(m.contains("f.perms in ('kangaroo:guideapply:list', 'kangaroo:ticket:list')"), "跟进用户提交的角色一并能看");
        assertTrue(m.contains("select 1, n.menu_id from sys_menu n where n.perms like 'kangaroo:merchantapply:%';"));
        String lower = m.toLowerCase();
        assertFalse(lower.contains("alter table kg_") || lower.contains("kg_add_col_if_absent('"), "不改已有表");
        assertFalse(lower.contains("delete from") || lower.contains("update sys_") || lower.contains("drop table"), "不改已有数据");
        assertTrue(m.contains("values (56, 'merchant_apply', now(), '"));
    }

    @Test
    void rollbackIsGuardedAndRefusesToLoseApplications() throws Exception
    {
        String r = sql("V56__rollback.sql");
        assertFalse(r.contains("\r\n"));
        assertTrue(r.contains("@kg_allow_merchant_apply_rollback"));
        assertTrue(r.contains("V56 rollback requires stopped application and explicit authorization"));
        assertTrue(r.contains("V56 rollback blocked by later migrations"));
        assertTrue(r.contains("V56 rollback blocked: kg_merchant_apply has applications (set @kg_allow_merchant_apply_loss = 1)"));
        int guard = r.indexOf("V56 rollback blocked: kg_merchant_apply has applications");
        assertTrue(guard < r.indexOf("drop table if exists kg_merchant_apply;"), "有申请时先拦住再删表");
        assertTrue(r.contains("delete from sys_menu where perms like 'kangaroo:merchantapply:%';"));
        assertTrue(r.contains("delete from kg_schema_version where version = 56;"));
        assertTrue(r.lastIndexOf("drop procedure if exists kg_drop_col_if_present;") > r.indexOf("call kg_rollback_merchant_apply();"));
    }
}
