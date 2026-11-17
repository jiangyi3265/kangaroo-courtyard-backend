package com.ruoyi.kangaroo.ext.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import com.ruoyi.kangaroo.domain.KgStore;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.mapper.KgStoreMapper;
import com.ruoyi.kangaroo.service.IKgStoreService;
import com.ruoyi.kangaroo.service.impl.KgStoreServiceImpl;

/**
 * 2026-10-18 岗位详情改版（V54「门店页」）：后台门店资料的校验、实地探访与安心认证同步（含认证权限）、岗位详情 storePage 的小院兜底（不连库）
 */
class KgStoreProfilesTest
{
    private static final String VIDEO = "/profile/upload/videos/2026/10/18/0123456789abcdef0123456789abcdef.mp4";

    private final KgJdbc db = mock(KgJdbc.class);
    private final IKgStoreService stores = mock(IKgStoreService.class);
    private final KgVideoUploads videos = mock(KgVideoUploads.class);
    private final KgStoreProfiles svc = new KgStoreProfiles();

    private static Map<String, Object> row(Object... kv)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2)
        {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(svc, "db", db);
        ReflectionTestUtils.setField(svc, "stores", stores);
        ReflectionTestUtils.setField(svc, "videos", videos);
        SysUser user = new SysUser();
        user.setUserId(7L);
        user.setUserName("ops-test");
        LoginUser login = new LoginUser();
        login.setUser(user);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login, null, Collections.emptyList()));
    }

    @AfterEach
    void clear()
    {
        SecurityContextHolder.clearContext();
    }

    private void store(String mode, String safeCert)
    {
        when(db.one(contains("from kg_store where store_id = :id"), anyMap()))
                .thenReturn(row("storeId", 102L, "storeName", "测试门店", "safeCert", safeCert, "interviewMode", mode));
    }

    private static void rejects(Runnable r, String msg)
    {
        ServiceException e = assertThrows(ServiceException.class, r::run);
        assertTrue(e.getMessage().contains(msg), e.getMessage());
    }

    // ---------------------------------------------------------------- 校验

    @Test
    void columnsValidateEveryField()
    {
        when(videos.exists(VIDEO)).thenReturn(true);
        Map<String, Object> ok = svc.columns(row("interviewMode", "online", "interviewVideo", VIDEO, "interviewSeconds", 299.6,
                "interviewCover", "/profile/upload/2026/10/18/c.jpg", "managerName", " 小林 ", "facilities", Arrays.asList("wifi", "gym", "cook", "gym"),
                "dailyImages", Arrays.asList("/profile/upload/a.jpg", "/profile/upload/b.jpg"), "licenseImage", ""));
        assertEquals("online", ok.get("interview_mode"));
        assertEquals(VIDEO, ok.get("interview_video"));
        assertEquals(Integer.valueOf(300), ok.get("interview_seconds"));
        assertEquals("小林", ok.get("manager_name"));
        assertEquals("gym,cook,wifi", ok.get("facilities"), "去重并按亮点表的顺序存");
        assertEquals("[\"/profile/upload/a.jpg\",\"/profile/upload/b.jpg\"]", ok.get("daily_images"));
        assertEquals("", ok.get("license_image"));
        assertFalse(ok.containsKey("manager_quote"), "没传的项不动");
        assertEquals("", svc.columns(row("dailyImages", Collections.emptyList())).get("daily_images"), "清空日常照片存 ''");
        assertNull(svc.columns(row("interviewSeconds", null)).get("interview_seconds"));
        assertEquals("ebike,ac", svc.columns(row("facilities", "ac,ebike")).get("facilities"), "逗号拼接也认");

        rejects(() -> svc.columns(row("interviewMode", "vip")), "采访方式");
        rejects(() -> svc.columns(row("interviewVideo", "https://evil.example/a.mp4")), "采访视频");
        rejects(() -> svc.columns(row("interviewVideo", VIDEO.replace("0123", "9999"))), "采访视频"); // 格式对、文件不在
        assertEquals(302, svc.columns(row("interviewSeconds", 302)).get("interview_seconds"));
        assertEquals(3600, svc.columns(row("interviewSeconds", 3600)).get("interview_seconds"));
        assertEquals(60, svc.columns(row("interviewSeconds", 60)).get("interview_seconds"));
        // 2026-10-31 主人拍板：去掉 60 秒下限，多短都能传，仍要求是有效正数
        assertEquals(60, svc.columns(row("interviewSeconds", 59.99)).get("interview_seconds"));
        assertEquals(5, svc.columns(row("interviewSeconds", 5)).get("interview_seconds"));
        assertEquals(1, svc.columns(row("interviewSeconds", 0.4)).get("interview_seconds"), "不足 1 秒按 1 秒存，0 会被页面当成没有时长");
        rejects(() -> svc.columns(row("interviewSeconds", 0)), "秒数");
        rejects(() -> svc.columns(row("interviewSeconds", -1)), "秒数");
        rejects(() -> svc.columns(row("interviewSeconds", Double.POSITIVE_INFINITY)), "秒数");
        rejects(() -> svc.columns(row("interviewSeconds", "abc")), "秒数");
        rejects(() -> svc.columns(row("managerAvatar", "https://evil.example/a.jpg")), "店长头像");
        rejects(() -> svc.columns(row("licenseImage", "/kangaroo/static/x.png")), "营业执照");
        rejects(() -> svc.columns(row("interviewCover", "/profile/upload/a.jpg,https://x")), "封面");
        rejects(() -> svc.columns(row("facilities", Arrays.asList("wifi", "sauna"))), "sauna");
        rejects(() -> svc.columns(row("dailyImages", Arrays.asList("/profile/upload/1.jpg", "/profile/upload/2.jpg", "/profile/upload/3.jpg",
                "/profile/upload/4.jpg", "/profile/upload/5.jpg", "/profile/upload/6.jpg", "/profile/upload/7.jpg", "/profile/upload/8.jpg",
                "/profile/upload/9.jpg", "/profile/upload/10.jpg"))), "最多 9 张");
        rejects(() -> svc.columns(row("dailyImages", Arrays.asList("/profile/upload/1.jpg", "http://x/2.jpg"))), "店里日常");
        rejects(() -> svc.columns(row("managerName", new String(new char[33]).replace('\0', '林'))), "店长称呼最多 32 字");
        rejects(() -> svc.columns(row("managerQuote", new String(new char[201]).replace('\0', 'a'))), "店长说最多 200 字");
        rejects(() -> svc.columns(row("travelRail", new String(new char[501]).replace('\0', 'a'))), "最多 500 字");
        rejects(() -> svc.columns(row("travelCost", new String(new char[33]).replace('\0', '1'))), "预估最低出行成本");
        assertEquals(500, ((String) svc.columns(row("travelAirport", new String(new char[500]).replace('\0', 'a'))).get("travel_airport")).length());
    }

    // ---------------------------------------------------------------- 实地探访 = 安心认证

    @Test
    void newLicenseCannotBypassWatermarkThroughGenericImageUpload()
    {
        store("", "1");
        rejects(() -> svc.save(102L, row("licenseImage", "/profile/upload/unmarked.jpg"), false), "认证水印");
        verify(db, never()).updateRow(anyString(), anyString(), any(), anyMap());
        svc.save(102L, row("licenseImage", ""), false);
    }

    @Test
    void explicitEmptyFacilitiesDoNotReappearFromStay()
    {
        Map<String, Object> cols = svc.columns(row("facilities", Collections.emptyList(), "inheritFacilities", false));
        assertEquals("[]", cols.get("facilities"));
        Map<String, Object> store = row("storeId", 102L, "facilities", cols.get("facilities"));
        assertEquals(Collections.emptyList(), KgStoreProfiles.page(store, stay(), Collections.emptyList()).get("facilities"));
        assertFalse(KgStoreProfiles.inheritsFacilities(store.get("facilities")));
        assertFalse(cols.containsKey("interview_mode"), "只改亮点不能改掉采访状态");

        Map<String, Object> inherited = svc.columns(row("inheritFacilities", true, "facilities", Arrays.asList("pool")));
        assertEquals("@inherit", inherited.get("facilities"));
        store.put("facilities", inherited.get("facilities"));
        assertFalse(((List<?>) KgStoreProfiles.page(store, stay(), Collections.emptyList()).get("facilities")).isEmpty());
        assertTrue(KgStoreProfiles.inheritsFacilities(store.get("facilities")));
        rejects(() -> svc.columns(row("inheritFacilities", "false")), "布尔值");
        rejects(() -> svc.columns(row("inheritFacilities", false)), "同时提交");
    }

    @Test
    void adminReturnsExplicitSelectionAndOnlySelectedFacilitiesReachTheApp()
    {
        Map<String, Object> saved = row("storeId", 102L, "storeName", "测试门店", "safeCert", "1", "interviewMode", "online", "facilities", "[]");
        when(db.one(contains("from kg_store where store_id = :id"), anyMap())).thenReturn(saved);
        assertEquals(false, svc.adminProfile(102L).get("inheritFacilities"));
        assertEquals(Collections.emptyList(), svc.adminProfile(102L).get("facilities"));
        saved.put("facilities", svc.columns(row("facilities", Arrays.asList("gym", "pool"), "inheritFacilities", false)).get("facilities"));
        List<?> facilities = (List<?>) KgStoreProfiles.page(saved, stay(), Collections.emptyList()).get("facilities");
        assertEquals(2, facilities.size());
        assertEquals("gym", ((Map<?, ?>) facilities.get(0)).get("code"));
        assertEquals("pool", ((Map<?, ?>) facilities.get(1)).get("code"));
    }

    @Test
    void modeFollowsSafeCert()
    {
        assertEquals("onsite", KgStoreProfiles.mode("", "0"), "认证过的店就是实地探访（老版本程序认证时没写采访方式）");
        assertEquals("onsite", KgStoreProfiles.mode("online", "0"));
        assertEquals("", KgStoreProfiles.mode("onsite", "1"), "老版本程序撤销了认证：不再算实地探访");
        assertEquals("online", KgStoreProfiles.mode("online", "1"));
        assertEquals("", KgStoreProfiles.mode(null, null));
    }

    @Test
    void choosingOnsiteCertifiesAndNeedsTheCertPermission()
    {
        store("", "1");
        rejects(() -> svc.save(102L, row("interviewMode", "onsite"), false), "认证权限");
        verify(stores, never()).certify(anyLong(), anyBoolean());
        verify(db, never()).updateRow(anyString(), anyString(), any(), anyMap());

        svc.save(102L, row("interviewMode", "onsite", "managerName", "小林"), true);
        verify(stores).certify(102L, true);
        ArgumentCaptor<Map<String, Object>> cols = colsCaptor();
        verify(db).updateRow(eq("kg_store"), eq("store_id"), eq(102L), cols.capture());
        assertEquals("onsite", cols.getValue().get("interview_mode"));
        assertEquals("小林", cols.getValue().get("manager_name"));
        assertEquals("ops-test", cols.getValue().get("update_by"));
    }

    @Test
    void leavingOnsiteRevokesAndNeedsTheCertPermission()
    {
        store("onsite", "0");
        rejects(() -> svc.save(102L, row("interviewMode", "online"), false), "认证权限");
        rejects(() -> svc.save(102L, row("interviewMode", ""), false), "认证权限");
        verify(stores, never()).certify(anyLong(), anyBoolean());

        svc.save(102L, row("interviewMode", "online"), true);
        verify(stores).certify(102L, false);
        ArgumentCaptor<Map<String, Object>> cols = colsCaptor();
        verify(db).updateRow(eq("kg_store"), eq("store_id"), eq(102L), cols.capture());
        assertEquals("online", cols.getValue().get("interview_mode"), "撤销认证后写上选定的线上采访");
    }

    @Test
    void otherChangesDoNotTouchCertification()
    {
        // 线上采访 ↔ 未采访、只改资料、保持实地探访：都不用认证权限，也不动认证
        store("online", "1");
        svc.save(102L, row("interviewMode", ""), false);
        store("", "1");
        svc.save(102L, row("interviewMode", "online", "travelCost", "约37元起"), false);
        store("", "0");
        svc.save(102L, row("interviewMode", "onsite", "managerQuote", "欢迎来"), false);
        svc.save(102L, row("managerYears", "3年"), false);
        verify(stores, never()).certify(anyLong(), anyBoolean());
        verify(db, times(4)).updateRow(eq("kg_store"), eq("store_id"), eq(102L), anyMap());
    }

    /** 有视频就要有时长：只拒这次改动造成的「有视频、没时长」，去掉视频和没存时长的老数据不受影响 */
    @Test
    void savingAVideoNeedsSecondsUnlessNothingAboutItChanges()
    {
        String other = "/profile/upload/videos/2026/10/31/fedcba9876543210fedcba9876543210.mp4";
        when(videos.exists(VIDEO)).thenReturn(true);
        when(videos.exists(other)).thenReturn(true);
        when(db.one(contains("from kg_store where store_id = :id"), anyMap()))
                .thenReturn(row("storeId", 102L, "safeCert", "0", "interviewMode", "online", "interviewVideo", VIDEO, "interviewSeconds", 120));
        rejects(() -> svc.save(102L, row("interviewVideo", other), false), "秒数");
        rejects(() -> svc.save(102L, row("interviewVideo", other, "interviewSeconds", null), false), "秒数");
        rejects(() -> svc.save(102L, row("interviewVideo", other, "interviewSeconds", ""), false), "秒数");
        rejects(() -> svc.save(102L, row("interviewSeconds", null), false), "秒数");
        rejects(() -> svc.save(102L, row("interviewVideo", VIDEO, "interviewSeconds", ""), false), "秒数");
        verify(db, never()).updateRow(anyString(), anyString(), any(), anyMap());
        svc.save(102L, row("interviewVideo", other, "interviewSeconds", 5), false);
        svc.save(102L, row("interviewVideo", "", "interviewSeconds", null), false);
        svc.save(102L, row("interviewVideo", VIDEO), false);
        svc.save(102L, row("managerName", "小林"), false);
        verify(db, times(4)).updateRow(eq("kg_store"), eq("store_id"), eq(102L), anyMap());

        // 老数据：视频在、库里没存时长。门店设置页只改别的项时会原样带上这两项，不能存不上
        when(db.one(contains("from kg_store where store_id = :id"), anyMap()))
                .thenReturn(row("storeId", 102L, "safeCert", "0", "interviewMode", "online", "interviewVideo", VIDEO, "interviewSeconds", null));
        svc.save(102L, row("interviewVideo", VIDEO, "interviewSeconds", null, "managerName", "小林"), false);
        verify(db, times(5)).updateRow(eq("kg_store"), eq("store_id"), eq(102L), anyMap());
        rejects(() -> svc.save(102L, row("interviewVideo", other, "interviewSeconds", null), false), "秒数");
    }

    @Test
    void missingStoreIsRefused()
    {
        when(db.one(anyString(), anyMap())).thenReturn(null);
        rejects(() -> svc.save(5L, row("managerName", "x"), true), "门店不存在");
        rejects(() -> svc.adminProfile(5L), "门店不存在");
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static ArgumentCaptor<Map<String, Object>> colsCaptor()
    {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
    }

    @Test
    void certifyKeepsInterviewModeInSync()
    {
        KgStoreServiceImpl impl = new KgStoreServiceImpl();
        KgStoreMapper mapper = mock(KgStoreMapper.class);
        ReflectionTestUtils.setField(impl, "kgStoreMapper", mapper);
        ReflectionTestUtils.setField(impl, "db", db);
        KgStore s = new KgStore();
        s.setStoreId(102L);
        when(mapper.selectKgStoreByStoreId(102L)).thenReturn(s);
        impl.certify(102L, true);
        impl.certify(102L, false);
        verify(mapper).clearCert(102L);
        verify(db).update(argThat((String sql) -> sql.contains("interview_mode = case when :c = 1 then 'onsite' when interview_mode = 'onsite' then '' else interview_mode end")),
                eq(KgJdbc.p("c", 1, "id", 102L)));
        verify(db).update(anyString(), eq(KgJdbc.p("c", 0, "id", 102L)));
    }

    // ---------------------------------------------------------------- 岗位详情 storePage

    private static Map<String, Object> stay()
    {
        return row("stayId", 258L, "stayName", "海边小院", "hostName", "阿青", "hostAvatar", "/profile/upload/h.jpg", "hostSkills", "冲浪",
                "hostIntro", "住下来就是朋友", "facilities", "wifi,balcony,bath,hotwater,gym", "cookable", "1",
                "travelRail", "平潭站打车 20 分钟", "travelAirport", "");
    }

    @Test
    void emptyStoreFallsBackToTheStay()
    {
        Map<String, Object> store = row("storeId", 102L, "safeCert", "1", "interviewMode", "online", "interviewVideo", "", "rating", null,
                "managerName", "", "facilities", "", "travelRail", "", "travelAirport", "", "travelCost", "", "licenseImage", "", "dailyImages", "");
        Map<String, Object> page = KgStoreProfiles.page(store, stay(), Arrays.asList("/profile/upload/d1.jpg", "/profile/upload/d2.jpg"));
        assertEquals("online", page.get("interviewMode"));
        assertNull(page.get("interview"), "没传视频就没有采访卡");
        Map<String, Object> m = cast(page.get("manager"));
        assertEquals("阿青", m.get("name"));
        assertEquals("/profile/upload/h.jpg", m.get("avatar"));
        assertEquals("", m.get("years"));
        assertEquals("冲浪", m.get("skills"));
        assertEquals("住下来就是朋友", m.get("quote"));
        assertNull(m.get("rating"), "门店还没有评分");
        List<Map<String, Object>> fac = cast(page.get("facilities"));
        assertEquals(Collections.emptyList(), codes(fac), "未选择设施，不能自动显示发布门店的小院设施");
        Map<String, Object> travel = cast(page.get("travel"));
        assertEquals("平潭站打车 20 分钟", travel.get("rail"));
        assertEquals("", travel.get("airport"));
        assertEquals("", travel.get("cost"));
        assertEquals("", page.get("licenseImage"));
        assertEquals(Arrays.asList("/profile/upload/d1.jpg", "/profile/upload/d2.jpg"), page.get("dailyImages"));
    }

    @Test
    void storeFieldsWinOverTheStay()
    {
        Map<String, Object> store = row("storeId", 102L, "safeCert", "0", "interviewMode", "", "interviewVideo", VIDEO,
                "interviewCover", "/profile/upload/c.jpg", "interviewSeconds", 245, "interviewNote", "", "rating", new BigDecimal("4.80"),
                "managerName", "小林", "managerAvatar", "https://evil.example/a.jpg", "managerYears", "3年", "managerSkills", "做饭", "managerQuote", "来吧",
                "facilities", "pool,ebike", "travelRail", "", "travelAirport", "长乐机场大巴", "travelCost", "约37元起",
                "licenseImage", "/profile/upload/l.jpg", "dailyImages", "[\"/profile/upload/x.jpg\",\"https://evil.example/y.jpg\"]");
        Map<String, Object> page = KgStoreProfiles.page(store, stay(), Arrays.asList("/profile/upload/d1.jpg"));
        assertEquals("onsite", page.get("interviewMode"), "认证过的店是实地探访");
        Map<String, Object> iv = cast(page.get("interview"));
        assertEquals(VIDEO, iv.get("video"));
        assertEquals("/profile/upload/c.jpg", iv.get("cover"));
        assertEquals(245, iv.get("seconds"));
        assertEquals("", iv.get("note"));
        Map<String, Object> m = cast(page.get("manager"));
        assertEquals("小林", m.get("name"));
        assertEquals("", m.get("avatar"), "不是平台图片的头像不下发");
        assertEquals("3年", m.get("years"));
        assertEquals(new BigDecimal("4.80"), m.get("rating"));
        assertEquals(Arrays.asList("pool", "ebike"), codes(cast(page.get("facilities"))));
        Map<String, Object> travel = cast(page.get("travel"));
        assertEquals("平潭站打车 20 分钟", travel.get("rail"), "门店没填的那一项用小院的");
        assertEquals("长乐机场大巴", travel.get("airport"));
        assertEquals("约37元起", travel.get("cost"));
        assertEquals("/profile/upload/l.jpg", page.get("licenseImage"));
        assertEquals(Collections.singletonList("/profile/upload/x.jpg"), page.get("dailyImages"), "门店传了日常照片就不用小院的；外链丢掉");
    }

    @Test
    void noStayAndNothingFilledGivesEmptyBlocks()
    {
        Map<String, Object> page = KgStoreProfiles.page(row("safeCert", "1", "interviewMode", "", "interviewVideo", VIDEO), null, null);
        assertEquals("", page.get("interviewMode"));
        assertNull(page.get("interview"), "未采访不出采访卡（哪怕传过视频）");
        assertNull(page.get("manager"));
        assertTrue(((List<?>) page.get("facilities")).isEmpty());
        assertEquals(row("rail", "", "airport", "", "cost", ""), page.get("travel"));
        assertEquals("", page.get("licenseImage"));
        assertEquals(Collections.emptyList(), page.get("dailyImages"));
        assertEquals(Collections.emptyList(), KgStoreProfiles.parseImages("not json"));
    }

    @Test
    void storePageQueriesTheOnlineStayAndItsVisiblePhotos()
    {
        when(db.one(contains("from kg_store where store_id = :id"), anyMap())).thenReturn(row("storeId", 102L, "safeCert", "1", "interviewMode", ""));
        when(db.one(contains("from kg_stay s where s.store_id = :st"), anyMap())).thenReturn(stay());
        when(db.list(contains("from kg_stay_diary d"), anyMap())).thenReturn(Arrays.asList(row("mediaUrl", "/profile/upload/d1.jpg"), row("mediaUrl", "/profile/upload/d1.jpg")));
        Map<String, Object> page = svc.storePage(102L);
        assertEquals(Collections.singletonList("/profile/upload/d1.jpg"), page.get("dailyImages"));
        verify(db).one(argThat((String s) -> s.contains("s.status = 'online' and s.del_flag = '0'") && s.contains("order by s.sort, s.stay_id limit 1")), eq(KgJdbc.p("st", 102L)));
        verify(db).list(argThat((String s) -> s.contains("d.del_flag = '0' and d.status = '0' and d.media_type = 'image'") && s.endsWith("limit 6")), eq(KgJdbc.p("s", 258L)));
        when(db.one(contains("from kg_store where store_id = :id"), anyMap())).thenReturn(null);
        assertNull(svc.storePage(103L));
    }

    @Test
    void adminProfileShowsOptionsAndTheFallback()
    {
        store("online", "1");
        when(db.one(contains("from kg_stay s where s.store_id = :st"), anyMap())).thenReturn(stay());
        when(db.list(contains("from kg_stay_diary d"), anyMap())).thenReturn(Arrays.asList(row("mediaUrl", "/profile/upload/d1.jpg")));
        Map<String, Object> p = svc.adminProfile(102L);
        assertEquals("online", p.get("interviewMode"));
        assertEquals(Collections.emptyList(), p.get("dailyImages"));
        assertEquals(11, ((List<?>) p.get("facilityOptions")).size());
        Map<String, Object> fb = cast(p.get("fallback"));
        assertEquals(Boolean.TRUE, fb.get("hasStay"));
        assertEquals("海边小院", fb.get("stayName"));
        assertEquals("阿青", fb.get("managerName"));
        assertEquals(Arrays.asList("gym", "cook", "wifi", "bath"), fb.get("facilities"));
        assertEquals(1, fb.get("dailyCount"));
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object o)
    {
        return (T) o;
    }

    private static List<String> codes(List<Map<String, Object>> items)
    {
        List<String> out = new java.util.ArrayList<String>();
        for (Map<String, Object> m : items)
        {
            out.add(String.valueOf(m.get("code")));
        }
        return out;
    }
}
