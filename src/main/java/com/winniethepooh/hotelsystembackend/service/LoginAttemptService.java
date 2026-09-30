package com.winniethepooh.hotelsystembackend.service;

import com.winniethepooh.hotelsystembackend.exception.BusinessException;
import com.winniethepooh.hotelsystembackend.exception.PasswordIncorrectException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 登录失败限流（S9）：同一账号或同一 IP 在窗口期内失败达到上限后，限制一段时间内不能登录。
 * 计数 login:fail:{account|ip}:{值}，限制标记 login:lock:{account|ip}:{值}；IP 取 remoteAddr，不信任 X-Forwarded-For。
 */
@Service
public class LoginAttemptService {

    @Autowired
    private StringRedisTemplate redisTemplate;
    @Value("${hotel.login.max-failures:5}")
    private int maxFailures;
    @Value("${hotel.login.window-minutes:15}")
    private long windowMinutes;
    @Value("${hotel.login.lock-minutes:15}")
    private long lockMinutes;

    /** 包住一次登录校验：被限制时直接拒绝；账号或密码错误时记一次失败；成功时清掉该账号的失败计数 */
    public <T> T guard(String account, String ip, Supplier<T> login) {
        if (Boolean.TRUE.equals(redisTemplate.hasKey("login:lock:account:" + account))
                || Boolean.TRUE.equals(redisTemplate.hasKey("login:lock:ip:" + ip))) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "登录失败次数过多，请稍后再试");
        }
        T result;
        try {
            result = login.get();
        } catch (PasswordIncorrectException e) {
            recordFailure("account", account);
            recordFailure("ip", ip);
            throw e;
        }
        redisTemplate.delete("login:fail:account:" + account);
        return result;
    }

    private void recordFailure(String kind, String value) {
        String failKey = "login:fail:" + kind + ":" + value;
        Long failures = redisTemplate.opsForValue().increment(failKey);
        if (failures == null) return;
        if (failures == 1) redisTemplate.expire(failKey, Duration.ofMinutes(windowMinutes));
        if (failures >= maxFailures) {
            redisTemplate.opsForValue().set("login:lock:" + kind + ":" + value, "1", Duration.ofMinutes(lockMinutes));
        }
    }
}
