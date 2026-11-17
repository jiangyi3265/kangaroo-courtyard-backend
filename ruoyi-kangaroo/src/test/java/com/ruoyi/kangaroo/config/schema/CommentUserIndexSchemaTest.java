package com.ruoyi.kangaroo.config.schema;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

/** V45：评论表按评论人查的索引登记到启动核对里；迁移与回退脚本按约定写（不连库，实跑见发布说明） */
class CommentUserIndexSchemaTest
{
    @Test
    void registersTheIndexAndNamesTheMigration()
    {
        CommentUserIndexSchema s = new CommentUserIndexSchema();
        assertEquals(Collections.singletonMap("kg_post_comment", Arrays.asList("idx_pc_user")), s.requiredIndexes());
        assertEquals("sql/kangaroo/V45__post_comment_user_idx.sql", s.migration());
    }

    /** 模块目录是 ruoyi-kangaroo（surefire 的 basedir）：迁移脚本在 ../sql/kangaroo */
    private static String sql(String name) throws Exception
    {
        Path p = Paths.get(System.getProperty("basedir", "."), "..", "sql", "kangaroo", name);
        assertTrue(Files.exists(p), p.toAbsolutePath().toString());
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    void migrationOnlyAddsTheIndex() throws Exception
    {
        String m = sql("V45__post_comment_user_idx.sql");
        assertFalse(m.contains("\r\n"), "迁移脚本统一 LF");
        assertTrue(m.contains("call kg_assert_env('44');"));
        assertTrue(m.contains("call kg_add_idx_if_absent('kg_post_comment', 'idx_pc_user', 'index idx_pc_user (app_user_id)');"));
        String body = m.substring(m.indexOf("call kg_assert_env('44');"), m.indexOf("-- @kg-footer-begin"));
        for (String line : body.split("\n"))
        {
            // 注释行不算：只看真正执行的语句
            assertFalse(line.matches("^\\s*(update|delete|insert|alter|drop)\\b.*"), "只加索引，不改数据：" + line);
        }
        assertTrue(m.contains("values (45, 'post_comment_user_idx', now(), '"));
    }

    @Test
    void rollbackIsGuardedAndDropsOnlyTheIndex() throws Exception
    {
        String r = sql("V45__rollback.sql");
        assertFalse(r.contains("\r\n"));
        assertTrue(r.contains("@kg_allow_comment_user_idx_rollback"), "回退要显式授权");
        assertTrue(r.contains("V45 rollback blocked by later migrations"));
        assertTrue(r.contains("call kg_drop_idx_if_present('kg_post_comment', 'idx_pc_user');"));
        assertTrue(r.contains("delete from kg_schema_version where version = 45;"));
        assertTrue(r.indexOf("create procedure kg_drop_idx_if_present") < r.indexOf("call kg_rollback_post_comment_user_idx();"));
        assertTrue(r.lastIndexOf("drop procedure if exists kg_drop_idx_if_present;") > r.indexOf("call kg_rollback_post_comment_user_idx();"));
    }
}
