package com.ruoyi.kangaroo.security.admin;

import java.util.*;
/** Only set for authenticated management requests. Never inherited by background jobs. */
public final class AdminCityContext {
    private static final ThreadLocal<List<Long>> CITIES = new ThreadLocal<>();
    private AdminCityContext() {}
    public static void set(List<Long> cities) { CITIES.set(Collections.unmodifiableList(new ArrayList<>(cities))); }
    public static List<Long> cities() { return CITIES.get(); }
    public static boolean limited() { return CITIES.get() != null; }
    public static void clear() { CITIES.remove(); }
    public static String ids() {
        if (cities() == null || cities().isEmpty()) return "-1";
        StringJoiner s = new StringJoiner(",");
        for (Long id : cities()) { if (id == null || id <= 0) throw new IllegalArgumentException("city"); s.add(id.toString()); }
        return s.toString();
    }
}
