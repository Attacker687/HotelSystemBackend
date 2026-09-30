package com.winniethepooh.hotelsystembackend.filter;

import com.winniethepooh.hotelsystembackend.context.BaseContext;
import com.winniethepooh.hotelsystembackend.utils.JwtUtils;
import io.jsonwebtoken.Claims;
import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

@Component
@WebFilter
@Slf4j

public class LoginFilter implements Filter {
    /** 不需要登录的接口，按完整路径精确匹配 */
    private static final Set<String> PUBLIC_PATHS = Set.of("/user/login", "/user/register", "/staff/login");
    private static final Set<String> STATIC_PATHS = Set.of("/", "/index.html", "/app.js", "/style.css");

    private final StringRedisTemplate redisTemplate;
    private final JwtUtils jwtUtils;
    private final boolean devProfile;

    public LoginFilter(StringRedisTemplate redisTemplate, JwtUtils jwtUtils, Environment environment) {
        this.redisTemplate = redisTemplate;
        this.jwtUtils = jwtUtils;
        this.devProfile = environment.acceptsProfiles(Profiles.of("dev"));
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest httpServletRequest = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;
        log.info("The filter is working");
        log.info("Current url: {}", httpServletRequest.getRequestURI());

        try {
            if (isPublic(httpServletRequest)) {
                filterChain.doFilter(servletRequest, servletResponse);
                return;
            }
            String tokenGotFromRequest = httpServletRequest.getHeader("token");
            Claims claims;
            if (tokenGotFromRequest == null) {
                response.sendError(401, "Unauthorized");
                return;
            }
            try {
                ValueOperations<String, String> ops = redisTemplate.opsForValue();
                String info = ops.get(tokenGotFromRequest);
                if (info == null) throw new RuntimeException();

                claims = jwtUtils.parseJWT(tokenGotFromRequest);
                Integer id = (Integer) claims.get("id");
                Integer role = (Integer) claims.get("role");
                if (id != null) BaseContext.setCurrentId(id);
                if (role != null) BaseContext.setCurrentRole(role);
            } catch (Exception e) {
                e.printStackTrace();
                response.sendError(401, "Unauthorized");
                return;
            }
            filterChain.doFilter(servletRequest, servletResponse);
        } finally {
            BaseContext.clear();
        }
    }

    private boolean isPublic(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return PUBLIC_PATHS.contains(path) || (STATIC_PATHS.contains(path)
                && (request.getMethod().equals("GET") || request.getMethod().equals("HEAD")))
                || (devProfile && (path.equals("/swagger-ui.html")
                || path.startsWith("/swagger-ui/") || path.equals("/v3/api-docs")
                || path.startsWith("/v3/api-docs/")));
    }
}
