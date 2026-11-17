package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.ContactMask;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.util.MaskUtils;

/**
 * 简历经历 / 技能描述里的联系方式（2026-10-14 回归 R3-27）：之前只遮 7 位以上连在一起的数字，
 * 「138-1234-5678」「138 1234 5678」、「微信 kgtest_0101」原样送到普通员工的报名详情、脱敏简历屏和人才档案
 */
class KgResumeContactMaskTest
{
    static final String LEAK = "有事打我电话 138-1234-5678 或 138 1234 5678，微信 kgtest_0101";

    static void noContact(Object text)
    {
        String s = String.valueOf(text);
        assertFalse(s.contains("1234"), s);
        assertFalse(s.contains("kgtest"), s);
        assertFalse(ContactMask.contains(s), s);
    }

    @Test
    void helperMasksSeparatedPhonesWechatAndLongDigits()
    {
        String m = MaskUtils.contacts(LEAK);
        noContact(m);
        assertTrue(m.contains(ContactMask.PHONE_MASK), m);
        assertTrue(m.contains(ContactMask.WECHAT_MASK), m);
        assertEquals("证件 ***", MaskUtils.contacts("证件 530102199001011234"));
        assertEquals("咖啡制作、摄影，做过 2 年前台", MaskUtils.contacts("咖啡制作、摄影，做过 2 年前台"));
        assertEquals("", MaskUtils.contacts(null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void resumeBriefForStoresMasksDescPlaceRoleAndSkills()
    {
        KgJdbc db = mock(KgJdbc.class);
        KgTalentService service = new KgTalentService();
        ReflectionTestUtils.setField(service, "db", db);
        when(db.one(contains("from kg_resume where"), anyMap())).thenReturn(p("skillText", "咖啡 加V：abc12345", "extraText", "电话 139 0000 1111"));
        when(db.list(contains("from kg_resume_exp"), anyMap())).thenReturn(Collections.singletonList(
                p("expId", 1L, "kind", "parttime", "title", "前台 wx kg_9988", "place", "古城店 13800001111", "startDate", "2026-03-01", "content", LEAK)));
        Map<String, Object> m = service.resumeBrief(7L);
        List<Map<String, Object>> exps = (List<Map<String, Object>>) m.get("exps");
        noContact(exps.get(0).get("desc"));
        noContact(exps.get(0).get("place"));
        assertFalse(String.valueOf(exps.get(0).get("role")).contains("9988"), String.valueOf(exps.get(0).get("role")));
        assertTrue(String.valueOf(exps.get(0).get("role")).endsWith("（兼职）"));
        String skill = String.valueOf(m.get("skillText"));
        assertFalse(skill.contains("abc12345") || skill.contains("0000"), skill);
        assertTrue(skill.startsWith("咖啡"), skill);
    }

    @Test
    @SuppressWarnings("unchecked")
    void talentProfileMasksExperienceAndRawSkills()
    {
        KgJdbc db = mock(KgJdbc.class);
        KgTalentService service = new KgTalentService();
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "support", mock(StaffSupport.class));
        StaffSupport.Ctx ctx = new StaffSupport.Ctx();
        ctx.storeId = 102L; ctx.storeName = "平潭测试门店"; ctx.role = "manager";
        ctx.me = new AppLoginUser(); ctx.me.setUserId(1L);
        when(db.list(contains("from kg_app_user u"), anyMap())).thenReturn(Collections.singletonList(p("userId", 7L, "name", "张三", "skillText", "咖啡 " + LEAK)));
        when(db.list(contains("select * from kg_resume_exp"), anyMap())).thenReturn(Arrays.asList(
                p("kind", "parttime", "title", "周末咖啡师", "place", "古城", "content", LEAK),
                p("kind", "training", "title", "咖啡基础课", "content", "老师电话 138 1234 5678")));
        Map<String, Object> m = service.profile(ctx, 7L);
        noContact(m.get("rawSkills"));
        List<Map<String, Object>> exps = (List<Map<String, Object>>) m.get("experiences");
        noContact(exps.get(0).get("desc"));
        List<Map<String, Object>> trainings = (List<Map<String, Object>>) m.get("trainings");
        noContact(trainings.get(trainings.size() - 1).get("desc"));
    }
}
