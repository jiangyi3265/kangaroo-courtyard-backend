package com.ruoyi.kangaroo.service.impl;

import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.domain.KgApplication;
import com.ruoyi.kangaroo.domain.KgJob;
import com.ruoyi.kangaroo.dto.ApplicationSubmitDTO;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.mapper.*;
import com.ruoyi.kangaroo.service.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercise the actual submission path, including offline validation and retry ownership. */
class KgRemoteApplicationTest
{
    private KgApplicationServiceImpl service;
    private KgApplicationMapper applications;
    private KgJobMapper jobs;
    private IKgIdempotencyService idempotency;
    private KgJdbc db;
    private KgJob job;
    private ApplicationSubmitDTO dto;

    @BeforeEach
    void setup()
    {
        service = new KgApplicationServiceImpl();
        applications = mock(KgApplicationMapper.class);
        jobs = mock(KgJobMapper.class);
        idempotency = mock(IKgIdempotencyService.class);
        db = mock(KgJdbc.class);
        ReflectionTestUtils.setField(service, "kgApplicationMapper", applications);
        ReflectionTestUtils.setField(service, "kgJobMapper", jobs);
        ReflectionTestUtils.setField(service, "idempotencyService", idempotency);
        ReflectionTestUtils.setField(service, "kgJdbcForQuota", db);
        ReflectionTestUtils.setField(service, "kgApplicationLogMapper", mock(KgApplicationLogMapper.class));
        ReflectionTestUtils.setField(service, "kgMessageService", mock(IKgMessageService.class));
        ReflectionTestUtils.setField(service, "kgStaffGrantService", mock(IKgStaffGrantService.class));
        job = new KgJob(); job.setJobId(18L); job.setStoreId(102L); job.setJobType("online");
        job.setStatus("published"); job.setTitle("远程写作任务"); job.setCity("平潭");
        job.setHeadcount(50); job.setHiredCount(0); job.setMinDays(30);
        when(jobs.selectKgJobByJobId(18L)).thenReturn(job);
        when(db.one(anyString(), anyMap())).thenReturn(KgJdbc.p("status", "0", "delFlag", "0"));
        when(idempotency.tryAcquire(anyString(), anyString())).thenReturn(true);
        when(applications.insertKgApplication(any())).thenAnswer(call -> {
            call.getArgument(0, KgApplication.class).setAppId(91L); return 1;
        });
        dto = new ApplicationSubmitDTO(); dto.setJobId(18L); dto.setName("测试接单人");
        dto.setAge(25); dto.setPhone("13800138000"); dto.setIdemKey("remote-test");
    }

    @Test void remoteApplicationNeedsNoArrivalOrStayAndKeepsPublisherOwnership()
    {
        KgApplication app = service.submit(dto, 9001L, "测试用户");
        assertNull(app.getArrivalDate()); assertNull(app.getStayDays());
        assertNull(app.getAge()); assertNull(app.getSkills());
        assertEquals(102L, app.getStoreId()); assertEquals("submitted", app.getStatus());
        verify(applications).insertKgApplication(app);
        verify(idempotency).bindBizId("remote-test", 91L);
        verify(jobs).increaseAppliedCount(18L);
    }

    @Test void remoteApplicationDiscardsStaleOfflineDraft()
    {
        dto.setArrival("2000-01-01"); dto.setDays(-1); dto.setAge(-1); dto.setSkills("旧线下草稿");
        KgApplication app = service.submit(dto, 9001L, "测试用户");
        assertNull(app.getArrivalDate()); assertNull(app.getStayDays());
        assertNull(app.getAge()); assertNull(app.getSkills());
    }

    @Test void onsiteApplicationStillRequiresArrivalAndMinimumStay()
    {
        job.setJobType("parttime");
        assertThrows(ServiceException.class, () -> service.submit(dto, 9001L, "测试用户"));
        dto.setArrival(LocalDate.now().plusDays(2).toString()); dto.setDays(2);
        assertThrows(ServiceException.class, () -> service.submit(dto, 9001L, "测试用户"));
        verify(applications, never()).insertKgApplication(any());
        dto.setDays(30);
        KgApplication app = service.submit(dto, 9001L, "测试用户");
        assertNotNull(app.getArrivalDate()); assertEquals(30, app.getStayDays());
    }

    @Test void remoteApplicationNeedsNoAgeOrSkills()
    {
        dto.setAge(null); dto.setSkills(null);
        KgApplication app = service.submit(dto, 9001L, "测试用户");
        assertNull(app.getAge()); assertNull(app.getSkills());
        assertEquals("13800138000", app.getPhone());
    }

    @Test void onsiteApplicationStillRequiresValidAge()
    {
        job.setJobType("parttime");
        dto.setArrival(LocalDate.now().plusDays(2).toString()); dto.setDays(30);
        dto.setAge(null);
        assertEquals("年龄不能为空", assertThrows(ServiceException.class, () -> service.submit(dto, 9001L, "测试用户")).getMessage());
        dto.setAge(0);
        assertThrows(ServiceException.class, () -> service.submit(dto, 9001L, "测试用户"));
        verify(applications, never()).insertKgApplication(any());
        dto.setAge(25); dto.setSkills("摄影");
        KgApplication app = service.submit(dto, 9001L, "测试用户");
        assertEquals(25, app.getAge()); assertEquals("摄影", app.getSkills());
    }

    @Test void expiredRemoteTaskStillCannotBeTaken()
    {
        when(db.count(anyString(), anyMap())).thenReturn(1L);
        assertThrows(ServiceException.class, () -> service.submit(dto, 9001L, "测试用户"));
        verify(applications, never()).insertKgApplication(any());
    }

    @Test void duplicateRetryReturnsOriginalRemoteApplication()
    {
        KgApplication app = service.submit(dto, 9001L, "测试用户");
        when(idempotency.tryAcquire(anyString(), anyString())).thenReturn(false);
        when(idempotency.findBizId("remote-test")).thenReturn(91L);
        when(applications.selectKgApplicationByAppId(91L)).thenReturn(app);
        assertSame(app, service.submit(dto, 9001L, "测试用户"));
        verify(applications, times(1)).insertKgApplication(any());
        assertThrows(ServiceException.class, () -> service.submit(dto, 9002L, "其他用户"));
    }
}
