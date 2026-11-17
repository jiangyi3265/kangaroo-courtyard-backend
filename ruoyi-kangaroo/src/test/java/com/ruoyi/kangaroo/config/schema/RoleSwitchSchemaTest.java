package com.ruoyi.kangaroo.config.schema;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.RolePhase;

class RoleSwitchSchemaTest
{
    @AfterEach
    void back()
    {
        RolePhase.set(RolePhase.LEGACY);
    }

    private static KgJdbc db(long ledger, long tables, Map<String, Object> phase)
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.count(eq(RoleSwitchSchema.LEDGER_SQL), anyMap())).thenReturn(ledger);
        when(db.count(eq(RoleSwitchSchema.TABLES_SQL), anyMap())).thenReturn(tables);
        when(db.one(eq(RoleSwitchSchema.PHASE_SQL), anyMap())).thenReturn(phase);
        return db;
    }

    private static Map<String, Object> phase(String v)
    {
        return Collections.<String, Object>singletonMap("phase", v);
    }

    @Test
    void loadsThePhaseFromTheDatabase()
    {
        new RoleSwitchSchema(db(1, RoleSwitchSchema.TABLES, phase("new")), true).check();
        assertTrue(RolePhase.isNew(), "重启后要接着走新规则");
        new RoleSwitchSchema(db(1, RoleSwitchSchema.TABLES, phase("legacy")), true).check();
        assertFalse(RolePhase.isNew());
    }

    @Test
    void refusesToStartWhenThePhaseCannotBeRead()
    {
        assertTrue(assertThrows(IllegalStateException.class, () -> new RoleSwitchSchema(db(0, RoleSwitchSchema.TABLES, phase("legacy")), true).check()).getMessage().contains("V64__role_switch.sql"));
        assertThrows(IllegalStateException.class, () -> new RoleSwitchSchema(db(1, 2, phase("legacy")), true).check());
        assertThrows(IllegalStateException.class, () -> new RoleSwitchSchema(db(1, RoleSwitchSchema.TABLES, null), true).check(), "没有状态行");
        assertThrows(IllegalStateException.class, () -> new RoleSwitchSchema(db(1, RoleSwitchSchema.TABLES, phase("half")), true).check(), "认不出的阶段");
    }

    @Test
    void devDatabaseWithoutAssertionsRunsLegacy()
    {
        KgJdbc broken = mock(KgJdbc.class);
        when(broken.one(anyString(), anyMap())).thenThrow(new RuntimeException("no table"));
        RolePhase.set(RolePhase.NEW);
        new RoleSwitchSchema(broken, false).check();
        assertFalse(RolePhase.isNew());
    }

    @Test
    void migrationNeverTouchesGrantsOrDictionaryAndRollbackGuardsThePhase() throws Exception
    {
        String up = new String(Files.readAllBytes(Paths.get("../sql/kangaroo/V64__role_switch.sql")), StandardCharsets.UTF_8);
        String down = new String(Files.readAllBytes(Paths.get("../sql/kangaroo/V64__rollback.sql")), StandardCharsets.UTF_8);
        assertFalse(up.contains("update kg_staff_grant") || up.contains("sys_dict_data"), "V64 不碰 kg_staff_grant 和字典");
        assertTrue(up.contains("call kg_assert_env('60,62,63')"), "继续不依赖 V61");
        assertTrue(up.contains("when exists (select 1 from sys_menu x where x.menu_id = 3970) then null"), "编号被占时退让");
        assertTrue(down.contains("@kg_allow_role_switch_rollback"));
        assertTrue(down.contains("phase <> 'legacy'"));
        assertTrue(down.contains("from kg_role_switch_backup"));
        assertEquals("sql/kangaroo/V64__role_switch.sql", new RoleSwitchSchema(mock(KgJdbc.class), true).migration());
    }
}
