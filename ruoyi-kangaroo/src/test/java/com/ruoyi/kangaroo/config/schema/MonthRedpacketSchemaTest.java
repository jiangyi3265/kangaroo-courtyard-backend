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

/** V44：kg_job 缺满月红包列时拒绝启动；关掉断言的开发库不查库；迁移与回退脚本按约定写（不连库，实跑见发布说明） */
class MonthRedpacketSchemaTest
{
    private static KgJdbc db(long columns)
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.count(contains("table_name = 'kg_job'"), anyMap())).thenReturn(columns);
        return db;
    }

    @Test
    void startsWhenColumnIsMigrated()
    {
        KgJdbc db = db(1L);
        assertDoesNotThrow(() -> new MonthRedpacketSchema(db, true).check());
        verify(db).count(contains("column_name = 'month_redpacket' and data_type = 'char' and is_nullable = 'NO' and column_default = '1'"), anyMap());
    }

    @Test
    void missingColumnRefusesStartupAndNamesTheMigration()
    {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new MonthRedpacketSchema(db(0L), true).check());
        assertTrue(e.getMessage().contains("kg_job.month_redpacket") && e.getMessage().contains("V44__job_month_redpacket.sql"), e.getMessage());
    }

    @Test
    void disabledAssertionSkipsDatabase()
    {
        KgJdbc db = mock(KgJdbc.class);
        assertDoesNotThrow(() -> new MonthRedpacketSchema(db, false).check());
        verifyNoInteractions(db);
    }

    @Test
    void registersTheMigrationWithoutNewIndexes()
    {
        MonthRedpacketSchema s = new MonthRedpacketSchema(mock(KgJdbc.class), true);
        assertTrue(s.requiredIndexes().isEmpty());
        assertEquals("sql/kangaroo/V44__job_month_redpacket.sql", s.migration());
    }

    /** 模块目录是 ruoyi-kangaroo（surefire 的 basedir）：迁移脚本在 ../sql/kangaroo */
    private static String sql(String name) throws Exception
    {
        Path p = Paths.get(System.getProperty("basedir", "."), "..", "sql", "kangaroo", name);
        assertTrue(Files.exists(p), p.toAbsolutePath().toString());
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    void migrationAddsTheFlagBackfillsOnceAndRepairsDeactivatedLikes() throws Exception
    {
        String m = sql("V44__job_month_redpacket.sql");
        assertFalse(m.contains("\r\n"), "迁移脚本统一 LF");
        assertTrue(m.contains("call kg_assert_env('43');"));
        assertTrue(m.contains("call kg_add_col_if_absent('kg_job', 'month_redpacket', 'month_redpacket char(1) not null default ''1'' comment ''做满一个月有红包（0有 1没有）'' after insurance_flag');"));
        // 老岗位按奖金规则自动勾：只在第一次执行（账本里还没有 V44）时
        assertTrue(m.contains("and not exists (select 1 from kg_schema_version v where v.version = 44)"));
        assertTrue(m.contains("and bonus_desc like '%红包%'"));
        for (String w : new String[] { "满一个月", "满1个月", "满30天", "满三十天" })
        {
            assertTrue(m.contains("like '%" + w + "%'"), w);
        }
        // 注销账号的点赞：删记录、按点赞表重算（子查询读点赞表，不读被更新的表）、通知改成说明并隐藏
        assertTrue(m.contains("delete l from kg_post_like l join kg_app_user u on u.user_id = l.app_user_id and u.del_flag = '2';"));
        assertTrue(m.contains("delete l from kg_post_comment_like l join kg_app_user u on u.user_id = l.app_user_id and u.del_flag = '2';"));
        assertTrue(m.contains("set p.likes = (select count(*) from kg_post_like l where l.post_id = p.post_id)"));
        assertTrue(m.contains("set c.likes = (select count(*) from kg_post_comment_like l where l.comment_id = c.comment_id)"));
        assertFalse(m.contains("(select count(*) from kg_post p") || m.contains("(select count(*) from kg_post_comment c"), "不能在子查询里读被更新的表本身（MySQL 1093）");
        assertTrue(m.contains("set m.title = '关联账号已注销', m.content = '对方的个人资料已清除', m.route = null, m.actor_id = null, m.del_flag = '2'"));
        assertTrue(m.contains("where m.category in ('like', 'comment');"));
        assertTrue(m.contains("values (44, 'job_month_redpacket', now(), '"));
    }

    @Test
    void rollbackIsGuardedAndDropsOnlyTheColumn() throws Exception
    {
        String r = sql("V44__rollback.sql");
        assertFalse(r.contains("\r\n"));
        assertTrue(r.contains("@kg_allow_month_redpacket_rollback"), "回退要显式授权");
        assertTrue(r.contains("@kg_allow_month_redpacket_discard"), "还有岗位勾着满月红包时要再确认一次");
        assertTrue(r.contains("V44 rollback blocked by later migrations"));
        assertTrue(r.contains("call kg_drop_col_if_present('kg_job', 'month_redpacket');"));
        assertTrue(r.contains("delete from kg_schema_version where version = 44;"));
        assertFalse(r.contains("kg_post_like"), "点赞修复是一次性数据修复，不回退");
        assertTrue(r.indexOf("create procedure kg_drop_col_if_present") < r.indexOf("call kg_rollback_job_month_redpacket();"));
        assertTrue(r.lastIndexOf("drop procedure if exists kg_drop_col_if_present;") > r.indexOf("call kg_rollback_job_month_redpacket();"));
    }
}
