package com.winniethepooh.hotelsystembackend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;


import java.util.List;

@Configuration
public class GlobalCorsConfig {
    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true); // 允许携带 Cookie 或 Token
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");

        // 允许动态来源的前端域名，设置为 * 会被 Spring 拒绝，所以此处不加 Origin 限制
        config.setAllowedOriginPatterns(List.of("*")); // Spring Boot 2.4+ 支持 setAllowedOriginPatterns 替代 addAllowedOrigin("*")

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new FilterRegistrationBean<>(new CorsFilter(source));
    }
}


