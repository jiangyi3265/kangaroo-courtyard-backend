package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.ext.user.KgPointRewards;
import com.ruoyi.kangaroo.security.AppLoginUser;

/**
 * 2026-10-17 消费积分的三个挂点：前台办理退房、离店后定时自动完成 → 发消费积分；退款到账 → 扣回多发的。
 * 只核对「在对的地方、对的条件下调了 KgPointRewards」，金额和幂等见 KgPointRewardsTest。
 */
class KgStayOrderPointsTest
{
    private final KgJdbc db = mock(KgJdbc.class);

    private final KgPointRewards rewards = mock(KgPointRewards.class);

    @Test
    void autoCompletionGrantsOnlyForOrdersThatReallyFlipped()
    {
        KgStayOrderService service = new KgStayOrderService();
        ReflectionTestUtils.setField(service, "db", db);
        // 自动完成后会发「住宿已完成，去评价」的站内信（2026-11-09）
        ReflectionTestUtils.setField(service, "support", mock(StaffSupport.class));
        ReflectionTestUtils.setField(service, "pointRewards", rewards);
        when(db.list(contains("where o.status = 'checked_in'"), anyMap())).thenReturn(Arrays.asList(p("orderId", 9L, "stayId", 6L), p("orderId", 10L, "stayId", 6L)));
        // 10 号单被前台抢先退房了（条件更新 0 行）：不在这里发，前台退房那条路已经发过
        when(db.update(contains("set status = 'completed'"), argThat(m -> m != null && Long.valueOf(9).equals(((Map<?, ?>) m).get("id"))))).thenReturn(1);
        service.progress();
        verify(rewards).stayCompleted(9L);
        verify(rewards, never()).stayCompleted(10L);
    }

    @Test
    void refundCallbackAsksForClawBack()
    {
        KgStayOrderService service = new KgStayOrderService();
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "support", mock(StaffSupport.class));
        ReflectionTestUtils.setField(service, "pointRewards", rewards);
        when(db.one(contains("from kg_stay_order where order_id = :id for update"), anyMap()))
                .thenReturn(new HashMap<String, Object>(p("orderId", 9L, "orderNo", "S1", "appUserId", 7L, "status", "completed", "payFen", 50000L)));
        service.onRefunded(p("bizId", 9L, "refundAmount", 20000L, "status", "part_refunded", "payNo", "P9"), 20000L);
        verify(rewards).stayRefunded(9L);
    }

    @Test
    void frontDeskCheckOutGrants()
    {
        KgStayPmsService service = new KgStayPmsService();
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "support", mock(StaffSupport.class));
        ReflectionTestUtils.setField(service, "stayOrders", mock(KgStayOrderService.class));
        ReflectionTestUtils.setField(service, "pointRewards", rewards);
        StaffSupport.Ctx ctx = new StaffSupport.Ctx();
        ctx.storeId = 102L;
        ctx.role = "manager";
        ctx.me = new AppLoginUser();
        ctx.me.setUserId(1L);
        ctx.me.setNickname("黑子");
        LocalDate today = LocalDate.now();
        when(db.list(contains("from kg_stay_assign a join kg_stay_unit u"), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        when(db.one(contains("from kg_stay_order o"), anyMap())).thenReturn(new HashMap<String, Object>(p("orderId", 9L, "orderNo", "S1", "storeId", 102L, "stayId", 6L,
                "roomTypeId", 31L, "status", "checked_in", "checkIn", today.minusDays(2).toString(), "checkOut", today.toString(), "nights", 2, "guests", 1, "units", 1,
                "guestName", "尚可凯", "guestPhone", "13800001234", "unitLabel", "床位", "typeName", "女生多人间", "checkoutTime", "12:00")));
        when(db.update(contains("set status = 'completed'"), anyMap())).thenReturn(1);
        service.checkOut(ctx, "S1");
        verify(rewards).stayCompleted(9L);
    }
}
