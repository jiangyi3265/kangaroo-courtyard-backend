package com.ruoyi.kangaroo.config.schema;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import com.ruoyi.kangaroo.ext.KgJdbc;

class StayCommentSchemaTest
{
    private static KgJdbc db(long ledger, long columns)
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.count(eq(StayCommentSchema.LEDGER_SQL), anyMap())).thenReturn(ledger);
        when(db.count(eq(StayCommentSchema.COLUMNS_SQL), anyMap())).thenReturn(columns);
        return db;
    }

    @Test
    void refusesToStartWithoutTheMigration()
    {
        new StayCommentSchema(db(1, StayCommentSchema.COLUMNS), true).check();
        assertTrue(assertThrows(IllegalStateException.class, () -> new StayCommentSchema(db(0, StayCommentSchema.COLUMNS), true).check()).getMessage().contains("V65__stay_comment.sql"));
        assertThrows(IllegalStateException.class, () -> new StayCommentSchema(db(1, 3), true).check());
    }

    @Test
    void devDatabaseSkips()
    {
        new StayCommentSchema(db(0, 0), false).check();
    }

    @Test
    void migrationGuards() throws Exception
    {
        String up = new String(Files.readAllBytes(Paths.get("../sql/kangaroo/V65__stay_comment.sql")), StandardCharsets.UTF_8);
        String down = new String(Files.readAllBytes(Paths.get("../sql/kangaroo/V65__rollback.sql")), StandardCharsets.UTF_8);
        assertTrue(up.contains("call kg_assert_env('60,62,63,64')"), "不依赖 V61");
        assertFalse(up.contains("alter table kg_review") || up.contains("update kg_review") || up.contains("kg_stay_diary (") || up.contains("update kg_stay_diary"), "评价和碎片一行不改");
        for (int id : new int[] { 2285, 2286, 2287 })
        {
            assertTrue(up.contains("when exists (select 1 from sys_menu x where x.menu_id = " + id + ") then null"), "编号被占时退让 " + id);
        }
        assertTrue(up.contains("select 1, n.menu_id from sys_menu n where n.perms like 'kangaroo:staycomment:%'"), "只给角色 1");
        assertFalse(up.contains("select distinct rm.role_id"), "不跟着别的菜单带给运营角色");
        assertTrue(down.contains("@kg_allow_stay_comment_rollback"));
        assertTrue(down.contains("kg_stay_comment has data"));
        assertTrue(down.contains("later migrations"));
        assertEquals("sql/kangaroo/V65__stay_comment.sql", new StayCommentSchema(mock(KgJdbc.class), true).migration());
    }
}
