package com.ruoyi.kangaroo.config.schema;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ruoyi.kangaroo.ext.KgJdbc;

/** V46：私信文字放宽到 2000 字、门店会话发文件的表登记到启动核对里；迁移与回退脚本按约定写（不连库，实跑见发布说明） */
class ChatFileSchemaTest
{
    @Test
    void registersTheFileTableIndexesAndTheMigration()
    {
        ChatFileSchema s = new ChatFileSchema(mock(KgJdbc.class), true);
        Map<String, List<String>> idx = s.requiredIndexes();
        assertEquals(1, idx.size());
        assertEquals(Arrays.asList("PRIMARY", "uk_chat_file_key", "idx_chat_file_sender"), idx.get("kg_chat_file"));
        assertEquals("sql/kangaroo/V46__chat_file.sql", s.migration());
    }

    @Test
    void narrowContentColumnRefusesStartupAndNamesMigration()
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.count(contains("column_name = 'content'"), anyMap())).thenReturn(0L);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new ChatFileSchema(db, true).check());
        assertTrue(e.getMessage().contains("kg_chat_msg.content") && e.getMessage().contains("V46__chat_file.sql"), e.getMessage());
        when(db.count(contains("column_name = 'content'"), anyMap())).thenReturn(1L);
        assertDoesNotThrow(() -> new ChatFileSchema(db, true).check());
        verify(db, times(2)).count(contains("data_type = 'varchar' and character_maximum_length >= 2000"), anyMap());
    }

    @Test
    void disabledAssertionSkipsDatabase()
    {
        KgJdbc db = mock(KgJdbc.class);
        assertDoesNotThrow(() -> new ChatFileSchema(db, false).check());
        verifyNoInteractions(db);
    }

    /** 模块目录是 ruoyi-kangaroo（surefire 的 basedir）：迁移脚本在 ../sql/kangaroo */
    private static String sql(String name) throws Exception
    {
        Path p = Paths.get(System.getProperty("basedir", "."), "..", "sql", "kangaroo", name);
        assertTrue(Files.exists(p), p.toAbsolutePath().toString());
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    void migrationWidensContentKeepingCollationAndCreatesThePrivateFileTable() throws Exception
    {
        String m = sql("V46__chat_file.sql");
        assertFalse(m.contains("\r\n"), "迁移脚本统一 LF");
        assertTrue(m.contains("call kg_assert_env('45');"));
        // 按原列的字符集、排序规则改长度；已经够长时不动（可重复执行）
        assertTrue(m.contains("if v_len < 2000 then"));
        assertTrue(m.contains("modify column content varchar(2000) character set ', v_cs, ' collate ', v_coll"));
        assertTrue(m.contains("create table if not exists kg_chat_file ("));
        assertTrue(m.contains("unique key uk_chat_file_key (file_key)"));
        assertTrue(m.contains("key idx_chat_file_sender (sender_id, create_time)"));
        assertTrue(m.contains("call kg_assert_same_collation('kg_chat_file');"));
        assertTrue(m.contains("drop procedure kg_v46_widen_chat_content;"));
        assertTrue(m.contains("values (46, 'chat_file', now(), '"));
    }

    @Test
    void rollbackIsGuardedAndRefusesToLoseData() throws Exception
    {
        String r = sql("V46__rollback.sql");
        assertFalse(r.contains("\r\n"));
        assertTrue(r.contains("@kg_allow_chat_file_rollback"), "回退要显式授权");
        assertTrue(r.contains("V46 rollback blocked by later migrations"));
        assertTrue(r.contains("V46 rollback blocked: chat file messages still exist"));
        assertTrue(r.contains("V46 rollback blocked: chat messages longer than 1000 characters exist"));
        assertTrue(r.contains("drop table if exists kg_chat_file;"));
        assertTrue(r.contains("modify column content varchar(1000) character set ', v_cs, ' collate ', v_coll"));
        assertTrue(r.contains("delete from kg_schema_version where version = 46;"));
        // 先挡住，再动表
        assertTrue(r.indexOf("chat file messages still exist") < r.indexOf("drop table if exists kg_chat_file;"));
    }
}
