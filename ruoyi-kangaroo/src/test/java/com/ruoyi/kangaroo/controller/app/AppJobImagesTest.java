package com.ruoyi.kangaroo.controller.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.Collections;
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

/** 岗位列表行 / 详情带上照片（V49）：images 是数组（可能为空），cover = 第一张 → 岗位原来的封面 → 门店封面（不连库） */
class AppJobImagesTest
{
    private static final String A = "/profile/upload/2026/10/16/a1.jpg";
    private static final String B = "/profile/upload/2026/10/16/b2.jpg";

    @AfterEach
    void cleanup()
    {
        AppUserContext.remove();
    }

    private static KgJob job(String images, String cover, String storeCover)
    {
        KgJob j = new KgJob();
        j.setJobId(10005L); j.setStoreId(102L); j.setJobType("volunteer"); j.setTitle("海边青旅前台"); j.setStatus("published");
        j.setImages(images); j.setCover(cover); j.setStoreCover(storeCover);
        return j;
    }

    private static Map<String, Object> row(KgJob j)
    {
        return ReflectionTestUtils.invokeMethod(new AppJobController(), "jobToMap", j, false);
    }

    @Test
    void rowsCarryImagesAndCoverWithFallbacks()
    {
        Map<String, Object> withPhotos = row(job("[\"" + A + "\",\"" + B + "\"]", "/profile/upload/old.jpg", "/profile/upload/store.jpg"));
        assertEquals(Arrays.asList(A, B), withPhotos.get("images"));
        assertEquals(A, withPhotos.get("cover"), "封面是第一张照片");
        Map<String, Object> jobCover = row(job("", "/profile/upload/old.jpg", "/profile/upload/store.jpg"));
        assertEquals(Collections.emptyList(), jobCover.get("images"), "没照片是空数组，不是 null");
        assertEquals("/profile/upload/old.jpg", jobCover.get("cover"));
        Map<String, Object> storeCover = row(job(null, null, "/profile/upload/store.jpg"));
        assertEquals("/profile/upload/store.jpg", storeCover.get("cover"), "岗位也没封面时用门店封面");
        assertEquals("", row(job(null, null, null)).get("cover"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void detailEndpointReturnsTheGallery()
    {
        AppJobController controller = new AppJobController();
        IKgJobService jobs = mock(IKgJobService.class);
        IKgStoreService stores = mock(IKgStoreService.class);
        when(jobs.requireJob(10005L)).thenReturn(job("[\"" + B + "\",\"" + A + "\"]", B, "/profile/upload/store.jpg"));
        KgStore store = new KgStore();
        store.setStoreId(102L); store.setStatus("0"); store.setStoreName("平潭袋鼠小院"); store.setCover("/profile/upload/store.jpg");
        when(stores.selectKgStoreByStoreId(102L)).thenReturn(store);
        ReflectionTestUtils.setField(controller, "kgJobService", jobs);
        ReflectionTestUtils.setField(controller, "kgStoreService", stores);
        ReflectionTestUtils.setField(controller, "kgJobFavoriteMapper", mock(KgJobFavoriteMapper.class));
        ReflectionTestUtils.setField(controller, "storeFollows", mock(com.ruoyi.kangaroo.ext.user.KgStoreFollowService.class));
        AjaxResult r = controller.detail(10005L);
        Map<String, Object> data = (Map<String, Object>) r.get("data");
        assertEquals(Arrays.asList(B, A), data.get("images"));
        assertEquals(B, data.get("cover"));
        assertEquals("/profile/upload/store.jpg", ((Map<String, Object>) data.get("store")).get("cover"));
    }
}
