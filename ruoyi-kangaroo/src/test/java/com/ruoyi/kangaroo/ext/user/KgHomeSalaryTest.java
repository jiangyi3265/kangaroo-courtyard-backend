package com.ruoyi.kangaroo.ext.user;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;

class KgHomeSalaryTest
{
    @Test
    void independentSupplementsNeverDisplayAsNegotiableOrWithOldBaseAmount()
    {
        assertEquals("奖金", KgHomeService.salary(p("salaryType", "none", "bonusDesc", "满勤奖500元")));
        assertEquals("提成", KgHomeService.salary(p("salaryType", "none", "commissionDesc", "销售额5%")));
        assertEquals("奖金 + 提成", KgHomeService.salary(p("salaryType", "none", "salaryMin", 5000, "bonusDesc", "满勤奖500元", "commissionDesc", "销售额5%")));
        assertEquals("面议", KgHomeService.salary(p("salaryType", "none")));
        assertEquals("奖金：满勤奖500元", com.ruoyi.kangaroo.domain.JobCompensation.details("", "满勤奖500元", null));
    }
    @Test
    void cardsKeepCentsForSingleAmountAndRange()
    {
        assertEquals("25.5", KgHomeService.salary(p("salaryMin", new BigDecimal("25.50"), "salaryMax", new BigDecimal("25.50"))));
        assertEquals("25.5-30.75", KgHomeService.salary(p("salaryMin", new BigDecimal("25.50"), "salaryMax", new BigDecimal("30.75"))));
        assertEquals("150", KgHomeService.salary(p("salaryMin", new BigDecimal("150.00"))));
    }
}
