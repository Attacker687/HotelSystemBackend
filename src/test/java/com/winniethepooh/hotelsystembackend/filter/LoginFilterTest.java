package com.winniethepooh.hotelsystembackend.filter;

import com.winniethepooh.hotelsystembackend.context.BaseContext;
import com.winniethepooh.hotelsystembackend.constant.RoleConstant;
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
import org.springframework.mock.env.MockEnvironment;

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
        // 不起库的单元测试：模拟身份由夹具别名派生，避免固定数据库 id。
        int id = Fixtures.registration("N").get("phone").hashCode();
        String token = jwt.generateJwt(Map.of("id", id, "role", RoleConstant.MANAGER));
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(token)).thenReturn("MANAGER_" + id);
        LoginFilter filter = new LoginFilter(redis, jwt, new MockEnvironment());

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

        assertThat(seenId.get()).isEqualTo(id);
        assertThat(seenRole.get()).isEqualTo(RoleConstant.MANAGER);
        assertThat(BaseContext.getCurrentId()).isNull();
        assertThat(BaseContext.getCurrentRole()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "dev", "test", "e2e"})
    void tc132_staticAssetsAreAnonymousWithoutOpeningSiblingApiPaths(String profile) throws Exception {
        MockEnvironment env = new MockEnvironment();
        if (!profile.isEmpty()) env.setActiveProfiles(profile);
        LoginFilter filter = new LoginFilter(mock(StringRedisTemplate.class), mock(JwtUtils.class), env);
        for (String path : new String[]{"/", "/index.html", "/app.js", "/style.css", "/app.js/extra",
                "/rooms/app.js", "/static/app.js", "/rooms", "/staff/register"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/hotel" + path);
            request.setContextPath("/hotel");
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);
            filter.doFilter(request, response, chain);
            boolean allowed = java.util.Set.of("/", "/index.html", "/app.js", "/style.css").contains(path);
            assertThat(response.getStatus()).as("%s %s", profile, path).isEqualTo(allowed ? 200 : 401);
            verify(chain, times(allowed ? 1 : 0)).doFilter(request, response);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "dev", "test", "e2e"})
    void tc136_swaggerWhitelistRespectsProfileAndPathBoundaries(String profile) throws Exception {
        MockEnvironment env = new MockEnvironment();
        if (!profile.isEmpty()) env.setActiveProfiles(profile);
        LoginFilter filter = new LoginFilter(mock(StringRedisTemplate.class), mock(JwtUtils.class), env);
        for (String path : new String[]{"/swagger-ui.html", "/swagger-ui/index.html", "/swagger-ui/swagger-ui.css",
                "/swagger-ui/swagger-ui-bundle.js", "/swagger-ui/swagger-initializer.js",
                "/v3/api-docs", "/v3/api-docs/swagger-config", "/swagger-ui.html/extra",
                "/swagger-ui-extra", "/v3/api-docs-extra", "/rooms/swagger-ui/index.html"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/hotel" + path);
            request.setContextPath("/hotel");
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            boolean allowed = profile.equals("dev") && !path.endsWith("extra") && !path.startsWith("/rooms/");
            assertThat(response.getStatus()).as("%s %s", profile, path).isEqualTo(allowed ? 200 : 401);
            verify(chain, times(allowed ? 1 : 0)).doFilter(request, response);
        }
    }
}
