package com.ruoyi.kangaroo.config.schema;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.ruoyi.kangaroo.ext.KgJdbc;

/** V63：账本或列不在时拒绝启动；迁移脚本的依赖和回滚不能写错。 */
class MerchantRolesSchemaTest
{
    @Test
    void startsWhenLedgerAndColumnsArePresent()
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.count(eq(MerchantRolesSchema.LEDGER_SQL), anyMap())).thenReturn(1L);
        when(db.count(eq(MerchantRolesSchema.COLUMNS_SQL), anyMap())).thenReturn((long) MerchantRolesSchema.COLUMNS);
        assertDoesNotThrow(() -> new MerchantRolesSchema(db, true).check());
    }

    @Test
    void refusesStartupAndNamesTheMigration()
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.count(eq(MerchantRolesSchema.LEDGER_SQL), anyMap())).thenReturn(0L);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new MerchantRolesSchema(db, true).check());
        assertTrue(e.getMessage().contains("V63__merchant_roles.sql"), e.getMessage());

        KgJdbc partial = mock(KgJdbc.class);
        when(partial.count(eq(MerchantRolesSchema.LEDGER_SQL), anyMap())).thenReturn(1L);
        when(partial.count(eq(MerchantRolesSchema.COLUMNS_SQL), anyMap())).thenReturn((long) MerchantRolesSchema.COLUMNS - 1);
        assertThrows(IllegalStateException.class, () -> new MerchantRolesSchema(partial, true).check());
    }

    @Test
    void columnListCoversTheStaffGrantSwitch() throws Exception
    {
        assertEquals(6, MerchantRolesSchema.COLUMNS);
        assertTrue(MerchantRolesSchema.COLUMNS_SQL.contains("table_name = 'kg_staff_grant' and column_name = 'also_service'"));
        String v = sql("V63__merchant_roles.sql");
        assertTrue(v.contains("call kg_add_col_if_absent('kg_staff_grant', 'also_service', 'also_service char(1) not null default ''0''"), "老 jar 插授权不带这一列，必须有默认值");
    }

    @Test
    void disabledAssertionSkipsDatabase()
    {
        KgJdbc db = mock(KgJdbc.class);
        assertDoesNotThrow(() -> new MerchantRolesSchema(db, false).check());
        verifyNoInteractions(db);
    }

    @Test
    void registersTheNewIndexes()
    {
        MerchantRolesSchema s = new MerchantRolesSchema(mock(KgJdbc.class), true);
        assertEquals("sql/kangaroo/V63__merchant_roles.sql", s.migration());
        assertEquals(Arrays.asList("uk_mreq_job", "idx_mreq_city_status", "idx_mreq_store"), s.requiredIndexes().get("kg_merchant_request"));
        assertEquals(Arrays.asList("uk_weekly_store_week_type"), s.requiredIndexes().get("kg_weekly_report"));
    }

    private static String sql(String name) throws Exception
    {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 4 && !Files.isDirectory(dir.resolve("sql/kangaroo")); i++)
        {
            dir = dir.getParent();
        }
        return new String(Files.readAllBytes(dir.resolve("sql/kangaroo").resolve(name)), StandardCharsets.UTF_8);
    }

    @Test
    void migrationDependsOn60And62NotOn61AndDoesNotTouchStoreImages() throws Exception
    {
        String v = sql("V63__merchant_roles.sql");
        assertTrue(v.contains("call kg_assert_env('60,62');"), "生产账本没有 V61");
        assertFalse(v.toLowerCase().contains("alter table kg_store"));
        assertFalse(v.contains("'kg_store',"), "kg_store 一列都不动（kg_store.images 生产没有）");
        assertTrue(v.contains("uk_weekly_store_week_type"));
        for (String id : Arrays.asList("3963", "3964", "3965")) assertTrue(v.contains("x.menu_id = " + id), id);
        for (String taken : Arrays.asList("3210", "3211", "3212", "3213", "3960", "3961", "3962")) assertFalse(v.contains("x.menu_id = " + taken), taken);
    }

    @Test
    void rollbackRevokesNewRolesBeforeAnythingElse() throws Exception
    {
        String r = sql("V63__rollback.sql");
        // 老代码把不认识的角色当「员工」，不撤销等于放大权限
        assertTrue(r.contains("role_code in ('merchant_service', 'city_lead')"));
        assertTrue(r.contains("granted_by = 'system:merchant_request'"));
        assertTrue(r.contains("uk_weekly_store_week (store_id, week_start)"), "唯一键恢复");
        assertTrue(r.indexOf("delete from kg_weekly_report") < r.indexOf("uk_weekly_store_week (store_id, week_start)"), "先删招募周报再恢复唯一键，否则同店同周两行建不了");
        assertTrue(r.contains("delete from kg_schema_version where version = 63"));
        assertTrue(r.contains("@kg_allow_merchant_roles_rollback"), "会删数据，必须明确放行");
        assertTrue(r.contains("call kg_drop_col_if_present('kg_staff_grant', 'also_service')"), "兼任开关一起拿掉");
    }

    @Test
    void ledgerChecksumIsTheTextBeforeTheMarkerInBothPlaces() throws Exception
    {
        String v = sql("V63__merchant_roles.sql");
        int marker = v.indexOf("-- @kg-ledger");
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(v.substring(0, marker).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format("%02x", b));
        String ledger = v.substring(marker);
        assertEquals(2, ledger.split(hex.toString(), -1).length - 1, "账本行两处都要是重算后的校验和：" + hex);
    }
}
