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

@WebFilter
@Slf4j
@Component
public class LoginFilter implements Filter {
    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        log.info("The filter is working");
        HttpServletRequest httpServletRequest = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        //登录放行
        if (httpServletRequest.getRequestURI().contains("/api/user/login") || httpServletRequest.getRequestURI().contains("/api/user/register")) {
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
            String userId = ops.get(tokenGotFromRequest);
            if (userId == null) throw new RuntimeException();
            claims = JwtUtils.parseJWT(tokenGotFromRequest);
            Integer id = (Integer) claims.get("id");
            if (id != null) BaseContext.setCurrentId(Long.valueOf(id));
        } catch (Exception e) {
            e.printStackTrace();
            response.sendError(401, "Unauthorized");
            return;
        }
        filterChain.doFilter(servletRequest, servletResponse);
    }
}
