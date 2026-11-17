package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * 责任书页「点此打开地图」的地址和坐标（Mock JDBC，不连库）。
 * 之前地址取岗位的、坐标一律取门店的：门店替合作商户发的岗位会被导航到门店。现在岗位自己有坐标就用岗位的；
 * 岗位没填地址或地址就是门店地址时才借用门店坐标；其余情况不给坐标，页面只显示地址文字。
 */
class KgContractNavPointTest
{
    private static final BigDecimal JOB_LAT = new BigDecimal("25.512345"), JOB_LNG = new BigDecimal("119.801234");
    private static final BigDecimal STORE_LAT = new BigDecimal("25.498765"), STORE_LNG = new BigDecimal("119.790001");
    private static final String STORE_ADDR = "福建省平潭县东湖山庄98号";

    private final KgJdbc db = mock(KgJdbc.class);
    private final KgUserHubService service = new KgUserHubService();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        // JDBC 在没有签署记录时返回 null；Mockito 默认返回空 Map 不等同于真实未签署。
        when(db.one(contains("from kg_contract_sign"), anyMap())).thenReturn(null);
    }

    private static Map<String, Object> app(String jobAddr, Object jobLat, Object jobLng)
    {
        return p("address", jobAddr, "jobLat", jobLat, "jobLng", jobLng, "storeAddress", STORE_ADDR, "storeLat", STORE_LAT, "storeLng", STORE_LNG);
    }

    @Test
    void jobCoordinatesWinWheneverThePairIsPresent()
    {
        assertArrayEquals(new Object[] { "南舍庄园", JOB_LAT, JOB_LNG }, KgUserHubService.navPoint(app("南舍庄园", JOB_LAT, JOB_LNG)));
        // 岗位地址和门店一样但岗位单独选过点：仍以岗位选的点为准
        assertArrayEquals(new Object[] { STORE_ADDR, JOB_LAT, JOB_LNG }, KgUserHubService.navPoint(app(STORE_ADDR, JOB_LAT, JOB_LNG)));
    }

    @Test
    void storeCoordinatesOnlyWhenJobAddressIsEmptyOrTheStoreAddress()
    {
        assertArrayEquals(new Object[] { STORE_ADDR, STORE_LAT, STORE_LNG }, KgUserHubService.navPoint(app(STORE_ADDR, null, null)));
        assertArrayEquals(new Object[] { " " + STORE_ADDR + " ", STORE_LAT, STORE_LNG }, KgUserHubService.navPoint(app(" " + STORE_ADDR + " ", null, null)),
                "首尾空白不算不同地址");
        // 岗位没填地址：地址和坐标一起借门店的，页面不再显示「门店还没有登记地址」
        assertArrayEquals(new Object[] { STORE_ADDR, STORE_LAT, STORE_LNG }, KgUserHubService.navPoint(app("", null, null)));
        assertArrayEquals(new Object[] { STORE_ADDR, STORE_LAT, STORE_LNG }, KgUserHubService.navPoint(app(null, null, null)));
        // 只有半个坐标不算选过点
        assertArrayEquals(new Object[] { STORE_ADDR, STORE_LAT, STORE_LNG }, KgUserHubService.navPoint(app(STORE_ADDR, JOB_LAT, null)));
    }

    @Test
    void customAddressWithoutJobCoordinatesGetsNoCoordinates()
    {
        assertArrayEquals(new Object[] { "福建省平潭县南舍庄园", null, null }, KgUserHubService.navPoint(app("福建省平潭县南舍庄园", null, null)));
        assertArrayEquals(new Object[] { "福建省平潭县南舍庄园", null, null }, KgUserHubService.navPoint(app("福建省平潭县南舍庄园", null, JOB_LNG)));
    }

    @Test
    void storeWithoutAddressOrCoordinatesStaysEmpty()
    {
        Map<String, Object> a = p("address", "", "storeAddress", null, "storeLat", null, "storeLng", null);
        assertArrayEquals(new Object[] { null, null, null }, KgUserHubService.navPoint(a));
    }

    @Test
    void contractReadsJobCoordinatesAndHidesLocationBeforeHire()
    {
        KgCityService cities = mock(KgCityService.class);
        ReflectionTestUtils.setField(service, "cityService", cities);
        when(cities.jobCity("福建平潭岛")).thenReturn("平潭");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        Map<String, Object> row = p("appId", 12L, "status", "store_confirmed", "title", "直播换工", "storeName", "平潭袋鼠小院", "contactPhone", "13800000000",
                "realName", "用户", "city", "福建平潭岛", "address", "福建省平潭县南舍庄园", "jobLat", JOB_LAT, "jobLng", JOB_LNG,
                "storeAddress", STORE_ADDR, "storeLat", STORE_LAT, "storeLng", STORE_LNG);
        when(db.one(startsWith("select a.*, j.title"), anyMap())).thenReturn(row);
        Map<String, Object> m = service.contract(7L, 12L);
        verify(db).one(sql.capture(), eq(p("a", 12L, "u", 7L)));
        String q = sql.getValue();
        assertTrue(q.contains("j.latitude job_lat") && q.contains("j.longitude job_lng"), "取岗位坐标（V42 的 kg_job.latitude / longitude）");
        assertTrue(q.contains("s.address store_address") && q.contains("s.latitude store_lat") && q.contains("s.longitude store_lng"));
        assertEquals("福建省平潭县南舍庄园", m.get("address"));
        assertEquals(JOB_LAT, m.get("latitude"));
        assertEquals(JOB_LNG, m.get("longitude"));
        assertEquals("13800000000", m.get("storePhone"));
        assertEquals("2026-11", m.get("version"));
        assertEquals("平潭", m.get("activityCity"), "当地活动使用归一后的岗位地区");
        assertEquals("福建平潭岛", m.get("city"), "保留原始岗位地区供其他显示使用");
        assertNull(m.get("onboardTime"), "尚未到店不能生成确认时间");
        row.put("status", "onboarded");
        row.put("onboardTime", "2026-11-09 14:30:00");
        assertEquals("2026-11-09 14:30:00", service.contract(7L, 12L).get("onboardTime"), "到店确认页使用门店确认的真实时间");

        // 还没录用：地址、坐标、电话都不给
        row.put("status", "submitted");
        m = service.contract(7L, 12L);
        assertNull(m.get("address"));
        assertNull(m.get("latitude"));
        assertNull(m.get("longitude"));
        assertNull(m.get("storePhone"));

        // 录用后、岗位地址是自定义的又没选点：只给地址文字
        row.put("status", "contract_signed");
        row.put("jobLat", null);
        row.put("jobLng", null);
        m = service.contract(7L, 12L);
        assertEquals("福建省平潭县南舍庄园", m.get("address"));
        assertNull(m.get("latitude"));
        assertNull(m.get("longitude"));
    }
}
