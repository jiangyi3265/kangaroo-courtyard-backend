package com.ruoyi.kangaroo.controller.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.domain.KgJob;
import com.ruoyi.kangaroo.domain.KgStore;
import com.ruoyi.kangaroo.mapper.KgJobFavoriteMapper;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.service.IKgJobService;
import com.ruoyi.kangaroo.service.IKgStoreService;

/** 岗位详情带上工作地点坐标（V42）：有坐标时小程序点「工作地点」打开地图；没选过点为 null（不连库） */
class AppJobLocationTest
{
    @AfterEach
    void cleanup()
    {
        AppUserContext.remove();
    }

    private static KgJob job(BigDecimal lat, BigDecimal lng)
    {
        KgJob j = new KgJob();
        j.setJobId(10005L); j.setStoreId(102L); j.setJobType("volunteer"); j.setTitle("南舍庄园招直播换工"); j.setStatus("published");
        j.setCity("福州"); j.setAddress("福州市闽侯县南舍庄园"); j.setLatitude(lat); j.setLongitude(lng);
        return j;
    }

    @Test
    void jobMapCarriesCoordinates()
    {
        Map<String, Object> m = ReflectionTestUtils.invokeMethod(new AppJobController(), "jobToMap",
                job(new BigDecimal("25.600000"), new BigDecimal("119.300000")), false);
        assertEquals(new BigDecimal("25.600000"), m.get("latitude"));
        assertEquals(new BigDecimal("119.300000"), m.get("longitude"));
        assertEquals("福州市闽侯县南舍庄园", m.get("address"));
        Map<String, Object> none = ReflectionTestUtils.invokeMethod(new AppJobController(), "jobToMap", job(null, null), false);
        assertTrue(none.containsKey("latitude"));
        assertNull(none.get("latitude"));
        assertNull(none.get("longitude"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void detailEndpointReturnsTheCoordinatesToGuests()
    {
        AppJobController controller = new AppJobController();
        IKgJobService jobs = mock(IKgJobService.class);
        IKgStoreService stores = mock(IKgStoreService.class);
        when(jobs.requireJob(10005L)).thenReturn(job(new BigDecimal("25.600000"), new BigDecimal("119.300000")));
        KgStore store = new KgStore();
        store.setStoreId(102L); store.setStatus("0"); store.setStoreName("平潭袋鼠小院");
        when(stores.selectKgStoreByStoreId(102L)).thenReturn(store);
        ReflectionTestUtils.setField(controller, "kgJobService", jobs);
        ReflectionTestUtils.setField(controller, "kgStoreService", stores);
        ReflectionTestUtils.setField(controller, "kgJobFavoriteMapper", mock(KgJobFavoriteMapper.class));
        ReflectionTestUtils.setField(controller, "storeFollows", mock(com.ruoyi.kangaroo.ext.user.KgStoreFollowService.class));
        AjaxResult r = controller.detail(10005L);
        Map<String, Object> data = (Map<String, Object>) r.get("data");
        assertEquals(new BigDecimal("25.600000"), data.get("latitude"));
        assertEquals(new BigDecimal("119.300000"), data.get("longitude"));
        verify(jobs).requireJob(eq(10005L));
    }
}
