package com.ruoyi.kangaroo.security;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import com.ruoyi.common.exception.ServiceException;

/** A city service identity combines current merchant-service grants, never other roles. */
public final class ServiceCityMessageScope
{
    public static final String PREFIX = "service-city:";
    private ServiceCityMessageScope() { }

    public static String key(StaffGrantView grant)
    {
        String city = city(grant);
        if (city == null) return null;
        // Match JavaScript encodeURIComponent, including literal plus signs and spaces.
        try
        {
            return PREFIX + URLEncoder.encode(city, StandardCharsets.UTF_8.name())
                .replace("+", "%20").replace("%21", "!").replace("%27", "'")
                .replace("%28", "(").replace("%29", ")").replace("%7E", "~");
        }
        catch (UnsupportedEncodingException e) { throw new IllegalStateException(e); }
    }

    public static List<Long> stores(AppLoginUser me, String scope)
    {
        String requested;
        try
        {
            if (scope == null || !scope.startsWith(PREFIX)) throw denied();
            requested = URLDecoder.decode(scope.substring(PREFIX.length()).replace("+", "%2B"), StandardCharsets.UTF_8.name());
        }
        catch (IllegalArgumentException e) { throw denied(); }
        catch (UnsupportedEncodingException e) { throw new IllegalStateException(e); }
        if (requested.isEmpty() || !requested.equals(requested.trim())) throw denied();
        boolean authorized = false;
        Set<Long> ids = new LinkedHashSet<>();
        if (me != null && me.getStaffGrants() != null)
        {
            for (StaffGrantView grant : me.getStaffGrants())
            {
                if (!requested.equals(city(grant))) continue;
                authorized = true;
                if (grant.getStoreId() != null && grant.getStoreId() > 0) ids.add(grant.getStoreId());
            }
        }
        if (!authorized) throw denied();
        // A city grant without active stores must not fall back to an unscoped inbox.
        if (ids.isEmpty()) ids.add(-1L);
        return new ArrayList<>(ids);
    }

    private static String city(StaffGrantView grant)
    {
        if (grant == null || !Boolean.TRUE.equals(grant.getActive()) || !"merchant_service".equals(grant.getRoleCode())) return null;
        String city = grant.getCity() == null ? "" : grant.getCity().trim();
        return city.isEmpty() ? null : city;
    }

    private static ServiceException denied()
    {
        return new ServiceException("当前身份授权已变化，请重新选择身份", 403);
    }
}
