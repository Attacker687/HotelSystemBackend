package com.winniethepooh.hotelsystembackend;

import com.winniethepooh.hotelsystembackend.support.Fixtures;
import com.winniethepooh.hotelsystembackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** S1：类上的 @RoleRequired 生效；S2：白名单精确匹配，其余路径一律要 token。 */
class AccessControlIT extends IntegrationTestBase {

    private static final String DENIED = "无权限访问该资源";

    private Fixtures.Account account(String role) {
        return switch (role) {
            case "住客A" -> base.userA();
            case "经理" -> base.manager();
            case "前台" -> base.front();
            case "餐厅" -> base.restaurant();
            default -> throw new IllegalArgumentException(role);
        };
    }

    static Stream<Arguments> businessQueries() {
        LocalDate today = LocalDate.now();
        String range = "startDate=" + today + "&endDate=" + today.plusDays(6);
        List<String> paths = List.of(
                "/business/revenue/stats?date=" + today,
                "/business/revenue/trend?" + range,
                "/business/revenue/room-type?" + range,
                "/business/occupancy/heatmap?" + range,
                "/business/dish/top10?" + range,
                "/business/detail?" + range,
                "/business/calendar?" + range + "&roomType=0");
        return Stream.of("住客A", "前台", "餐厅").flatMap(role -> paths.stream().map(p -> Arguments.of(role, p)));
    }

    @ParameterizedTest(name = "{0} GET {1}")
    @MethodSource("businessQueries")
    void tc010_nonManagerCannotQueryBusiness(String role, String path) {
        String token = login(account(role));

        Resp r = get(path, token);

        assertThat(r.status()).isEqualTo(403);
        assertThat(r.data().isNull()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"住客A", "前台", "餐厅"})
    void tc011_nonManagerCannotUpdatePriceCalendar(String role) {
        LocalDate today = LocalDate.now();
        fx.price(0, today.plusDays(2), new BigDecimal("300.00"));
        String snapshotSql = "select concat(date, '|', price, '|', coalesce(updated_at, '')) from price_calendar "
                + "where room_type = 0 and date between ? and ? order by date";
        Supplier<List<String>> snapshot = () -> jdbc.queryForList(snapshotSql, String.class,
                today.plusDays(1), today.plusDays(7));
        List<String> before = snapshot.get();
        String token = login(account(role));

        Resp r = post("/business/calendar", token, Map.of("startDate", today.plusDays(1).toString(),
                "endDate", today.plusDays(7).toString(), "roomType", 0, "price", 1));

        assertThat(r.status()).isEqualTo(403);
        assertThat(r.data().isNull()).isTrue();
        assertThat(snapshot.get()).isEqualTo(before).hasSize(1);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"住客A", "前台", "经理"})
    void tc012_nonRestaurantCannotReadLiveOrders(String role) {
        String token = login(account(role));

        Resp r = get("/restaurant/live-order", token);

        assertThat(r.status()).isEqualTo(403);
        assertThat(r.data().isNull()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"住客A", "前台", "经理"})
    void tc013_nonRestaurantCannotChangeMealOrderStatus(String role) {
        long order = fx.mealOrder(base.userA().id(), new BigDecimal("38.00"), 0);
        String token = login(account(role));

        Resp r = put("/restaurant/status?id=" + order + "&status=1", token, null);

        assertThat(r.status()).isEqualTo(403);
        assertThat(r.data().isNull()).isTrue();
        assertThat(jdbc.queryForObject("select order_status from meal_order where id = ?", Integer.class, order)).isZero();
    }

    @Test
    void tc014_frontCannotRegisterStaff() {
        String token = login(base.front());

        Resp r = post("/staff/register", token, Map.of("account", "it_front_forbidden", "password", Fixtures.PASSWORD,
                "role", 1, "status", 1));

        assertThat(r.status()).isEqualTo(403);
        assertThat(r.msg()).isEqualTo(DENIED);
        assertThat(fx.count("staff", "account = ?", "it_front_forbidden")).isZero();
    }

    @Test
    void tc015_userCannotDeleteOrder() {
        LocalDateTime d = LocalDate.now().plusDays(10).atStartOfDay();
        long individual = jdbc.queryForObject("select id from individual where phone = ?", Long.class, base.userA().login());
        long order = fx.roomOrder(base.userA().id(), individual, base.room("R1").id(), d.withHour(14), d.plusDays(1).withHour(12),
                new BigDecimal("199.00"), 0, 0);
        String token = login(base.userA());

        Resp r = delete("/order/" + order, token);

        assertThat(r.status()).isEqualTo(403);
        assertThat(r.msg()).isEqualTo(DENIED);
        assertThat(jdbc.queryForObject("select is_deleted from room_order where id = ?", Integer.class, order)).isZero();
    }

    @ParameterizedTest(name = "GET {0}")
    @ValueSource(strings = {"/rooms/login", "/order/register", "/rooms"})
    void tc017_pathsOutsideWhitelistRequireToken(String path) {
        assertThat(get(path, null).status()).isEqualTo(401);
    }
}
