package com.winniethepooh.hotelsystembackend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;


import java.util.Arrays;

@Configuration
public class GlobalCorsConfig {
    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter(@Value("${hotel.cors.allowed-origins:}") String[] allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true); // 允许携带 Cookie 或 Token
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");

        // 只允许按环境配置的前端域名（hotel.cors.allowed-origins / CORS_ALLOWED_ORIGINS，逗号分隔），未配置时不允许跨域
        config.setAllowedOrigins(Arrays.stream(allowedOrigins).map(String::trim).filter(s -> !s.isEmpty()).toList());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE); // 先于 LoginFilter：跨域预检不带 token
        return registration;
    }
}

