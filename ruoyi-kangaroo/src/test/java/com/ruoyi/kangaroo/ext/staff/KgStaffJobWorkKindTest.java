package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.core.KgVolunteerKinds;
import com.ruoyi.kangaroo.security.AppLoginUser;

/** 员工端发布岗位的换工类型：存进 work_kind、只对换工岗位生效、类型不对就拦下；生成描述用类型名（不连库） */
class KgStaffJobWorkKindTest extends com.ruoyi.kangaroo.testsupport.WithJobPhoto
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final KgVolunteerKinds kinds = mock(KgVolunteerKinds.class);
    private final KgStaffJobService service = new KgStaffJobService();
    private final StaffSupport.Ctx ctx = new StaffSupport.Ctx();
    private final AtomicReference<Map<String, Object>> saved = new AtomicReference<>();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "support", mock(StaffSupport.class));
        ReflectionTestUtils.setField(service, "volunteerKinds", kinds);
        ctx.storeId = 102L; ctx.me = new AppLoginUser(); ctx.me.setUserId(9001L);
        when(db.must(anyString(), anyMap(), anyString())).thenReturn(p("storeName", "小院", "city", "平潭", "address", "海边"));
        when(db.insert(eq("kg_job"), anyMap())).thenAnswer(inv -> { saved.set(inv.getArgument(1)); return 99L; });
        when(kinds.normalize(anyString(), anyString(), any())).thenAnswer(inv -> {
            String type = inv.getArgument(0), kind = inv.getArgument(1);
            if (!"volunteer".equals(type) || kind.isEmpty()) return "";
            if ("photo".equals(kind) || "other".equals(kind)) return kind;
            throw new ServiceException("换工类型不存在或已停用，请重新选择", 422);
        });
        when(kinds.label("photo")).thenReturn("摄影换工");
        when(kinds.label("craft")).thenReturn("手作教学");
        when(kinds.label("other")).thenReturn("其他");
    }

    private Map<String, Object> input(String type, String kind)
    {
        return p("images", java.util.Collections.singletonList(PHOTO), "jobType", type, "workKind", kind, "startDate", "2026-10-20", "pay", "board", "headcount", 2,
                "title", "海边小院换工", "intro", "介绍", "work", "拍照", "hope", "认真负责");
    }

    @Test
    void volunteerKindIsSaved()
    {
        service.create(ctx, input("volunteer", "photo"));
        assertEquals("photo", saved.get().get("work_kind"));
    }

    @Test
    void otherJobTypesSaveEmptyKind()
    {
        service.create(ctx, input("parttime", "photo"));
        assertEquals("", saved.get().get("work_kind"));
    }

    @Test
    void notChoosingAKindIsFine()
    {
        service.create(ctx, input("volunteer", null));
        assertEquals("", saved.get().get("work_kind"));
    }

    @Test
    void unknownKindStopsThePublish()
    {
        assertEquals(Integer.valueOf(422), assertThrows(ServiceException.class, () -> service.create(ctx, input("volunteer", "spaceship"))).getCode());
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    void generatedTextUsesTheKindName()
    {
        Map<String, Object> d = service.describe(ctx, input("volunteer", "photo"), 0);
        assertTrue(d.get("title").toString().contains("摄影换工"), d.get("title").toString());
        assertTrue(d.get("basis").toString().contains("换工类型：摄影换工"));
        // 名字不以「换工」结尾的补上「换工」；「其他」仍按普通换工写
        assertTrue(service.describe(ctx, input("volunteer", "craft"), 0).get("title").toString().contains("手作教学换工"));
        Map<String, Object> other = service.describe(ctx, input("volunteer", "other"), 0);
        assertFalse(other.get("title").toString().contains("其他"), other.get("title").toString());
        assertTrue(service.describe(ctx, input("volunteer", null), 0).get("basis").toString().contains("换工类型：未选"));
        assertFalse(service.describe(ctx, input("fulltime", "photo"), 0).get("basis").toString().contains("换工类型"));
    }
}
