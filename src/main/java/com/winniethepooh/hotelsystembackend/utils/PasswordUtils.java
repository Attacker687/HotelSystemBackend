package com.winniethepooh.hotelsystembackend.utils;

import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 密码哈希：新密码一律 BCrypt；兼容 S8 之前入库的不加盐 MD5，由调用方在登录成功后迁移。 */
public final class PasswordUtils {

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();

    private PasswordUtils() {
    }

    public static String hash(String rawPassword) {
        return BCRYPT.encode(rawPassword);
    }

    public static boolean matches(String rawPassword, String stored) {
        if (rawPassword == null || stored == null) return false;
        if (!isLegacy(stored)) return BCRYPT.matches(rawPassword, stored);
        return MessageDigest.isEqual(DigestUtils.md5Hex(rawPassword).getBytes(StandardCharsets.US_ASCII),
                stored.toLowerCase().getBytes(StandardCharsets.US_ASCII));
    }

    /** 不是 BCrypt 格式（$2a$/$2b$/$2y$ 开头）的就是旧 MD5 哈希 */
    public static boolean isLegacy(String stored) {
        return !stored.startsWith("$2");
    }
}
