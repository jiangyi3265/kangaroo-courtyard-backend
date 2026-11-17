package com.ruoyi.kangaroo.ext.staff;

import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.StaffGrantView;
import com.ruoyi.kangaroo.service.IKgApplicationService;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 面试详情带上报名所属门店与责任书签署留痕：双向确认屏据此显示签署进度，而不是把按钮带去保险办理。 */
class KgStaffInterviewContractTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final KgStaffInterviewService service = new KgStaffInterviewService();
    private final StaffSupport.Ctx ctx = new StaffSupport.Ctx();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "apps", mock(IKgApplicationService.class));
        ReflectionTestUtils.setField(service, "support", mock(StaffSupport.class));
        StaffGrantView g = new StaffGrantView();
        g.setStoreId(102L); g.setRoleCode("staff"); g.setActive(true);
        ctx.me = new AppLoginUser(); ctx.me.setUserId(1L); ctx.me.setStaffGrants(Collections.singletonList(g));
        ctx.storeId = 102L; ctx.role = "staff";
        // KgJdbc.one 查不到时返回 null（Mockito 默认给空 Map，会被当成"有签署记录"）
        when(db.one(anyString(), anyMap())).thenReturn(null);
        when(db.must(contains("from kg_interview i join kg_application a"), anyMap(), eq("面试不存在"))).thenReturn(p(
                "interviewId", 5L, "appId", 201L, "slotId", 9L, "storeId", 102L, "appUserId", 7L, "status", "contract_signed",
                "istatus", "done", "realName", "张三", "phone", "13800000000", "jobTitle", "咖啡师", "jobType", "volunteer",
                "startTime", "2026-09-01 10:00:00", "endTime", "2026-09-01 10:30:00"));
    }

    @Test
    void signedContractIsReportedWithStoreId()
    {
        when(db.one(contains("from kg_contract_sign"), eq(p("a", 201L))))
                .thenReturn(p("signerName", "张三", "version", "2026-09", "signedTime", "2026-09-02 08:30:00"));
        Map<String, Object> d = service.detail(ctx, 5L);
        assertEquals(102L, d.get("storeId"));
        assertEquals(Boolean.TRUE, d.get("contractSigned"));
        assertEquals("张*", d.get("contractSigner"));
        assertEquals("2026-09", d.get("contractVersion"));
        assertEquals("2026-09-02 08:30", d.get("contractSignTime"));
    }

    @Test
    void unsignedContractIsReportedAsNotSigned()
    {
        Map<String, Object> d = service.detail(ctx, 5L);
        assertEquals(Boolean.FALSE, d.get("contractSigned"));
        assertNull(d.get("contractSignTime"));
        assertNull(d.get("contractSigner"));
    }
}
