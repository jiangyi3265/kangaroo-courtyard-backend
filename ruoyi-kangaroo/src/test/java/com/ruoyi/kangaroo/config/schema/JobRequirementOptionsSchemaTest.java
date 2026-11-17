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

/** V67（2026-11-03 岗位年龄段 / 性格选项）：账本里没有 V67 时拒绝启动；迁移与回退脚本按约定写（不连库） */
class JobRequirementOptionsSchemaTest
{
    private static KgJdbc db(long ledger)
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.count(contains("from kg_schema_version where version = 67 and name = 'job_requirement_options'"), anyMap())).thenReturn(ledger);
        return db;
    }

    @Test
    void startsWhenV67IsInTheLedger()
    {
        assertDoesNotThrow(() -> new JobRequirementOptionsSchema(db(1L), true).check());
    }

    @Test
    void missingLedgerRefusesStartupAndNamesTheMigration()
    {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new JobRequirementOptionsSchema(db(0L), true).check());
        assertTrue(e.getMessage().contains("V67__job_requirement_options.sql"), e.getMessage());
    }

    @Test
    void disabledAssertionSkipsDatabase()
    {
        KgJdbc db = mock(KgJdbc.class);
        assertDoesNotThrow(() -> new JobRequirementOptionsSchema(db, false).check());
        verifyNoInteractions(db);
    }

    @Test
    void registersTheMigrationWithoutIndexes()
    {
        JobRequirementOptionsSchema s = new JobRequirementOptionsSchema(mock(KgJdbc.class), true);
        assertTrue(s.requiredIndexes().isEmpty());
        assertEquals("sql/kangaroo/V67__job_requirement_options.sql", s.migration());
        assertNotNull(JobRequirementOptionsSchema.class.getAnnotation(org.springframework.stereotype.Component.class));
    }

    private static String sql(String name) throws Exception
    {
        Path p = Paths.get(System.getProperty("basedir", "."), "..", "sql", "kangaroo", name);
        assertTrue(Files.exists(p), p.toAbsolutePath().toString());
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    void migrationSeedsTwoDictionariesAndTheMenuAfterV66() throws Exception
    {
        String m = sql("V67__job_requirement_options.sql");
        assertFalse(m.contains("\r\n"), "迁移脚本统一 LF");
        assertTrue(m.contains("call kg_assert_env('66');"));
        for (String age : new String[] { "不限", "18-25岁", "18-30岁", "18-35岁", "18-40岁", "18岁以上" })
        {
            assertTrue(m.contains("'" + age + "', '" + age + "', 'kg_job_age'"), age);
        }
        for (String p : new String[] { "活泼开朗", "细心耐心", "善于沟通", "吃苦耐劳", "有责任心", "安静内向也欢迎" })
        {
            assertTrue(m.contains("'" + p + "', '" + p + "', 'kg_job_personality'"), p);
        }
        assertTrue(m.contains("from dual where not exists (select 1 from sys_dict_type where dict_type = 'kg_job_age');"), "可重复执行");
        assertTrue(m.contains("'岗位要求选项', v_parent, v_order, 'jobopt', 'kangaroo/ext/jobopt/index', '', 'KgExtJobOpt', 1, 0, 'C', '0', '0', 'kangaroo:jobopt:list'"));
        int byJob = m.indexOf("select m.parent_id from sys_menu m where m.perms = 'kangaroo:job:list' and m.menu_type = 'C'");
        int byPath = m.indexOf("select m.menu_id from sys_menu m where m.parent_id = 0 and m.path = 'kangaroo' and m.menu_type = 'M'");
        int fail = m.indexOf("signal sqlstate '45000' set message_text = '[V67] 找不到「袋鼠小院」目录");
        assertTrue(byJob > 0 && byPath > byJob && fail > byPath, "先按岗位管理的上级找，再按根目录 path 找，都找不到报错中止");
        assertTrue(fail < m.indexOf("insert into sys_menu"), "找不到上级时不插菜单");
        for (String perm : new String[] { "query", "add", "edit", "remove" })
        {
            assertTrue(m.contains("'kangaroo:jobopt:" + perm + "', '#'"), perm);
        }
        assertTrue(m.contains("join sys_menu f on f.menu_id = rm.menu_id and f.perms = 'kangaroo:job:edit'"), "能改岗位的角色一并能改选项");
        assertTrue(m.contains("select 1, n.menu_id from sys_menu n where n.perms like 'kangaroo:jobopt:%';"));
        String lower = m.toLowerCase();
        assertFalse(lower.contains("create table") || lower.contains("alter table kg_") || lower.contains("kg_add_col_if_absent('"), "不建表、不加列");
        assertFalse(lower.contains("delete from") || lower.contains("update sys_") || lower.contains("update kg_"), "不改已有数据");
        assertTrue(m.contains("values (67, 'job_requirement_options', now(), '"));
    }

    @Test
    void ledgerChecksumMatchesTheScript() throws Exception
    {
        String m = sql("V67__job_requirement_options.sql");
        int at = m.indexOf("-- @kg-ledger");
        byte[] d = MessageDigest.getInstance("SHA-256").digest(m.substring(0, at).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : d)
        {
            hex.append(String.format("%02x", b));
        }
        assertTrue(m.substring(at).contains("'" + hex + "'"), "账本 checksum 要等于标记行之前内容的 sha256（用 make_migration.py 生成，别手改）");
    }

    @Test
    void rollbackIsGuardedAndRemovesMenuDictionariesAndLedger() throws Exception
    {
        String r = sql("V67__rollback.sql");
        assertFalse(r.contains("\r\n"));
        assertTrue(r.contains("@kg_allow_job_requirement_options_rollback"));
        assertTrue(r.contains("V67 rollback requires stopped application and explicit authorization"));
        assertTrue(r.contains("V67 rollback blocked by later migrations"));
        assertTrue(r.contains("delete rm from sys_role_menu rm join sys_menu m on m.menu_id = rm.menu_id where m.perms like 'kangaroo:jobopt:%';"));
        assertTrue(r.contains("delete from sys_menu where perms = 'kangaroo:jobopt:list';"));
        assertTrue(r.contains("delete from sys_dict_data where dict_type in ('kg_job_age', 'kg_job_personality');"));
        assertTrue(r.contains("delete from sys_dict_type where dict_type in ('kg_job_age', 'kg_job_personality');"));
        assertTrue(r.contains("delete from kg_schema_version where version = 67;"));
        assertTrue(r.indexOf("V67 rollback blocked by later migrations") < r.indexOf("delete rm from sys_role_menu"));
        assertTrue(r.indexOf("delete rm from sys_role_menu") < r.indexOf("delete from sys_menu"), "先删授权再删菜单");
        assertFalse(r.toLowerCase().contains("kg_job "), "不动岗位");
        assertTrue(r.lastIndexOf("drop procedure if exists kg_drop_col_if_present;") > r.indexOf("call kg_rollback_job_requirement_options();"));
    }
}
