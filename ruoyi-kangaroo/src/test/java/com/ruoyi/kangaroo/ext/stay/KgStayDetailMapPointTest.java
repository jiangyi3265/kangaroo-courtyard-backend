package com.ruoyi.kangaroo.ext.stay;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 房源详情的地图位置（10-16 反馈「袋鼠小院地址定位无法唤起地图」）：房源自己没选点时用门店登记的坐标兜底，
 * 和「到店路线」同一规则；按一对整体取，不拼半个房源点半个门店点；门店坐标字段不外露。
 */
class KgStayDetailMapPointTest
{
    private static Map<String, Object> row(Object lat, Object lng, Object storeLat, Object storeLng)
    {
        Map<String, Object> s = new HashMap<String, Object>();
        s.put("latitude", lat); s.put("longitude", lng); s.put("storeLat", storeLat); s.put("storeLng", storeLng);
        return s;
    }

    @Test
    void stayOwnPointWins()
    {
        Map<String, Object> s = row(new BigDecimal("25.5"), new BigDecimal("119.79"), new BigDecimal("25.0"), new BigDecimal("119.0"));
        KgStayBrowseService.mapPoint(s);
        assertEquals(new BigDecimal("25.5"), s.get("latitude"));
        assertEquals("stay", s.get("mapFrom"));
        assertFalse(s.containsKey("storeLat"));
        assertFalse(s.containsKey("storeLng"));
    }

    @Test
    void missingStayPointFallsBackToStore()
    {
        Map<String, Object> s = row(null, null, new BigDecimal("25.5"), new BigDecimal("119.79"));
        KgStayBrowseService.mapPoint(s);
        assertEquals(new BigDecimal("25.5"), s.get("latitude"));
        assertEquals(new BigDecimal("119.79"), s.get("longitude"));
        assertEquals("store", s.get("mapFrom"));
    }

    @Test
    void halfOrZeroStayPointIsNotMixedWithStore()
    {
        // 房源只填了纬度（或 0,0）：整对换成门店的，不拼成「房源纬度 + 门店经度」
        Map<String, Object> s = row(new BigDecimal("30.1"), null, new BigDecimal("25.5"), new BigDecimal("119.79"));
        KgStayBrowseService.mapPoint(s);
        assertEquals(new BigDecimal("25.5"), s.get("latitude"));
        assertEquals(new BigDecimal("119.79"), s.get("longitude"));
        Map<String, Object> z = row(BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("25.5"), new BigDecimal("119.79"));
        KgStayBrowseService.mapPoint(z);
        assertEquals("store", z.get("mapFrom"));
    }

    @Test
    void neitherPointMeansCopyAddress()
    {
        Map<String, Object> s = row(new BigDecimal("30.1"), null, null, "");
        KgStayBrowseService.mapPoint(s);
        assertNull(s.get("latitude"));
        assertNull(s.get("longitude"));
        assertEquals("", s.get("mapFrom"));
    }
}
