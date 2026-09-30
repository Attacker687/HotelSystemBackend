package com.winniethepooh.hotelsystembackend;

import com.winniethepooh.hotelsystembackend.constant.RoleConstant;
import com.winniethepooh.hotelsystembackend.support.Fixtures;
import com.winniethepooh.hotelsystembackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** S9 / GAP-07：同一账号或同一 IP 15 分钟内失败 5 次，限制 15 分钟；test profile 的 1000 在这里改回默认值。 */
@TestPropertySource(properties = {"hotel.login.max-failures=5", "hotel.login.window-minutes=15", "hotel.login.lock-minutes=15"})
class LoginRateLimitIT extends IntegrationTestBase {

    private static final String IP = "127.0.0.1";

    private Fixtures.Account account(String who) {
        return who.equals("住客A") ? base.userA() : base.front();
    }

    private Resp attempt(Fixtures.Account a, String password) {
        return a.role() == RoleConstant.USER
                ? post("/user/login", null, Map.of("phone", a.login(), "password", password))
                : post("/staff/login", null, Map.of("account", a.login(), "password", password));
    }

    private static boolean hasToken(Resp r) {
        return r.data().hasNonNull("token");
    }

    private void failTimes(Fixtures.Account a, int n) {
        for (int i = 0; i < n; i++) assertThat(attempt(a, "Wrong@1234").code()).isNotZero();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"住客A", "前台"})
    void tc048_fourFailuresStillAllowCorrectPassword(String who) {
        Fixtures.Account a = account(who);
        failTimes(a, 4);

        Resp r = attempt(a, a.password());

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.code()).isZero();
        assertThat(hasToken(r)).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"住客A", "前台"})
    void tc049_fiveFailuresLockTheAccount(String who) {
        Fixtures.Account a = account(who);
        failTimes(a, 5);
        redis.delete(List.of("login:fail:ip:" + IP, "login:lock:ip:" + IP)); // 只留按账号的计数和限制

        Resp r = attempt(a, a.password());

        assertThat(r.code()).isNotZero();
        assertThat(hasToken(r)).isFalse();
    }

    @Test
    void tc050_fiveFailuresFromOneIpAcrossAccountsLockTheIp() {
        List<Fixtures.Account> ls = new ArrayList<>();
        for (int i = 1; i <= 6; i++) ls.add(fx.user("测试限流" + i, "170000000" + (10 + i), Fixtures.PASSWORD));
        for (int i = 0; i < 5; i++) assertThat(attempt(ls.get(i), "Wrong@1234").code()).isNotZero();

        Resp r = attempt(ls.get(5), ls.get(5).password());

        assertThat(r.code()).isNotZero();
        assertThat(hasToken(r)).isFalse();
    }

    @Test
    void tc051_loginAllowedAgainAfterLockExpires() throws InterruptedException {
        Fixtures.Account a = base.userA();
        failTimes(a, 5);
        assertThat(attempt(a, a.password()).code()).isNotZero();

        for (String key : List.of("login:fail:account:" + a.login(), "login:lock:account:" + a.login(),
                "login:fail:ip:" + IP, "login:lock:ip:" + IP)) {
            if (Boolean.TRUE.equals(redis.hasKey(key))) redis.expire(key, Duration.ofSeconds(1));
        }
        Thread.sleep(2000);

        Resp r = attempt(a, a.password());
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.code()).isZero();
        assertThat(hasToken(r)).isTrue();
    }
}
