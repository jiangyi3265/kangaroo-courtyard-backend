package com.ruoyi.kangaroo.controller.app;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.domain.KgApplication;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgChatReception;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.service.IKgApplicationService;

/** 进度页「联系主理人 / 联系专员」（2026-10-16）：只有本店在职专员发布的线上任务，报名详情才带 contactSpecialist = true */
class AppApplicationContactSpecialistTest
{
    final AppApplicationController controller = new AppApplicationController();
    final IKgApplicationService apps = mock(IKgApplicationService.class);
    final KgJdbc db = mock(KgJdbc.class);
    final KgChatReception reception = mock(KgChatReception.class);

    @BeforeEach void setup()
    {
        ReflectionTestUtils.setField(controller, "kgApplicationService", apps);
        ReflectionTestUtils.setField(controller, "db", db);
        ReflectionTestUtils.setField(controller, "reception", reception);
        AppLoginUser me = new AppLoginUser();
        me.setUserId(7L);
        me.setNickname("小林");
        AppUserContext.set(me);
        KgApplication a = new KgApplication();
        a.setAppId(300L);
        a.setJobId(55L);
        a.setStoreId(22L);
        a.setStatus("review_passed");
        when(apps.requireOwnedByUser(300L, 7L)).thenReturn(a);
    }

    @AfterEach void clear()
    {
        AppUserContext.remove();
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> detail()
    {
        AjaxResult r = controller.detail(300L);
        return (Map<String, Object>) r.get(AjaxResult.DATA_TAG);
    }

    @Test void onlineTaskPublishedBySpecialistSaysContactSpecialist()
    {
        when(db.one(startsWith("select job_type from kg_job"), anyMap())).thenReturn(p("jobType", "online"));
        when(reception.specialistOf(22L, 300L)).thenReturn(10L);
        assertEquals(Boolean.TRUE, detail().get("contactSpecialist"));
    }

    @Test void managerTaskRevokedSpecialistOrOfflineJobKeepsContactOwner()
    {
        when(db.one(startsWith("select job_type from kg_job"), anyMap())).thenReturn(p("jobType", "online"));
        when(reception.specialistOf(22L, 300L)).thenReturn(null);
        assertEquals(Boolean.FALSE, detail().get("contactSpecialist"));
        when(db.one(startsWith("select job_type from kg_job"), anyMap())).thenReturn(p("jobType", "parttime"));
        when(reception.specialistOf(22L, 300L)).thenReturn(10L);
        assertEquals(Boolean.FALSE, detail().get("contactSpecialist"));
    }
}
