package com.ruoyi.kangaroo.service.impl;

import java.math.BigDecimal;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.domain.KgJob;
import com.ruoyi.kangaroo.domain.KgStore;
import com.ruoyi.kangaroo.ext.staff.KgOnlineTaskService;
import com.ruoyi.kangaroo.mapper.KgJobMapper;
import com.ruoyi.kangaroo.mapper.KgStoreMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 岗位管理里的线上任务：不能新增、改价同步扩展行、交付日期不可手改（不连库） */
class KgJobOnlineEditTest extends com.ruoyi.kangaroo.testsupport.WithJobPhoto
{
    private KgJobServiceImpl service;
    private KgJobMapper jobs;
    private KgOnlineTaskService tasks;

    @BeforeEach
    void setUp()
    {
        service = new KgJobServiceImpl();
        jobs = mock(KgJobMapper.class);
        KgStoreMapper stores = mock(KgStoreMapper.class);
        when(stores.selectKgStoreByStoreId(102L)).thenReturn(new KgStore());
        tasks = mock(KgOnlineTaskService.class);
        ReflectionTestUtils.setField(service, "kgJobMapper", jobs);
        ReflectionTestUtils.setField(service, "kgStoreMapper", stores);
        ReflectionTestUtils.setField(service, "onlineTasks", tasks);
        SysUser user = new SysUser(); user.setUserId(1L); user.setUserName("ops-test");
        LoginUser login = new LoginUser(); login.setUser(user);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login, null, Collections.emptyList()));
    }

    @AfterEach
    void tearDown()
    {
        SecurityContextHolder.clearContext();
    }

    private static KgJob job(String type, String min, String max)
    {
        KgJob j = new KgJob();
        j.setImages(PHOTO);
        j.setJobId(7L); j.setStoreId(102L); j.setTitle("测试剪辑任务"); j.setCity("不限"); j.setJobType(type);
        j.setHeadcount(2); j.setSalaryType("piece"); j.setSalaryUnit("元/单");
        j.setSalaryMin(min == null ? null : new BigDecimal(min)); j.setSalaryMax(max == null ? null : new BigDecimal(max));
        j.setDeliverDesc("10月5日 18:00 前"); j.setHiredCount(0);
        return j;
    }

    @Test
    void adminCannotCreateOnlineTaskInJobManagement()
    {
        ServiceException e = assertThrows(ServiceException.class, () -> service.insertKgJob(job("online", "12.50", "12.50")));
        assertTrue(e.getMessage().contains("线上任务"));
        verify(jobs, never()).insertKgJob(any());
    }

    @Test
    void priceEditUpdatesCommissionExactlyAndIgnoresDeliverDescAndSalaryType()
    {
        when(jobs.selectKgJobByJobId(7L)).thenReturn(job("online", "12.50", "12.50"));
        when(jobs.updateKgJobByAdmin(any())).thenReturn(1);
        when(tasks.syncFromJobEdit(7L, 2000L, "ops-test")).thenReturn("10月6日 09:30 前");
        KgJob req = job("online", "20", "20");
        req.setDeliverDesc("随便写的日期"); req.setSalaryType("none"); req.setSalaryUnit("元/天");
        assertEquals(1, service.updateKgJob(req));
        ArgumentCaptor<KgJob> saved = ArgumentCaptor.forClass(KgJob.class);
        verify(jobs).updateKgJobByAdmin(saved.capture());
        assertEquals(0, new BigDecimal("20.00").compareTo(saved.getValue().getSalaryMin()));
        assertEquals(0, new BigDecimal("20.00").compareTo(saved.getValue().getSalaryMax()));
        assertEquals("piece", saved.getValue().getSalaryType());
        assertEquals("元/单", saved.getValue().getSalaryUnit());
        assertEquals("10月6日 09:30 前", saved.getValue().getDeliverDesc());
        verify(tasks).syncFromJobEdit(7L, 2000L, "ops-test");
    }

    @Test
    void onlineTaskNeverCarriesTheMonthRedpacket()
    {
        // 线上任务按单结算，没有「做满一个月」：后台勾了也一律存「没有」（1）
        when(jobs.selectKgJobByJobId(7L)).thenReturn(job("online", "12.50", "12.50"));
        when(jobs.updateKgJobByAdmin(any())).thenReturn(1);
        when(tasks.syncFromJobEdit(anyLong(), anyLong(), anyString())).thenReturn("10月6日 09:30 前");
        for (String flag : new String[] { "0", null, "1" })
        {
            reset(jobs);
            when(jobs.selectKgJobByJobId(7L)).thenReturn(job("online", "12.50", "12.50"));
            when(jobs.updateKgJobByAdmin(any())).thenReturn(1);
            KgJob req = job("online", "12.50", "12.50");
            req.setMonthRedpacket(flag);
            service.updateKgJob(req);
            ArgumentCaptor<KgJob> saved = ArgumentCaptor.forClass(KgJob.class);
            verify(jobs).updateKgJobByAdmin(saved.capture());
            assertEquals("1", saved.getValue().getMonthRedpacket(), String.valueOf(flag));
        }
    }

    @Test
    void centsAreNotTruncatedAndLegacyRowKeepsItsDeliverDesc()
    {
        KgJob exist = job("online", "10", "10");
        exist.setDeliverDesc("2天内");
        when(jobs.selectKgJobByJobId(7L)).thenReturn(exist);
        when(jobs.updateKgJobByAdmin(any())).thenReturn(1);
        when(tasks.syncFromJobEdit(anyLong(), anyLong(), anyString())).thenReturn(null);
        KgJob req = job("online", "12.5", null);
        req.setDeliverDesc("明天");
        service.updateKgJob(req);
        verify(tasks).syncFromJobEdit(7L, 1250L, "ops-test");
        ArgumentCaptor<KgJob> saved = ArgumentCaptor.forClass(KgJob.class);
        verify(jobs).updateKgJobByAdmin(saved.capture());
        assertEquals("2天内", saved.getValue().getDeliverDesc());
        assertEquals(0, new BigDecimal("12.50").compareTo(saved.getValue().getSalaryMax()));
    }

    @Test
    void invalidOnlinePricesAreRejectedBeforeAnyWrite()
    {
        when(jobs.selectKgJobByJobId(7L)).thenReturn(job("online", "12.50", "12.50"));
        for (String[] p : new String[][] { { "0.5", "0.5" }, { "5000.01", "5000.01" }, { "12.345", "12.345" }, { "20", "25" }, { null, null } })
        {
            assertThrows(ServiceException.class, () -> service.updateKgJob(job("online", p[0], p[1])), p[0] + "-" + p[1]);
        }
        verify(jobs, never()).updateKgJobByAdmin(any());
        verify(tasks, never()).syncFromJobEdit(anyLong(), anyLong(), anyString());
    }

    @Test
    void jobTypeCannotSwitchIntoOrOutOfOnline()
    {
        when(jobs.selectKgJobByJobId(7L)).thenReturn(job("online", "12.50", "12.50"));
        assertThrows(ServiceException.class, () -> service.updateKgJob(job("parttime", "12.50", "12.50")));
        reset(jobs);
        when(jobs.selectKgJobByJobId(7L)).thenReturn(job("parttime", "150", "150"));
        assertThrows(ServiceException.class, () -> service.updateKgJob(job("online", "150", "150")));
        verify(jobs, never()).updateKgJobByAdmin(any());
    }

    @Test
    void otherJobTypesAreUntouched()
    {
        KgJob exist = job("parttime", "150", "200");
        exist.setSalaryType("daily");
        when(jobs.selectKgJobByJobId(7L)).thenReturn(exist);
        when(jobs.updateKgJobByAdmin(any())).thenReturn(1);
        KgJob req = job("parttime", "150", "200");
        req.setSalaryType("daily"); req.setDeliverDesc("运营手填");
        service.updateKgJob(req);
        ArgumentCaptor<KgJob> saved = ArgumentCaptor.forClass(KgJob.class);
        verify(jobs).updateKgJobByAdmin(saved.capture());
        assertEquals("运营手填", saved.getValue().getDeliverDesc());
        assertEquals(0, new BigDecimal("200").compareTo(saved.getValue().getSalaryMax()));
        assertEquals("元/天", saved.getValue().getSalaryUnit());
        verifyNoInteractions(tasks);
    }
}
