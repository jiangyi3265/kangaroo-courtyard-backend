package com.ruoyi.kangaroo.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
import com.ruoyi.kangaroo.ext.core.KgVolunteerKinds;
import com.ruoyi.kangaroo.mapper.KgJobMapper;
import com.ruoyi.kangaroo.mapper.KgStoreMapper;

/** 后台岗位管理的换工类型：只对换工岗位生效、必须是启用中的类型、老版本表单不带字段时保持原值（不连库） */
class KgJobWorkKindTest extends com.ruoyi.kangaroo.testsupport.WithJobPhoto
{
    private KgJobServiceImpl service;
    private KgJobMapper jobs;
    private KgVolunteerKinds kinds;

    @BeforeEach
    void setUp()
    {
        service = new KgJobServiceImpl();
        jobs = mock(KgJobMapper.class);
        KgStoreMapper stores = mock(KgStoreMapper.class);
        when(stores.selectKgStoreByStoreId(102L)).thenReturn(new KgStore());
        kinds = mock(KgVolunteerKinds.class);
        when(kinds.normalize(anyString(), anyString(), any())).thenAnswer(inv -> {
            String type = inv.getArgument(0), kind = inv.getArgument(1), keep = inv.getArgument(2);
            if (!"volunteer".equals(type) || kind.trim().isEmpty()) return "";
            if (kind.equals(keep) || "photo".equals(kind) || "daily".equals(kind)) return kind;
            throw new ServiceException("换工类型不存在或已停用，请重新选择", 422);
        });
        ReflectionTestUtils.setField(service, "kgJobMapper", jobs);
        ReflectionTestUtils.setField(service, "kgStoreMapper", stores);
        ReflectionTestUtils.setField(service, "volunteerKinds", kinds);
        when(jobs.insertKgJob(any())).thenReturn(1);
        when(jobs.updateKgJobByAdmin(any())).thenReturn(1);
        SysUser user = new SysUser(); user.setUserId(1L); user.setUserName("ops-test");
        LoginUser login = new LoginUser(); login.setUser(user);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login, null, Collections.emptyList()));
    }

    @AfterEach
    void tearDown()
    {
        SecurityContextHolder.clearContext();
    }

    private static KgJob job(String type, String kind)
    {
        KgJob j = new KgJob();
        j.setImages(PHOTO);
        j.setJobId(7L); j.setStoreId(102L); j.setTitle("海边小院换工"); j.setCity("平潭"); j.setJobType(type);
        j.setHeadcount(2); j.setSalaryType("none"); j.setHiredCount(0); j.setWorkKind(kind);
        return j;
    }

    private KgJob savedOnInsert()
    {
        ArgumentCaptor<KgJob> c = ArgumentCaptor.forClass(KgJob.class);
        verify(jobs).insertKgJob(c.capture());
        return c.getValue();
    }

    private KgJob savedOnUpdate()
    {
        ArgumentCaptor<KgJob> c = ArgumentCaptor.forClass(KgJob.class);
        verify(jobs).updateKgJobByAdmin(c.capture());
        return c.getValue();
    }

    @Test
    void volunteerJobKeepsAValidKind()
    {
        service.insertKgJob(job("volunteer", "photo"));
        assertEquals("photo", savedOnInsert().getWorkKind());
    }

    @Test
    void otherJobTypesNeverCarryAKind()
    {
        service.insertKgJob(job("parttime", "photo"));
        assertEquals("", savedOnInsert().getWorkKind());
    }

    @Test
    void missingKindOnCreateIsStoredAsEmpty()
    {
        service.insertKgJob(job("volunteer", null));
        assertEquals("", savedOnInsert().getWorkKind());
    }

    @Test
    void unknownKindIsRefusedBeforeAnythingIsWritten()
    {
        ServiceException e = assertThrows(ServiceException.class, () -> service.insertKgJob(job("volunteer", "spaceship")));
        assertEquals(Integer.valueOf(422), e.getCode());
        verify(jobs, never()).insertKgJob(any());
    }

    @Test
    void oldAdminFormWithoutTheFieldKeepsTheStoredKind()
    {
        when(jobs.selectKgJobByJobId(7L)).thenReturn(job("volunteer", "daily"));
        service.updateKgJob(job("volunteer", null));
        // null = 这次没带这个字段：全量更新语句里 <if test="workKind != null"> 跳过，库里保持 daily
        assertNull(savedOnUpdate().getWorkKind());
    }

    @Test
    void editingKeepsADisabledKindTheJobAlreadyHad()
    {
        when(jobs.selectKgJobByJobId(7L)).thenReturn(job("volunteer", "live"));
        service.updateKgJob(job("volunteer", "live"));
        assertEquals("live", savedOnUpdate().getWorkKind());
        verify(kinds).normalize("volunteer", "live", "live");
    }

    @Test
    void switchingAwayFromVolunteerClearsTheKind()
    {
        when(jobs.selectKgJobByJobId(7L)).thenReturn(job("volunteer", "photo"));
        service.updateKgJob(job("fulltime", "photo"));
        assertEquals("", savedOnUpdate().getWorkKind());
    }

    @Test
    void clearingTheKindOnEditIsAllowed()
    {
        when(jobs.selectKgJobByJobId(7L)).thenReturn(job("volunteer", "photo"));
        service.updateKgJob(job("volunteer", ""));
        assertEquals("", savedOnUpdate().getWorkKind());
    }
}
