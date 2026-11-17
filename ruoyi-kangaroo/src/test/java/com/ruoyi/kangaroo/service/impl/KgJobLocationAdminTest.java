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
import com.ruoyi.kangaroo.mapper.KgJobMapper;
import com.ruoyi.kangaroo.mapper.KgStoreMapper;
import com.ruoyi.kangaroo.testsupport.UploadedImageFixture;

/**
 * 后台「岗位管理」保存时的工作地点坐标（V42）。后台表单没有坐标输入框，但打开编辑时会把库里的坐标随整个对象带回来再原样提交：
 * 没改地点 → 保留门店在小程序里选好的点（老页面不带坐标也保留）；改了地点文字 → 没带坐标或带回旧点都清掉；带了新坐标 → 校验后保存（不连库）
 */
class KgJobLocationAdminTest
{
    private static final BigDecimal LAT = new BigDecimal("25.600000");
    private static final BigDecimal LNG = new BigDecimal("119.300000");
    private static final String PHOTO = "/profile/upload/location-test.jpg";
    private UploadedImageFixture imageFiles;

    private KgJobServiceImpl service;
    private KgJobMapper jobs;
    private KgJob exist;

    @BeforeEach
    void setUp()
    {
        imageFiles = new UploadedImageFixture(PHOTO);
        service = new KgJobServiceImpl();
        jobs = mock(KgJobMapper.class);
        KgStoreMapper stores = mock(KgStoreMapper.class);
        when(stores.selectKgStoreByStoreId(102L)).thenReturn(new KgStore());
        ReflectionTestUtils.setField(service, "kgJobMapper", jobs);
        ReflectionTestUtils.setField(service, "kgStoreMapper", stores);
        when(jobs.insertKgJob(any())).thenReturn(1);
        when(jobs.updateKgJobByAdmin(any())).thenReturn(1);
        exist = job("福州市闽侯县南舍庄园", LAT, LNG);
        exist.setHiredCount(0);
        when(jobs.selectKgJobByJobId(7L)).thenAnswer(inv -> exist);
        SysUser user = new SysUser(); user.setUserId(1L); user.setUserName("ops-test");
        LoginUser login = new LoginUser(); login.setUser(user);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login, null, Collections.emptyList()));
    }

    @AfterEach
    void tearDown()
    {
        SecurityContextHolder.clearContext();
        imageFiles.close();
    }

    private static KgJob job(String address, BigDecimal lat, BigDecimal lng)
    {
        KgJob j = new KgJob();
        j.setJobId(7L); j.setStoreId(102L); j.setTitle("南舍庄园直播换工"); j.setCity("福州"); j.setJobType("volunteer");
        j.setHeadcount(2); j.setSalaryType("none"); j.setWorkKind(""); j.setAddress(address); j.setLatitude(lat); j.setLongitude(lng);
        j.setImages(PHOTO);
        return j;
    }

    private KgJob saved()
    {
        ArgumentCaptor<KgJob> c = ArgumentCaptor.forClass(KgJob.class);
        verify(jobs).updateKgJobByAdmin(c.capture());
        return c.getValue();
    }

    private static void assertPoint(KgJob j, String lat, String lng)
    {
        if (lat == null)
        {
            assertNull(j.getLatitude());
            assertNull(j.getLongitude());
            return;
        }
        assertEquals(0, new BigDecimal(lat).compareTo(j.getLatitude()), String.valueOf(j.getLatitude()));
        assertEquals(0, new BigDecimal(lng).compareTo(j.getLongitude()), String.valueOf(j.getLongitude()));
    }

    @Test
    void untouchedAddressWithoutCoordinatesKeepsThePickedPoint()
    {
        // 更早打开的后台页面根本不带坐标字段：不能一保存就把小程序里选好的点清掉
        service.updateKgJob(job("福州市闽侯县南舍庄园", null, null));
        assertPoint(saved(), "25.6", "119.3");
    }

    @Test
    void roundTrippedCoordinatesWithTheSameAddressAreKept()
    {
        // 表单整对象回填：坐标原样带回来（JSON 里可能是 25.6 这种短写法）
        service.updateKgJob(job(" 福州市闽侯县南舍庄园 ", new BigDecimal("25.6"), new BigDecimal("119.3")));
        KgJob s = saved();
        assertPoint(s, "25.6", "119.3");
        assertEquals(6, s.getLatitude().scale(), "统一 6 位小数");
    }

    @Test
    void changedAddressWithoutCoordinatesClearsThePoint()
    {
        service.updateKgJob(job("平潭县君山片区李喵喵民宿", null, null));
        assertPoint(saved(), null, null);
    }

    @Test
    void changedAddressWithTheRoundTrippedOldPointClearsIt()
    {
        // 运营只改了地址文字，表单还带着库里那个旧点：点没跟着改，不能留下指向旧地址的导航
        service.updateKgJob(job("平潭县君山片区李喵喵民宿", new BigDecimal("25.6"), new BigDecimal("119.30")));
        assertPoint(saved(), null, null);
    }

    @Test
    void changedAddressWithANewPointSavesTheNewPoint()
    {
        service.updateKgJob(job("平潭县君山片区李喵喵民宿", new BigDecimal("25.4987654"), new BigDecimal("119.7")));
        assertPoint(saved(), "25.498765", "119.7");
    }

    @Test
    void clearingTheAddressAlsoClearsThePoint()
    {
        service.updateKgJob(job("", LAT, LNG));
        assertPoint(saved(), null, null);
    }

    @Test
    void jobWithoutAPointStaysWithout()
    {
        exist = job("福州市闽侯县南舍庄园", null, null);
        exist.setHiredCount(0);
        service.updateKgJob(job("福州市闽侯县南舍庄园", null, null));
        assertPoint(saved(), null, null);
    }

    @Test
    void sentCoordinatesAreValidated()
    {
        for (BigDecimal[] bad : new BigDecimal[][] {
                { new BigDecimal("25.6"), null }, { null, new BigDecimal("119.3") },
                { new BigDecimal("91"), new BigDecimal("119.3") }, { new BigDecimal("25.6"), new BigDecimal("-180.5") },
                { BigDecimal.ZERO, BigDecimal.ZERO } })
        {
            ServiceException e = assertThrows(ServiceException.class, () -> service.updateKgJob(job("南舍庄园", bad[0], bad[1])));
            assertEquals(Integer.valueOf(422), e.getCode(), e.getMessage());
            ServiceException ins = assertThrows(ServiceException.class, () -> service.insertKgJob(job("南舍庄园", bad[0], bad[1])));
            assertEquals(Integer.valueOf(422), ins.getCode(), ins.getMessage());
        }
        verify(jobs, never()).updateKgJobByAdmin(any());
        verify(jobs, never()).insertKgJob(any());
    }

    @Test
    void insertSavesValidCoordinatesAndNothingOtherwise()
    {
        service.insertKgJob(job("南舍庄园", new BigDecimal("25.6"), new BigDecimal("119.3")));
        ArgumentCaptor<KgJob> c = ArgumentCaptor.forClass(KgJob.class);
        verify(jobs).insertKgJob(c.capture());
        assertPoint(c.getValue(), "25.6", "119.3");
        reset(jobs);
        when(jobs.insertKgJob(any())).thenReturn(1);
        service.insertKgJob(job("南舍庄园", null, null));
        verify(jobs).insertKgJob(c.capture());
        assertPoint(c.getValue(), null, null);
    }

    @Test
    void onlineTasksNeverKeepCoordinates()
    {
        exist.setJobType("online");
        exist.setAddress("远程");
        KgJob j = job("远程", LAT, LNG);
        j.setJobType("online");
        j.setSalaryType("piece"); j.setSalaryMin(new BigDecimal("50")); j.setSalaryMax(new BigDecimal("50"));
        // 线上任务改价要同步扩展表；这里只看坐标，没有扩展服务时会在同步那一步拒绝 —— 坐标在那之前已经清掉
        assertThrows(ServiceException.class, () -> service.updateKgJob(j));
        assertNull(j.getLatitude());
        assertNull(j.getLongitude());
    }
}
