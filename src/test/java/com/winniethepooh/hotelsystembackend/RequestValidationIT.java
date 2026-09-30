package com.winniethepooh.hotelsystembackend;

import com.winniethepooh.hotelsystembackend.support.Fixtures;
import com.winniethepooh.hotelsystembackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** C1/C3：参数错误返回 HTTP 400，提示取约束注解上的文案；非法请求不进入 Service、不落库。 */
class RequestValidationIT extends IntegrationTestBase {

    /** 新住客 N 的注册信息（strategy.data），password 替换为给定值。 */
    private static Map<String, Object> registerN(String password) {
        Map<String, Object> m = Fixtures.registration("N");
        m.put("password", password);
        return m;
    }

    private static final String N_PHONE = (String) Fixtures.registration("N").get("phone");

    private String passwordHashOf(String phone) {
        return jdbc.queryForObject("select password from user where phone = ?", String.class, phone);
    }

    @Test
    void tc124_priceCalendarStartAfterEndReturns400() {
        String token = login(base.manager());
        LocalDate d = LocalDate.now().plusDays(10);

        Resp r = post("/business/calendar", token,
                Map.of("startDate", d.plusDays(5).toString(), "endDate", d.toString(), "roomType", 0, "price", 300));

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.msg()).isEqualTo("开始日期不能在结束日期之后");
        assertThat(fx.count("price_calendar")).isZero();
    }

    @Test
    void tc125_registerWithNullNameReturns400BeforeService() {
        int users = fx.count("user"), individuals = fx.count("individual");
        Map<String, Object> body = Fixtures.registration("N");
        body.put("name", null);

        Resp r = post("/user/register", null, body);

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.msg()).contains("姓名");
        assertThat(fx.count("user")).isEqualTo(users);
        assertThat(fx.count("individual")).isEqualTo(individuals);
    }

    @Test
    void tc126_roomOrderWithNullCheckInTimeReturns400BeforeService() {
        String token = login(base.userA());
        int individuals = fx.count("individual"), orders = fx.count("room_order");
        LocalDateTime checkout = LocalDate.now().plusDays(11).atTime(12, 0);
        Map<String, Object> body = Fixtures.roomOrderBody(base.room("R1").number(), null, checkout);

        Resp r = post("/order", token, body);

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.msg()).contains("入住");
        assertThat(fx.count("individual")).isEqualTo(individuals);
        assertThat(fx.count("room_order")).isEqualTo(orders);
    }

    @Test
    void tc127_mealOrderItemWithZeroQuantityReturns400BeforeService() {
        String token = login(base.userA());
        int orders = fx.count("meal_order"), items = fx.count("meal_order_item");

        Resp r = post("/order/meal-order", token, Map.of("address", "1208 房", "totalAmount", 0,
                "itemList", List.of(Map.of("dishId", base.dish("X").id(), "quantity", 0, "unitPrice", base.dish("X").price()))));

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.msg()).contains("数量");
        assertThat(fx.count("meal_order")).isEqualTo(orders);
        assertThat(fx.count("meal_order_item")).isEqualTo(items);
    }

    @ParameterizedTest(name = "tc128[{index}] {0}={1} -> {2}")
    @CsvSource(delimiter = '|', value = {
            "name        | 一二三四五六七八九十一二三四五六七 | 用户姓名不能超过16个字",
            "idCardNumber| 110101199001010188                | 请输入正确的身份证",
            "phone       | 12345678901                       | 请输入正确的手机号",
            "email       | abc@                              | 请输入正确的邮箱"
    })
    void tc128_registerValidationKeepsOriginalMessages(String field, String value, String expectedMsg) {
        int users = fx.count("user");
        Map<String, Object> body = Fixtures.registration("N");
        body.put(field, value);

        Resp r = post("/user/register", null, body);

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.msg()).isEqualTo(expectedMsg);
        assertThat(fx.count("user")).isEqualTo(users);
    }

    @ParameterizedTest(name = "tc129[{index}] {0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "Abcdefg1              | 密码必须包含大小写字母、数字和特殊字符",
            "Aa1!aaa               | 密码不得少于8位",
            "Aa1!aaaaaaaaaaaaaaaaa | 密码不得多于20位"
    })
    void tc129_invalidPasswordRejectedWithSameMessageByRegisterAndChange(String password, String expectedMsg) {
        String hashBefore = passwordHashOf(base.userA().login());

        Resp reg = post("/user/register", null, registerN(password));
        String token = login(base.userA());
        Resp change = post("/user/change", token, Map.of("phone", base.userA().login(),
                "originPassword", base.userA().password(), "passwordToChange", password));

        assertThat(reg.status()).isEqualTo(400);
        assertThat(change.status()).isEqualTo(400);
        assertThat(reg.msg()).isEqualTo(change.msg()).isEqualTo(expectedMsg);
        assertThat(fx.count("user", "phone = ?", N_PHONE)).isZero();
        assertThat(passwordHashOf(base.userA().login())).isEqualTo(hashBefore);
    }

    @ParameterizedTest(name = "tc130[{index}] {0}")
    @ValueSource(strings = {"Aa1!aaaa", "Aa1!aaaaaaaaaaaaaaaa"})
    void tc130_validPasswordAcceptedByRegisterAndChange(String password) {
        Resp reg = post("/user/register", null, registerN(password));

        assertThat(reg.status()).isEqualTo(200);
        assertThat(reg.code()).isZero();
        assertThat(passwordHashOf(N_PHONE)).isEqualTo(Fixtures.hash(password));

        Map<String, Object> n2 = Fixtures.registration("N2");
        String otherPhone = (String) n2.get("phone");
        assertThat(post("/user/register", null, n2).code()).isZero();
        String token = login(new Fixtures.Account(0, otherPhone, Fixtures.PASSWORD, 0));

        Resp change = post("/user/change", token, Map.of("phone", otherPhone,
                "originPassword", Fixtures.PASSWORD, "passwordToChange", password));

        assertThat(change.status()).isEqualTo(200);
        assertThat(change.code()).isZero();
        assertThat(passwordHashOf(otherPhone)).isEqualTo(Fixtures.hash(password));
    }
}
