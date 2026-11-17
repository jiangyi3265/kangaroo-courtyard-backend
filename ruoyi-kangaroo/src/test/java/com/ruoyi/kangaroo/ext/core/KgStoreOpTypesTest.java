package com.ruoyi.kangaroo.ext.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;

/** 门店来源类型单独改（2026-11-03 主人：来源类型加「合作」；不经过 V61 挡住的门店服务，不连库） */
class KgStoreOpTypesTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final KgStoreOpTypes service = new KgStoreOpTypes();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
    }

    private void store(String opType)
    {
        Map<String, Object> s = new HashMap<String, Object>();
        s.put("storeId", 102L);
        s.put("storeName", "测试门店");
        s.put("opType", opType);
        when(db.one(contains("from kg_store"), anyMap())).thenReturn(s);
    }

    @Test
    void acceptsTheFourCodesOnly()
    {
        assertEquals("direct", KgStoreOpTypes.normalize(" direct "));
        assertEquals("franchise", KgStoreOpTypes.normalize("franchise"));
        assertEquals("managed", KgStoreOpTypes.normalize("managed"));
        assertEquals("partner", KgStoreOpTypes.normalize("partner"));
        for (Object bad : new Object[] { null, "", "合作", "PARTNER", "other" })
        {
            ServiceException e = assertThrows(ServiceException.class, () -> KgStoreOpTypes.normalize(bad));
            assertEquals("来源类型只能是 直营 / 加盟 / 代运营 / 合作", e.getMessage());
        }
    }

    @Test
    void setsPartnerTouchingOnlyOpTypeColumns()
    {
        store("direct");
        Map<String, Object> out = service.set(102L, "partner", "admin");
        assertEquals("partner", out.get("opType"));
        assertEquals("测试门店", out.get("storeName"));
        assertEquals(Boolean.TRUE, out.get("changed"));
        verify(db).one(contains("del_flag = '0'"), argThat(m -> Long.valueOf(102L).equals(m.get("id"))));
        verify(db).update(argThat(sql -> sql.startsWith("update kg_store set op_type = :op, update_by = :by, update_time = now()")
                && sql.contains("where store_id = :id and del_flag = '0'") && !sql.contains("images")),
                argThat(m -> "partner".equals(m.get("op")) && "admin".equals(m.get("by")) && Long.valueOf(102L).equals(m.get("id"))));
        // 查的列里也不能有 images（线上没有这一列）
        assertFalse(KgStoreOpTypes.FIND_SQL.contains("images"));
    }

    @Test
    void sameTypeDoesNotWrite()
    {
        store("partner");
        Map<String, Object> out = service.set(102L, "partner", "admin");
        assertEquals(Boolean.FALSE, out.get("changed"));
        verify(db, never()).update(contains("update kg_store"), anyMap());
    }

    @Test
    void rejectsMissingStoreAndBadInput()
    {
        when(db.one(contains("from kg_store"), anyMap())).thenReturn(null);
        assertEquals("门店不存在或已删除", assertThrows(ServiceException.class, () -> service.set(9L, "partner", "admin")).getMessage());
        assertEquals("门店ID不能为空", assertThrows(ServiceException.class, () -> service.set(null, "partner", "admin")).getMessage());
        // 代码不对时连库都不查
        store("direct");
        assertThrows(ServiceException.class, () -> service.set(102L, "vip", "admin"));
        verify(db, never()).update(contains("update kg_store"), anyMap());
    }
}
