package com.winniethepooh.hotelsystembackend;

import com.winniethepooh.hotelsystembackend.support.Fixtures;
import com.winniethepooh.hotelsystembackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** C1：错误按 HTTP 状态返回，响应体仍是 Result（code=1 + 具体原因）。 */
class ErrorResponseIT extends IntegrationTestBase {

    @Test
    void c1_insufficientRoleReturns403() {
        String token = login(base.userA());

        Resp r = get("/order/query", token);

        assertThat(r.status()).isEqualTo(403);
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.msg()).isEqualTo("无权限访问该资源");
        assertThat(r.data().isNull()).isTrue();
    }

    @Test
    void c1_businessExceptionCarriesItsStatusAndMessage() {
        Map<String, Object> body = Fixtures.registration("N");
        body.put("phone", base.user("A").login());

        Resp r = post("/user/register", null, body);

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.msg()).isEqualTo("该手机号已被注册");
    }

    @Test
    void c1_plainRuntimeMessageIsNoLongerSwallowed() {
        String token = login(base.userA());

        Map<String, Object> body = Fixtures.mealOrderBody();
        body.put("totalAmount", 1);

        Resp r = post("/order/meal-order", token, body);

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.msg()).isEqualTo("订单金额校验失败");
        assertThat(fx.count("meal_order")).isZero();
        assertThat(fx.count("meal_order_item")).isZero();
    }

    @Test
    void c1_springMvcErrorsKeepTheirOwnStatus() {
        Resp badJson = post("/user/register", null, "{not json");
        Resp wrongMethod = call(HttpMethod.DELETE, "/user/register", null, null);

        assertThat(badJson.status()).isEqualTo(400);
        assertThat(badJson.code()).isEqualTo(1);
        assertThat(wrongMethod.status()).isEqualTo(405);
        assertThat(wrongMethod.code()).isEqualTo(1);
    }
}
