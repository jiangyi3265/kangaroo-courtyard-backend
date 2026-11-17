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
import com.ruoyi.kangaroo.mapper.KgJobMapper;
import com.ruoyi.kangaroo.mapper.KgStoreMapper;
import com.ruoyi.kangaroo.testsupport.UploadedImageFixture;

/**
 * 后台岗位管理的岗位照片（V49）：图片上传组件给逗号分隔的路径（也认 JSON 数组），存成 JSON 数组、最多 9 张、只收平台上传的；
 * 封面跟着第一张；清空照片时封面若还是原来的第一张就退回门店封面；老版本表单不带这个字段时不动（不连库）
 */
class KgJobImagesAdminTest
{
    private static final String A = "/profile/upload/2026/10/16/a1.jpg";
    private static final String B = "/profile/upload/2026/10/16/b2.jpg";

    private KgJobServiceImpl service;
    private KgJobMapper jobs;
    private UploadedImageFixture imageFiles;

    @BeforeEach
    void setUp()
    {
        imageFiles = new UploadedImageFixture(A, B);
        service = new KgJobServiceImpl();
        jobs = mock(KgJobMapper.class);
        KgStoreMapper stores = mock(KgStoreMapper.class);
        KgStore store = new KgStore();
        store.setCover("/profile/upload/2026/10/01/store.jpg");
        when(stores.selectKgStoreByStoreId(102L)).thenReturn(store);
        ReflectionTestUtils.setField(service, "kgJobMapper", jobs);
        ReflectionTestUtils.setField(service, "kgStoreMapper", stores);
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
        imageFiles.close();
    }

    private static KgJob job(String images, String cover)
    {
        KgJob j = new KgJob();
        j.setJobId(7L); j.setStoreId(102L); j.setTitle("海边小院"); j.setCity("平潭"); j.setJobType("volunteer");
        j.setHeadcount(2); j.setSalaryType("none"); j.setHiredCount(0); j.setBoardType("all");
        j.setImages(images); j.setCover(cover);
        return j;
    }

    private KgJob inserted()
    {
        ArgumentCaptor<KgJob> c = ArgumentCaptor.forClass(KgJob.class);
        verify(jobs).insertKgJob(c.capture());
        return c.getValue();
    }

    private KgJob saved()
    {
        ArgumentCaptor<KgJob> c = ArgumentCaptor.forClass(KgJob.class);
        verify(jobs, atLeastOnce()).updateKgJobByAdmin(c.capture());
        return c.getValue();
    }

    @Test
    void addWithUploadedPhotosStoresJsonAndUsesTheFirstAsCover()
    {
        service.insertKgJob(job(B + "," + A, "/static/media/jobs-vol-1.jpg"));
        KgJob j = inserted();
        assertEquals("[\"" + B + "\",\"" + A + "\"]", j.getImages());
        assertEquals(B, j.getCover());
    }

    @Test
    void missingPhotosCannotCreateButExistingPhotosCanBeKept()
    {
        for (String type : new String[] { "volunteer", "parttime", "fulltime" }) {
            for (String photos : new String[] { null, "", "[]", "[\" \" ]" }) {
                KgJob missing = job(photos, A); missing.setJobType(type);
                assertEquals(Integer.valueOf(422), assertThrows(ServiceException.class, () -> service.insertKgJob(missing)).getCode());
            }
        }
        verify(jobs, never()).insertKgJob(any());
        KgJob exist = job("[\"" + A + "\"]", A);
        when(jobs.selectKgJobByJobId(7L)).thenReturn(exist);
        service.updateKgJob(job(null, A));
        assertEquals("[\"" + A + "\"]", saved().getImages());
    }

    @Test
    void clearingAllPhotosOrEditingAnImagelessJobIsRejected()
    {
        when(jobs.selectKgJobByJobId(7L)).thenReturn(job("[\"" + A + "\",\"" + B + "\"]", A));
        assertThrows(ServiceException.class, () -> service.updateKgJob(job("", A)));
        assertThrows(ServiceException.class, () -> service.updateKgJob(job("[]", "/static/media/jobs-vol-2.jpg")));
        when(jobs.selectKgJobByJobId(7L)).thenReturn(job(null, A));
        assertThrows(ServiceException.class, () -> service.updateKgJob(job(null, A)));
        verify(jobs, never()).updateKgJobByAdmin(any());
    }

    @Test
    void publishAndResumeRequirePhotosButPausingDoesNot()
    {
        for (String type : new String[] { "volunteer", "parttime", "fulltime" }) {
            for (String status : new String[] { "draft", "paused" }) {
                KgJob missing = job("", A); missing.setStatus(status); missing.setJobType(type);
                when(jobs.selectKgJobByJobId(7L)).thenReturn(missing);
                assertEquals(Integer.valueOf(422), assertThrows(ServiceException.class, () -> service.changeStatus(7L, "published", null)).getCode());
            }
        }
        verify(jobs, never()).updateKgJob(any());
        when(jobs.updateKgJob(any())).thenReturn(1);
        KgJob valid = job(A, A); valid.setStatus("draft");
        when(jobs.selectKgJobByJobId(7L)).thenReturn(valid);
        assertDoesNotThrow(() -> service.changeStatus(7L, "published", null));
        KgJob legacy = job("", A); legacy.setStatus("published");
        when(jobs.selectKgJobByJobId(7L)).thenReturn(legacy);
        assertDoesNotThrow(() -> service.changeStatus(7L, "paused", null));
        verify(jobs, times(2)).updateKgJob(any());
    }

    @Test
    void createAndPublishRejectMissingOrDisguisedPhotosBeforeWriting() throws java.io.IOException
    {
        String fake = "/profile/upload/2026/10/27/renamed.jpg";
        imageFiles.write(fake, "not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (String photo : new String[] { "/profile/upload/missing.jpg", "/profile/upload/uploaded.mp4", fake }) {
            assertThrows(ServiceException.class, () -> service.insertKgJob(job(photo, photo)));
            for (String status : new String[] { "draft", "paused" }) {
                KgJob stored = job(photo, photo); stored.setStatus(status);
                when(jobs.selectKgJobByJobId(7L)).thenReturn(stored);
                assertThrows(ServiceException.class, () -> service.changeStatus(7L, "published", null));
            }
        }
        verify(jobs, never()).insertKgJob(any());
        verify(jobs, never()).updateKgJob(any());
    }

    @Test
    void reorderingMovesTheCover()
    {
        when(jobs.selectKgJobByJobId(7L)).thenReturn(job("[\"" + A + "\",\"" + B + "\"]", A));
        service.updateKgJob(job("[\"" + B + "\",\"" + A + "\"]", A));
        KgJob j = saved();
        assertEquals("[\"" + B + "\",\"" + A + "\"]", j.getImages());
        assertEquals(B, j.getCover());
    }

    @Test
    void foreignPathsAndMoreThanNineAreRejected()
    {
        ServiceException e = assertThrows(ServiceException.class, () -> service.insertKgJob(job("https://example.com/a.jpg", null)));
        assertEquals(Integer.valueOf(422), e.getCode());
        StringBuilder ten = new StringBuilder();
        for (int i = 0; i < 10; i++)
        {
            ten.append(i == 0 ? "" : ",").append("/profile/upload/2026/10/16/p").append(i).append(".jpg");
        }
        assertThrows(ServiceException.class, () -> service.insertKgJob(job(ten.toString(), null)));
        verify(jobs, never()).insertKgJob(any());
    }
}
