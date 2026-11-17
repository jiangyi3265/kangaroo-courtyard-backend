package com.ruoyi.kangaroo.security;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.UriUtils;
import com.ruoyi.kangaroo.ext.core.KgArrivalTickets;

@Component
public class ArrivalTicketResourceInterceptor implements HandlerInterceptor
{
    @Autowired private KgArrivalTickets files;
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception
    {
        String path = UriUtils.decode(request.getRequestURI(), "UTF-8");
        String context = request.getContextPath();
        if (!context.isEmpty()) path = path.substring(context.length());
        if ("/common/download/resource".equals(path)) {
            String resource = request.getParameter("resource");
            // Match the CommonController's substringAfter("/profile") resolution, including prefixed/absolute URLs.
            path = resource != null && resource.contains("/profile") ? "/profile" + com.ruoyi.common.utils.file.FileUtils.stripPrefix(resource) : null;
        }
        if (path == null || !path.startsWith("/profile/")) return true;
        // Resource handlers resolve dot segments and repeated separators to the same file.
        path = org.springframework.util.StringUtils.cleanPath(path.replaceAll("/{2,}", "/"));
        if (files.isLegacyPrivate(path)) {
            response.setHeader("Cache-Control", "no-store");
            response.sendError(404);
            return false;
        }
        return true;
    }
}
