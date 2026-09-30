package com.winniethepooh.hotelsystembackend;

import com.winniethepooh.hotelsystembackend.support.Fixtures;
import com.winniethepooh.hotelsystembackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** S2：Tomcat 只有 1 个工作线程时，经理请求留下的身份不会被匿名的 POST /staff/register 继承。 */
@TestPropertySource(properties = "server.tomcat.threads.max=1")
class AnonymousStaffRegisterIT extends IntegrationTestBase {

    @Test
    void tc016_anonymousStaffRegisterOnManagersThreadIsRejected() {
        String managerToken = login(base.manager());

        for (int round = 1; round <= 5; round++) {
            String account = "anon_mgr_" + round;
            assertThat(get("/staff/list?page=1&pageSize=10", managerToken).status()).isEqualTo(200);

            Resp r = post("/staff/register", null, Map.of("account", account, "password", Fixtures.PASSWORD,
                    "role", 1, "status", 1));

            assertThat(r.status()).as("round %d", round).isEqualTo(401);
            assertThat(fx.count("staff", "account = ?", account)).as("round %d", round).isZero();
        }
    }
}
