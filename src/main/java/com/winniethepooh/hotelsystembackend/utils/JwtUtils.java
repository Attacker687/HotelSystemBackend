package com.winniethepooh.hotelsystembackend.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * JWT 签发与解析。签名密钥来自配置 hotel.jwt.secret（环境变量 JWT_SECRET），
 * 缺失或短于 256 位时拒绝启动，不再使用写死在代码里的常量。
 */
@Component
public class JwtUtils {

    private static final int MIN_SECRET_BYTES = 32; // HS256 至少 256 位
    private static final Long expire = 1000 * 60 * 60 * 3L; // 过期时间3小时

    private final byte[] signKey;

    public JwtUtils(@Value("${hotel.jwt.secret:}") String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("缺少 JWT 签名密钥：请用环境变量 JWT_SECRET（配置项 hotel.jwt.secret）"
                    + "提供至少 256 位（32 字节）的随机密钥");
        }
        this.signKey = secret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 生成JWT令牌
     * @param claims JWT第二部分负载 payload 中存储的内容
     */
    public String generateJwt(Map<String, Object> claims){
        return Jwts.builder()
                .addClaims(claims)
                .signWith(SignatureAlgorithm.HS256, signKey)
                .setExpiration(new Date(System.currentTimeMillis() + expire))
                .compact();
    }

    /**
     * 解析JWT令牌
     * @param jwt JWT令牌
     * @return JWT第二部分负载 payload 中存储的内容
     */
    public Claims parseJWT(String jwt){
        return Jwts.parser()
                .setSigningKey(signKey)
                .parseClaimsJws(jwt)
                .getBody();
    }
}
