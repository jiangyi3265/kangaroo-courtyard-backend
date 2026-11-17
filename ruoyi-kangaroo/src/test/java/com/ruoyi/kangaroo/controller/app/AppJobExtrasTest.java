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

/** 用户端岗位详情的餐补、宠物、住宿环境（V66）：只在详情里带，读的时候按岗位类型和食宿收口；列表不变大（不连库）。 */
class AppJobExtrasTest
{
    private static final String A = "/profile/upload/2026/10/31/a1.jpg";
    private static final String VIDEO = "/profile/upload/videos/2026/10/31/0123456789abcdef0123456789abcdef.mp4";

    @AfterEach
    void cleanup()
    {
        AppUserContext.remove();
    }

    private static KgJob job(String type, String board)
    {
        KgJob j = new KgJob();
        j.setJobId(10005L);
        j.setStoreId(102L);
        j.setJobType(type);
        j.setBoardType(board);
        j.setTitle("海边青旅前台");
        j.setStatus("published");
        j.setMealAllowance("15");
        j.setMealAllowanceUnit("day");
        j.setPetStatus("yes");
        j.setPetImages("[\"" + A + "\"]");
        j.setLodgingImages("[\"" + A + "\"]");
        j.setLodgingVideo(VIDEO);
        j.setLodgingVideoCover(A);
        return j;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detail(KgJob j)
    {
        AppJobController controller = new AppJobController();
        IKgJobService jobs = mock(IKgJobService.class);
        IKgStoreService stores = mock(IKgStoreService.class);
        when(jobs.requireJob(10005L)).thenReturn(j);
        KgStore store = new KgStore();
        store.setStoreId(102L);
        store.setStatus("0");
        store.setStoreName("平潭袋鼠小院");
        when(stores.selectKgStoreByStoreId(102L)).thenReturn(store);
        ReflectionTestUtils.setField(controller, "kgJobService", jobs);
        ReflectionTestUtils.setField(controller, "kgStoreService", stores);
        ReflectionTestUtils.setField(controller, "kgJobFavoriteMapper", mock(KgJobFavoriteMapper.class));
        ReflectionTestUtils.setField(controller, "storeFollows", mock(com.ruoyi.kangaroo.ext.user.KgStoreFollowService.class));
        AjaxResult r = controller.detail(10005L);
        return (Map<String, Object>) r.get("data");
    }

    @Test
    void volunteerSeesEverything()
    {
        Map<String, Object> d = detail(job("volunteer", "room"));
        assertEquals("15 元/天", d.get("mealAllowanceText"));
        assertEquals("15", d.get("mealAllowance"));
        assertEquals("day", d.get("mealAllowanceUnit"));
        assertEquals("yes", d.get("petStatus"));
        assertEquals(Arrays.asList(A), d.get("petImages"));
        assertEquals(Arrays.asList(A), d.get("lodgingImages"));
        assertEquals(VIDEO, d.get("lodgingVideo"));
        assertEquals(A, d.get("lodgingVideoCover"));
    }

    @Test
    void oldClientChangingBoardToAllHidesTheAllowanceWithoutLosingIt()
    {
        KgJob j = job("volunteer", "all");
        Map<String, Object> d = detail(j);
        assertEquals("", d.get("mealAllowanceText"));
        assertEquals("15", j.getMealAllowance(), "库里的值不动");
    }

    @Test
    void partTimeHasNoPetAndNoLodgingWithoutHousing()
    {
        Map<String, Object> d = detail(job("parttime", "none"));
        assertEquals("15 元/天", d.get("mealAllowanceText"));
        assertEquals("", d.get("petStatus"));
        assertEquals(Collections.emptyList(), d.get("petImages"));
        assertEquals(Collections.emptyList(), d.get("lodgingImages"));
        assertEquals("", d.get("lodgingVideo"));
    }

    @Test
    void onlineTaskHasNone()
    {
        Map<String, Object> d = detail(job("online", "none"));
        assertEquals("", d.get("mealAllowanceText"));
        assertEquals("", d.get("petStatus"));
        assertEquals(Collections.emptyList(), d.get("lodgingImages"));
        assertEquals("", d.get("lodgingVideo"));
    }

    @Test
    void oldJobsWithNullsAreFine()
    {
        KgJob j = new KgJob();
        j.setJobId(10005L);
        j.setStoreId(102L);
        j.setJobType("volunteer");
        j.setBoardType("all");
        j.setStatus("published");
        Map<String, Object> d = detail(j);
        assertEquals("", d.get("mealAllowanceText"));
        assertEquals("", d.get("petStatus"));
        assertEquals(Collections.emptyList(), d.get("petImages"));
        assertEquals(Collections.emptyList(), d.get("lodgingImages"));
        assertEquals("", d.get("lodgingVideo"));
        assertEquals("", d.get("lodgingVideoCover"));
    }

    @Test
    void listRowsStayTheSameSize()
    {
        Map<String, Object> row = ReflectionTestUtils.invokeMethod(new AppJobController(), "jobToMap", job("volunteer", "room"), false);
        for (String key : Arrays.asList("mealAllowance", "mealAllowanceUnit", "mealAllowanceText", "petStatus", "petImages", "lodgingImages", "lodgingVideo", "lodgingVideoCover"))
        {
            assertFalse(row.containsKey(key), key + " 只在详情里");
        }
    }
}
