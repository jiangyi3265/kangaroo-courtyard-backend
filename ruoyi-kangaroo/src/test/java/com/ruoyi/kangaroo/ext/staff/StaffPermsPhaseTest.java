package com.ruoyi.kangaroo.ext.staff;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ruoyi.kangaroo.enums.StaffRole;

/**
 * 角色两阶段：legacy 矩阵必须和线上基线逐格相同。GOLDEN 是从 artifacts/release-20261030-merchant-roles/backend/ruoyi-admin.jar（1ceb7d44399b）里的 StaffPerms
 * 跑出来的，不是从源码抄的；所有角色（含未知角色）、所有项目、所有动作都比。收尾包删 legacy 时才删这份。
 */
class StaffPermsPhaseTest
{
    /** 角色|项目|查看,编辑,审核,支付,导出 */
    private static final String[] GOLDEN = {
        "manager|rooms|no,no,no,no,no",
        "manager|jobs|yes,yes,yes,no,yes",
        "manager|online|no,no,no,no,no",
        "manager|intake|yes,yes,yes,no,no",
        "manager|dispatch|yes,yes,yes,no,yes",
        "manager|activities|yes,yes,yes,no,yes",
        "manager|reviews|yes,yes,yes,no,yes",
        "manager|insurance|yes,yes,yes,yes,yes",
        "manager|finance|yes,yes,yes,yes,yes",
        "manager|dashboard|yes,no,no,no,yes",
        "keeper|rooms|yes,yes,yes,no,no",
        "keeper|jobs|yes,yes,yes,no,no",
        "keeper|online|yes,yes,yes,no,no",
        "keeper|intake|no,no,no,no,no",
        "keeper|dispatch|yes,yes,yes,no,no",
        "keeper|activities|yes,yes,yes,no,no",
        "keeper|reviews|yes,yes,yes,no,no",
        "keeper|insurance|yes,yes,no,no,no",
        "keeper|finance|part,no,no,no,no",
        "keeper|dashboard|yes,no,no,no,no",
        "staff|rooms|no,no,no,no,no",
        "staff|jobs|yes,yes,yes,yes,yes",
        "staff|online|no,no,no,no,no",
        "staff|intake|no,no,no,no,no",
        "staff|dispatch|yes,yes,yes,yes,yes",
        "staff|activities|yes,yes,yes,yes,yes",
        "staff|reviews|yes,yes,yes,yes,yes",
        "staff|insurance|yes,yes,yes,yes,yes",
        "staff|finance|no,no,no,no,no",
        "staff|dashboard|yes,yes,yes,yes,yes",
        "online_staff|rooms|no,no,no,no,no",
        "online_staff|jobs|no,no,no,no,no",
        "online_staff|online|yes,yes,yes,no,no",
        "online_staff|intake|no,no,no,no,no",
        "online_staff|dispatch|no,no,no,no,no",
        "online_staff|activities|no,no,no,no,no",
        "online_staff|reviews|no,no,no,no,no",
        "online_staff|insurance|no,no,no,no,no",
        "online_staff|finance|no,no,no,no,no",
        "online_staff|dashboard|no,no,no,no,no",
        "merchant|rooms|yes,yes,no,no,no",
        "merchant|jobs|yes,yes,no,no,no",
        "merchant|online|yes,yes,yes,no,no",
        "merchant|intake|no,no,no,no,no",
        "merchant|dispatch|no,no,no,no,no",
        "merchant|activities|yes,yes,no,no,no",
        "merchant|reviews|yes,yes,no,no,no",
        "merchant|insurance|no,no,no,no,no",
        "merchant|finance|no,no,no,no,no",
        "merchant|dashboard|no,no,no,no,no",
        "merchant_service|rooms|no,no,no,no,no",
        "merchant_service|jobs|no,no,no,no,no",
        "merchant_service|online|yes,yes,yes,no,no",
        "merchant_service|intake|yes,yes,no,no,no",
        "merchant_service|dispatch|no,no,no,no,no",
        "merchant_service|activities|no,no,no,no,no",
        "merchant_service|reviews|no,no,no,no,no",
        "merchant_service|insurance|no,no,no,no,no",
        "merchant_service|finance|no,no,no,no,no",
        "merchant_service|dashboard|no,no,no,no,no",
        "city_lead|rooms|no,no,no,no,no",
        "city_lead|jobs|no,no,no,no,no",
        "city_lead|online|no,no,no,no,no",
        "city_lead|intake|yes,no,no,no,no",
        "city_lead|dispatch|no,no,no,no,no",
        "city_lead|activities|no,no,no,no,no",
        "city_lead|reviews|no,no,no,no,no",
        "city_lead|insurance|no,no,no,no,no",
        "city_lead|finance|no,no,no,no,no",
        "city_lead|dashboard|no,no,no,no,no",
        "nobody|rooms|no,no,no,no,no",
        "nobody|jobs|no,no,no,no,no",
        "nobody|online|no,no,no,no,no",
        "nobody|intake|no,no,no,no,no",
        "nobody|dispatch|no,no,no,no,no",
        "nobody|activities|no,no,no,no,no",
        "nobody|reviews|no,no,no,no,no",
        "nobody|insurance|no,no,no,no,no",
        "nobody|finance|no,no,no,no,no",
        "nobody|dashboard|no,no,no,no,no"
    };

    private static final List<String> ITEMS = Arrays.asList("rooms", "jobs", "online", "intake", "dispatch", "activities", "reviews", "insurance", "finance", "dashboard");
    private static final List<String> ACTIONS = Arrays.asList(StaffPerms.VIEW, StaffPerms.EDIT, StaffPerms.AUDIT, StaffPerms.PAY, StaffPerms.EXPORT);

