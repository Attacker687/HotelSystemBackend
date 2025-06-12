package com.winniethepooh.hotelsystembackend.filter;

import com.winniethepooh.hotelsystembackend.context.BaseContext;
import com.winniethepooh.hotelsystembackend.utils.JwtUtils;
import io.jsonwebtoken.Claims;
import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@WebFilter
@Slf4j

public class LoginFilter implements Filter {
    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest httpServletRequest = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;
        log.info("The filter is working");
        log.info("Current url: {}", httpServletRequest.getRequestURI());

        if (httpServletRequest.getRequestURI().contains("/login") || httpServletRequest.getRequestURI().contains("/register")
        || httpServletRequest.getRequestURI().contains("/swagger-ui.html" )) {
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

            claims = JwtUtils.parseJWT(tokenGotFromRequest);
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
    }
}
