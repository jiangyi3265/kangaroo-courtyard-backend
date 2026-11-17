package com.ruoyi.kangaroo.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.domain.KgJob;
import com.ruoyi.kangaroo.domain.KgStore;
import com.ruoyi.kangaroo.mapper.KgStoreMapper;

/**
 * 后台新增 / 修改岗位的最短停留天数（2026-10-16 复查）：上限和门店发布同一口径 1–365 天。
 * 之前后端只拦了小于 1，365 的上限只在后台页面的输入框上，直接调接口能存进 1000。
 */
class KgJobAdminMinDaysTest
{
    private static KgJobServiceImpl service()
    {
        KgJobServiceImpl s = new KgJobServiceImpl();
        KgStoreMapper stores = mock(KgStoreMapper.class);
        when(stores.selectKgStoreByStoreId(anyLong())).thenReturn(new KgStore());
        ReflectionTestUtils.setField(s, "kgStoreMapper", stores);
        return s;
    }

    private static KgJob job(Integer minDays)
    {
        KgJob j = new KgJob();
        j.setTitle("前台换工");
        j.setCity("大理");
        j.setJobType("volunteer");
        j.setStoreId(1L);
        j.setHeadcount(1);
        j.setSalaryType("none");
        j.setMinDays(minDays);
        return j;
    }

    private static String errorOf(Integer minDays)
    {
        try
        {
            ReflectionTestUtils.invokeMethod(service(), "validate", job(minDays));
            return null;
        }
        catch (ServiceException e)
        {
            return e.getMessage();
        }
    }

    @Test
    void moreThan365DaysIsRefused()
    {
        assertEquals("最短停留天数最多 365 天", errorOf(1000));
        assertEquals("最短停留天数最多 365 天", errorOf(366));
    }

    @Test
    void boundsStillAccepted()
    {
        for (Integer d : new Integer[] { null, 1, 30, 365 })
        {
            String msg = errorOf(d);
            assertTrue(msg == null || !msg.startsWith("最短停留天数"), d + " → " + msg);
        }
        assertEquals("最短停留天数至少为 1 天", errorOf(0));
    }
}
