package com.winniethepooh.hotelsystembackend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Springdoc 的宽泛资源匹配会覆盖原入口；dev 用精确路径提供静态页面。 */
@Configuration
@Profile("dev")
public class SwaggerStaticPageConfig implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/swagger-ui.html").addResourceLocations("classpath:/static/");
    }
}
