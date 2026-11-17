package com.ruoyi.kangaroo.ext.staff;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.ruoyi.kangaroo.ext.ExtEntity;
import com.ruoyi.kangaroo.ext.ExtField;

/** Actual entity metadata; no database or browser. */
class KgStaffOpsInsuranceMetadataTest
{
    private ExtEntity entity()
    {
        return new KgStaffOpsModule().entities().stream()
                .filter(e -> "insapply".equals(e.key)).findFirst().orElseThrow(AssertionError::new);
    }

    @Test
    void storedEffectiveMeansIssuedWithoutChangingReadOnlyOrPermissions()
    {
        ExtEntity entity = entity();
        ExtField status = entity.fields.stream().filter(f -> "status".equals(f.name))
                .findFirst().orElseThrow(AssertionError::new);
        assertNull(status.dict, "Historical global dictionary incorrectly says effective=已生效");
        assertEquals("pending:待登记保单,effective:已出单,rejected:已驳回", status.options);
        assertEquals("eq", status.search);
        assertEquals("kangaroo:insapply", entity.perm);
        assertFalse(entity.allowAdd); assertFalse(entity.allowEdit); assertFalse(entity.allowDelete);
    }

    @Test
    void policyRegistrationExplainsActualDatesAndRetainsDedicatedHandlePermission()
    {
        ExtEntity entity = entity();
        Map<String, Object> action = entity.actions.stream().filter(a -> "effect".equals(a.get("key")))
                .findFirst().orElseThrow(AssertionError::new);
        String confirm = (String) action.get("confirm");
        assertTrue(confirm.contains("真实保单"));
        assertTrue(confirm.contains("不等于保障已生效"));
        assertTrue(confirm.contains("起止日期"));
        assertFalse(confirm.contains("即生效"));
        assertEquals("kangaroo:insapply:handle", action.get("perm"));
        assertEquals("/kangaroo/staffops/insapply/{applyId}/effect", action.get("url"));
        assertEquals("put", action.get("method"));
        assertEquals(true, action.get("prompt"));
        assertEquals(Collections.singletonMap("status", Arrays.asList("pending")), action.get("showIf"));
        assertEquals("请填写保险公司出具的保单号", action.get("promptLabel"));
    }
}
