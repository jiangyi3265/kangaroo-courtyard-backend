package com.ruoyi.kangaroo.ext.staff;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.AppLoginUser;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KgStaffJobSalaryTest extends com.ruoyi.kangaroo.testsupport.WithJobPhoto
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
        ctx.storeId = 102L;
        ctx.me = new AppLoginUser(); ctx.me.setUserId(9001L);
        when(db.must(anyString(), anyMap(), anyString())).thenReturn(p("storeName", "小院", "city", "平潭", "address", "海边"));
        when(db.insert(eq("kg_job"), anyMap())).thenAnswer(inv -> { saved.set(inv.getArgument(1)); return 99L; });
    }

    private Map<String, Object> input(String type, Object amount)
    {
        return p("images", java.util.Collections.singletonList(PHOTO), "jobType", "fulltime", "startDate", "2026-10-09", "hours", "每周 5 天，每天 8 小时",
                "pay", "salary", "salaryType", type, "salary", amount, "headcount", 2,
                "title", "小院伙伴", "intro", "岗位介绍", "work", "门店接待", "hope", "认真负责");
    }

    @ParameterizedTest
    @CsvSource({"hourly,元/小时", "daily,元/天", "weekly,元/周", "monthly,元/月"})
    void descriptionAndSavedRowAgreeOnAmountAndUnit(String type, String unit)
    {
        Map<String, Object> in = input(type, "25.50");
        Map<String, Object> description = service.describe(ctx, in, 0);
        assertTrue(description.get("intro").toString().contains("25.5" + unit));
        assertTrue(description.get("basis").toString().contains("25.5" + unit));
        assertEquals("draft", service.create(ctx, in).get("status"));
        assertEquals(type, saved.get().get("salary_type"));
        assertEquals(unit, saved.get().get("salary_unit"));
        assertEquals(new BigDecimal("25.50"), saved.get().get("salary_min"));
        assertEquals(saved.get().get("salary_min"), saved.get().get("salary_max"));
    }

    @Test
    void olderClientWithoutPeriodStillCreatesDailySalary()
    {
        Map<String, Object> in = input(null, 150); in.remove("salaryType");
        service.create(ctx, in);
        assertEquals("daily", saved.get().get("salary_type"));
        assertEquals("元/天", saved.get().get("salary_unit"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "-1", "1.234", "100000.01", "NaN", "1e2", "25元"})
    void badAmountRejectedBeforeDescriptionOrInsert(String amount)
    {
        assertThrows(ServiceException.class, () -> service.describe(ctx, input("hourly", amount), 0));
        assertThrows(ServiceException.class, () -> service.create(ctx, input("hourly", amount)));
        verifyNoInteractions(db);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "yearly", "piece", "none"})
    void unsupportedEmployeePeriodRejected(String type)
    {
        assertThrows(ServiceException.class, () -> service.create(ctx, input(type, 150)));
        verifyNoInteractions(db);
    }

    @ParameterizedTest
    @ValueSource(strings = {"board", "none"})
    void hiddenBaseNotSavedButIndependentSupplementsRetained(String pay)
    {
        Map<String, Object> in = input("weekly", 1000); in.put("pay", pay);
        in.put("bonusEnabled", true); in.put("bonusDesc", "达标奖500元");
        in.put("commissionEnabled", true); in.put("commissionDesc", "销售额5%");
        service.create(ctx, in);
        assertEquals("none", saved.get().get("salary_type"));
        assertFalse(saved.get().containsKey("salary_min"));
        assertFalse(saved.get().containsKey("salary_unit"));
        assertEquals("达标奖500元", saved.get().get("bonus_desc"));
        assertEquals("销售额5%", saved.get().get("commission_desc"));
    }

    @ParameterizedTest
    @CsvSource({"salary,true,true", "salary,true,false", "salary,false,true", "salary,false,false",
            "board,true,true", "board,true,false", "board,false,true", "board,false,false",
            "none,true,true", "none,true,false", "none,false,true", "none,false,false"})
    void optionalBonusAndCommissionPersistIndependently(String pay, boolean bonus, boolean commission)
    {
        Map<String, Object> in = input("monthly", 5000);
        in.put("pay", pay); in.put("jobType", "volunteer");
        if (!"salary".equals(pay)) in.remove("salary");
        in.put("bonusEnabled", bonus); in.put("bonusDesc", " 达标奖500元 ");
        in.put("commissionEnabled", commission); in.put("commissionDesc", " 个人销售额5% ");
        String intro = service.describe(ctx, in, 0).get("intro").toString();
        assertEquals(bonus, intro.contains("奖金：达标奖500元"));
        assertEquals(commission, intro.contains("提成：个人销售额5%"));
        service.create(ctx, in);
        assertEquals("salary".equals(pay) ? new BigDecimal("5000") : null, saved.get().get("salary_min"));
        assertEquals(bonus ? "达标奖500元" : null, saved.get().get("bonus_desc"));
        assertEquals(commission ? "个人销售额5%" : null, saved.get().get("commission_desc"));
    }

    @ParameterizedTest
    @CsvSource({"salary,bonus", "salary,commission", "board,bonus", "board,commission", "none,bonus", "none,commission"})
    void selectedSupplementCannotBeBlankOrExceedLimit(String pay, String key)
    {
        Map<String, Object> in = input("monthly", 5000);
        in.put("pay", pay);
        in.put(key + "Enabled", true); in.put(key + "Desc", " ");
        assertThrows(ServiceException.class, () -> service.create(ctx, in));
        in.put(key + "Desc", new String(new char[121]).replace('\0', '奖'));
        assertThrows(ServiceException.class, () -> service.describe(ctx, in, 0));
        verifyNoInteractions(db);
    }

    @Test
    void longStoreIntroDoesNotTruncateSupplementRules()
    {
        when(db.must(anyString(), anyMap(), anyString())).thenReturn(p("storeName", "小院", "city", "平潭", "intro", new String(new char[150]).replace('\0', '院')));
        Map<String, Object> in = input("monthly", 5000);
        String bonus = new String(new char[120]).replace('\0', '奖');
        String commission = new String(new char[120]).replace('\0', '提');
        in.put("bonusDesc", bonus); in.put("commissionDesc", commission);
        String intro = service.describe(ctx, in, 0).get("intro").toString();
        assertTrue(intro.length() <= 300);
        assertTrue(intro.contains("5000元/月"));
        assertTrue(intro.contains(bonus)); assertTrue(intro.contains(commission));
    }
}
