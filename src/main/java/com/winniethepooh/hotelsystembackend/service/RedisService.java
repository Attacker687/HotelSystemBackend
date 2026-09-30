package com.winniethepooh.hotelsystembackend.service;

import com.winniethepooh.hotelsystembackend.constant.RoleConstant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * 登录态：{token} → {ROLE}_{id}，反向索引 session:{ROLE}_{id} → 当前 token，两者 TTL 都与 JWT 有效期一致（3 小时）。
 * 登录、退出、停用、删除、改密都按反向索引直接删除，不扫描 keyspace。
 */
@Service
public class RedisService {

    public static final Duration SESSION_TTL = Duration.ofHours(3);
    private static final String SESSION_PREFIX = "session:";

    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 登录态主体，格式 {ROLE}_{id}，如 MANAGER_5 */
    public static String principal(Integer role, Integer id) {
        return RoleConstant.convertToStringConstant(role) + "_" + id;
    }

    /** 登录：撤销该身份的旧 token（同一身份只保留一个），再登记新 token */
    public void saveSession(String principal, String token) {
        revokeSession(principal);
        ValueOperations<String, String> ops = redisTemplate.opsForValue();
        ops.set(token, principal, SESSION_TTL);
        ops.set(SESSION_PREFIX + principal, token, SESSION_TTL);
    }

    /** 让该身份当前的 token 立即失效 */
    public void revokeSession(String principal) {
        String sessionKey = SESSION_PREFIX + principal;
        String token = redisTemplate.opsForValue().get(sessionKey);
        if (token != null) redisTemplate.delete(List.of(token, sessionKey));
    }
}
