package com.ruoyi.kangaroo.controller.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.domain.KgApplication;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.ContactMask;
import com.ruoyi.kangaroo.ext.staff.KgTalentService;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.ext.user.KgPointsService;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.security.StaffGrantView;
import com.ruoyi.kangaroo.service.IKgApplicationService;

/**
 * 员工端报名详情的「技能与经历」（2026-10-14 回归 R3-27）：只看脱敏号码的普通员工，号码脱敏了，
 * 报名人在「技能与经历」里写的 138-1234-5678、微信号却原样下发；主理人 / 管家照旧看原文
 */
class AppStaffApplicationSkillsMaskTest
{
    static final String SKILLS = "咖啡制作、有事打我电话 138-1234-5678、微信 kgtest_0101";
    static final String NOTE = "周末都可以，微信 kgtest_0202 电话 138－1234－5678";
    KgJdbc db;

    @AfterEach
    void cleanup()
    {
        AppUserContext.remove();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> detailAs(String role)
    {
        AppStaffController controller = new AppStaffController();
        IKgApplicationService apps = mock(IKgApplicationService.class);
        KgApplication a = new KgApplication();
        a.setAppId(3692L); a.setStoreId(102L); a.setAppUserId(7L); a.setJobType("online_staff".equals(role) ? "online" : "volunteer"); a.setStatus("submitted");
        a.setRealName("张三"); a.setPhone("13900007094"); a.setSkills(SKILLS); a.setNote(NOTE);
        when(apps.requireOwnedByStore(any(), any())).thenReturn(a);
        StaffSupport support = mock(StaffSupport.class);
        doCallRealMethod().when(support).need(any(), anyString(), anyString(), anyString(), anyString());
        StaffSupport.Ctx ctx = new StaffSupport.Ctx();
        ctx.storeId = 102L; ctx.role = role;
        when(support.ctxForStore(any())).thenReturn(ctx);
        ReflectionTestUtils.setField(controller, "kgApplicationService", apps);
        ReflectionTestUtils.setField(controller, "staffSupport", support);
        db = mock(KgJdbc.class);
        ReflectionTestUtils.setField(controller, "db", db);
        ReflectionTestUtils.setField(controller, "talents", mock(KgTalentService.class));
        ReflectionTestUtils.setField(controller, "points", mock(KgPointsService.class));
        StaffGrantView g = new StaffGrantView();
        g.setStoreId(102L); g.setRoleCode(role); g.setActive(true);
        AppLoginUser me = new AppLoginUser();
        me.setUserId(1L); me.setStaffGrants(Collections.singletonList(g));
        AppUserContext.set(me);
        AjaxResult r = controller.applicationDetail(3692L);
        return (Map<String, Object>) r.get(AjaxResult.DATA_TAG);
    }

    @Test
    void onlineSpecialistWithoutOfflineHiringRightsGetsMaskedContacts()
    {
        Map<String, Object> d = detailAs("online_staff");
        assertEquals(Boolean.TRUE, d.get("contactMasked"));
        String skills = String.valueOf(d.get("skills"));
        assertFalse(skills.contains("1234") || skills.contains("kgtest"), skills);
        assertFalse(ContactMask.contains(skills), skills);
        assertTrue(skills.startsWith("咖啡制作、"), skills);
        assertEquals("139****7094", d.get("phone"));
        // 备注 / 接单说明同样隐去联系方式（10-14 回归）
        String note = String.valueOf(d.get("note"));
        assertFalse(ContactMask.contains(note) || note.contains("kgtest") || note.contains("5678"), note);
        assertTrue(note.startsWith("周末都可以"), note);
    }

    @Test
    void managerStillSeesTheOriginalText()
    {
        Map<String, Object> d = detailAs("manager");
        assertEquals(Boolean.FALSE, d.get("contactMasked"));
        assertEquals(SKILLS, d.get("skills"));
        assertEquals(NOTE, d.get("note"));
    }

    @Test
    void staffWithCurrentHiringEditPermissionSeesOriginalContacts()
    {
        Map<String, Object> d = detailAs("staff");
        assertEquals(Boolean.FALSE, d.get("contactMasked"));
        assertEquals("13900007094", d.get("phone"));
        assertEquals(SKILLS, d.get("skills"));
        assertEquals(NOTE, d.get("note"));
    }

    /** 「接站需求」已下线：到店信息不再查 need_pickup，老版本员工端就不会再显示「需要接站」 */
    @Test
    void arrivalNoLongerSelectsNeedPickup()
    {
        detailAs("manager");
        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db, atLeastOnce()).one(sql.capture(), any());
        String arrival = sql.getAllValues().stream().filter(x -> x.contains("kg_app_arrival")).findFirst().orElse("");
        assertTrue(arrival.contains("arrive_date") && arrival.contains("tickets"), arrival);
        assertFalse(arrival.contains("need_pickup"), arrival);
    }
}
