package com.ruoyi.kangaroo.ext.core;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.ExtEntity;
import com.ruoyi.kangaroo.ext.KgJdbc;

/** 岗位年龄段 / 性格选项（V67）：给小程序的清单、后台「岗位要求选项」只能改这两个字典、文字规则（不连库） */
class KgJobRequirementOptionsTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final KgJobRequirementOptions opts = new KgJobRequirementOptions();

    @BeforeEach
    void setUp()
    {
        ReflectionTestUtils.setField(opts, "db", db);
    }

    @Test
    @SuppressWarnings("unchecked")
    void optionsListEnabledLabelsInOrderWithPersonalityLimit()
    {
        when(db.list(contains("status = '0'"), argThat(m -> m != null && KgJobRequirementOptions.DICT_AGE.equals(m.get("t"))))).thenReturn(Arrays.asList(
                p("dictLabel", "不限"), p("dictLabel", "18-30岁"), p("dictLabel", " 18-30岁 "), p("dictLabel", "")));
        when(db.list(contains("status = '0'"), argThat(m -> m != null && KgJobRequirementOptions.DICT_PERSONALITY.equals(m.get("t"))))).thenReturn(Arrays.asList(
                p("dictLabel", "活泼开朗"), p("dictLabel", "善于沟通")));
        Map<String, Object> m = opts.options();
        assertEquals(Arrays.asList("不限", "18-30岁"), m.get("ages"), "去空白、去重、空的不列");
        assertEquals(Arrays.asList("活泼开朗", "善于沟通"), m.get("personalities"));
        assertEquals(3, m.get("personalityMax"));
    }

    @Test
    void allDisabledMeansEmptyButDatabaseErrorFallsBackToDefaults()
    {
        when(db.list(anyString(), anyMap())).thenReturn(Collections.<Map<String, Object>>emptyList());
        assertEquals(Collections.emptyList(), opts.options().get("ages"), "运营全停用了就是空");
        when(db.list(anyString(), anyMap())).thenThrow(new RuntimeException("db down"));
        assertEquals(KgJobRequirementOptions.DEFAULT_AGES, opts.options().get("ages"));
        assertEquals(KgJobRequirementOptions.DEFAULT_PERSONALITIES, opts.options().get("personalities"));
    }

    @Test
    void adminEntityOnlyCoversTheTwoDictionaries()
    {
        List<ExtEntity> list = opts.entities();
        assertEquals(1, list.size());
        ExtEntity e = list.get(0);
        assertEquals("jobopt", e.key);
        assertEquals("sys_dict_data", e.table);
        assertEquals("kangaroo:jobopt", e.perm);
        assertEquals("dict_type in ('kg_job_age', 'kg_job_personality')", e.baseWhere);
        assertFalse(e.softDelete, "sys_dict_data 没有 del_flag");
        assertTrue(e.field("dictType").createOnly, "建好后不能改成别的字典");
        assertNotNull(e.validator);
        assertNotNull(e.beforeDelete);
    }

    private static Map<String, Object> cols(Object... kv)
    {
        return new HashMap<String, Object>(p(kv));
    }

    @Test
    void creatingCopiesLabelIntoValueAndRejectsOtherDictionaries()
    {
        Map<String, Object> c = cols("dict_type", "kg_job_personality", "dict_label", " 爱干净 ");
        opts.check(c, true);
        assertEquals("爱干净", c.get("dict_label"));
        assertEquals("爱干净", c.get("dict_value"), "岗位里存的就是文字");
        assertEquals("N", c.get("is_default"));
        assertEquals("0", c.get("status"), "新增没选状态：默认启用，否则存成 NULL 小程序看不到");
        assertEquals(0L, c.get("dict_sort"));
        ServiceException e = assertThrows(ServiceException.class, () -> opts.check(cols("dict_type", "sys_user_sex", "dict_label", "男"), true));
        assertEquals(Integer.valueOf(422), e.getCode());
    }

    @Test
    void labelsWithSeparatorsOrTooLongAreRejected()
    {
        for (String bad : new String[] { "活泼、开朗", "年龄:18", "开朗，大方", "两 个字", "一二三四五六七八九十一二三" })
        {
            assertThrows(ServiceException.class, () -> opts.check(cols("dict_type", "kg_job_personality", "dict_label", bad), true), bad);
        }
    }

    @Test
    void duplicateLabelInTheSameDictionaryIsRejected()
    {
        when(db.count(contains("dict_label = :l"), anyMap())).thenReturn(1L);
        ServiceException e = assertThrows(ServiceException.class, () -> opts.check(cols("dict_type", "kg_job_age", "dict_label", "18-30岁"), true));
        assertTrue(e.getMessage().contains("已经有了"));
    }

    @Test
    void editingMustTargetOneOfOurRowsAndExcludesItselfFromTheDuplicateCheck()
    {
        when(db.one(contains("dict_code = :id"), anyMap())).thenReturn(null);
        assertThrows(ServiceException.class, () -> opts.check(cols("_pk", 1L, "dict_label", "改个名"), false), "不是这两个字典的行不能改");

        when(db.one(contains("dict_code = :id"), anyMap())).thenReturn(p("dictType", "kg_job_age"));
        when(db.count(contains("dict_code <> :id"), anyMap())).thenReturn(0L);
        Map<String, Object> c = cols("_pk", 9L, "dict_label", "18-28岁", "status", "1");
        opts.check(c, false);
        assertEquals("18-28岁", c.get("dict_value"));
        verify(db).count(contains("dict_code <> :id"), argThat(m -> Long.valueOf(9L).equals(m.get("id")) && "kg_job_age".equals(m.get("t"))));

        // 只改状态（停用）：不碰文字
        Map<String, Object> s = cols("_pk", 9L, "status", "1");
        opts.check(s, false);
        assertFalse(s.containsKey("dict_value"));
    }

    @Test
    void publicEndpointServesTheOptionsAndIsOpenWithoutLogin() throws Exception
    {
        when(db.list(anyString(), anyMap())).thenReturn(Arrays.asList(p("dictLabel", "18-30岁")));
        com.ruoyi.kangaroo.controller.app.AppJobController c = new com.ruoyi.kangaroo.controller.app.AppJobController();
        assertEquals(Collections.emptyMap(), c.requirementOptions().get("data"), "没有这个组件（不连库的单测）时给空表，不报错");
        ReflectionTestUtils.setField(c, "requirementOptions", opts);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) c.requirementOptions().get("data");
        assertEquals(Arrays.asList("18-30岁"), data.get("ages"));
        java.lang.reflect.Method m = c.getClass().getMethod("requirementOptions");
        assertEquals("/requirement-options", m.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class).value()[0]);
        assertFalse(m.getAnnotation(com.ruoyi.kangaroo.security.AppAuth.class).required());
    }

    @Test
    void deletingOtherDictionaryRowsIsRefused()
    {
        when(db.count(contains("dict_code = :id"), anyMap())).thenReturn(0L);
        assertThrows(ServiceException.class, () -> opts.mustBeOurs(5L));
        when(db.count(contains("dict_code = :id"), anyMap())).thenReturn(1L);
        assertDoesNotThrow(() -> opts.mustBeOurs(6L));
    }
}
