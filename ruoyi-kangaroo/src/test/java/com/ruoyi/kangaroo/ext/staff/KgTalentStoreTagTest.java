package com.ruoyi.kangaroo.ext.staff;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.AppLoginUser;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 报名详情「＋技能标签」（V35）：门店标签按门店存、去重、限 8 个 / 12 字，并且算进人才库的标签。 */
class KgTalentStoreTagTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final KgTalentService service = new KgTalentService();
    private final StaffSupport.Ctx ctx = new StaffSupport.Ctx();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "support", mock(StaffSupport.class));
        ctx.storeId = 102L; ctx.role = "manager";
        ctx.me = new AppLoginUser(); ctx.me.setUserId(1L);
    }

    private void existing(String tags)
    {
        when(db.one(contains("select store_tags from kg_talent_mark"), anyMap())).thenReturn(tags == null ? null : p("storeTags", tags));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> savedParams()
    {
        ArgumentCaptor<Map<String, Object>> ps = ArgumentCaptor.forClass(Map.class);
        verify(db).update(contains("insert into kg_talent_mark (store_id, app_user_id, store_tags"), ps.capture());
        return ps.getValue();
    }

    @Test
    void addAppendsTrimmedTagForThisStoreOnly()
    {
        existing("会拍照");
        assertEquals(Arrays.asList("会拍照", "咖啡师"), service.editStoreTag(ctx, 7L, " 咖啡师 ", true));
        Map<String, Object> ps = savedParams();
        assertEquals("会拍照,咖啡师", ps.get("t"));
        assertEquals(102L, ps.get("s"));
        assertEquals(7L, ps.get("u"));
    }

    @Test
    void duplicateAddIsANoOp()
    {
        existing("咖啡师");
        assertEquals(Collections.singletonList("咖啡师"), service.editStoreTag(ctx, 7L, "咖啡师", true));
        verify(db, never()).update(anyString(), anyMap());
    }

    @Test
    void commasCannotSmuggleExtraTags()
    {
        existing(null);
        assertEquals(Collections.singletonList("咖啡师拉花"), service.editStoreTag(ctx, 7L, "咖啡师,拉花", true));
    }

    @Test
    void limitsAreEnforced()
    {
        existing("a1,a2,a3,a4,a5,a6,a7,a8");
        assertEquals(Integer.valueOf(422), assertThrows(ServiceException.class, () -> service.editStoreTag(ctx, 7L, "第九个", true)).getCode());
        assertThrows(ServiceException.class, () -> service.editStoreTag(ctx, 7L, "一二三四五六七八九十十一十二", true));
        assertThrows(ServiceException.class, () -> service.editStoreTag(ctx, 7L, "  ", true));
    }

    @Test
    void removingLastTagClearsTheColumn()
    {
        existing("咖啡师");
        assertEquals(Collections.emptyList(), service.editStoreTag(ctx, 7L, "咖啡师", false));
        assertNull(savedParams().get("t"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void storeTagsCountInTalentPoolTags()
    {
        when(db.list(contains("from kg_app_user u"), anyMap())).thenReturn(Collections.singletonList(
                p("userId", 7L, "name", "张三", "skillText", "咖啡", "storeTags", "会拍照,咖啡师")));
        when(db.list(contains("from kg_resume_exp"), anyMap())).thenReturn(Collections.emptyList());
        List<Map<String, Object>> tags = (List<Map<String, Object>>) service.tags(ctx).get("tags");
        List<Object> names = new java.util.ArrayList<Object>();
        for (Map<String, Object> t : tags) names.add(t.get("name"));
        assertTrue(names.contains("会拍照"), names.toString());
        assertTrue(names.contains("咖啡师"), names.toString());
    }
}
