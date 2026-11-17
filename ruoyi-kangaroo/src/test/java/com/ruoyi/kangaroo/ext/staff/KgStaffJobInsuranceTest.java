package com.ruoyi.kangaroo.ext.staff;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.AppLoginUser;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Mock unit coverage is separate from the real HTTP/SQL acceptance script. */
class KgStaffJobInsuranceTest extends com.ruoyi.kangaroo.testsupport.WithJobPhoto
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final StaffSupport support = mock(StaffSupport.class);
    private final KgStaffJobService service = new KgStaffJobService();
    private final StaffSupport.Ctx ctx = new StaffSupport.Ctx();
    private final AtomicReference<Map<String, Object>> saved = new AtomicReference<>();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "support", support);
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
    void checkedAndUncheckedPersistAndDescriptionsAgree()
    {
        for (String flag : Arrays.asList("0", "1"))
        {
            Map<String, Object> in = input(); in.put("insuranceFlag", flag);
            Map<String, Object> description = service.describe(ctx, in, 0);
            assertEquals("0".equals(flag), description.get("intro").toString().contains("保障以实际保单为准"));
            assertTrue(description.get("basis").toString().contains("0".equals(flag) ? "为录用人员安排保险" : "未承诺提供保险"));
            assertEquals("draft", service.create(ctx, in).get("status"));
            assertEquals(flag, saved.get().get("insurance_flag"));
        }
        verify(db, times(2)).insert(eq("kg_job"), anyMap());
        verify(db, never()).insert(eq("kg_insurance_apply"), anyMap());
    }

    @Test
    void missingOldClientFieldDefaultsToNoPromise()
    {
        service.create(ctx, input()); assertEquals("1", saved.get().get("insurance_flag"));
    }

    @Test
    void invalidExplicitFieldsRejectBeforeDatabaseAccess()
    {
        for (Object value : Arrays.asList(null, "", "2", false, 0, Arrays.asList("0")))
        {
            Map<String, Object> in = input(); in.put("insuranceFlag", value);
            assertEquals(422, assertThrows(ServiceException.class, () -> service.describe(ctx, in, 0)).getCode());
            assertEquals(422, assertThrows(ServiceException.class, () -> service.create(ctx, in)).getCode());
        }
        verifyNoInteractions(db);
    }

    @Test
    void onlineTaskCannotAdvertiseOfflineInsurance()
    {
        Map<String, Object> in = input(); in.put("jobType", "online"); in.put("insuranceFlag", "0");
        assertThrows(ServiceException.class, () -> service.describe(ctx, in, 0));
        assertThrows(ServiceException.class, () -> service.create(ctx, in)); verifyNoInteractions(db);
    }

    @Test
    void maximumSupplementRulesAndInsuranceRemainWithinDescriptionLimit()
    {
        Map<String, Object> in = input(); in.put("insuranceFlag", "0");
        in.put("pay", "salary"); in.put("salaryType", "hourly"); in.put("salary", "99999.99");
        String bonus = new String(new char[120]).replace('\0', '奖');
        String commission = new String(new char[120]).replace('\0', '提');
        in.put("bonusEnabled", true); in.put("bonusDesc", bonus);
        in.put("commissionEnabled", true); in.put("commissionDesc", commission);
        for (int variant = 0; variant < 3; variant++)
        {
            String intro = service.describe(ctx, in, variant).get("intro").toString();
            assertTrue(intro.length() <= 300); assertTrue(intro.contains(bonus)); assertTrue(intro.contains(commission));
            assertTrue(intro.contains("保障以实际保单为准"));
        }
    }
}
