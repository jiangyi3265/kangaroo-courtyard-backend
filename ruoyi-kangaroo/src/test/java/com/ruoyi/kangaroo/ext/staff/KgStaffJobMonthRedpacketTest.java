package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.AppLoginUser;

/** 门店发布岗位的「做满一个月有红包」（V44）：勾了存 0、不勾或老客户端存 1、乱传拒绝（不连库） */
class KgStaffJobMonthRedpacketTest extends com.ruoyi.kangaroo.testsupport.WithJobPhoto
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final KgStaffJobService service = new KgStaffJobService();
    private final StaffSupport.Ctx ctx = new StaffSupport.Ctx();
    private final AtomicReference<Map<String, Object>> saved = new AtomicReference<>();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "support", mock(StaffSupport.class));
        ctx.storeId = 102L; ctx.me = new AppLoginUser(); ctx.me.setUserId(9001L);
        when(db.must(anyString(), anyMap(), anyString())).thenReturn(p("storeName", "小院", "city", "平潭", "address", "海边"));
        when(db.insert(eq("kg_job"), anyMap())).thenAnswer(inv -> { saved.set(inv.getArgument(1)); return 99L; });
    }

    private Map<String, Object> input()
    {
        return p("images", java.util.Collections.singletonList(PHOTO), "jobType", "volunteer", "startDate", "2026-10-09", "hours", "每日4小时", "pay", "board", "headcount", 2,
                "title", "小院伙伴", "intro", "介绍", "work", "接待", "hope", "认真负责");
    }

    @Test
    void tickedIsSavedAndListedInTheBasis()
    {
        Map<String, Object> in = input(); in.put("monthRedpacket", Boolean.TRUE);
        assertTrue(service.describe(ctx, in, 0).get("basis").toString().contains("满月红包"));
        service.create(ctx, in);
        assertEquals("0", saved.get().get("month_redpacket"));
    }

    @Test
    void untickedOrOldClientSavesNo()
    {
        Map<String, Object> in = input(); in.put("monthRedpacket", Boolean.FALSE);
        service.create(ctx, in);
        assertEquals("1", saved.get().get("month_redpacket"));
        service.create(ctx, input());
        assertEquals("1", saved.get().get("month_redpacket"));
        assertFalse(service.describe(ctx, input(), 0).get("basis").toString().contains("满月红包"));
    }

    @Test
    void invalidValueIsRejectedBeforeDatabaseAccess()
    {
        for (Object v : Arrays.asList("0", 1, "true", Arrays.asList(true)))
        {
            Map<String, Object> in = input(); in.put("monthRedpacket", v);
            assertEquals(422, assertThrows(ServiceException.class, () -> service.create(ctx, in)).getCode());
        }
        verifyNoInteractions(db);
    }
}
