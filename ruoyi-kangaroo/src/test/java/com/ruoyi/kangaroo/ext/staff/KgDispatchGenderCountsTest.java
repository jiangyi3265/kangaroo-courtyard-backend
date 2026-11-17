package com.ruoyi.kangaroo.ext.staff;

import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.AppLoginUser;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KgDispatchGenderCountsTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final KgDispatchService service = new KgDispatchService();

    @BeforeEach
    void setup() { ReflectionTestUtils.setField(service, "db", db); }

    @Test
    void splitRecruitmentCannotBeAdjustedAsOneTotal()
    {
        StaffSupport.Ctx c = new StaffSupport.Ctx();
        c.me = mock(AppLoginUser.class);
        when(c.me.hasStoreAccess(1L)).thenReturn(true);
        when(db.must(anyString(), anyMap(), anyString())).thenReturn(p("storeId", 1L, "headcount", 3,
                "genderMaleCount", 1, "genderFemaleCount", 2));
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.adjust(c, 99L, 1, "2099-01-01", "2099-01-02", "增加名额"));
        assertTrue(e.getMessage().contains("岗位管理"));
        verify(db, never()).update(anyString(), anyMap());
    }

    @Test
    void scheduledOldAdjustmentIsCancelledWhenGenderPlanReplacesIt()
    {
        Map<String, Object> log = p("logId", 7L, "jobId", 99L, "storeId", 1L, "delta", 2);
        when(db.list(contains("applied_time is null"), anyMap())).thenReturn(Collections.singletonList(log));
        when(db.update(startsWith("update kg_dispatch_log set applied_time"), anyMap())).thenReturn(1);
        // Conditional update refuses to overwrite a newly configured split plan.
        when(db.update(startsWith("update kg_job set headcount"), anyMap())).thenReturn(0);
        service.sync();
        verify(db).update(contains("gender_male_count is null and gender_female_count is null"), anyMap());
        verify(db).update(startsWith("update kg_dispatch_log set reverted_time"), argThat(m -> Long.valueOf(7L).equals(m.get("id"))));
        verify(db).insert(eq("kg_dispatch_log"), argThat(m -> String.valueOf(m.get("after_value")).contains("作废")));
    }

    @Test
    void expiredAdjustmentPreservesTheReplacementGenderPlan()
    {
        when(db.list(contains("applied_time is not null"), anyMap()))
                .thenReturn(Collections.singletonList(p("logId", 8L, "jobId", 99L, "storeId", 1L, "delta", 2)));
        when(db.update(startsWith("update kg_dispatch_log set reverted_time"), anyMap())).thenReturn(1);
        when(db.update(startsWith("update kg_job set headcount"), anyMap())).thenReturn(0);
        service.sync();
        verify(db).update(contains("gender_male_count is null and gender_female_count is null"), anyMap());
        verify(db).insert(eq("kg_dispatch_log"), argThat(m -> String.valueOf(m.get("after_value")).contains("保留现有计划")));
    }
}
