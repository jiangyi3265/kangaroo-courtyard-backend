package com.ruoyi.kangaroo.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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

/**
 * 后台岗位管理的筛选条件（V48：性别偏好 / 休息排班 / 住宿房型）：白名单、按岗位类型和食宿收口、
 * 老版本表单不带字段时按原值（再收口一次），新增不带 = ''（不连库）
 */
class KgJobFilterFieldsAdminTest extends com.ruoyi.kangaroo.testsupport.WithJobPhoto
{
    @Test
    void adminCanSaveAndClearGenderPlanAndLegacyClientsPreserveIt()
    {
        KgJob old=job("volunteer","all","",""," ".trim());
        old.setGenderMaleCount(1); old.setGenderFemaleCount(1);
        when(jobs.selectKgJobByJobId(7L)).thenReturn(old);
        KgJob change=job("volunteer","all","","","");
        service.updateKgJob(change);
        assertEquals(1,updated().getGenderMaleCount());
        change=job("volunteer","all","","","");
        change.setGenderCountsEnabled(true); change.setGenderMaleCount(0); change.setGenderFemaleCount(2);
        service.updateKgJob(change);
        assertEquals("F",updated().getGenderPref());
        assertEquals(2,updated().getGenderFemaleCount());
        change=job("volunteer","all","","",""); change.setGenderCountsEnabled(false);
        service.updateKgJob(change); assertNull(updated().getGenderFemaleCount());
        assertFalse(updated().getGenderCountsEnabled());
    }

    @Test
    void genderSplitCannotLowerTotalBelowExistingReservations()
    {
        KgJob old=job("volunteer","all","","","");old.setReservedCount(2);
        when(jobs.selectKgJobByJobId(7L)).thenReturn(old);
        KgJob change=job("volunteer","all","","",""); change.setHeadcount(1);
        change.setGenderCountsEnabled(true);change.setGenderMaleCount(0);change.setGenderFemaleCount(1);
        assertThrows(ServiceException.class,()->service.updateKgJob(change));
        verify(jobs,never()).updateKgJobByAdmin(any());
    }

    private KgJobServiceImpl service;
    private KgJobMapper jobs;

