package com.ruoyi.kangaroo.controller.app;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.GetMapping;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.domain.KgApplication;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgChatReception;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.service.IKgApplicationService;

/** 报名结束页的行程与接待联系方式，只给报名人本人；分享行程不带电话。 */
class AppApplicationAfterApplyTest
{
    final AppApplicationController controller = new AppApplicationController();
    final IKgApplicationService apps = mock(IKgApplicationService.class);
    final KgJdbc db = mock(KgJdbc.class);
    final KgChatReception reception = mock(KgChatReception.class);
    KgApplication app;

    @BeforeEach
    void setup() throws Exception
    {
        ReflectionTestUtils.setField(controller, "kgApplicationService", apps);
        ReflectionTestUtils.setField(controller, "db", db);
        ReflectionTestUtils.setField(controller, "reception", reception);
        AppLoginUser me = new AppLoginUser();
        me.setUserId(7L);
        me.setNickname("小杨的微信昵称");
        AppUserContext.set(me);
        app = new KgApplication();
        app.setAppId(300L);
        app.setJobId(55L);
        app.setStoreId(22L);
        app.setStatus("submitted");
        app.setRealName("小杨");
        app.setPhone("13812345678");
        app.setArrivalDate(new SimpleDateFormat("yyyy-MM-dd").parse("2026-11-07"));
        app.setStayDays(30);
        when(apps.requireOwnedByUser(300L, 7L)).thenReturn(app);
        when(reception.offlineLead(22L, 7L, 55L)).thenAnswer(i -> new LinkedHashMap<String, Object>(p("kind", "manager", "roleLabel", "换工主理人", "name", "小林", "avatar", "/lin.jpg")));
    }

    @AfterEach
    void clear()
    {
        AppUserContext.remove();
    }

