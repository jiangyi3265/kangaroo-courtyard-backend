package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.controller.app.AppStaffDailyController;
import com.ruoyi.kangaroo.ext.KgJdbc;

/** 线上任务招募概况：城市按门店、缺口按在做人数、只有数字（用 Mock 代替 JDBC） */
class KgOnlineDailyServiceTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final KgMerchantRequestService requests = mock(KgMerchantRequestService.class);
    private final KgOnlineDailyService service = new KgOnlineDailyService();
    private final StaffSupport.Ctx ctx = new StaffSupport.Ctx();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "requests", requests);
        ctx.storeId = 10L;
        when(requests.townOf(any())).thenReturn("大理");
        when(requests.normalCity(any())).thenAnswer(i -> {
            String n = String.valueOf((Object) i.getArgument(0));
            return n.endsWith("市") ? n.substring(0, n.length() - 1) : n;
        });
    }

    private static Map<String, Object> job(long id, String city, String status, long headcount, long working, long toReview, long toAccept)
    {
        return p("jobId", id, "storeId", 30L, "title", "任务" + id, "status", status, "headcount", headcount, "city", city, "storeName", "喜洲小院",
                "working", working, "toReview", toReview, "toAccept", toAccept);
    }

    @Test
    @SuppressWarnings("unchecked")
    void countsAndRowsFollowTheAgreedRules()
    {
        when(db.list(anyString(), anyMap())).thenReturn(Arrays.asList(
                job(1, "大理市", "published", 5, 2, 1, 0),
                job(2, "大理", "published", 1, 3, 0, 0),      // 超额不算负缺口
                job(3, "大理", "closed", 9, 1, 2, 4),         // 关了但还有待办：缺口不算，待办算
                job(4, "大理", "closed", 9, 1, 0, 0),         // 关了也没待办：不出现
                job(5, "丽江", "published", 9, 0, 7, 7)));    // 别的城市
        Map<String, Object> out = service.overview(ctx);
        assertEquals("大理", out.get("city"));
        assertEquals(2L, out.get("recruiting"));
        assertEquals(3L, out.get("shortfall"));
        assertEquals(3L, out.get("toReview"));
        assertEquals(4L, out.get("toAccept"));
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) out.get("tasks");
        assertEquals(3, tasks.size());
        Map<String, Object> first = tasks.get(0);
        assertEquals(1L, first.get("jobId"));
        assertEquals("招募中", first.get("statusLabel"));
        assertEquals(5L, first.get("headcount"));
        assertEquals(2L, first.get("working"));
        assertEquals(3L, first.get("shortfall"));
        assertEquals(0L, tasks.get(1).get("shortfall"));
        assertEquals("已关闭", tasks.get(2).get("statusLabel"));
        assertEquals(0L, tasks.get(2).get("shortfall"));
    }

    @Test
    void sqlAvoidsTheTwoColumnsThatLieForOnlineTasksAndCarriesNoApplicantData()
    {
        service.overview(ctx);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db).list(sql.capture(), anyMap());
        String s = sql.getValue();
        assertTrue(s.contains("j.job_type = 'online'"), s);
        assertTrue(s.contains("j.status <> 'draft'"), s);
        assertTrue(s.contains("s.city"), s);
        assertFalse(s.contains("j.city"), "线上任务的 city 固定是不限");
        assertFalse(s.contains("hired_count"), "线上任务的 hired_count 永远是 0");
        assertFalse(s.contains("real_name"), s);
        assertFalse(s.contains("phone"), s);
        assertTrue(s.contains("'review_passed'") && s.contains("'onboarded'"), "在做的口径和线上任务页一样");
    }

    @Test
    void emptyCityStillAnswersWithZeros()
    {
        Map<String, Object> out = service.overview(ctx);
        assertEquals(0L, out.get("recruiting"));
        assertEquals(0L, out.get("shortfall"));
        assertTrue(((List<?>) out.get("tasks")).isEmpty());
    }

    @Test
    void controllerAsksServiceCtxFirst()
    {
        AppStaffDailyController c = new AppStaffDailyController();
        KgOnlineDailyService online = mock(KgOnlineDailyService.class);
        ReflectionTestUtils.setField(c, "online", online);
        ReflectionTestUtils.setField(c, "requests", requests);
        when(requests.serviceCtx(eq(10L), eq(StaffPerms.VIEW))).thenThrow(new ServiceException("这项功能只有商家服务人可以使用", 403));
        assertEquals(403, assertThrows(ServiceException.class, () -> c.online(10L)).getCode(), "普通小院主理人、换工主理人");
        verify(online, never()).overview(any());

        reset(requests);
        when(requests.serviceCtx(eq(10L), eq(StaffPerms.VIEW))).thenReturn(ctx);
        c.online(10L);
        verify(online).overview(ctx);
    }
}
