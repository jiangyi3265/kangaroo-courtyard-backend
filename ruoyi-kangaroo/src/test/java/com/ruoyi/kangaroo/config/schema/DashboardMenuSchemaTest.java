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

/** V55（2026-10-18 数据看板菜单）：账本里没有 V55 时拒绝启动；迁移与回退脚本按约定写（不连库，实跑见 acceptance/20261018-store/local_migrate_v55.py） */
class DashboardMenuSchemaTest
{
    private static KgJdbc db(long ledger)
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.count(contains("from kg_schema_version where version = 55 and name = 'dashboard_menu'"), anyMap())).thenReturn(ledger);
        return db;
    }

    @Test
    void startsWhenV55IsInTheLedger()
    {
        assertDoesNotThrow(() -> new DashboardMenuSchema(db(1L), true).check());
    }

    @Test
    void missingLedgerRefusesStartupAndNamesTheMigration()
    {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new DashboardMenuSchema(db(0L), true).check());
        assertTrue(e.getMessage().contains("V55__dashboard_menu.sql"), e.getMessage());
    }

    @Test
    void disabledAssertionSkipsDatabase()
    {
        KgJdbc db = mock(KgJdbc.class);
        assertDoesNotThrow(() -> new DashboardMenuSchema(db, false).check());
        verifyNoInteractions(db);
    }

    @Test
    void registersTheMigrationWithoutIndexes()
    {
        DashboardMenuSchema s = new DashboardMenuSchema(mock(KgJdbc.class), true);
        assertTrue(s.requiredIndexes().isEmpty());
        assertEquals("sql/kangaroo/V55__dashboard_menu.sql", s.migration());
        assertNotNull(DashboardMenuSchema.class.getAnnotation(org.springframework.stereotype.Component.class));
    }

    private static String sql(String name) throws Exception
    {
        Path p = Paths.get(System.getProperty("basedir", "."), "..", "sql", "kangaroo", name);
        assertTrue(Files.exists(p), p.toAbsolutePath().toString());
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    void migrationAddsTheMenuFirstUnderKangarooAfterV54() throws Exception
    {
        String m = sql("V55__dashboard_menu.sql");
        assertFalse(m.contains("\r\n"), "迁移脚本统一 LF");
        assertTrue(m.contains("call kg_assert_env('54');"));
        assertTrue(m.contains("'数据看板', v_parent, 0, 'dashboard', 'kangaroo/dashboard/index', '', 'KgDashboard', 1, 0, 'C', '0', '0', 'kangaroo:dashboard:view'"));
        int byStore = m.indexOf("select m.parent_id from sys_menu m where m.perms = 'kangaroo:store:list' and m.menu_type = 'C'");
        int byPath = m.indexOf("select m.menu_id from sys_menu m where m.parent_id = 0 and m.path = 'kangaroo' and m.menu_type = 'M'");
        int fail = m.indexOf("signal sqlstate '45000' set message_text = '[V55] 找不到「袋鼠小院」目录");
        assertTrue(byStore > 0 && byPath > byStore && fail > byPath, "先按门店管理的上级找，再按根目录 path 找，都找不到报错中止");
        assertTrue(fail < m.indexOf("insert into sys_menu"), "找不到上级时不插菜单");
        assertTrue(m.contains("if not exists (select 1 from sys_menu where perms = 'kangaroo:dashboard:view') then"), "可重复执行：菜单已在就跳过");
        assertTrue(m.contains("call kg_v55_dashboard_menu();\ndrop procedure kg_v55_dashboard_menu;"));
        assertTrue(m.contains("join sys_menu f on f.menu_id = rm.menu_id and f.perms = 'kangaroo:payorder:list'"), "能看支付单的角色一并能看");
        assertTrue(m.contains("select 1, n.menu_id from sys_menu n where n.perms = 'kangaroo:dashboard:view';"));
        String lower = m.toLowerCase();
        assertFalse(lower.contains("create table") || lower.contains("alter table kg_") || lower.contains("kg_add_col_if_absent('"), "只加菜单");
        assertFalse(lower.contains("delete from") || lower.contains("update sys_"), "不改已有数据");
        assertTrue(m.contains("values (55, 'dashboard_menu', now(), '"));
    }

    @Test
    void rollbackIsGuardedAndOnlyRemovesTheMenu() throws Exception
    {
        String r = sql("V55__rollback.sql");
        assertFalse(r.contains("\r\n"));
        assertTrue(r.contains("@kg_allow_dashboard_menu_rollback"));
        assertTrue(r.contains("V55 rollback requires stopped application and explicit authorization"));
        assertTrue(r.contains("V55 rollback blocked by later migrations"));
        assertTrue(r.contains("delete rm from sys_role_menu rm join sys_menu m on m.menu_id = rm.menu_id where m.perms = 'kangaroo:dashboard:view';"));
        assertTrue(r.contains("delete from sys_menu where perms = 'kangaroo:dashboard:view';"));
        assertTrue(r.contains("delete from kg_schema_version where version = 55;"));
        assertTrue(r.indexOf("V55 rollback blocked by later migrations") < r.indexOf("delete from sys_menu"));
        assertTrue(r.lastIndexOf("drop procedure if exists kg_drop_col_if_present;") > r.indexOf("call kg_rollback_dashboard_menu();"));
    }
}