    private void job(Object... extra)
    {
        Map<String, Object> m = new HashMap<String, Object>(p("jobType", "volunteer", "title", "平潭海岛体验官", "city", "平潭", "cover", "/job.jpg", "images", "", "storeName", "平潭袋鼠小院", "storeCover", "/store.jpg"));
        for (int i = 0; i + 1 < extra.length; i += 2)
        {
            m.put(String.valueOf(extra[i]), extra[i + 1]);
        }
        when(db.one(contains("from kg_job j"), anyMap())).thenReturn(m);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data()
    {
        return (Map<String, Object>) controller.afterApply(300L).get("data");
    }

    @Test
    void routeIsUnderTheLoggedInApplicationController() throws Exception
    {
        Method m = AppApplicationController.class.getMethod("afterApply", Long.class);
        assertArrayEquals(new String[] { "/{appId}/after-apply" }, m.getAnnotation(GetMapping.class).value());
        assertNotNull(AppApplicationController.class.getAnnotation(AppAuth.class));
    }

    @Test
    void offlineJobGivesTripAndSafety()
    {
        job();
        Map<String, Object> d = data();
        assertEquals(Boolean.FALSE, d.get("online"));
        assertEquals(Boolean.FALSE, d.get("ended"));
        @SuppressWarnings("unchecked")
        Map<String, Object> trip = (Map<String, Object>) d.get("trip");
        assertEquals(55L, trip.get("jobId"));
        assertEquals("volunteer", trip.get("jobType"));
        assertEquals("平潭海岛体验官", trip.get("jobTitle"));
        assertEquals("平潭袋鼠小院", trip.get("place"));
        assertEquals("平潭", trip.get("city"));
        assertEquals("/job.jpg", trip.get("cover"));
        assertEquals("2026-11-07", trip.get("arrival"));
        assertEquals(30, trip.get("days"));
        assertEquals("小杨", trip.get("name"), "报名时的简历称呼，不是微信昵称");
        @SuppressWarnings("unchecked")
        Map<String, Object> safety = (Map<String, Object>) d.get("safety");
        assertEquals("manager", safety.get("kind"));
        assertEquals("小林", safety.get("name"));
        assertEquals(300L, safety.get("appId"), "打开聊天走门店会话 chat?appId=");
    }

    @Test
    void applicantPhoneIsNeverExposed()
    {
        job();
        assertFalse(String.valueOf(data()).contains("13812345678"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void activeApplicantGetsConfiguredStorePhoneOnlyInSafety()
    {
        job();
        app.setStatus("review_passed");
        when(db.one(contains("select contact_phone from kg_store"), anyMap())).thenReturn(p("contactPhone", "(0591) 8888-1234"));
        Map<String, Object> d = data();
        assertEquals("059188881234", ((Map<String, Object>) d.get("safety")).get("contactPhone"));
        assertFalse(String.valueOf(d.get("trip")).contains("059188881234"));
        assertFalse(String.valueOf(d).contains("13812345678"));
        verify(db).one(contains("status = '0' and del_flag = '0'"), eq(p("s", 22L)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingOrInvalidPhoneDoesNotBecomeADialTarget()
    {
        job();
        for (String phone : new String[] { "", "138****5678", "请联系微信", "123;4567", "1234", "+" })
        {
            when(db.one(contains("select contact_phone from kg_store"), anyMap())).thenReturn(p("contactPhone", phone));
            assertEquals("", ((Map<String, Object>) data().get("safety")).get("contactPhone"), phone);
        }
        when(db.one(contains("select contact_phone from kg_store"), anyMap())).thenReturn(null);
        assertEquals("", ((Map<String, Object>) data().get("safety")).get("contactPhone"));
        when(db.one(contains("select contact_phone from kg_store"), anyMap())).thenReturn(p("contactPhone", "+86 139-0000-0000"));
        assertEquals("+8613900000000", ((Map<String, Object>) data().get("safety")).get("contactPhone"));
    }

    @Test
    void placeIsTheRecruitStoreThenTheStoreThenTheCity()
    {
        job("recruitStoreId", 9L, "recruitStoreName", "南舍庄园", "recruitStoreCover", "/nanshe.jpg", "cover", "", "images", "");
        @SuppressWarnings("unchecked")
        Map<String, Object> trip = (Map<String, Object>) data().get("trip");
        assertEquals("南舍庄园", trip.get("place"));
        assertEquals("/nanshe.jpg", trip.get("cover"));
        job("storeName", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> noStore = (Map<String, Object>) data().get("trip");
        assertEquals("平潭", noStore.get("place"));
    }

    @Test
    void firstPhotoIsTheCover()
    {
        job("images", "[\"/profile/upload/2026/10/31/a.jpg\"]");
        @SuppressWarnings("unchecked")
        Map<String, Object> trip = (Map<String, Object>) data().get("trip");
        assertEquals("/profile/upload/2026/10/31/a.jpg", trip.get("cover"));
    }

    @Test
    void onlineTaskShowsNothing()
    {
        job("jobType", "online");
        Map<String, Object> d = data();
        assertEquals(Boolean.TRUE, d.get("online"));
        assertEquals(1, d.size(), "线上任务只有这一个字段");
        verifyNoInteractions(reception);
        verify(db, never()).one(contains("contact_phone"), anyMap());
    }

    @Test
    void endedApplicationsDoNotGetASafetyContact()
    {
        job();
        for (String status : new String[] { "review_rejected", "withdrawn", "terminated" })
        {
            app.setStatus(status);
            Map<String, Object> d = data();
            assertEquals(Boolean.TRUE, d.get("ended"), status);
            assertNull(d.get("safety"), status);
            assertNotNull(d.get("trip"), status);
        }
        verifyNoInteractions(reception);
        verify(db, never()).one(contains("contact_phone"), anyMap());
    }

    @Test
    void everyLiveStatusGetsOne()
    {
        job();
        for (String status : new String[] { "submitted", "review_passed", "interview_booked", "interview_done", "user_confirmed", "store_confirmed", "contract_signed", "onboarded" })
        {
            app.setStatus(status);
            assertEquals(Boolean.FALSE, data().get("ended"), status);
        }
    }

    @Test
    void customerServiceFallbackHasNoAppId()
    {
        job();
        when(reception.offlineLead(22L, 7L, 55L)).thenAnswer(i -> new LinkedHashMap<String, Object>(p("kind", "service", "roleLabel", "人工客服", "name", "袋鼠客服", "avatar", "")));
        @SuppressWarnings("unchecked")
        Map<String, Object> safety = (Map<String, Object>) data().get("safety");
        assertEquals("service", safety.get("kind"));
        assertFalse(safety.containsKey("appId"));
        assertFalse(safety.containsKey("contactPhone"));
        verify(db, never()).one(contains("contact_phone"), anyMap());
    }

    @Test
    void someoneElsesApplicationIsRefused()
    {
        when(apps.requireOwnedByUser(301L, 7L)).thenThrow(new ServiceException("报名记录不存在", 403));
        ServiceException e = assertThrows(ServiceException.class, () -> controller.afterApply(301L));
        assertEquals(403, e.getCode());
        verifyNoInteractions(reception);
        verify(db, never()).one(anyString(), anyMap());
    }

    @Test
    void jobRowMissingEntirely()
    {
        when(db.one(contains("from kg_job j"), anyMap())).thenReturn(null);
        AjaxResult r = controller.afterApply(300L);
        assertEquals(200, r.get("code"));
        Map<String, Object> d = data();
        assertEquals(Boolean.TRUE, d.get("ended"));
        assertNull(d.get("trip"));
        assertNull(d.get("safety"));
    }
}
