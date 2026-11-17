package com.ruoyi.kangaroo.ext.staff;

import java.math.BigDecimal;
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

class KgStaffJobBenefitsTest extends com.ruoyi.kangaroo.testsupport.WithJobPhoto
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
        return p("images", java.util.Collections.singletonList(PHOTO), "jobType", "volunteer", "startDate", "2026-10-09", "pay", "board", "headcount", 2,
                "title", "小院伙伴", "intro", "介绍", "work", "接待", "hope", "认真负责",
                "salaryType", "monthly", "salary", "5000", "bonusEnabled", true, "bonusDesc", "达标奖500元");
    }

    @Test
    void independentBenefitsPersistForEveryOfflineTypeAndCycle()
    {
        for (String type : Arrays.asList("volunteer", "parttime", "fulltime"))
        for (String cycle : Arrays.asList("hourly", "daily", "weekly", "monthly"))
        for (boolean board : new boolean[] {false, true})
        for (boolean salary : new boolean[] {false, true})
        {
            Map<String, Object> in = input(); in.put("jobType", type); in.put("salaryType", cycle);
            in.put("boardEnabled", board); in.put("salaryEnabled", salary);
            service.create(ctx, in);
            Map<String, Object> row = saved.get();
            assertEquals(board ? "all" : "none", row.get("board_type"));
            assertEquals(board ? "提供食宿" : null, row.get("board_desc"));
            assertEquals(salary ? cycle : "none", row.get("salary_type"));
            assertEquals(salary ? new BigDecimal("5000") : null, row.get("salary_min"));
            assertEquals("达标奖500元", row.get("bonus_desc"));
            String basis = service.describe(ctx, in, 0).get("basis").toString();
            assertEquals(board, basis.contains("提供食宿"));
            assertEquals(salary, basis.contains("5000元/"));
        }
        verify(db, never()).insert(eq("kg_insurance_apply"), anyMap());
    }

    @Test
    void oldClientsKeepTheirOriginalChoice()
    {
        for (String pay : Arrays.asList("board", "salary", "none"))
        {
            Map<String, Object> in = input(); in.put("pay", pay); service.create(ctx, in);
            assertEquals("board".equals(pay) ? "all" : "none", saved.get().get("board_type"));
            assertEquals("salary".equals(pay) ? "monthly" : "none", saved.get().get("salary_type"));
        }
    }

    @Test
    void malformedExplicitBenefitStatesRejectWithoutWrites()
    {
        for (String field : Arrays.asList("boardEnabled", "salaryEnabled"))
        for (Object value : Arrays.asList(null, "true", "", 1, Arrays.asList(true)))
        {
            Map<String, Object> in = input(); in.put(field, value);
            assertEquals(422, assertThrows(ServiceException.class, () -> service.create(ctx, in)).getCode());
            assertEquals(422, assertThrows(ServiceException.class, () -> service.describe(ctx, in, 0)).getCode());
        }
        verifyNoInteractions(db);
    }

    @Test
    void enabledSalaryRequiresAmountAndCycleEvenWhenFoodSelected()
    {
        Map<String, Object> in = input(); in.put("boardEnabled", true); in.put("salaryEnabled", true);
        for (String bad : Arrays.asList("", "0", "-1", "1.234", "100000.01", "1e2"))
        {
            in.put("salary", bad); assertThrows(ServiceException.class, () -> service.create(ctx, in));
        }
        in.put("salary", "5000"); in.put("salaryType", "yearly");
        assertThrows(ServiceException.class, () -> service.create(ctx, in)); verifyNoInteractions(db);
    }

    @Test
    void maximumCombinedBenefitsAndInsuranceFitWithoutDroppingRules()
    {
        Map<String, Object> in = input(); in.put("boardEnabled", true); in.put("salaryEnabled", true);
        in.put("salaryType", "hourly"); in.put("salary", "99999.99"); in.put("insuranceFlag", "0");
        String bonus = new String(new char[120]).replace('\0', '奖');
        String commission = new String(new char[120]).replace('\0', '提');
        in.put("bonusDesc", bonus); in.put("commissionEnabled", true); in.put("commissionDesc", commission);
        for (int v = 0; v < 3; v++)
        {
            String intro = service.describe(ctx, in, v).get("intro").toString();
            assertTrue(intro.length() <= 300); assertTrue(intro.contains("提供食宿 + 99999.99元/小时"));
            assertTrue(intro.contains(bonus)); assertTrue(intro.contains(commission)); assertTrue(intro.contains("保障以实际保单为准"));
        }
    }
}
