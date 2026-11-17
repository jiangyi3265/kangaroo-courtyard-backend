package com.ruoyi.kangaroo.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.ruoyi.kangaroo.security.AppAuthInterceptor;

/**
 * 注册小程序端鉴权拦截器。
 * 路径与管理后台接口完全分离：小程序接口一律挂在 /app/** 下。
 */
@Configuration
public class KangarooWebMvcConfig implements WebMvcConfigurer
{
    @Autowired
    private AppAuthInterceptor appAuthInterceptor;
    @Autowired private com.ruoyi.kangaroo.security.CommunityFeatureInterceptor communityFeature;

    @Autowired
    private com.ruoyi.kangaroo.security.ArrivalTicketResourceInterceptor arrivalTickets;

    @Override
    public void addInterceptors(InterceptorRegistry registry)
    {
        registry.addInterceptor(appAuthInterceptor).addPathPatterns("/app/**");
        registry.addInterceptor(communityFeature).addPathPatterns("/app/**");
        // /profile/** 下是用户上传的公开文件：一律带 nosniff，浏览器只按扩展名给的类型处理，不会把伪装成图片的文件当网页执行。
        // 放在最前面，后面的拦截器拒绝访问时回的响应也带着
        registry.addInterceptor(new NoSniffInterceptor()).addPathPatterns("/profile/**");
        registry.addInterceptor(arrivalTickets).addPathPatterns("/profile/**", "/common/download/resource");
    }

    /** 给响应加 X-Content-Type-Options: nosniff */
    public static class NoSniffInterceptor implements HandlerInterceptor
    {
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
        {
            response.setHeader("X-Content-Type-Options", "nosniff");
            return true;
        }
    }
}