    @BeforeEach
    void setUp()
    {
        service = new KgJobServiceImpl();
        jobs = mock(KgJobMapper.class);
        KgStoreMapper stores = mock(KgStoreMapper.class);
        when(stores.selectKgStoreByStoreId(102L)).thenReturn(new KgStore());
        KgOnlineTaskService tasks = mock(KgOnlineTaskService.class);
        when(tasks.syncFromJobEdit(anyLong(), anyLong(), anyString())).thenReturn("10月6日 09:30 前");
        ReflectionTestUtils.setField(service, "kgJobMapper", jobs);
        ReflectionTestUtils.setField(service, "kgStoreMapper", stores);
        ReflectionTestUtils.setField(service, "onlineTasks", tasks);
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

    private static KgJob job(String type, String board, String gender, String rest, String room)
    {
        KgJob j = new KgJob();
        j.setImages(PHOTO);
        j.setJobId(7L); j.setStoreId(102L); j.setTitle("海边小院"); j.setCity("平潭"); j.setJobType(type);
        j.setHeadcount(2); j.setSalaryType("none"); j.setHiredCount(0); j.setBoardType(board);
        j.setGenderPref(gender); j.setRestMode(rest); j.setRoomType(room);
        if ("online".equals(type))
        {
            j.setSalaryType("piece"); j.setSalaryMin(new BigDecimal("20")); j.setSalaryMax(new BigDecimal("20"));
        }
        return j;
    }

    private KgJob inserted()
    {
        ArgumentCaptor<KgJob> c = ArgumentCaptor.forClass(KgJob.class);
        verify(jobs).insertKgJob(c.capture());
        return c.getValue();
    }

    private KgJob updated()
    {
        ArgumentCaptor<KgJob> c = ArgumentCaptor.forClass(KgJob.class);
        verify(jobs, atLeastOnce()).updateKgJobByAdmin(c.capture());
        return c.getValue();
    }

    private static String fields(KgJob j)
    {
        return j.getGenderPref() + "|" + j.getRestMode() + "|" + j.getRoomType();
    }

    @Test
    void volunteerKeepsWhatWasChosen()
    {
        service.insertKgJob(job("volunteer", "none", "F", "5-2", "single"));
        assertEquals("F|5-2|single", fields(inserted()));
    }

    @Test
    void newJobWithoutTheFieldsSavesEmpty()
    {
        service.insertKgJob(job("volunteer", "all", null, null, null));
        assertEquals("||", fields(inserted()));
    }

    @Test
    void outOfScopeValuesAreCleared()
    {
        // 兼职：不写性别；食宿不包住就没有房型
        service.insertKgJob(job("parttime", "none", "M", "6-1", "double"));
        assertEquals("|6-1|", fields(inserted()));
        reset(jobs);
        when(jobs.insertKgJob(any())).thenReturn(1);
        // 全职包住宿：房型保留
        service.insertKgJob(job("fulltime", "room", "F", "", "multi"));
        assertEquals("||multi", fields(inserted()));
    }

    @Test
    void labelsAreAcceptedAndUnknownValuesRejected()
    {
        service.insertKgJob(job("volunteer", "all", "女", "做六休一", "双人间"));
        assertEquals("F|6-1|double", fields(inserted()));
        for (String[] bad : new String[][] { { "X", "", "" }, { "", "9-9", "" }, { "", "", "suite" } })
        {
            ServiceException e = assertThrows(ServiceException.class, () -> service.insertKgJob(job("volunteer", "all", bad[0], bad[1], bad[2])));
            assertEquals(Integer.valueOf(422), e.getCode());
        }
    }

    @Test
    void oldAdminFormKeepsStoredValuesButReappliesTheScope()
    {
        when(jobs.selectKgJobByJobId(7L)).thenReturn(job("volunteer", "all", "F", "5-2", "single"));
        // 老版本表单不带这三个字段：原样保留
        service.updateKgJob(job("volunteer", "all", null, null, null));
        assertEquals("F|5-2|single", fields(updated()));
        // 改成兼职、食宿改成不包：残留的性别、房型清掉，排班保留
        reset(jobs);
        when(jobs.updateKgJobByAdmin(any())).thenReturn(1);
        when(jobs.selectKgJobByJobId(7L)).thenReturn(job("volunteer", "all", "F", "5-2", "single"));
        service.updateKgJob(job("parttime", "none", null, null, null));
        assertEquals("|5-2|", fields(updated()));
        // 表单没带食宿时按库里的食宿判断（包吃包住 → 房型保留）
        reset(jobs);
        when(jobs.updateKgJobByAdmin(any())).thenReturn(1);
        when(jobs.selectKgJobByJobId(7L)).thenReturn(job("fulltime", "all", "", "", "triple"));
        service.updateKgJob(job("fulltime", null, null, null, null));
        assertEquals("||triple", fields(updated()));
    }

    @Test
    void clearingInTheFormReallyClears()
    {
        when(jobs.selectKgJobByJobId(7L)).thenReturn(job("volunteer", "all", "F", "5-2", "single"));
        service.updateKgJob(job("volunteer", "all", "", "", ""));
        assertEquals("||", fields(updated()));
    }

    @Test
    void onlineTasksNeverCarryTheFields()
    {
        when(jobs.selectKgJobByJobId(7L)).thenReturn(job("online", "none", "", "", ""));
        service.updateKgJob(job("online", "none", "F", "5-2", "single"));
        assertEquals("||", fields(updated()));
    }

    @Test
    void unknownStoredValueIsTreatedAsEmpty()
    {
        // 只可能是手改库：不让它挡住后台保存
        when(jobs.selectKgJobByJobId(7L)).thenReturn(job("volunteer", "all", "Z", "x", "y"));
        service.updateKgJob(job("volunteer", "all", null, null, null));
        assertEquals("||", fields(updated()));
    }
}
