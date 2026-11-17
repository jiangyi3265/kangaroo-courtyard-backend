package com.ruoyi;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/**
 * web容器中进行部署
 * 
 * @author ruoyi
 */
public class RuoYiServletInitializer extends SpringBootServletInitializer
{
    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder application)
    {
        // 外置容器部署时 RuoYiApplication.main() 不会执行，时区必须在这里补上，
        // 否则同一份代码打成 war 就会退回"Java 与 MySQL 差 16 小时"的老问题。
        RuoYiApplication.pinDefaultTimeZone();
        return application.sources(RuoYiApplication.class);
    }
}
