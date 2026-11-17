package com.ruoyi.kangaroo.config.schema;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import com.ruoyi.kangaroo.ext.KgJdbc;

/** V53（2026-10-17 R3）：小院视频 / 房型视频 / 生活碎片 / 评价视频，缺列缺表拒绝启动、新表索引登记给 SchemaAssertion；迁移和回退脚本按约定写（不连库，实跑见 local_migrate_v53.py） */
class StayMediaSchemaTest
{
    private static KgJdbc db(long text, long review, long diary)
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.count(contains("column_default = '' and ("), anyMap())).thenReturn(text);
        when(db.count(contains("table_name = 'kg_review'"), anyMap())).thenReturn(review);
        when(db.count(contains("table_name = 'kg_stay_diary'"), anyMap())).thenReturn(diary);
        return db;
    }

    @Test
    void startsWhenEverythingIsPresent()
    {
        KgJdbc db = db(7L, 2L, 11L);
        assertDoesNotThrow(() -> new StayMediaSchema(db, true).check());
        String sql = StayMediaSchema.textSql();
        for (String c : Arrays.asList("(table_name = 'kg_stay' and column_name = 'intro_video' and character_maximum_length >= 255)",
                "(table_name = 'kg_stay' and column_name = 'diary_title' and character_maximum_length >= 32)",
                "(table_name = 'kg_stay_room_type' and column_name = 'video_url' and character_maximum_length >= 255)",
                "(table_name = 'kg_stay_room_type' and column_name = 'video_cover' and character_maximum_length >= 255)"))
        {
            assertTrue(sql.contains(c), c);
        }
        assertTrue(sql.contains("data_type = 'varchar' and is_nullable = 'NO' and column_default = ''"), "不可空、默认 ''：老版本程序新增房源 / 房型时落成「没传」");
        assertEquals(7, StayMediaSchema.TEXT_COLUMNS.size());
        assertTrue(StayMediaSchema.REVIEW_SQL.contains("is_nullable = 'YES' and character_maximum_length >= 600"));
    }

    @Test
    void missingPiecesRefuseStartupAndNameTheMigration()
    {
        for (long[] n : new long[][] { { 6, 2, 11 }, { 0, 2, 11 }, { 7, 1, 11 }, { 7, 2, 0 }, { 7, 2, 10 } })
        {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> new StayMediaSchema(db(n[0], n[1], n[2]), true).check());
            assertTrue(e.getMessage().contains("V53__stay_media.sql"), e.getMessage());
        }
    }

    @Test
    void disabledAssertionSkipsDatabase()
    {
        KgJdbc db = mock(KgJdbc.class);
        assertDoesNotThrow(() -> new StayMediaSchema(db, false).check());
        verifyNoInteractions(db);
    }

    @Test
    void registersTheDiaryTableIndexesAndTheMigration()
    {
        StayMediaSchema s = new StayMediaSchema(mock(KgJdbc.class), true);
        assertEquals(Collections.singletonMap("kg_stay_diary", Arrays.asList("PRIMARY", "idx_stay_diary_list")), s.requiredIndexes());
        assertEquals("sql/kangaroo/V53__stay_media.sql", s.migration());
        assertNotNull(StayMediaSchema.class.getAnnotation(org.springframework.stereotype.Component.class));
    }

    private static String sql(String name) throws Exception
    {
        Path p = Paths.get(System.getProperty("basedir", "."), "..", "sql", "kangaroo", name);
        assertTrue(Files.exists(p), p.toAbsolutePath().toString());
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    void migrationAddsColumnsTableAndMenusAfterV52() throws Exception
    {
        String m = sql("V53__stay_media.sql");
        assertFalse(m.contains("\r\n"), "迁移脚本统一 LF");
        assertTrue(m.contains("call kg_assert_env('52');"));
        for (String c : Arrays.asList("call kg_add_col_if_absent('kg_stay', 'intro_video', 'intro_video varchar(255) not null default ''''",
                "call kg_add_col_if_absent('kg_stay', 'intro_video_cover', 'intro_video_cover varchar(255) not null default ''''",
                "call kg_add_col_if_absent('kg_stay', 'diary_bg', 'diary_bg varchar(255) not null default ''''",
                "call kg_add_col_if_absent('kg_stay', 'diary_title', 'diary_title varchar(32) not null default ''''",
                "call kg_add_col_if_absent('kg_stay', 'diary_intro', 'diary_intro varchar(255) not null default ''''",
                "call kg_add_col_if_absent('kg_stay_room_type', 'video_url', 'video_url varchar(255) not null default ''''",
                "call kg_add_col_if_absent('kg_stay_room_type', 'video_cover', 'video_cover varchar(255) not null default ''''",
                "call kg_add_col_if_absent('kg_review', 'videos', 'videos varchar(600) null",
                "call kg_add_col_if_absent('kg_review', 'video_covers', 'video_covers varchar(600) null",
                "create table if not exists kg_stay_diary (", "key idx_stay_diary_list (stay_id, del_flag, shot_date)",
                "call kg_assert_same_collation('kg_stay_diary');", "'kangaroo:staydiary:list'", "'kangaroo:staydiary:remove'",
                "'kangaroo/ext/staydiary/index'", "values (53, 'stay_media', now(), '"))
        {
            assertTrue(m.contains(c), c);
        }
        assertFalse(m.toLowerCase().contains("update kg_stay ") || m.toLowerCase().contains("update kg_review"), "不改动任何已有数据");
        assertTrue(m.contains("case when exists (select 1 from sys_menu x where x.menu_id = 2295) then null else 2295 end"), "菜单号被占时让数据库编号，不悄悄漏掉菜单");
    }

    @Test
    void rollbackIsGuardedAndRefusesToDropStoredMedia() throws Exception
    {
        String r = sql("V53__rollback.sql");
        assertFalse(r.contains("\r\n"));
        assertTrue(r.contains("@kg_allow_stay_media_rollback"));
        assertTrue(r.contains("V53 rollback blocked by later migrations"));
        assertTrue(r.contains("@kg_allow_stay_media_loss"));
        assertTrue(r.indexOf("kg_stay_diary has entries") < r.indexOf("drop table if exists kg_stay_diary;"));
        for (String c : Arrays.asList("call kg_drop_col_if_present('kg_review', 'videos');", "call kg_drop_col_if_present('kg_stay_room_type', 'video_url');",
                "call kg_drop_col_if_present('kg_stay', 'intro_video');", "call kg_drop_col_if_present('kg_stay', 'diary_intro');",
                "delete from sys_menu where perms like 'kangaroo:staydiary:%';", "delete from kg_schema_version where version = 53;"))
        {
            assertTrue(r.contains(c), c);
        }
        assertTrue(r.lastIndexOf("drop procedure if exists kg_assert_env;") > r.indexOf("call kg_rollback_stay_media();"));
    }
}
