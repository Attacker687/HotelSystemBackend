package com.winniethepooh.hotelsystembackend;

import com.winniethepooh.hotelsystembackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** S9：登录失败时，账号不存在和密码错误的提示、状态码完全相同，不能用来枚举账号。 */
class LoginFailureIT extends IntegrationTestBase {

    private static final String MSG = "账号或密码错误";

    private static void assertNoToken(Resp r) {
        assertThat(r.code()).isNotZero();
        assertThat(r.msg()).isEqualTo(MSG);
        assertThat(r.data().path("token").isMissingNode() || r.data().path("token").isNull()).isTrue();
    }

    @Test
    void tc046_userLoginUnknownAccountAndWrongPasswordLookTheSame() {
        Resp unknown = post("/user/login", null, Map.of("phone", "17000000900", "password", "Whatever@1"));
        Resp wrong = post("/user/login", null, Map.of("phone", base.userA().login(), "password", "Wrong@1234"));

        assertNoToken(unknown);
        assertNoToken(wrong);
        assertThat(unknown.status()).isEqualTo(wrong.status());
    }

    @Test
    void tc047_staffLoginUnknownAccountAndWrongPasswordLookTheSame() {
        Resp unknown = post("/staff/login", null, Map.of("account", "no_such_staff", "password", "Whatever@1"));
        Resp wrong = post("/staff/login", null, Map.of("account", base.front().login(), "password", "Wrong@1234"));

        assertNoToken(unknown);
        assertNoToken(wrong);
        assertThat(unknown.status()).isEqualTo(wrong.status());
    }
}
