package com.ruoyi.kangaroo.ext.stay;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import com.ruoyi.common.exception.ServiceException;

/** Coordinates are an atomic GCJ-02 pair. An empty pair explicitly clears a stale point. */
public final class StayLocation
{
    private StayLocation() {}

    public static void normalize(Map<String, Object> values)
    {
        if (!values.containsKey("latitude") && !values.containsKey("longitude")) return;
        if (!values.containsKey("latitude") || !values.containsKey("longitude"))
            throw new ServiceException("请同时提供纬度和经度", 422);
        BigDecimal lat = number(values.get("latitude"), 90);
        BigDecimal lng = number(values.get("longitude"), 180);
        if ((lat == null) != (lng == null) || (lat != null && lat.signum() == 0 && lng.signum() == 0))
            throw new ServiceException("请选择有效的地图位置", 422);
        values.put("latitude", lat);
        values.put("longitude", lng);
    }

    private static BigDecimal number(Object raw, int limit)
    {
        if (raw == null || raw.toString().trim().isEmpty()) return null;
        try {
            BigDecimal n = new BigDecimal(raw.toString().trim());
            if (n.abs().compareTo(BigDecimal.valueOf(limit)) <= 0) return n.setScale(6, RoundingMode.HALF_UP);
        } catch (NumberFormatException ignored) { }
        throw new ServiceException("地图坐标无效或超出范围", 422);
    }
}
