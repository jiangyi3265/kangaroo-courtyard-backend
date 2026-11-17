package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.ExtEntity;
import com.ruoyi.kangaroo.ext.KgJdbc;

class KgRegionGroupServiceTest
{
    private static final String QR = "/profile/upload/group.png";
    private static final LocalDate DAY = LocalDate.of(2026, 11, 9);
    private Map<String, Object> row(String expiry) { return p("regionGroupName", "平潭换工畅玩群", "regionGroupQr", QR, "regionGroupExpire", expiry, "regionGroupNote", "入群请备注昵称"); }

    @Test void expiryIncludesWholeChinaCalendarDay() {
        Map<String, Object> view = KgRegionGroupService.view("平潭", row("2026-11-09"), DAY);
        assertEquals("ready", view.get("state")); assertEquals(QR, view.get("qrUrl"));
        assertEquals(DAY.plusDays(1).atStartOfDay(KgRegionGroupService.ZONE).toInstant().toEpochMilli(), view.get("validUntil"));
    }
    @Test void expiredAndUndatedCodesAreNeverReturned() {
        Map<String, Object> expired = KgRegionGroupService.view("平潭", row("2026-11-08"), DAY);
        assertEquals("expired", expired.get("state")); assertNull(expired.get("qrUrl"));
        for (String date : new String[] { "", "bad", "2026-02-30" }) {
            Map<String, Object> out = KgRegionGroupService.view("平潭", row(date), DAY);
            assertEquals("unavailable", out.get("state")); assertNull(out.get("qrUrl"));
        }
    }
    @Test void absentAndUnsafeCodesHaveNoMerchantFallback() {
        assertEquals("平潭换工畅玩群", KgRegionGroupService.view("平潭", null, DAY).get("name"));
        for (String qr : new String[] { "javascript:alert(1)", "https://other.example/qr.png", "/static/media/contract-qr.jpg", QR + ",/profile/upload/other.png" }) {
            Map<String, Object> r = row("2026-11-15"); r.put("regionGroupQr", qr);
            assertNull(KgRegionGroupService.view("平潭", r, DAY).get("qrUrl"));
        }
    }
    @Test void cityUsesExistingJobDestinationNormalizationAndOnlyEnabledExactCity() {
        KgJdbc db = mock(KgJdbc.class); KgCityService cities = mock(KgCityService.class);
        KgRegionGroupService service = new KgRegionGroupService();
        ReflectionTestUtils.setField(service, "db", db); ReflectionTestUtils.setField(service, "cities", cities);
        when(cities.jobCity("福建平潭岛")).thenReturn("平潭");
        when(db.one(contains("region_group_qr"), eq(p("city", "平潭")))).thenReturn(row("2099-01-01"));
        assertEquals("平潭", service.forCity("福建平潭岛").get("city"));
        verify(db).one(contains("name = :city and status = '0'"), eq(p("city", "平潭")));
        when(cities.jobCity("")).thenReturn(""); service.forCity(""); verifyNoMoreInteractions(db);
    }
    @Test void ownerAndPostSignatureOfflineStateAreRequired() {
        KgJdbc db = mock(KgJdbc.class); KgRegionGroupService groups = mock(KgRegionGroupService.class);
        when(db.one(anyString(), anyMap())).thenReturn(null);
        KgUserHubService hub = new KgUserHubService();
        ReflectionTestUtils.setField(hub, "db", db); ReflectionTestUtils.setField(hub, "regionGroups", groups);
        assertEquals(404, assertThrows(ServiceException.class, () -> hub.regionGroup(7L, 81L)).getCode());
        Map<String, Object> app = p("status", "contract_signed", "jobType", "volunteer", "city", "福建平潭岛", "storeId", 9);
        when(db.one(startsWith("select a.*, j.title"), eq(p("a", 81L, "u", 7L)))).thenReturn(app);
        when(groups.forCity("福建平潭岛")).thenReturn(p("name", "平潭换工畅玩群"));
        assertEquals("平潭换工畅玩群", hub.regionGroup(7L, 81L).get("name"));
        app.put("storeId", 10); assertEquals("平潭换工畅玩群", hub.regionGroup(7L, 81L).get("name"));
        for (String state : new String[] { "submitted", "store_confirmed", "withdrawn", "terminated" }) {
            app.put("status", state); assertEquals(403, assertThrows(ServiceException.class, () -> hub.regionGroup(7L, 81L)).getCode());
        }
        app.put("status", "contract_signed"); app.put("jobType", "online");
        assertEquals(403, assertThrows(ServiceException.class, () -> hub.regionGroup(7L, 81L)).getCode());
        assertEquals(404, assertThrows(ServiceException.class, () -> hub.regionGroup(8L, 81L)).getCode());
        verify(groups, times(2)).forCity("福建平潭岛");
    }
    @Test void adminFormRequiresUploadedImageAndExplicitExpiry(@TempDir Path dir) throws Exception {
        String old = RuoYiConfig.getProfile();
        try {
            new RuoYiConfig().setProfile(dir.toString()); Files.createDirectories(dir.resolve("upload"));
            Files.write(dir.resolve("upload/group.png"), Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aWQAAAABJRU5ErkJggg=="));
            Map<String, Object> cols = p("region_group_qr", QR);
            assertTrue(assertThrows(ServiceException.class, () -> KgRegionGroupService.validate(cols)).getMessage().contains("有效期"));
            cols.put("region_group_expire", "2026-11-15"); KgRegionGroupService.validate(cols); assertEquals(QR, cols.get("region_group_qr"));
            cols.put("region_group_qr", "/profile/upload/missing.png"); assertThrows(ServiceException.class, () -> KgRegionGroupService.validate(cols));
            cols.put("region_group_qr", ""); assertDoesNotThrow(() -> KgRegionGroupService.validate(cols));
        } finally { new RuoYiConfig().setProfile(old); }
    }
    @Test void existingCityAdminMetadataExposesAllGroupFields() {
        ExtEntity city = new KgUserModule().entities().stream().filter(e -> "city".equals(e.key)).findFirst().orElseThrow();
        for (String name : new String[] { "regionGroupName", "regionGroupQr", "regionGroupExpire", "regionGroupNote" }) assertNotNull(city.field(name));
        assertEquals("kangaroo:city", city.perm);
    }
}
