package com.winniethepooh.hotelsystembackend.utils;

import com.winniethepooh.hotelsystembackend.support.Fixtures;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** S11：JWT 密钥来自配置，不再使用仓库里的硬编码常量。 */
class JwtUtilsTest {

    @Test
    void tc005_tokenSignedWithLegacyHardcodedKeyIsRejected() throws Exception {
        JwtUtils jwt = new JwtUtils(Fixtures.JWT_SECRET);
        String legacy = new ClassPathResource("jwt/legacy-hardcoded-key.jwt")
                .getContentAsString(StandardCharsets.US_ASCII).trim();

        // 同一实例签发的 token 能解析，说明失败只因签名不符
        Object phone = Fixtures.registration("N").get("phone");
        assertThat(jwt.parseJWT(jwt.generateJwt(Map.of("phone", phone))).get("phone")).isEqualTo(phone);
        assertThatThrownBy(() -> jwt.parseJWT(legacy)).isInstanceOf(JwtException.class);
    }

    @Test
    void tc035_tokensForSameClaimsAreDistinct() {
        JwtUtils jwt = new JwtUtils(Fixtures.JWT_SECRET);
        Map<String, Object> claims = Map.of("phone", Fixtures.registration("N").get("phone"));

        assertThat(jwt.generateJwt(claims)).isNotEqualTo(jwt.generateJwt(claims));
    }

    @Test
    void tc058_secretShorterThan256BitsIsRejected() {
        assertThatThrownBy(() -> new JwtUtils("x".repeat(31)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
        assertThatThrownBy(() -> new JwtUtils(""))
                .hasMessageContaining("hotel.jwt.secret");
    }
}
