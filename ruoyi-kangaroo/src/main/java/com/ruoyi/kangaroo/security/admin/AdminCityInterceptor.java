package com.ruoyi.kangaroo.security.admin;

import javax.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;

@Component
public class AdminCityInterceptor implements HandlerInterceptor, WebMvcConfigurer {
    private final AdminCityScopeService scopes;
    public AdminCityInterceptor(AdminCityScopeService scopes) { this.scopes=scopes; }
    @Override public void addInterceptors(InterceptorRegistry r) {
        r.addInterceptor(this).addPathPatterns("/kangaroo/**","/system/**","/monitor/**","/tool/**","/common/download/**")
            // Signed private downloads use their own owner/signature validation, not an admin session.
            .excludePathPatterns("/kangaroo/file/private", "/kangaroo/file/material", "/kangaroo/file/chat",
                "/kangaroo/file/resume", "/kangaroo/file/mediation");
    }
    @Override public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler) {
        AdminCityContext.clear();
        long uid=SecurityUtils.getUserId();
        if(scopes.all(uid)) return true;
        String path=req.getRequestURI().substring(req.getContextPath().length());
        if(path.startsWith("/system/user/profile"))return true;
        // System authorization/configuration and global exports must not allow a city account to expand its own scope.
        if(!path.startsWith("/kangaroo/") && !path.startsWith("/system/user/profile") && !path.startsWith("/system/dict/data/type/") && !path.startsWith("/system/config/configKey/"))
            throw new ServiceException("此功能需要全部城市权限，请联系超级管理员",403);
        java.util.List<Long> cities=scopes.cities(uid);
        if(path.startsWith("/kangaroo/") && cities.isEmpty()) throw new ServiceException("当前账号尚未配置业务城市，请联系超级管理员授权",403);
        AdminCityContext.set(cities);
        return true;
    }
    @Override public void afterCompletion(HttpServletRequest req,HttpServletResponse res,Object handler,Exception ex) { AdminCityContext.clear(); }
}
