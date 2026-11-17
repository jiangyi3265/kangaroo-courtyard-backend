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
import com.ruoyi.kangaroo.ext.core.KgVideoUploads;
import com.ruoyi.kangaroo.mapper.KgJobMapper;
import com.ruoyi.kangaroo.mapper.KgStoreMapper;
import com.ruoyi.kangaroo.testsupport.UploadedImageFixture;

/** 后台岗位管理的餐补、宠物、住宿照片和视频（V66）：带了才校验、收口；老版本后台表单不带（null）时交给 mapper 保持原值。不连库 */
class KgJobExtrasAdminTest
{
    private static final String PHOTO = "/profile/upload/2026/10/31/p.jpg";
    private static final String A = "/profile/upload/2026/10/31/a1.jpg";
    private static final String B = "/profile/upload/2026/10/31/b2.jpg";
    private static final String VIDEO = "/profile/upload/videos/2026/10/31/0123456789abcdef0123456789abcdef.mp4";

    private KgJobServiceImpl service;
    private KgJobMapper jobs;
    private KgVideoUploads videos;
    private UploadedImageFixture files;

    @BeforeEach
    void setUp()
    {
        files = new UploadedImageFixture(PHOTO, A, B);
        service = new KgJobServiceImpl();
        jobs = mock(KgJobMapper.class);
        videos = mock(KgVideoUploads.class);
        when(videos.exists(VIDEO)).thenReturn(true);
        KgStoreMapper stores = mock(KgStoreMapper.class);
        when(stores.selectKgStoreByStoreId(102L)).thenReturn(new KgStore());
        ReflectionTestUtils.setField(service, "kgJobMapper", jobs);
        ReflectionTestUtils.setField(service, "kgStoreMapper", stores);
        ReflectionTestUtils.setField(service, "videos", videos);
        when(jobs.insertKgJob(any())).thenReturn(1);
        when(jobs.updateKgJobByAdmin(any())).thenReturn(1);
        SysUser user = new SysUser();
        user.setUserId(1L);
        user.setUserName("ops-test");
        LoginUser login = new LoginUser();
        login.setUser(user);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login, null, Collections.emptyList()));
    }

    @AfterEach
    void tearDown()
    {
        SecurityContextHolder.clearContext();
        files.close();
    }

    private static KgJob job(String type, String board)
    {
        KgJob j = new KgJob();
        j.setJobId(7L);
        j.setStoreId(102L);
        j.setTitle("海边小院");
        j.setCity("平潭");
        j.setJobType(type);
        j.setHeadcount(2);
        j.setSalaryType("none");
        j.setHiredCount(0);
        j.setBoardType(board);
        j.setImages(PHOTO);
        return j;
    }

    private KgJob saved(KgJob existing, KgJob form)
    {
        when(jobs.selectKgJobByJobId(7L)).thenReturn(existing);
        service.updateKgJob(form);
        ArgumentCaptor<KgJob> c = ArgumentCaptor.forClass(KgJob.class);
        verify(jobs).updateKgJobByAdmin(c.capture());
        return c.getValue();
    }

    @Test
    void oldAdminFormLeavesEverythingNullSoTheMapperKeepsIt()
    {
        KgJob existing = job("volunteer", "room");
        existing.setMealAllowance("30");
        existing.setPetStatus("yes");
        KgJob s = saved(existing, job("volunteer", "all"));
        assertNull(s.getMealAllowance(), "没带 = null，updateKgJobByAdmin 不动这一列；食宿改成包吃也不清（读的时候收口）");
        assertNull(s.getMealAllowanceUnit());
        assertNull(s.getPetStatus());
        assertNull(s.getPetImages());
        assertNull(s.getLodgingImages());
        assertNull(s.getLodgingVideo());
        assertNull(s.getLodgingVideoCover());
    }

    @Test
    void newAdminFormIsValidatedAndStoredLikeTheMiniProgram()
    {
        KgJob form = job("volunteer", "room");
        form.setMealAllowance("15.50");
        form.setMealAllowanceUnit("day");
        form.setPetStatus("yes");
        form.setPetImages(A + "," + B);
        form.setLodgingImages("[\"" + A + "\"]");
        form.setLodgingVideo(VIDEO);
        form.setLodgingVideoCover(B);
        KgJob s = saved(job("volunteer", "room"), form);
        assertEquals("15.5", s.getMealAllowance());
        assertEquals("day", s.getMealAllowanceUnit());
        assertEquals("yes", s.getPetStatus());
        assertEquals("[\"" + A + "\",\"" + B + "\"]", s.getPetImages());
        assertEquals("[\"" + A + "\"]", s.getLodgingImages());
        assertEquals(VIDEO, s.getLodgingVideo());
        assertEquals(B, s.getLodgingVideoCover());
    }

    @Test
    void adminFormCollapsesByTypeAndBoard()
    {
        KgJob form = job("parttime", "none");
        form.setMealAllowance("20");
        form.setMealAllowanceUnit("day");
        form.setPetStatus("yes");
        form.setPetImages(A);
        form.setLodgingImages(A);
        form.setLodgingVideo(VIDEO);
        KgJob s = saved(job("parttime", "none"), form);
        assertEquals("20", s.getMealAllowance(), "不包食宿有餐补");
        assertEquals("", s.getPetStatus(), "宠物只给换工");
        assertEquals("", s.getPetImages());
        assertEquals("", s.getLodgingImages(), "不包住");
        assertEquals("", s.getLodgingVideo());
    }

    @Test
    void emptyTextClearsAndBadValuesAreRefused()
    {
        KgJob form = job("volunteer", "room");
        form.setPetStatus("");
        form.setLodgingVideo("");
        KgJob s = saved(job("volunteer", "room"), form);
        assertEquals("", s.getPetStatus());
        assertEquals("", s.getPetImages(), "没填宠物，照片一起清");
        assertEquals("", s.getLodgingVideo());
        assertEquals("", s.getLodgingVideoCover());

        KgJob bad = job("volunteer", "room");
        bad.setMealAllowance("很多");
        bad.setMealAllowanceUnit("day");
        ServiceException e = assertThrows(ServiceException.class, () -> service.updateKgJob(bad));
        assertEquals(422, e.getCode());
        KgJob badVideo = job("volunteer", "room");
        badVideo.setLodgingVideo("https://evil.example/v.mp4");
        assertEquals(422, assertThrows(ServiceException.class, () -> service.updateKgJob(badVideo)).getCode());
    }

    @Test
    void formWithoutBoardFallsBackToTheStoredBoard()
    {
        KgJob form = job("parttime", null);
        form.setMealAllowance("20");
        form.setMealAllowanceUnit("day");
        KgJob s = saved(job("parttime", "all"), form);
        assertEquals("", s.getMealAllowance(), "表单没带食宿，按库里的包吃包住算");
    }

    @Test
    void createKeepsNullsWhenNothingIsGiven()
    {
        KgJob form = job("volunteer", "room");
        service.insertKgJob(form);
        ArgumentCaptor<KgJob> c = ArgumentCaptor.forClass(KgJob.class);
        verify(jobs).insertKgJob(c.capture());
        assertNull(c.getValue().getMealAllowance());
        assertNull(c.getValue().getLodgingImages());
    }
}
