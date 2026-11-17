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

/** V72（2026-11-03 主人：「历史聊天记录，永久保存，不能删除」）：账本或表缺了拒绝启动；迁移与回退脚本按约定写（不连库） */
class SupportChatSchemaTest
{
    private static KgJdbc db(long ledger, long columns)
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.count(contains("from kg_schema_version where version = 72 and name = 'support_chat'"), anyMap())).thenReturn(ledger);
        when(db.count(contains("table_name = 'kg_support_chat'"), anyMap())).thenReturn(columns);
        return db;
    }

    @Test
    void startsWhenLedgerAndColumnsArePresent()
    {
        assertDoesNotThrow(() -> new SupportChatSchema(db(1L, 9L), true).check());
    }

    @Test
    void missingLedgerOrColumnsRefusesStartupAndNamesTheMigration()
    {
        for (KgJdbc db : new KgJdbc[] { db(0L, 9L), db(1L, 8L), db(1L, 0L) })
        {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> new SupportChatSchema(db, true).check());
            assertTrue(e.getMessage().contains("V72__support_chat.sql"), e.getMessage());
        }
    }

    @Test
    void disabledAssertionSkipsDatabase()
    {
        KgJdbc db = mock(KgJdbc.class);
        assertDoesNotThrow(() -> new SupportChatSchema(db, false).check());
        verifyNoInteractions(db);
    }

    @Test
    void registersTheMigrationWithoutIndexes()
    {
        SupportChatSchema s = new SupportChatSchema(mock(KgJdbc.class), true);
        assertTrue(s.requiredIndexes().isEmpty());
        assertEquals("sql/kangaroo/V72__support_chat.sql", s.migration());
        assertNotNull(SupportChatSchema.class.getAnnotation(org.springframework.stereotype.Component.class));
    }

    private static String sql(String name) throws Exception
    {
        Path p = Paths.get(System.getProperty("basedir", "."), "..", "sql", "kangaroo", name);
        assertTrue(Files.exists(p), p.toAbsolutePath().toString());
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    void migrationCreatesAnAppendOnlyTableAndAReadOnlyMenu() throws Exception
    {
        String m = sql("V72__support_chat.sql");
        assertFalse(m.contains("\r\n"), "迁移脚本统一 LF");
        assertTrue(m.contains("call kg_assert_env('67');"));
        assertTrue(m.contains("create table if not exists kg_support_chat ("), "可重复执行");
        for (String col : new String[] { "chat_id", "app_user_id", "role", "content", "source", "intent", "context", "extra", "create_time" })
        {
            assertTrue(m.contains("\n  " + col + " "), col);
        }
        assertEquals(SupportChatSchema.COLUMNS, 9);
        assertTrue(m.contains("content       varchar(2000) not null"), "文字最长 2000 字");
        assertTrue(m.contains("key idx_schat_user (app_user_id, chat_id)"), "按用户 + id 翻页");
        assertTrue(m.contains("call kg_assert_same_collation('kg_support_chat');"));
        assertTrue(m.contains("'AI客服聊天记录', v_parent, v_order, 'supportchat', 'kangaroo/ext/supportchat/index', '', 'KgExtSupportChat', 1, 0, 'C', '0', '0', 'kangaroo:supportchat:list'"));
        assertTrue(m.contains("'kangaroo:supportchat:query', '#'"));
        for (String perm : new String[] { "add", "edit", "remove", "export" })
        {
            assertFalse(m.contains("kangaroo:supportchat:" + perm), "后台只读，没有 " + perm);
        }
        int byTicket = m.indexOf("select m.parent_id from sys_menu m where m.perms = 'kangaroo:ticket:list' and m.menu_type = 'C'");
        int byPath = m.indexOf("select m.menu_id from sys_menu m where m.parent_id = 0 and m.path = 'kgusersvc' and m.menu_type = 'M'");
        int fail = m.indexOf("signal sqlstate '45000' set message_text = '[V72] 找不到「用户服务」目录");
        assertTrue(byTicket > 0 && byPath > byTicket && fail > byPath, "先按客服工单的上级找，再按根目录 path 找，都找不到报错中止");
        assertTrue(fail < m.indexOf("insert into sys_menu"), "找不到上级时不插菜单");
        assertTrue(m.contains("join sys_menu f on f.menu_id = rm.menu_id and f.perms = 'kangaroo:ticket:list'"), "能看客服工单的角色一并能看");
        String lower = m.toLowerCase();
        assertFalse(lower.contains("delete from") || lower.contains("update kg_") || lower.contains("update sys_") || lower.contains("drop table"), "不改、不删已有数据");
        assertTrue(m.contains("values (72, 'support_chat', now(), '"));
    }

    @Test
    void ledgerChecksumMatchesTheScript() throws Exception
    {
        String m = sql("V72__support_chat.sql");
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
    void rollbackIsGuardedAndNeverDropsSavedHistory() throws Exception
    {
        String r = sql("V72__rollback.sql");
        assertFalse(r.contains("\r\n"));
        assertTrue(r.contains("@kg_allow_support_chat_rollback"));
        assertTrue(r.contains("V72 rollback requires stopped application and explicit authorization"));
        assertTrue(r.contains("V72 rollback blocked by later migrations"));
        assertTrue(r.contains("V72 rollback blocked: kg_support_chat has data"), "聊天记录是要永久保存的用户数据：表里有记录就不让回滚");
        assertTrue(r.indexOf("kg_support_chat has data") < r.indexOf("drop table if exists kg_support_chat;"));
        assertTrue(r.indexOf("V72 rollback blocked by later migrations") < r.indexOf("delete rm from sys_role_menu"));
        assertTrue(r.indexOf("delete rm from sys_role_menu") < r.indexOf("delete from sys_menu"), "先删授权再删菜单");
        assertTrue(r.contains("delete from kg_schema_version where version = 72;"));
        assertTrue(r.lastIndexOf("drop procedure if exists kg_drop_col_if_present;") > r.indexOf("call kg_rollback_support_chat();"));
    }
}
