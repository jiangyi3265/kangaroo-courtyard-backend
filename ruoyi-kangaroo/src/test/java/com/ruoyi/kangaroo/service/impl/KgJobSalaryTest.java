package com.ruoyi.kangaroo.service.impl;

import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.kangaroo.domain.KgJob;
import com.ruoyi.kangaroo.domain.KgStore;
import com.ruoyi.kangaroo.mapper.KgStoreMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KgJobSalaryTest
{
    @ParameterizedTest
    @CsvSource({"hourly,元/小时", "daily,元/天", "weekly,元/周", "monthly,元/月", "piece,元/篇"})
    void adminAcceptsPeriodAndCanonicalizesUnitWhilePreservingPieceUnit(String type, String expectedUnit)
    {
        KgJobServiceImpl service = new KgJobServiceImpl();
        KgStoreMapper stores = mock(KgStoreMapper.class);
        when(stores.selectKgStoreByStoreId(102L)).thenReturn(new KgStore());
        ReflectionTestUtils.setField(service, "kgStoreMapper", stores);
        KgJob job = new KgJob();
        job.setTitle("测试岗位"); job.setCity("平潭"); job.setJobType("fulltime");
        job.setStoreId(102L); job.setHeadcount(2); job.setSalaryType(type);
        job.setSalaryMin(new BigDecimal("25.50")); job.setSalaryUnit("元/篇");
        job.setBonusDesc(" 达标奖500元 "); job.setCommissionDesc(" 销售额5% ");
        ReflectionTestUtils.invokeMethod(service, "validate", job);
        assertEquals(type, job.getSalaryType());
        assertEquals(expectedUnit, job.getSalaryUnit());
        assertEquals(job.getSalaryMin(), job.getSalaryMax());
        assertEquals("达标奖500元", job.getBonusDesc());
        assertEquals("销售额5%", job.getCommissionDesc());
        job.setSalaryType("none");
        ReflectionTestUtils.invokeMethod(service, "validate", job);
        assertNull(job.getSalaryMin()); assertNull(job.getSalaryMax()); assertNull(job.getSalaryUnit());
        assertEquals("达标奖500元", job.getBonusDesc()); assertEquals("销售额5%", job.getCommissionDesc());
        job.setBonusDesc(new String(new char[121]).replace('\0', '奖'));
        assertThrows(com.ruoyi.common.exception.ServiceException.class, () -> ReflectionTestUtils.invokeMethod(service, "validate", job));
    }
}