    @AfterEach
    void back()
    {
        RolePhase.set(RolePhase.LEGACY);
    }

    private static String cell(String role, String item)
    {
        List<String> v = new ArrayList<String>();
        for (String a : ACTIONS)
        {
            v.add(StaffPerms.value(role, item, a));
        }
        return String.join(",", v);
    }

    @Test
    void legacyMatrixIsCellByCellTheBaselineJar()
    {
        assertFalse(RolePhase.isNew(), "默认就是 legacy：读不到库的场景也不能放大权限");
        assertEquals(ITEMS, new ArrayList<String>(StaffPerms.ITEMS.keySet()));
        assertEquals(ACTIONS, new ArrayList<String>(StaffPerms.ACTIONS.keySet()));
        for (String line : GOLDEN)
        {
            String[] p = line.split("\\|");
            assertEquals(p[2], cell(p[0], p[1]), p[0] + " · " + p[1]);
        }
        assertEquals(80, GOLDEN.length);
        assertEquals(Arrays.asList("merchant", "online_staff", "merchant_service", "city_lead", "staff", "keeper", "manager"), StaffPerms.roles());
    }

    @Test
    void newMatrixDiffersFromGoldenOnlyInTheCellsTheOwnerMoved()
    {
        RolePhase.set(RolePhase.NEW);
        List<String> diffs = new ArrayList<String>();
        for (String line : GOLDEN)
        {
            String[] p = line.split("\\|");
            String now = cell(p[0], p[1]);
            if (!p[2].equals(now))
            {
                diffs.add(p[0] + "|" + p[1] + "|" + now);
            }
        }
        java.util.Collections.sort(diffs);
        List<String> expect = new ArrayList<String>();
        // 换工主理人去掉财务和保险·支付
        // 城市负责人（2026-11-01）：四个二级角色的并集
        expect.add("city_lead|rooms|yes,yes,yes,no,no");
        expect.add("city_lead|jobs|yes,yes,yes,no,yes");
        expect.add("city_lead|online|yes,yes,yes,no,no");
        expect.add("city_lead|intake|yes,yes,yes,no,no");
        expect.add("city_lead|dispatch|yes,yes,yes,no,yes");
        expect.add("city_lead|activities|yes,yes,yes,yes,yes");
        expect.add("city_lead|reviews|yes,yes,yes,no,yes");
        expect.add("city_lead|insurance|yes,yes,yes,yes,yes");
        expect.add("city_lead|finance|yes,yes,yes,yes,yes");
        expect.add("city_lead|dashboard|yes,no,no,no,yes");
        expect.add("manager|finance|no,no,no,no,no");
        expect.add("manager|insurance|yes,yes,yes,no,yes");
        // staff 没有这一行：九格全 no（online、finance 本来就是 no）
        for (String item : Arrays.asList("activities", "dashboard", "dispatch", "insurance", "jobs", "reviews"))
        {
            expect.add("staff|" + item + "|no,no,no,no,no");
        }
        java.util.Collections.sort(expect);
        assertEquals(expect, diffs);
        assertEquals(Arrays.asList("city_lead", "keeper", "manager", "online_staff", "merchant_service", "merchant"), StaffPerms.roles(), "后台 6 项，没有员工");
    }

    @Test
    void newCityLeadHasEverythingAnyLevelTwoRoleHas()
    {
        RolePhase.set(RolePhase.NEW);
        for (String role : Arrays.asList(StaffRole.KEEPER, StaffRole.MANAGER, StaffRole.ONLINE_STAFF, StaffRole.MERCHANT_SERVICE))
        {
            for (String item : ITEMS)
            {
                for (String a : ACTIONS)
                {
                    assertTrue(!StaffPerms.allows(role, item, a) || StaffPerms.allows(StaffRole.CITY_LEAD, item, a), role + " 有 " + item + " " + a);
                }
            }
        }
    }

    @Test
    void newManagerHasNoMoney()
    {
        RolePhase.set(RolePhase.NEW);
        for (String item : Arrays.asList("jobs", "dispatch", "activities", "reviews", "insurance", "dashboard", "intake"))
        {
            assertFalse(StaffPerms.allows(StaffRole.MANAGER, item, StaffPerms.PAY), item);
        }
        assertFalse(StaffPerms.allows(StaffRole.MANAGER, "finance", StaffPerms.VIEW));
        assertTrue(StaffPerms.allows(StaffRole.MANAGER, "intake", StaffPerms.AUDIT), "招募、核实归换工主理人");
        assertTrue(StaffPerms.allows(StaffRole.MANAGER, "insurance", StaffPerms.AUDIT), "登记保单号归换工主理人");
        assertFalse(StaffPerms.canRegisterOnlinePayout(StaffRole.MANAGER), "线上付款不归换工主理人");
        assertTrue(StaffPerms.canRegisterOnlinePayout(StaffRole.MERCHANT));
        assertTrue(StaffPerms.canRegisterOnlinePayout(StaffRole.MERCHANT_SERVICE));
    }

    @Test
    void unknownRoleIsNothingInBothPhases()
    {
        for (String phase : Arrays.asList(RolePhase.LEGACY, RolePhase.NEW))
        {
            RolePhase.set(phase);
            for (String item : ITEMS)
            {
                assertEquals("no,no,no,no,no", cell("nobody", item));
            }
        }
    }

    @Test
    void phaseRefusesAnUnknownValue()
    {
        assertThrows(IllegalStateException.class, () -> RolePhase.set("maybe"));
        assertFalse(RolePhase.isNew());
    }
}
