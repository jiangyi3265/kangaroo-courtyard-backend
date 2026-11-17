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

/** V43：kg_message 缺触发人列时拒绝启动；关掉断言的开发库不查库；迁移与回退脚本按约定写（不连库，实跑见发布说明） */
class InboxInteractionsSchemaTest
{
    private static KgJdbc db(long columns)
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.count(contains("table_name = 'kg_message'"), anyMap())).thenReturn(columns);
        return db;
    }

    @Test
    void startsWhenTheColumnIsMigrated()
    {
        KgJdbc db = db(1L);
        assertDoesNotThrow(() -> new InboxInteractionsSchema(db, true).check());
        verify(db).count(contains("column_name = 'actor_id' and data_type = 'bigint' and is_nullable = 'YES'"), anyMap());
    }

    @Test
    void missingColumnRefusesStartupAndNamesTheMigration()
    {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new InboxInteractionsSchema(db(0L), true).check());
        assertTrue(e.getMessage().contains("kg_message.actor_id") && e.getMessage().contains("V43__inbox_interactions.sql"), e.getMessage());
    }

    @Test
    void disabledAssertionSkipsDatabase()
    {
        KgJdbc db = mock(KgJdbc.class);
        assertDoesNotThrow(() -> new InboxInteractionsSchema(db, false).check());
        verifyNoInteractions(db);
    }

    @Test
    void registersTheMigrationWithoutNewIndexes()
    {
        InboxInteractionsSchema s = new InboxInteractionsSchema(mock(KgJdbc.class), true);
        assertTrue(s.requiredIndexes().isEmpty());
        assertEquals("sql/kangaroo/V43__inbox_interactions.sql", s.migration());
    }

    /** 模块目录是 ruoyi-kangaroo（surefire 的 basedir）：迁移脚本在 ../sql/kangaroo */
    private static String sql(String name) throws Exception
    {
        Path p = Paths.get(System.getProperty("basedir", "."), "..", "sql", "kangaroo", name);
        assertTrue(Files.exists(p), p.toAbsolutePath().toString());
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    void migrationAddsTheColumnDictionaryAndMovesOnlyOldCommentNotices() throws Exception
    {
        String m = sql("V43__inbox_interactions.sql");
        assertFalse(m.contains("\r\n"), "迁移脚本统一 LF");
        assertTrue(m.contains("call kg_assert_env('42');"));
        assertTrue(m.contains("call kg_add_col_if_absent('kg_message', 'actor_id', 'actor_id bigint null comment "));
        assertTrue(m.contains("select 9, '评论', 'comment', 'kg_msg_category'"));
        assertTrue(m.contains("select 10, '点赞', 'like', 'kg_msg_category'"));
        // 只改归两种老评论通知，审核结果等仍是系统通知
        assertTrue(m.contains("update kg_message set category = 'comment'\n where mode = 'user' and category = 'system' and biz_type = 'post'\n"
                + "   and title in ('你的动态有新评论', '有人回复了你的评论');"));
        // 补触发人只填空着的新列
        assertTrue(m.contains("update kg_message m\n   set m.actor_id = ("));
        assertTrue(m.contains("where m.mode = 'user' and m.category = 'comment' and m.biz_type = 'post' and m.actor_id is null"));
        // 回退过再执行：新式评论 / 点赞通知归位，回退时随列删掉的触发人也补回来（否则消息页没有对方昵称，拉黑后也不隐藏）
        int restore = m.indexOf("update kg_message set category = 'like' where mode = 'user' and category = 'system' and biz_type in ('post_like', 'comment_like');");
        assertTrue(restore > 0);
        assertTrue(m.indexOf("update kg_message m join kg_post_comment c on c.comment_id = m.biz_id\n   set m.actor_id = c.app_user_id\n"
                + " where m.mode = 'user' and m.category = 'comment' and m.biz_type = 'comment' and m.actor_id is null;") > restore);
        assertTrue(m.indexOf("from kg_post_like l where l.post_id = m.biz_id and l.app_user_id <> m.recipient_id") > restore);
        assertTrue(m.indexOf("from kg_post_comment_like l where l.comment_id = m.biz_id and l.app_user_id <> m.recipient_id") > restore);
        assertTrue(m.contains("values (43, 'inbox_interactions', now(), '"));
    }

    @Test
    void rollbackIsGuardedAndRestoresTheOldCategories() throws Exception
    {
        String r = sql("V43__rollback.sql");
        assertFalse(r.contains("\r\n"));
        assertTrue(r.contains("@kg_allow_inbox_rollback"), "回退要显式授权");
        assertTrue(r.contains("V43 rollback blocked by later migrations"));
        assertTrue(r.contains("update kg_message set category = 'system' where mode = 'user' and category in ('comment', 'like');"));
        assertTrue(r.contains("delete from sys_dict_data where dict_type = 'kg_msg_category' and dict_value in ('comment', 'like');"));
        assertTrue(r.contains("call kg_drop_col_if_present('kg_message', 'actor_id');"));
        assertTrue(r.contains("delete from kg_schema_version where version = 43;"));
        assertTrue(r.indexOf("create procedure kg_drop_col_if_present") < r.indexOf("call kg_rollback_inbox_interactions();"));
        assertTrue(r.lastIndexOf("drop procedure if exists kg_drop_col_if_present;") > r.indexOf("call kg_rollback_inbox_interactions();"));
    }
}
