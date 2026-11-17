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

/** V74（2026-11-03 员工任务流转 / 全体派发 / 按岗位派发）：账本里没有 V74 时拒绝启动；迁移与回退脚本按约定写（不连库） */
class StaffTaskFlowSchemaTest
{
    private static KgJdbc db(long ledger)
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.count(contains("from kg_schema_version where version = 74 and name = 'staff_task_flow'"), anyMap())).thenReturn(ledger);
        return db;
    }

    @Test
    void startsOnlyWhenV74IsInTheLedger()
    {
        assertDoesNotThrow(() -> new StaffTaskFlowSchema(db(1L), true).check());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new StaffTaskFlowSchema(db(0L), true).check());
        assertTrue(e.getMessage().contains("V74__staff_task_flow.sql"), e.getMessage());
        KgJdbc off = mock(KgJdbc.class);
        assertDoesNotThrow(() -> new StaffTaskFlowSchema(off, false).check());
        verifyNoInteractions(off);
    }

    @Test
    void registersTheNewIndexes()
    {
        StaffTaskFlowSchema s = new StaffTaskFlowSchema(mock(KgJdbc.class), true);
        assertEquals("sql/kangaroo/V74__staff_task_flow.sql", s.migration());
        assertTrue(s.requiredIndexes().get("kg_staff_task_batch").contains("uk_staff_task_batch_request"));
        assertTrue(s.requiredIndexes().get("kg_staff_task_event").contains("idx_staff_task_event"));
        assertTrue(s.requiredIndexes().get("kg_staff_task").contains("idx_staff_task_attention"));
        assertNotNull(StaffTaskFlowSchema.class.getAnnotation(org.springframework.stereotype.Component.class));
    }

    private static String sql(String name) throws Exception
    {
        Path p = Paths.get(System.getProperty("basedir", "."), "..", "sql", "kangaroo", name);
        assertTrue(Files.exists(p), p.toAbsolutePath().toString());
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    void migrationIsGuardedAndOnlyAdds() throws Exception
    {
        String m = sql("V74__staff_task_flow.sql");
        assertFalse(m.contains("\r\n"), "迁移脚本统一 LF");
        assertTrue(m.contains("call kg_assert_env('62');"));
        assertTrue(m.contains("create table if not exists kg_staff_task_batch"));
        assertTrue(m.contains("create table if not exists kg_staff_task_event"));
        assertTrue(m.contains("call kg_assert_same_collation('kg_staff_task_batch');"));
        assertTrue(m.contains("call kg_assert_same_collation('kg_staff_task_event');"));
        for (String col : new String[] { "batch_id", "role_code", "requested_due_date", "admin_attention" })
        {
            assertTrue(m.contains("call kg_add_col_if_absent('kg_staff_task', '" + col + "'"), col);
        }
        assertTrue(m.contains("where not exists (select 1 from kg_staff_task_event e where e.task_id = t.task_id and e.action = 'assign');"), "补时间线可重复执行");
        assertTrue(m.contains("where perms = 'kangaroo:stafftask:list' and menu_name = '主理人任务派发';"), "只改还叫老名字的菜单");
        assertTrue(m.contains("'kangaroo:stafftask:handle', '#'"));
        assertTrue(m.contains("join sys_menu f on f.menu_id = rm.menu_id and f.perms = 'kangaroo:stafftask:audit'"));
        String lower = m.toLowerCase();
        assertFalse(lower.contains("drop table"), "不删表");
        assertFalse(lower.contains("delete from kg_") || lower.contains("delete from sys_"), "不删数据");
        assertTrue(m.contains("values (74, 'staff_task_flow', now(), '"));
    }

    @Test
    void ledgerChecksumMatchesTheScript() throws Exception
    {
        String m = sql("V74__staff_task_flow.sql");
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
    void rollbackIsGuarded() throws Exception
    {
        String r = sql("V74__rollback.sql");
        assertFalse(r.contains("\r\n"));
        assertTrue(r.contains("@kg_allow_staff_task_flow_rollback"));
        assertTrue(r.contains("V74 rollback requires stopped application and explicit authorization"));
        assertTrue(r.contains("V74 rollback blocked by later migrations"));
        assertTrue(r.contains("V74 rollback blocked: closed staff tasks exist"));
        assertTrue(r.indexOf("V74 rollback blocked by later migrations") < r.indexOf("update kg_staff_task set status = 'pending'"));
        assertTrue(r.indexOf("delete rm from sys_role_menu") < r.indexOf("delete from sys_menu"), "先删授权再删菜单");
        assertTrue(r.contains("delete from kg_schema_version where version = 74;"));
        assertFalse(r.toLowerCase().contains("drop table if exists kg_staff_task;"), "任务本身保留");
        assertTrue(r.lastIndexOf("drop procedure if exists kg_drop_col_if_present;") > r.indexOf("call kg_rollback_staff_task_flow();"));
    }
}
