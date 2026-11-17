package com.ruoyi.kangaroo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.web.firewall.HttpStatusRequestRejectedHandler;

/** Firewall input rejection happens before MVC; return a client error without relaxing the firewall. */
@Configuration
public class KangarooSecurityErrorConfig {
    @Bean
    public WebSecurityCustomizer kangarooRequestRejection() {
        return web -> web.requestRejectedHandler(new HttpStatusRequestRejectedHandler(400));
    }
}
