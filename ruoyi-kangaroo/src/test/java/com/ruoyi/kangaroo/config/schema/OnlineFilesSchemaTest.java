package com.ruoyi.kangaroo.config.schema;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ruoyi.kangaroo.ext.KgJdbc;

/** V41：库里没有附件表的索引、退回截图列，或退回说明列还是 varchar(200) 时拒绝启动；关掉断言的开发库不查库。 */
class OnlineFilesSchemaTest
{
    private static KgJdbc db(long images, long comment)
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.count(contains("column_name = 'review_images'"), anyMap())).thenReturn(images);
        when(db.count(contains("column_name = 'review_comment'"), anyMap())).thenReturn(comment);
        return db;
    }

    @Test
    void startsWhenBothSubmissionColumnsAreMigrated()
    {
        KgJdbc db = db(1L, 1L);
        assertDoesNotThrow(() -> new OnlineFilesSchema(db, true).check());
        verify(db).count(contains("data_type = 'varchar' and character_maximum_length >= 2000"), anyMap());
        verify(db).count(contains("data_type in ('text', 'mediumtext', 'longtext')"), anyMap());
    }

    @Test
    void missingScreenshotColumnRefusesStartupAndNamesMigration()
    {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new OnlineFilesSchema(db(0L, 1L), true).check());
        assertTrue(e.getMessage().contains("review_images") && e.getMessage().contains("V41__online_files.sql"), e.getMessage());
    }

    @Test
    void narrowReviewCommentRefusesStartup()
    {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new OnlineFilesSchema(db(1L, 0L), true).check());
        assertTrue(e.getMessage().contains("review_comment") && e.getMessage().contains("V41__online_files.sql"), e.getMessage());
    }

    @Test
    void disabledAssertionSkipsDatabase()
    {
        KgJdbc db = mock(KgJdbc.class);
        assertDoesNotThrow(() -> new OnlineFilesSchema(db, false).check());
        verifyNoInteractions(db);
    }

    @Test
    void registersAttachmentTableIndexesAndTheMigration()
    {
        OnlineFilesSchema s = new OnlineFilesSchema(mock(KgJdbc.class), true);
        Map<String, List<String>> idx = s.requiredIndexes();
        assertEquals(1, idx.size());
        assertEquals(Arrays.asList("PRIMARY", "uk_mfile_key", "idx_mfile_material", "idx_mfile_app", "idx_mfile_store"), idx.get("kg_online_material_file"));
        assertEquals("sql/kangaroo/V41__online_files.sql", s.migration());
    }
}
