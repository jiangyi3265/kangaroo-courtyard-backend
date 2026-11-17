package com.ruoyi.kangaroo.ext.staff;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgCommunityGuard;
import com.ruoyi.kangaroo.security.AppLoginUser;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 评价详情「关联订单」的状态显示中文，不把 interview_done / refund_pending 这类编码原样给员工看（不连库） */
class KgStaffReviewStatusLabelTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final KgStaffReviewService service = new KgStaffReviewService();
    private final StaffSupport.Ctx ctx = new StaffSupport.Ctx();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "support", mock(StaffSupport.class));
        ReflectionTestUtils.setField(service, "guard", mock(KgCommunityGuard.class));
        ctx.storeId = 102L; ctx.storeName = "测试门店"; ctx.role = "manager";
        ctx.me = new AppLoginUser(); ctx.me.setUserId(1L);
    }

    private void review(String bizType, long bizId)
    {
        Map<String, Object> r = new HashMap<String, Object>();
        r.put("reviewId", 9L); r.put("storeId", 102L); r.put("bizType", bizType); r.put("bizId", bizId); r.put("rating", 4);
        r.put("createTime", "2026-10-01 10:00:00"); r.put("appUserId", 1L); r.put("nickname", "测试用户");
        when(db.one(contains("from kg_review v"), anyMap())).thenReturn(r);
    }

    @SuppressWarnings("unchecked")
    private String orderStatus()
    {
        return String.valueOf(((Map<String, Object>) service.detail(ctx, 9L).get("fulfillment")).get("status"));
    }

    private void application(String status)
    {
        Map<String, Object> a = new HashMap<String, Object>();
        a.put("appId", 7L); a.put("status", status); a.put("title", "前台换工"); a.put("city", "平潭"); a.put("address", "北港村");
        when(db.one(contains("from kg_application a join kg_job"), anyMap())).thenReturn(a);
    }

    private void stayOrder(String status)
    {
        Map<String, Object> o = new HashMap<String, Object>();
        o.put("orderNo", "S20261001000001"); o.put("status", status); o.put("checkIn", "2026-10-01"); o.put("checkOut", "2026-10-02");
        o.put("nights", 1); o.put("stayName", "海边小院"); o.put("city", "平潭"); o.put("area", "北港");
        when(db.one(contains("from kg_stay_order o"), anyMap())).thenReturn(o);
    }

    @Test
    void applicationStatusUsesChineseLabels()
    {
        review("application", 7L);
        application("interview_done");
        assertEquals("面试已完成", orderStatus());
        application("onboarded");
        assertEquals("已入职", orderStatus());
        application("terminated");
        assertEquals("已结束", orderStatus());
    }

    @Test
    void stayOrderStatusUsesChineseLabels()
    {
        review("stay_order", 8L);
        stayOrder("refund_pending");
        assertEquals("退款审核中", orderStatus());
        stayOrder("checked_in");
        assertEquals("已入住", orderStatus());
        stayOrder("completed");
        assertEquals("已离店", orderStatus());
    }
}
