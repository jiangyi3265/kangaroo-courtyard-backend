package com.ruoyi.kangaroo.ext.core;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;

/** 换工类型字典：保存时的校验与归一（含值的格式）、类型名称（含已停用）、「其他」也包括没选类型的岗位（不连库） */
class KgVolunteerKindsTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final KgVolunteerKinds kinds = new KgVolunteerKinds();

    @BeforeEach
    void setUp()
    {
        ReflectionTestUtils.setField(kinds, "db", db);
        // 启用中的类型（status = '0'）：日常、摄影
        when(db.list(contains("status = '0'"), anyMap())).thenReturn(Arrays.asList(
                p("dictValue", "daily", "dictLabel", "日常换工", "remark", "前台、接待、打扫、陪伴客人"),
                p("dictValue", "photo", "dictLabel", "摄影换工", "remark", "摄影、拍照、修图")));
    }

    @Test
    void enabledListsValueLabelAndOneLineDescription()
    {
        List<Map<String, Object>> list = kinds.enabled();
        assertEquals(2, list.size());
        assertEquals("daily", list.get(0).get("value"));
        assertEquals("日常换工", list.get(0).get("label"));
        assertEquals("前台、接待、打扫、陪伴客人", list.get(0).get("desc"));
    }

    @Test
    void nonVolunteerJobsAlwaysStoreEmptyKind()
    {
        // 换工改成兼职时，表单里残留的类型不能跟过去
        assertEquals("", kinds.normalize("parttime", "photo", null));
        assertEquals("", kinds.normalize("online", "daily", "daily"));
        assertEquals("", kinds.normalize("fulltime", null, null));
    }

    @Test
    void legacyDictionaryLabelsUseNewWordingWithoutChangingValues()
    {
        when(db.list(anyString(), anyMap())).thenReturn(Collections.singletonList(
                p("dictValue", "photo", "dictLabel", "摄影义工", "remark", "义工拍摄")));
        Map<String, Object> option = kinds.enabled().get(0);
        assertEquals("photo", option.get("value"));
        assertEquals("摄影换工", option.get("label"));
        assertEquals("换工拍摄", option.get("desc"));
        assertEquals("摄影换工", kinds.label("photo"));
    }

    @Test
    void blankKindIsAllowedForVolunteerJobs()
    {
        assertEquals("", kinds.normalize("volunteer", null, null));
        assertEquals("", kinds.normalize("volunteer", "  ", null));
    }

    @Test
    void enabledKindIsAcceptedAndUnknownOrDisabledKindIsRefused()
    {
        assertEquals("photo", kinds.normalize("volunteer", " photo ", null));
        ServiceException e = assertThrows(ServiceException.class, () -> kinds.normalize("volunteer", "live", null));
        assertEquals(Integer.valueOf(422), e.getCode());
        assertTrue(e.getMessage().contains("换工类型"));
    }

    @Test
    void editingAnOldJobKeepsItsDisabledKind()
    {
        // live 已被运营停用，但这个岗位原来就是直播换工：改别的字段不能被它卡住
        assertEquals("live", kinds.normalize("volunteer", "live", "live"));
        // 换成另一个已停用的类型仍然不行
        assertThrows(ServiceException.class, () -> kinds.normalize("volunteer", "edit", "live"));
    }

    @Test
    void labelIncludesDisabledKindsAndIsCached()
    {
        when(db.list(argThat((String sql) -> sql != null && !sql.contains("status = '0'") && !sql.contains("in (:v)")), anyMap())).thenReturn(Arrays.asList(
                p("dictValue", "daily", "dictLabel", "日常换工"), p("dictValue", "live", "dictLabel", "直播换工")));
        assertEquals("直播换工", kinds.label("live"));
        assertEquals("日常换工", kinds.label("daily"));
        assertEquals("", kinds.label(""));
        assertEquals("", kinds.label(null));
        assertEquals("", kinds.label("unknown"));
        // 半分钟内只查一次库
        verify(db, times(1)).list(argThat((String sql) -> sql != null && !sql.contains("status = '0'") && !sql.contains("in (:v)")), anyMap());
    }

    @Test
    void labelFailureDoesNotBreakTheJobList()
    {
        when(db.list(argThat((String sql) -> sql != null && !sql.contains("status = '0'")), anyMap())).thenThrow(new RuntimeException("db down"));
        assertEquals("", kinds.label("daily"));
    }

    // ---------------------------------------------------------------- 类型值的格式（kg_job.work_kind 是 varchar(32)）

    private static String repeat(char c, int n)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++)
        {
            sb.append(c);
        }
        return sb.toString();
    }

    @Test
    void valueFormatIsLowercaseLettersDigitsUnderscoreDashUpTo32()
    {
        assertTrue(KgVolunteerKinds.validValue("daily"));
        assertTrue(KgVolunteerKinds.validValue("video_edit-2"));
        assertTrue(KgVolunteerKinds.validValue(repeat('a', 32)));
        assertFalse(KgVolunteerKinds.validValue(repeat('a', 33)));
        assertFalse(KgVolunteerKinds.validValue("Photo"));
        assertFalse(KgVolunteerKinds.validValue("摄影"));
        assertFalse(KgVolunteerKinds.validValue("a b"));
        assertFalse(KgVolunteerKinds.validValue("a'b"));
        assertFalse(KgVolunteerKinds.validValue(""));
        assertFalse(KgVolunteerKinds.validValue(null));
    }

    @Test
    void malformedKindIsRefusedWith422BeforeItReachesTheDatabase()
    {
        // 之前超过 32 位的值一路存到 kg_job.work_kind，数据库报「数据太长」变成系统错误
        for (String bad : new String[] { repeat('a', 40), "Photo", "摄影换工", "photo;drop" })
        {
            ServiceException e = assertThrows(ServiceException.class, () -> kinds.normalize("volunteer", bad, null), bad);
            assertEquals(Integer.valueOf(422), e.getCode(), bad);
            assertEquals("换工类型不合法", e.getMessage(), bad);
        }
        verify(db, never()).list(anyString(), anyMap());
    }

    @Test
    void tooLongDictionaryValueIsNotListedAndCannotBeSaved()
    {
        // 运营在字典里填了 40 位的值、中文值、大写值：选项里不出现，硬传进来也是 422，而不是数据库报错
        String tooLong = repeat('x', 40);
        when(db.list(contains("status = '0'"), anyMap())).thenReturn(Arrays.asList(
                p("dictValue", "daily", "dictLabel", "日常换工", "remark", ""),
                p("dictValue", tooLong, "dictLabel", "超长类型", "remark", ""),
                p("dictValue", "摄影", "dictLabel", "中文值", "remark", ""),
                p("dictValue", "Photo", "dictLabel", "大写值", "remark", ""),
                p("dictValue", "", "dictLabel", "空值", "remark", "")));
        List<Map<String, Object>> list = kinds.enabled();
        assertEquals(1, list.size());
        assertEquals("daily", list.get(0).get("value"));
        assertEquals(Integer.valueOf(422), assertThrows(ServiceException.class, () -> kinds.normalize("volunteer", tooLong, null)).getCode());
        assertEquals("daily", kinds.normalize("volunteer", "daily", null));
    }

    @Test
    void onlyOtherAlsoCoversVolunteerJobsWithoutAKind()
    {
        assertTrue(KgVolunteerKinds.includesUntyped(Collections.singletonList("other")));
        assertTrue(KgVolunteerKinds.includesUntyped(Arrays.asList("photo", "other")));
        assertFalse(KgVolunteerKinds.includesUntyped(Arrays.asList("photo", "craft")));
        assertFalse(KgVolunteerKinds.includesUntyped(Collections.<String>emptyList()));
        assertFalse(KgVolunteerKinds.includesUntyped(null));
    }
}
