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

/** V70（2026-11-03 房型 7 天价、来源类型「合作」、设施「地暖」）：缺列 / 缺账本拒绝启动；迁移与回退脚本按约定写（不连库） */
class StayWeekPriceSchemaTest
{
    private static KgJdbc db(long column, long ledger)
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.count(contains("column_name = 'week_price_fen'"), anyMap())).thenReturn(column);
        when(db.count(contains("from kg_schema_version where version = 70 and name = 'stay_week_price_options'"), anyMap())).thenReturn(ledger);
        return db;
    }

    @Test
    void startsWhenColumnAndLedgerExist()
    {
        assertDoesNotThrow(() -> new StayWeekPriceSchema(db(1L, 1L), true).check());
    }

    @Test
    void missingColumnOrLedgerRefusesStartupAndNamesTheMigration()
    {
        IllegalStateException a = assertThrows(IllegalStateException.class, () -> new StayWeekPriceSchema(db(0L, 1L), true).check());
        assertTrue(a.getMessage().contains("V70__stay_week_price_options.sql"), a.getMessage());
        IllegalStateException b = assertThrows(IllegalStateException.class, () -> new StayWeekPriceSchema(db(1L, 0L), true).check());
        assertTrue(b.getMessage().contains("V70__stay_week_price_options.sql"), b.getMessage());
    }

    @Test
    void disabledAssertionSkipsDatabase()
    {
        KgJdbc db = mock(KgJdbc.class);
        assertDoesNotThrow(() -> new StayWeekPriceSchema(db, false).check());
        verifyNoInteractions(db);
        assertTrue(new StayWeekPriceSchema(db, true).requiredIndexes().isEmpty());
        assertNotNull(StayWeekPriceSchema.class.getAnnotation(org.springframework.stereotype.Component.class));
    }

    private static String sql(String name) throws Exception
    {
        Path p = Paths.get(System.getProperty("basedir", "."), "..", "sql", "kangaroo", name);
        assertTrue(Files.exists(p), p.toAbsolutePath().toString());
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    void migrationAddsPartnerFloorHeatAndTheWeekPriceColumn() throws Exception
    {
        String m = sql("V70__stay_week_price_options.sql");
        assertFalse(m.contains("\r\n"), "迁移脚本统一 LF");
        assertTrue(m.contains("select 4, '合作', 'partner', 'kg_store_op_type'"));
        assertTrue(m.contains("from dual where not exists (select 1 from sys_dict_data where dict_type = 'kg_store_op_type' and dict_value = 'partner');"), "可重复执行");
        // 主人要的顺序：直营、加盟、代运营、合作
        String[] order = { "1 where dict_type = 'kg_store_op_type' and dict_value = 'direct'", "2 where dict_type = 'kg_store_op_type' and dict_value = 'franchise'",
                "3 where dict_type = 'kg_store_op_type' and dict_value = 'managed'", "4 where dict_type = 'kg_store_op_type' and dict_value = 'partner'" };
        for (String o : order)
        {
            assertTrue(m.contains("update sys_dict_data set dict_sort = " + o + ";"), o);
        }
        assertTrue(m.contains("select 10, '地暖', 'floorheat', 'kg_stay_facility'"));
        assertTrue(m.contains("call kg_add_col_if_absent('kg_stay_room_type', 'week_price_fen', 'week_price_fen varchar(80) null default null"));
        // 只看正文（头尾标准片段里有 drop column 的存储过程定义）
        String lower = m.substring(m.indexOf("-- @kg-header-end"), m.indexOf("-- @kg-footer-begin")).toLowerCase();
        assertFalse(lower.contains("delete from") || lower.contains("update kg_") || lower.contains("drop column"), "不删数据、不改业务表数据");
        assertTrue(m.contains("values (70, 'stay_week_price_options', now(), '"));
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
    void rollbackIsGuardedAndRefusesWhenPartnerOrWeekPricesAreInUse() throws Exception
    {
        String r = sql("V70__rollback.sql");
        assertFalse(r.contains("\r\n"));
        assertTrue(r.contains("@kg_allow_stay_week_price_options_rollback"));
        assertTrue(r.contains("V70 rollback blocked by later migrations"));
        assertTrue(r.contains("V70 rollback blocked: stores still use op_type partner"));
        assertTrue(r.contains("@kg_v70_drop_week_prices"));
        assertTrue(r.contains("call kg_drop_col_if_present('kg_stay_room_type', 'week_price_fen');"));
        assertTrue(r.contains("delete from sys_dict_data where dict_type = 'kg_store_op_type' and dict_value = 'partner';"));
        assertTrue(r.contains("delete from sys_dict_data where dict_type = 'kg_stay_facility' and dict_value = 'floorheat';"));
        assertTrue(r.contains("delete from kg_schema_version where version = 70;"));
        assertFalse(r.contains("insert into kg_schema_version"));
    }
}
