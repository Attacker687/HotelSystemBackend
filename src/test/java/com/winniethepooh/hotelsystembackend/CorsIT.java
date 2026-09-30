package com.winniethepooh.hotelsystembackend;

import com.winniethepooh.hotelsystembackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** S13：CORS 只允许 hotel.cors.allowed-origins 里的前端域名（test profile 只配了 https://front.test.example）。 */
class CorsIT extends IntegrationTestBase {

    private ResponseEntity<String> preflight(String origin) {
        HttpHeaders h = new HttpHeaders();
        h.setOrigin(origin);
        h.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");
        h.set(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "token");
        return rest.exchange("/rooms", HttpMethod.OPTIONS, new HttpEntity<>(h), String.class);
    }

    @Test
    void tc061_preflightFromUnlistedOriginGetsNoAllowOrigin() {
        ResponseEntity<String> r = preflight("https://evil.example");

        assertThat(r.getHeaders().containsKey(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isFalse();
    }

    @Test
    void tc062_preflightFromListedOriginIsAllowedWithCredentials() {
        String origin = "https://front.test.example";

        ResponseEntity<String> r = preflight(origin);

        assertThat(r.getHeaders().getAccessControlAllowOrigin()).isEqualTo(origin);
        assertThat(r.getHeaders().getAccessControlAllowCredentials()).isTrue();
    }
}
