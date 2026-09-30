package com.winniethepooh.hotelsystembackend.filter;

import com.winniethepooh.hotelsystembackend.context.BaseContext;
import com.winniethepooh.hotelsystembackend.support.Fixtures;
import com.winniethepooh.hotelsystembackend.utils.JwtUtils;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** S2：LoginFilter 在请求结束时（正常返回或抛异常）清理 BaseContext，身份不会残留在 Tomcat 工作线程上。 */
class LoginFilterTest {

    @AfterEach
    void clear() {
        BaseContext.clear();
    }

    @ParameterizedTest(name = "下游{0}")
    @ValueSource(strings = {"正常返回", "抛异常"})
    @SuppressWarnings("unchecked")
    void tc004_baseContextIsClearedAfterRequest(String downstream) throws Exception {
        JwtUtils jwt = new JwtUtils(Fixtures.JWT_SECRET);
        String token = jwt.generateJwt(Map.of("id", 5, "role", 1));
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(token)).thenReturn("MANAGER_5");
        LoginFilter filter = new LoginFilter(redis, jwt);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/business/revenue/stats");
        request.addHeader("token", token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Integer> seenId = new AtomicReference<>();
        AtomicReference<Integer> seenRole = new AtomicReference<>();
        FilterChain chain = mock(FilterChain.class);
        doAnswer(inv -> {
            seenId.set(BaseContext.getCurrentId());
            seenRole.set(BaseContext.getCurrentRole());
            if (downstream.equals("抛异常")) throw new RuntimeException("boom");
            return null;
        }).when(chain).doFilter(any(), any());

        if (downstream.equals("抛异常")) {
            assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                    .isInstanceOf(RuntimeException.class).hasMessage("boom");
        } else {
            filter.doFilter(request, response, chain);
        }

        assertThat(seenId.get()).isEqualTo(5);
        assertThat(seenRole.get()).isEqualTo(1);
        assertThat(BaseContext.getCurrentId()).isNull();
        assertThat(BaseContext.getCurrentRole()).isNull();
    }
}
