package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.controller.app.AppJobController;
import com.ruoyi.kangaroo.domain.KgJob;
import com.ruoyi.kangaroo.domain.KgStore;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.mapper.KgJobFavoriteMapper;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.service.IKgJobService;
import com.ruoyi.kangaroo.service.IKgStoreService;

/**
 * 岗位详情「我在这家店做过，写评价」（2026-10-16 内测反馈 2.0）：GET /app/job/{id} 多给 myReviewableAppId——
 * 当前用户在这家店已入职（onboarded / completed）、不是线上任务、还没评价过的最新一条报名；游客 / 没有为 null。
 * 口径和写评价接口一致；真实 SQL 在 8201 端到端跑过（见交付说明）。
 */
class KgReviewEntryServiceTest
{
    final KgJdbc db = mock(KgJdbc.class);
    final KgReviewEntryService service = new KgReviewEntryService();

    KgReviewEntryServiceTest()
    {
        ReflectionTestUtils.setField(service, "db", db);
    }

    @AfterEach
    void cleanup()
    {
        AppUserContext.remove();
    }

    @Test
    void findsTheLatestOnboardedUnreviewedApplicationAtThisStore()
    {
        when(db.one(anyString(), anyMap())).thenReturn(p("appId", 31L));
        assertEquals(Long.valueOf(31L), service.myReviewableAppId(7L, 102L));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db).one(sql.capture(), eq(p("u", 7L, "s", 102L)));
        String q = sql.getValue();
        assertTrue(q.contains("a.app_user_id = :u and coalesce(j.recruit_store_id,j.store_id) = :s and a.del_flag = '0'"), q);
        assertTrue(q.contains("a.status in ('onboarded','completed')"), q);
        assertTrue(q.contains("j.job_type <> 'online'"), q);
        assertTrue(q.contains("not exists (select 1 from kg_review v where v.biz_type = 'application' and v.biz_id = a.app_id and v.app_user_id = :u)"), q);
        assertTrue(q.endsWith("order by a.app_id desc limit 1"), q);
    }

    @Test
    void nothingToReviewOrGuestIsNull()
    {
        assertNull(service.myReviewableAppId(7L, 102L));
        assertNull(service.myReviewableAppId(null, 102L));
        assertNull(service.myReviewableAppId(7L, null));
        verify(db, times(1)).one(anyString(), anyMap());
    }

    private AppJobController controller(KgReviewEntryService entry)
    {
        AppJobController controller = new AppJobController();
        IKgJobService jobs = mock(IKgJobService.class);
        IKgStoreService stores = mock(IKgStoreService.class);
        KgJob j = new KgJob();
        j.setJobId(10005L); j.setStoreId(102L); j.setJobType("volunteer"); j.setTitle("前台换工"); j.setStatus("published"); j.setCity("平潭");
        when(jobs.requireJob(10005L)).thenReturn(j);
        KgStore store = new KgStore();
        store.setStoreId(102L); store.setStatus("0"); store.setStoreName("平潭袋鼠小院");
        when(stores.selectKgStoreByStoreId(102L)).thenReturn(store);
        ReflectionTestUtils.setField(controller, "kgJobService", jobs);
        ReflectionTestUtils.setField(controller, "kgStoreService", stores);
        ReflectionTestUtils.setField(controller, "kgJobFavoriteMapper", mock(KgJobFavoriteMapper.class));
        ReflectionTestUtils.setField(controller, "storeFollows", mock(KgStoreFollowService.class));
        ReflectionTestUtils.setField(controller, "reviewEntry", entry);
        return controller;
    }

    @Test
    @SuppressWarnings("unchecked")
    void jobDetailCarriesMyReviewableAppId()
    {
        AppLoginUser me = new AppLoginUser();
        me.setUserId(7L);
        AppUserContext.set(me);
        when(db.one(anyString(), anyMap())).thenReturn(p("appId", 31L));
        AjaxResult r = controller(service).detail(10005L);
        Map<String, Object> data = (Map<String, Object>) r.get("data");
        assertEquals(Long.valueOf(31L), data.get("myReviewableAppId"));
        verify(db).one(anyString(), eq(p("u", 7L, "s", 102L)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void guestsGetANullKey()
    {
        AjaxResult r = controller(service).detail(10005L);
        Map<String, Object> data = (Map<String, Object>) r.get("data");
        assertTrue(data.containsKey("myReviewableAppId"));
        assertNull(data.get("myReviewableAppId"));
        verifyNoInteractions(db);
    }
}
