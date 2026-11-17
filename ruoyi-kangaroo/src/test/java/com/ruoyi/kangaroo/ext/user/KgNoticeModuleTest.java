package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.ExtEntity;
import com.ruoyi.kangaroo.ext.KgJdbc;

/** 后台公告管理：改标题 / 正文自动升版本、固定标识的格式与唯一、页面引用的标识不能改、有标识的不能删（不连库） */
class KgNoticeModuleTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final KgNoticeModule module = new KgNoticeModule();

    @BeforeEach
    void setUp()
    {
        ReflectionTestUtils.setField(module, "db", db);
        when(db.one(contains("select title, content, notice_key, version from kg_app_notice"), anyMap()))
                .thenReturn(p("title", "线上兼职须知", "content", "旧正文", "noticeKey", "online-guide", "version", 3L));
    }

    private static Map<String, Object> cols(Object... kv)
    {
        return new HashMap<String, Object>(p(kv));
    }

    @Test
    void entityIsRegisteredWithItsOwnPermissionPrefix()
    {
        ExtEntity e = module.entities().get(0);
        assertEquals("appnotice", e.key);
        assertEquals("kg_app_notice", e.table);
        assertEquals("kangaroo:appnotice", e.perm);
        assertFalse(e.field("version").editable);
        assertTrue(e.field("content").required);
    }

    @Test
    void newNoticeStartsAtVersionOne()
    {
        Map<String, Object> c = cols("title", "新公告", "content", "正文", "notice_key", null);
        module.check(c, true);
        assertEquals(1L, c.get("version"));
        assertNull(c.get("notice_key"));
    }

    @Test
    void editingTitleOrContentBumpsVersion()
    {
        Map<String, Object> c = cols("_pk", 9L, "title", "线上兼职须知", "content", "新正文", "notice_key", "online-guide");
        module.check(c, false);
        assertEquals(4L, c.get("version"));
        c = cols("_pk", 9L, "title", "线上兼职须知（新版）", "content", "旧正文", "notice_key", "online-guide");
        module.check(c, false);
        assertEquals(4L, c.get("version"));
    }

    @Test
    void changingOnlyStatusOrSortKeepsVersion()
    {
        Map<String, Object> c = cols("_pk", 9L, "title", "线上兼职须知", "content", " 旧正文 ", "notice_key", "online-guide", "status", "1", "sort", 5L);
        module.check(c, false);
        assertFalse(c.containsKey("version"));
    }

    @Test
    void pageReferencedKeyCannotBeChangedOrCleared()
    {
        assertEquals(Integer.valueOf(422), assertThrows(ServiceException.class,
                () -> module.check(cols("_pk", 9L, "title", "线上兼职须知", "content", "旧正文", "notice_key", "guide-2"), false)).getCode());
        assertEquals(Integer.valueOf(422), assertThrows(ServiceException.class,
                () -> module.check(cols("_pk", 9L, "title", "线上兼职须知", "content", "旧正文", "notice_key", null), false)).getCode());
    }

    @Test
    void keyMustBeSimpleAndUnique()
    {
        assertEquals(Integer.valueOf(422), assertThrows(ServiceException.class,
                () -> module.check(cols("title", "a", "content", "b", "notice_key", "中文标识"), true)).getCode());
        assertEquals(Integer.valueOf(422), assertThrows(ServiceException.class,
                () -> module.check(cols("title", "a", "content", "b", "notice_key", "popup"), true)).getCode());
        when(db.count(contains("notice_key = :k"), anyMap())).thenReturn(1L);
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class,
                () -> module.check(cols("title", "a", "content", "b", "notice_key", "spring-sale"), true)).getCode());
        when(db.count(contains("notice_key = :k"), anyMap())).thenReturn(0L);
        Map<String, Object> ok = cols("title", "a", "content", "b", "notice_key", " spring-sale ");
        module.check(ok, true);
        assertEquals("spring-sale", ok.get("notice_key"));
    }

    @Test
    void endMustBeAfterStart()
    {
        LocalDateTime t = LocalDateTime.of(2026, 10, 10, 9, 0);
        assertEquals(Integer.valueOf(422), assertThrows(ServiceException.class,
                () -> module.check(cols("title", "a", "content", "b", "start_time", t, "end_time", t), true)).getCode());
    }

    @Test
    void missingRowOnEditIs404()
    {
        when(db.one(contains("select title, content, notice_key, version from kg_app_notice"), anyMap())).thenReturn(null);
        assertEquals(Integer.valueOf(404), assertThrows(ServiceException.class,
                () -> module.check(cols("_pk", 99L, "title", "a"), false)).getCode());
    }

    @Test
    void noticesWithAKeyCannotBeDeleted()
    {
        when(db.one(contains("select notice_key from kg_app_notice"), anyMap())).thenReturn(p("noticeKey", "online-guide"));
        ServiceException e = assertThrows(ServiceException.class, () -> module.checkDelete(9L));
        assertEquals(Integer.valueOf(409), e.getCode());
        assertTrue(e.getMessage().contains("下线"));
        when(db.one(contains("select notice_key from kg_app_notice"), anyMap())).thenReturn(p("noticeKey", null));
        assertDoesNotThrow(() -> module.checkDelete(10L));
    }
}
