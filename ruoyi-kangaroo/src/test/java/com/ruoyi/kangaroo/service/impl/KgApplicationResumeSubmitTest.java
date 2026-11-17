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

/**
 * 2026-10-18 报名改成「用简历投递」：新版报名页只传岗位、到岗日期、时长，姓名 / 年龄 / 手机 / 技能由服务端从「我的简历」取；
 * 老版本小程序照旧自己传，传了的以传的为准；简历缺项时照旧报原来的错。
 */
class KgApplicationResumeSubmitTest
{
    private KgApplicationServiceImpl service;
    private KgApplicationMapper applications;
    private KgJdbc db;
    private KgJob job;
    private ApplicationSubmitDTO dto;

    @BeforeEach
    void setup()
    {
        service = new KgApplicationServiceImpl();
        applications = mock(KgApplicationMapper.class);
        KgJobMapper jobs = mock(KgJobMapper.class);
        IKgIdempotencyService idempotency = mock(IKgIdempotencyService.class);
        db = mock(KgJdbc.class);
        ReflectionTestUtils.setField(service, "kgApplicationMapper", applications);
        ReflectionTestUtils.setField(service, "kgJobMapper", jobs);
        ReflectionTestUtils.setField(service, "idempotencyService", idempotency);
        ReflectionTestUtils.setField(service, "kgJdbcForQuota", db);
        ReflectionTestUtils.setField(service, "kgApplicationLogMapper", mock(KgApplicationLogMapper.class));
        ReflectionTestUtils.setField(service, "kgMessageService", mock(IKgMessageService.class));
        ReflectionTestUtils.setField(service, "kgStaffGrantService", mock(IKgStaffGrantService.class));
        job = new KgJob(); job.setJobId(28L); job.setStoreId(102L); job.setJobType("volunteer");
        job.setStatus("published"); job.setTitle("古城咖啡馆换工"); job.setCity("大理");
        job.setHeadcount(3); job.setHiredCount(0); job.setMinDays(30);
        when(jobs.selectKgJobByJobId(28L)).thenReturn(job);
        // 门店状态查询（和简历查询都走 db.one）：先给门店正常
        when(db.one(anyString(), anyMap())).thenReturn(KgJdbc.p("status", "0", "delFlag", "0"));
        when(idempotency.tryAcquire(anyString(), anyString())).thenReturn(true);
        when(applications.insertKgApplication(any())).thenAnswer(call -> {
            call.getArgument(0, KgApplication.class).setAppId(301L); return 1;
        });
        dto = new ApplicationSubmitDTO(); dto.setJobId(28L); dto.setIdemKey("resume-test");
        dto.setArrival(LocalDate.now().plusDays(3).toString()); dto.setDays(30);
    }

    private void resume(Object... kv)
    {
        when(db.one(contains("kg_resume"), anyMap())).thenReturn(KgJdbc.p(kv));
    }

    @Test void onlineApplicantWithNoResumeCanSubmitContactFieldsOnly()
    {
        job.setJobType("online"); dto.setArrival(null); dto.setDays(null);
        dto.setName("新接单人"); dto.setPhone("13900001111"); dto.setNote("可远程完成");
        when(db.one(contains("kg_resume"), anyMap())).thenThrow(new AssertionError("Online application must not read a resume"));
        KgApplication app = service.submit(dto, 9001L, "新接单人");
        assertEquals("新接单人", app.getRealName()); assertEquals("13900001111", app.getPhone());
        assertNull(app.getAge()); assertNull(app.getSkills());
        verify(applications).insertKgApplication(app); verify(db, never()).one(contains("kg_resume"), anyMap());
    }

    @Test void newClientSubmitsWithResumeOnly()
    {
        resume("name", "小林", "age", 24, "phone", "13900001111", "skillText", "会做咖啡，拍照", "userPhone", "13800002222");
        KgApplication app = service.submit(dto, 9001L, "测试用户");
        assertEquals("小林", app.getRealName());
        assertEquals(24, app.getAge());
        assertEquals("13900001111", app.getPhone());
        assertEquals("会做咖啡，拍照", app.getSkills());
        assertEquals(30, app.getStayDays());
        verify(applications).insertKgApplication(app);
    }

    @Test void resumeWithoutPhoneFallsBackToAccountPhone()
    {
        resume("name", "小林", "age", 24, "phone", null, "skillText", "", "userPhone", "13800002222");
        KgApplication app = service.submit(dto, 9001L, "测试用户");
        assertEquals("13800002222", app.getPhone());
        assertNull(app.getSkills());
    }

    @Test void oldClientValuesWinOverResume()
    {
        resume("name", "小林", "age", 24, "phone", "13900001111", "skillText", "简历里的技能", "userPhone", "13800002222");
        dto.setName("林小姐"); dto.setAge(26); dto.setPhone("13700003333"); dto.setSkills("报名表里写的技能");
        KgApplication app = service.submit(dto, 9001L, "测试用户");
        assertEquals("林小姐", app.getRealName());
        assertEquals(26, app.getAge());
        assertEquals("13700003333", app.getPhone());
        assertEquals("报名表里写的技能", app.getSkills());
        // 四项都传了：不用再查简历
        verify(db, never()).one(contains("kg_resume"), anyMap());
    }

    @Test void missingResumeKeepsTheOriginalErrors()
    {
        // 没有简历、账号也没绑手机：和以前一样报「姓名不能为空」，不插入报名
        assertEquals("姓名不能为空", assertThrows(ServiceException.class, () -> service.submit(dto, 9001L, "测试用户")).getMessage());
        resume("name", "小林", "age", null, "phone", "13900001111", "userPhone", null);
        assertEquals("年龄不能为空", assertThrows(ServiceException.class, () -> service.submit(dto, 9001L, "测试用户")).getMessage());
        verify(applications, never()).insertKgApplication(any());
    }

    @Test void resumeDoesNotSkipStayAndArrivalRules()
    {
        resume("name", "小林", "age", 24, "phone", "13900001111", "userPhone", null);
        dto.setDays(10);
        assertThrows(ServiceException.class, () -> service.submit(dto, 9001L, "测试用户"));
        verify(applications, never()).insertKgApplication(any());
    }

    @Test void onlineTaskOnlyTakesNameAndPhoneFromResume()
    {
        job.setJobType("online");
        dto.setArrival(null); dto.setDays(null);
        resume("name", "小林", "age", 24, "phone", "13900001111", "skillText", "文案", "userPhone", null);
        KgApplication app = service.submit(dto, 9001L, "测试用户");
        assertEquals("小林", app.getRealName());
        assertEquals("13900001111", app.getPhone());
        assertNull(app.getAge()); assertNull(app.getSkills());
    }
}
