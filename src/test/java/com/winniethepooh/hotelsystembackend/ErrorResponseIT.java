package com.winniethepooh.hotelsystembackend;

import com.winniethepooh.hotelsystembackend.support.Fixtures;
import com.winniethepooh.hotelsystembackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.List;
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
        Resp r = post("/user/register", null, Map.of("name", "重复", "idCardNumber", Fixtures.idCard(77),
                "phone", base.userA().login(), "email", "dup@example.test", "password", "Aa1!aaaa"));

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.msg()).isEqualTo("该手机号已被注册");
    }

    @Test
    void c1_plainRuntimeMessageIsNoLongerSwallowed() {
        String token = login(base.userA());

        Resp r = post("/order/meal-order", token, Map.of("address", "1208 房", "totalAmount", 1,
                "itemList", List.of(Map.of("dishId", base.dishX(), "quantity", 2, "unitPrice", 38.00))));

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
