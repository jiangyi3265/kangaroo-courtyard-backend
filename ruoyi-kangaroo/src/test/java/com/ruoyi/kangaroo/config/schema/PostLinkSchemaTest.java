package com.ruoyi.kangaroo.config.schema;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import com.ruoyi.kangaroo.ext.KgJdbc;

/** V52：帖子关联单独成列（link_type / link_id），缺列拒绝启动、索引登记给 SchemaAssertion；迁移、回填与回退脚本按约定写（不连库，实跑见发布说明） */
class PostLinkSchemaTest
{
    private static KgJdbc db(long columns)
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.count(contains("table_name = 'kg_post'"), anyMap())).thenReturn(columns);
        return db;
    }

    @Test
    void startsWhenBothColumnsArePresent()
    {
        KgJdbc db = db(2L);
        assertDoesNotThrow(() -> new PostLinkSchema(db, true).check());
        verify(db).count(contains("column_name = 'link_type' and data_type = 'varchar' and character_maximum_length >= 16 and is_nullable = 'NO' and column_default = ''"), anyMap());
        verify(db).count(contains("column_name = 'link_id' and data_type = 'bigint' and is_nullable = 'YES'"), anyMap());
    }

    @Test
    void missingColumnsRefuseStartupAndNameTheMigration()
    {
        for (long n : new long[] { 0L, 1L })
        {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> new PostLinkSchema(db(n), true).check());
            assertTrue(e.getMessage().contains("kg_post.link_type") && e.getMessage().contains("V52__post_link.sql"), e.getMessage());
        }
    }

    @Test
    void disabledAssertionSkipsDatabase()
    {
        KgJdbc db = mock(KgJdbc.class);
        assertDoesNotThrow(() -> new PostLinkSchema(db, false).check());
        verifyNoInteractions(db);
    }

    @Test
    void registersTheLookupIndexAndTheMigration()
    {
        PostLinkSchema s = new PostLinkSchema(mock(KgJdbc.class), true);
        assertEquals(Collections.singletonMap("kg_post", Collections.singletonList("idx_post_link")), s.requiredIndexes());
        assertTrue(s.requiredIndexes().get("kg_post").get(0).startsWith("idx_"), "普通查询索引，SchemaAssertion 不要求唯一");
        assertEquals("sql/kangaroo/V52__post_link.sql", s.migration());
        assertNotNull(PostLinkSchema.class.getAnnotation(org.springframework.stereotype.Component.class), "和其他核对一样登记成组件，启动时自动跑");
    }

    /** 模块目录是 ruoyi-kangaroo（surefire 的 basedir）：迁移脚本在 ../sql/kangaroo */
    private static String sql(String name) throws Exception
    {
        Path p = Paths.get(System.getProperty("basedir", "."), "..", "sql", "kangaroo", name);
        assertTrue(Files.exists(p), p.toAbsolutePath().toString());
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    void migrationAddsColumnsIndexAndBackfillsAfterV51() throws Exception
    {
        String m = sql("V52__post_link.sql");
        assertFalse(m.contains("\r\n"), "迁移脚本统一 LF");
        assertTrue(m.contains("call kg_assert_env('51');"));
        assertTrue(m.contains("call kg_add_col_if_absent('kg_post', 'link_type', 'link_type varchar(16) not null default ''''"));
        assertTrue(m.contains("call kg_add_col_if_absent('kg_post', 'link_id', 'link_id bigint null"));
        assertTrue(m.contains("call kg_add_idx_if_absent('kg_post', 'idx_post_link', 'key idx_post_link (link_type, link_id)');"));
        // 回填：只抄还没填的、只抄四种公开对象，经验分享（ref 是报名）不抄；ref_* 不动（老版本程序还读它）
        String backfill = m.substring(m.indexOf("update kg_post set link_type = ref_type, link_id = ref_id"));
        backfill = backfill.substring(0, backfill.indexOf(';'));
        assertTrue(backfill.contains("where link_type = '' and link_id is null and kind <> 'diary'"), backfill);
        assertTrue(backfill.contains("and ref_type in ('stay', 'store', 'job', 'activity') and ref_id is not null"), backfill);
        assertFalse(m.toLowerCase().contains("set ref_type") || m.toLowerCase().contains("ref_id = null"), "ref_* 原样保留");
        assertTrue(m.indexOf("idx_post_link") < m.indexOf("update kg_post set link_type"), "先加列和索引再回填");
        assertTrue(m.contains("values (52, 'post_link', now(), '"));
    }

    @Test
    void rollbackIsGuardedAndRefusesToDropLinksKeptOnlyInTheNewColumns() throws Exception
    {
        String r = sql("V52__rollback.sql");
        assertFalse(r.contains("\r\n"));
        assertTrue(r.contains("@kg_allow_post_link_rollback"), "回退要显式授权");
        assertTrue(r.contains("V52 rollback blocked by later migrations"));
        assertTrue(r.contains("not (ref_type <=> link_type and ref_id <=> link_id)"), "只存在 link_* 里的关联（经验分享自己选的）");
        assertTrue(r.contains("@kg_allow_post_link_loss"), "确认可以丢才回退");
        assertTrue(r.indexOf("V52 rollback blocked: posts with links kept only") < r.indexOf("call kg_drop_col_if_present('kg_post', 'link_id');"));
        assertTrue(r.contains("call kg_drop_idx_if_present('kg_post', 'idx_post_link');"));
        assertTrue(r.contains("call kg_drop_col_if_present('kg_post', 'link_type');"));
        assertFalse(r.contains("ref_type ="), "ref_* 不动");
        assertTrue(r.contains("delete from kg_schema_version where version = 52;"));
        assertTrue(r.lastIndexOf("drop procedure if exists kg_assert_env;") > r.indexOf("call kg_rollback_post_link();"));
    }
}
