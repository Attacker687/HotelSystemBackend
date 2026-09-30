package com.winniethepooh.hotelsystembackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.winniethepooh.hotelsystembackend.mapper.OrderMapper;
import com.winniethepooh.hotelsystembackend.service.CustomTaskScheduler;
import com.winniethepooh.hotelsystembackend.support.Fixtures;
import com.winniethepooh.hotelsystembackend.support.IntegrationTestBase;
import com.winniethepooh.hotelsystembackend.support.SqlCounter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.ConfigurableApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;

/** W2 + W3: real HTTP and scheduler entries; fixture aliases and standard request bodies. */
class OrderWorkflowIT extends IntegrationTestBase {
    private final LocalDate d = LocalDate.now().plusDays(10);
    @Autowired private CustomTaskScheduler scheduler;
    @SpyBean private OrderMapper mapper;

    private LocalDateTime in(int day) { return d.plusDays(day).atTime(14, 0); }
    private LocalDateTime out(int day) { return d.plusDays(day).atTime(12, 0); }
    private Resp book(String token, String room, LocalDateTime in, LocalDateTime out) {
        return post("/order", token, Fixtures.roomOrderBody(base.room(room).number(), in, out));
    }
    private long order(String token, String room, LocalDateTime in, LocalDateTime out) {
        Resp r = book(token, room, in, out);
        ok(r);
        assertThat(r.data().isIntegralNumber()).as("%s", r.body()).isTrue();
        return r.data().asLong();
    }
    private long order(String token) { return order(token, "R1", in(0), out(1)); }
    private void paid(String token, long id) { ok(post("/order/pay?id=" + id, token, null)); }
    private long front(String token, boolean paid) {
        Map<String, Object> body = Fixtures.roomOrderBody(base.room("R1").number(), in(0), out(2));
        body.put("paid", paid);
        ok(post("/order", token, body));
        return jdbc.queryForObject("select id from room_order where room_id = ?", Long.class, base.room("R1").id());
    }
    private long meal(String token) {
        Map<String, Object> body = mealBody("X", 1, base.dish("X").price());
        ok(post("/order/meal-order", token, body));
        return jdbc.queryForObject("select max(id) from meal_order", Long.class);
    }
    private Map<String, Object> mealBody(String dish, int quantity, BigDecimal unit) {
        Map<String, Object> body = Fixtures.mealOrderBody();
        body.put("itemList", List.of(Map.of("dishId", base.dish(dish).id(), "quantity", quantity, "unitPrice", unit)));
        body.put("totalAmount", unit.multiply(BigDecimal.valueOf(quantity)));
        return body;
    }
    private Resp comment(String token, String type, long id, String text, int star) {
        return post("/order/user/comment", token, Map.of("type", type, "id", id, "comment", text, "commentStar", star));
    }
    private void noComment(String table, long id) {
        Map<String, Object> row = jdbc.queryForMap("select comment, comment_star from " + table + " where id = ?", id);
        assertThat(row.get("comment")).isNull();
        assertThat(row.get("comment_star")).isNull();
    }
    private Map<String, Object> row(long id) { return jdbc.queryForMap("select * from room_order where id = ?", id); }
    private void state(long id, int status, int pay) {
        Map<String, Object> row = row(id);
        assertThat(((Number) row.get("status")).intValue()).isEqualTo(status);
        assertThat(((Number) row.get("pay_status")).intValue()).isEqualTo(pay);
    }
    private int roomState(String room) {
        return jdbc.queryForObject("select status from room where id = ?", Integer.class, base.room(room).id());
    }
    private void roomState(String room, int state) { jdbc.update("update room set status = ? where id = ?", state, base.room(room).id()); }
    private void ok(Resp r) {
        assertThat(r.status()).as("%s", r.body()).isEqualTo(200);
        assertThat(r.code()).as("%s", r.body()).isZero();
    }
    private void rejected(Resp r) {
        assertThat(r.code()).as("%s", r.body()).isNotZero();
        assertThat(r.msg()).as("%s", r.body()).isNotBlank().isNotEqualTo("操作失败，请联系管理员");
    }
    private void conflict(Resp r) { rejected(r); assertThat(r.msg()).contains("已被预订"); }
    private Resp modify(String token, long id, String room, LocalDateTime in, LocalDateTime out) {
        return put("/order/" + id, token, Map.of("roomId", String.valueOf(base.room(room).id()),
                "checkInTime", Fixtures.iso(in), "checkOutTime", Fixtures.iso(out)));
    }
    private <T> List<T> together(List<Callable<T>> jobs) throws Exception {
        var pool = Executors.newFixedThreadPool(jobs.size());
        CountDownLatch ready = new CountDownLatch(jobs.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> job : jobs) futures.add(pool.submit(() -> { ready.countDown(); go.await(); return job.call(); }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<T> result = new ArrayList<>();
            for (Future<T> future : futures) result.add(future.get(60, TimeUnit.SECONDS));
            return result;
        } finally { go.countDown(); pool.shutdownNow(); }
    }

    @Test void tc020_cannotCommentAnotherUsersCompletedRoomOrder() {
        long id = order(login(base.user("B")));
        jdbc.update("update room_order set status = 1 where id = ?", id);
        rejected(comment(login(base.user("A")), "room", id, "A 写的评价", 5));
        noComment("room_order", id);
    }
    @Test void tc021_cannotCommentAnotherUsersCompletedMealOrder() {
        long id = meal(login(base.user("B")));
        jdbc.update("update meal_order set order_status = 2 where id = ?", id);
        rejected(comment(login(base.user("A")), "meal", id, "A 写的评价", 5));
        noComment("meal_order", id);
    }
    @Test void tc022_cannotCommentOngoingRoomOrder() {
        String token = login(base.user("A")); long id = order(token);
        rejected(comment(token, "room", id, "提前评价", 4)); noComment("room_order", id);
    }
    @Test void tc023_cannotCommentNewMealOrder() {
        String token = login(base.user("A")); long id = meal(token);
        rejected(comment(token, "meal", id, "提前评价", 4)); noComment("meal_order", id);
    }
    @ParameterizedTest @ValueSource(ints = {-1, 0, 6, 999})
    void tc024_invalidStarsAre400AndUnchanged(int star) {
        String token = login(base.user("A")); long id = order(token);
        jdbc.update("update room_order set status = 1 where id = ?", id);
        assertThat(comment(token, "room", id, "评分边界", star).status()).isEqualTo(400);
        noComment("room_order", id);
    }
    @ParameterizedTest @ValueSource(ints = {1, 5})
    void tc025_validStarBoundariesAreWritten(int star) {
        String token = login(base.user("A")); long id = order(token);
        jdbc.update("update room_order set status = 1 where id = ?", id);
        ok(comment(token, "room", id, "评分边界", star));
        assertThat(row(id).get("comment_star")).isEqualTo(star);
    }
    @Test void tc026_500CharacterCommentIsWritten() {
        String token = login(base.user("A")); long id = order(token);
        jdbc.update("update room_order set status = 1 where id = ?", id);
        ok(comment(token, "room", id, "好".repeat(500), 5));
        assertThat(row(id).get("comment")).isEqualTo("好".repeat(500));
    }
    @Test void tc027_501CharacterCommentIs400AndUnchanged() {
        String token = login(base.user("A")); long id = order(token);
        jdbc.update("update room_order set status = 1 where id = ?", id);
        assertThat(comment(token, "room", id, "好".repeat(501), 5).status()).isEqualTo(400);
        noComment("room_order", id);
    }
    @Test void tc028_mealPricesComeFromDatabase() {
        ok(post("/order/meal-order", login(base.user("A")), mealBody("X", 2, new BigDecimal("0.01"))));
        assertThat(jdbc.queryForObject("select total_amount from meal_order", BigDecimal.class)).isEqualByComparingTo("76");
        Map<String, Object> item = jdbc.queryForMap("select * from meal_order_item");
        assertThat(item.get("quantity")).isEqualTo(2);
        assertThat((BigDecimal) item.get("unit_price")).isEqualByComparingTo("38");
        assertThat((BigDecimal) item.get("total_price")).isEqualByComparingTo("76");
    }
    private void badDish(String alias, boolean missing, String... words) {
        Map<String, Object> body = mealBody(alias, 1, base.dish(alias).price());
        if (missing) body.put("itemList", List.of(Map.of("dishId", 999999, "quantity", 1, "unitPrice", 10)));
        Resp r = post("/order/meal-order", login(base.user("A")), body);
        rejected(r); assertThat(r.msg()).contains(words);
        assertThat(fx.count("meal_order")).isZero(); assertThat(fx.count("meal_order_item")).isZero();
    }
    @Test void tc029_missingDishIsRejected() { badDish("X", true, "菜品", "不存在"); }
    @Test void tc030_deletedDishIsRejected() { badDish("Y", false, "菜品", "不存在"); }
    @Test void tc031_disabledDishIsRejected() { badDish("Z", false, "下架"); }

    @ParameterizedTest @ValueSource(strings = {"pay", "cancel"})
    void tc063_getCannotChangeOrder(String action) {
        String token = login(base.user("A")); long id = order(token);
        assertThat(get("/order/" + action + "?id=" + id, token).status()).isEqualTo(405); state(id, 0, 0);
    }
    private void overlap(int scenario, boolean pay) {
        String b = login(base.user("B")); long id = order(b, "R1", in(1), out(3));
        if (pay) paid(b, id);
        LocalDateTime checkin = scenario == 0 ? in(2) : scenario == 1 ? in(0) : d.plusDays(3).atTime(10, 0);
        conflict(book(login(base.user("A")), "R1", checkin, out(4)));
        assertThat(fx.count("room_order")).isEqualTo(1); state(id, 0, pay ? 1 : 0);
    }
    @ParameterizedTest @ValueSource(ints = {0, 1, 2}) void tc064_paidOverlapIsRejected(int scenario) { overlap(scenario, true); }
    @ParameterizedTest @ValueSource(ints = {0, 1, 2}) void tc065_unpaidOverlapIsRejected(int scenario) { overlap(scenario, false); }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void tc066_exactTouchingIntervalsAreAllowed(boolean first) {
        String b = login(base.user("B"));
        long old = order(b, "R1", first ? in(-2) : in(0), first ? out(0) : out(2)); paid(b, old);
        long id = order(login(base.user("A")), "R1", first ? out(0) : in(-2), first ? out(2) : in(0));
        assertThat(fx.count("room_order")).isEqualTo(2); state(id, 0, 0);
    }
    @Test void tc067_frontOverlapCannotChangeRoomOrWriteOrder() {
        String b = login(base.user("B")); LocalDate today = LocalDate.now();
        long id = order(b, "R2", today.atTime(23, 0), today.plusDays(2).atTime(12, 0)); paid(b, id);
        Map<String, Object> body = Fixtures.roomOrderBody(base.room("R2").number(), today.atTime(23, 30), today.plusDays(1).atTime(12, 0));
        body.put("paid", true); conflict(post("/order", login(base.front()), body));
        assertThat(fx.count("room_order")).isEqualTo(1); assertThat(roomState("R2")).isZero();
    }
    @Test void tc068_20SimultaneousBookingsHaveOneWinnerInEachOf10Rounds() throws Exception {
        String token = login(base.user("A"));
        for (int k = 1; k <= 10; k++) {
            LocalDateTime in = in(2 * k), out = out(2 * k + 1);
            List<Callable<Resp>> jobs = new ArrayList<>();
            for (int i = 0; i < 20; i++) jobs.add(() -> book(token, "R3", in, out));
            List<Resp> result = together(jobs);
            assertThat(result.stream().filter(r -> r.code() == 0).count()).as("round %s", k).isEqualTo(1);
            result.stream().filter(r -> r.code() != 0).forEach(this::conflict);
            assertThat(fx.count("room_order", "room_id = ? and checkin_time = ? and status = 0 and is_deleted = 0", base.room("R3").id(), in)).isEqualTo(1);
        }
    }
    @Test void tc069_timeoutCancelledOrderCannotBePaid() {
        String token = login(base.user("A")); long id = order(token); fx.createdMinutesAgo("room_order", id, 16);
        scheduler.flushExpiredRoomOrders(); state(id, 2, 0);
        rejected(post("/order/pay?id=" + id, token, null)); state(id, 2, 0);
    }
    @Test void tc070_cancelledOrderCannotBePaid() {
        String token = login(base.user("A")); long id = order(token); ok(post("/order/cancel?id=" + id, token, null));
        rejected(post("/order/pay?id=" + id, token, null)); state(id, 2, 0);
    }
    @Test void tc071_paidOrderCannotBePaidAgain() {
        String token = login(base.user("A")); long id = order(token); paid(token, id);
        rejected(post("/order/pay?id=" + id, token, null)); state(id, 0, 1);
    }
    @Test void tc072_paymentDeadlineIsCheckedWithoutScheduler() {
        String token = login(base.user("A")); long id = order(token); fx.createdMinutesAgo("room_order", id, 16);
        rejected(post("/order/pay?id=" + id, token, null)); state(id, 0, 0);
    }
    @Test void tc073_ownershipIsCheckedBeforePaymentStatus() {
        String b = login(base.user("B")); long id = order(b); paid(b, id);
        Resp r = post("/order/pay?id=" + id, login(base.user("A")), null); rejected(r);
        assertThat(r.msg()).isNotEqualTo("支付失败，房间已被预订"); state(id, 0, 1);
    }
    @Test void tc074_missingOrderHasSpecificPaymentFailure() {
        Resp r = post("/order/pay?id=999999999", login(base.user("A")), null); rejected(r);
        assertThat(r.msg()).isNotEqualTo("支付失败，房间已被预订");
    }
    @Test void tc075_concurrentPaymentAndCancellationNeverLeaveCancelledPaidIn50Rounds() throws Exception {
        String token = login(base.user("A"));
        for (int k = 1; k <= 50; k++) {
            long id = order(token, "R1", in(2 * k), out(2 * k + 1));
            List<Resp> result = together(List.of(() -> post("/order/pay?id=" + id, token, null), () -> post("/order/cancel?id=" + id, token, null)));
            result.forEach(r -> { if (r.code() != 0) rejected(r); });
            Map<String, Object> row = row(id); int status = ((Number) row.get("status")).intValue(), pay = ((Number) row.get("pay_status")).intValue();
            assertThat((status == 0 && pay == 1) || (status == 2 && (pay == 0 || pay == 2))).as("round %s: %s", k, row).isTrue();
        }
    }
    @Test void tc076_unpaidFutureOrderCanBeCancelled() {
        String token = login(base.user("A")); long id = order(token);
        ok(post("/order/cancel?id=" + id, token, null)); state(id, 2, 0);
    }
    @ParameterizedTest @ValueSource(ints = {1, 2})
    void tc077_terminalRoomOrdersCannotBeCancelled(int terminal) {
        String token = login(base.user("A")); long id = order(token);
        if (terminal == 1) jdbc.update("update room_order set status = 1 where id = ?", id);
        else ok(post("/order/cancel?id=" + id, token, null));
        Map<String, Object> before = row(id); rejected(post("/order/cancel?id=" + id, token, null)); assertThat(row(id)).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(ints = {1, 0})
    void tc078_startedOrderCannotBeCancelledRegardlessOfRoomStatus(int roomStatus) {
        String token = login(base.user("A")); long id = order(token); paid(token, id);
        jdbc.update("update room_order set checkin_time = ? where id = ?", LocalDateTime.now().minusMinutes(1), id); roomState("R1", roomStatus);
        rejected(post("/order/cancel?id=" + id, token, null)); state(id, 0, 1); assertThat(roomState("R1")).isEqualTo(roomStatus);
    }
    @Test void tc079_cannotCancelAnotherUsersRoomOrder() {
        long id = order(login(base.user("B"))); rejected(post("/order/cancel?id=" + id, login(base.user("A")), null)); state(id, 0, 0);
    }
    @Test void tc080_orderWriteFailureRollsBackNewGuest() {
        int people = fx.count("individual"); doThrow(new RuntimeException("injected order write failure")).when(mapper).insertRoomOrderV2(any());
        Map<String, Object> body = Fixtures.roomOrderBody(base.room("R1").number(), in(0), out(1)); body.putAll(Fixtures.guest("P9"));
        assertThat(post("/order", login(base.user("A")), body).code()).isNotZero();
        assertThat(fx.count("individual")).isEqualTo(people); assertThat(fx.count("individual", "phone = ?", Fixtures.guest("P9").get("phone"))).isZero();
        assertThat(fx.count("room_order")).isZero(); assertThat(fx.count("room_order_night")).isZero();
    }
    @Test void tc081_missingRoomIsRejectedBeforeAnyWrite() {
        int people = fx.count("individual"); Map<String, Object> body = Fixtures.roomOrderBody("9999", in(0), out(1)); body.putAll(Fixtures.guest("P9"));
        Resp r = post("/order", login(base.user("A")), body); rejected(r); assertThat(r.msg()).contains("房间不存在");
        assertThat(fx.count("individual")).isEqualTo(people); assertThat(fx.count("room_order")).isZero();
    }
    @Test void tc082_yesterdaysCheckinIs400() {
        assertThat(book(login(base.user("A")), "R1", LocalDate.now().minusDays(1).atTime(14, 0), LocalDate.now().plusDays(1).atTime(12, 0)).status()).isEqualTo(400);
        assertThat(fx.count("room_order")).isZero();
    }
    @Test void tc083_todayCanBeBookedAndAmountAndNightArePersisted() {
        String token = login(base.user("A")); LocalDate today = LocalDate.now();
        fx.price(base.room("R1").type(), today, new BigDecimal("250"));
        long id = order(token, "R1", today.atTime(14, 0), today.plusDays(1).atTime(12, 0)); state(id, 0, 0);
        assertThat(row(id).get("room_id")).isEqualTo(base.room("R1").id());
        assertThat((BigDecimal) row(id).get("total_amount")).isEqualByComparingTo("250");
        assertThat(fx.count("room_order_night", "room_order_id = ?", id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select price from room_order_night where room_order_id = ? and night = ?", BigDecimal.class, id, today)).isEqualByComparingTo("250");
        paid(token, id); Resp stats = get("/business/revenue/stats?date=" + today, login(base.manager())); ok(stats);
        assertThat(stats.data().path("today").decimalValue()).isEqualByComparingTo("250");
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void tc084_nonPositiveNightsAre400(boolean sameDay) {
        Resp r = book(login(base.user("A")), "R1", sameDay ? in(0) : in(2), sameDay ? d.atTime(20, 0) : out(0));
        assertThat(r.status()).as("%s", r.body()).isEqualTo(400); assertThat(r.msg()).contains("离店"); assertThat(fx.count("room_order")).isZero();
    }
    @Test void tc085_30NightsAreAllowed() {
        long id = order(login(base.user("A")), "R1", in(0), out(30));
        assertThat((BigDecimal) row(id).get("total_amount")).isEqualByComparingTo("5970"); assertThat(fx.count("room_order_night", "room_order_id = ?", id)).isEqualTo(30);
    }
    @Test void tc086_31NightsAre400() {
        assertThat(book(login(base.user("A")), "R1", in(0), out(31)).status()).isEqualTo(400); assertThat(fx.count("room_order")).isZero();
    }
    @Test void tc087_pricing30NightsSelectsCalendarOnce() {
        String token = login(base.user("A")); sql.reset(); order(token, "R1", in(0), out(30));
        assertThat(sql.statements().stream().filter(s -> s.toLowerCase().startsWith("select") && s.contains("price_calendar")).count()).as("%s", sql.statements()).isEqualTo(1);
    }
    @Test void tc088_paidFrontOrderHasAmountAndTwoNights() {
        long id = front(login(base.front()), true); state(id, 0, 1); assertThat(row(id).get("user_id")).isNull();
        assertThat((BigDecimal) row(id).get("total_amount")).isEqualByComparingTo("398"); assertThat(fx.count("room_order_night", "room_order_id = ?", id)).isEqualTo(2);
    }
    @Test void tc088_unpaidFrontOrderIsNotExpiredAfter15Minutes() {
        long id = front(login(base.front()), false); state(id, 0, 0); fx.createdMinutesAgo("room_order", id, 16); scheduler.flushExpiredRoomOrders(); state(id, 0, 0);
    }

    private long occupiedOrder() {
        String b = login(base.user("B")); LocalDate today = LocalDate.now();
        long id = order(b, "R1", today.plusDays(1).atTime(14, 0), today.plusDays(3).atTime(12, 0)); paid(b, id);
        jdbc.update("update room_order set checkin_time = ? where id = ?", LocalDateTime.now().minusHours(1), id); roomState("R1", 1); return id;
    }
    @Test void tc089_manualCheckoutEndsCurrentOrder() {
        long id = occupiedOrder(); ok(put("/rooms", login(base.front()), Map.of("id", base.room("R1").id(), "status", 0)));
        assertThat(roomState("R1")).isZero(); state(id, 1, 1);
    }
    @Test void tc090_occupiedRoomWithoutOrderCanBecomeAvailable() {
        roomState("R2", 1); List<Map<String, Object>> before = jdbc.queryForList("select * from room_order order by id");
        ok(put("/rooms", login(base.front()), Map.of("id", base.room("R2").id(), "status", 0)));
        assertThat(roomState("R2")).isZero(); assertThat(jdbc.queryForList("select * from room_order order by id")).isEqualTo(before);
    }
    @Test void tc091_missingRoomStatusChangeHasSpecificFailure() {
        Resp r = put("/rooms", login(base.front()), Map.of("id", 999999, "status", 2)); rejected(r); assertThat(r.msg()).contains("房间不存在");
    }
    @ParameterizedTest @ValueSource(ints = {3, 2})
    void tc092_checkinTaskPreservesManualRoomStatus(int manual) {
        long id = occupiedOrder(); roomState("R1", 0); scheduler.flushRoomStatus(); assertThat(roomState("R1")).isEqualTo(1);
        roomState("R1", manual); scheduler.flushRoomStatus(); assertThat(roomState("R1")).isEqualTo(manual); state(id, 0, 1);
    }
    private void expired(long id, String room) {
        jdbc.update("update room_order set checkin_time = ?, checkout_time = ? where id = ?", LocalDateTime.now().minusDays(2), LocalDateTime.now().minusMinutes(1), id); roomState(room, 1);
    }
    @Test void tc093_releaseFailureRollsBackRoomAndOrder() {
        long id = occupiedOrder(); expired(id, "R1");
        doThrow(new RuntimeException("injected order status failure")).when(mapper).modifyRoomOrderStatus(any(), anyInt());
        assertThatThrownBy(() -> scheduler.releaseExpiredRooms()).isInstanceOf(RuntimeException.class);
        assertThat(roomState("R1")).isEqualTo(1); state(id, 0, 1);
    }
    @Test void tc094_twoInstancesReleaseEachOrderOnlyOnce() throws Exception {
        String token = login(base.user("B")); List<Long> orders = new ArrayList<>();
        for (String room : List.of("R1", "R2", "R3")) { long id = order(token, room, in(0), out(2)); paid(token, id); expired(id, room); orders.add(id); }
        try (ConfigurableApplicationContext second = new SpringApplicationBuilder(HotelSystemBackendApplication.class, SqlCounter.class)
                .profiles("test").run("--server.port=0", "--spring.datasource.url=" + MYSQL.getJdbcUrl(),
                        "--spring.datasource.username=" + MYSQL.getUsername(), "--spring.datasource.password=" + MYSQL.getPassword(),
                        "--spring.data.redis.host=" + REDIS.getHost(), "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                        "--hotel.jwt.secret=" + Fixtures.JWT_SECRET, "--hotel.scheduler.enabled=false")) {
            CustomTaskScheduler other = second.getBean(CustomTaskScheduler.class); SqlCounter otherSql = second.getBean(SqlCounter.class);
            sql.reset(); otherSql.reset();
            together(List.of(() -> { scheduler.releaseExpiredRooms(); return true; }, () -> { other.releaseExpiredRooms(); return true; }));
            List<String> updates = new ArrayList<>(sql.statements()); updates.addAll(otherSql.statements());
            assertThat(updates.stream().filter(s -> s.startsWith("update room_order ") && s.contains("set status")).count()).as("%s", updates).isEqualTo(3);
            assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema = database() and table_name = 'scheduler_task_lock'", Integer.class)).isEqualTo(1);
            Map<String, Object> lock = jdbc.queryForMap("select * from scheduler_task_lock where task_name = 'releaseExpiredRooms'");
            assertThat(lock.get("last_run")).isNotNull(); assertThat(lock.get("owner")).isNotNull();
            orders.forEach(id -> state(id, 1, 1)); for (String room : List.of("R1", "R2", "R3")) assertThat(roomState(room)).isEqualTo(2);
        }
    }
    @Test void tc095_moveToOverlappingRoomIsRejected() {
        String f = login(base.front()); long id = front(f, true); String b = login(base.user("B")); long old = order(b, "R2", in(1), out(3)); paid(b, old);
        Map<String, Object> before = row(id); conflict(modify(f, id, "R2", in(0), out(2))); assertThat(row(id)).isEqualTo(before);
    }
    @Test void tc096_invalidModificationDatesAre400() {
        String f = login(base.front()); long id = front(f, true); Map<String, Object> before = row(id);
        assertThat(modify(f, id, "R1", in(0), out(-1)).status()).isEqualTo(400); assertThat(row(id)).isEqualTo(before);
    }
    @Test void tc097_extensionRepricesAndRewritesNights() {
        String f = login(base.front()); long id = front(f, true);
        assertThat((BigDecimal) row(id).get("total_amount")).isEqualByComparingTo("398"); ok(modify(f, id, "R1", in(0), out(3)));
        assertThat((BigDecimal) row(id).get("total_amount")).isEqualByComparingTo("597");
        assertThat(fx.count("room_order_night", "room_order_id = ?", id)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select sum(price) from room_order_night where room_order_id = ?", BigDecimal.class, id)).isEqualByComparingTo("597");
        Resp stats = get("/business/revenue/stats?date=" + d.plusDays(2), login(base.manager())); ok(stats);
        assertThat(stats.data().path("today").decimalValue()).isEqualByComparingTo("199");
    }
    @Test void tc098_moveToSuiteRepricesNights() {
        String f = login(base.front()); long id = front(f, true); assertThat((BigDecimal) row(id).get("total_amount")).isEqualByComparingTo("398");
        ok(modify(f, id, "S1", in(0), out(2))); assertThat(row(id).get("room_id")).isEqualTo(base.room("S1").id());
        assertThat((BigDecimal) row(id).get("total_amount")).isEqualByComparingTo("998");
        assertThat(jdbc.queryForObject("select sum(price) from room_order_night where room_order_id = ?", BigDecimal.class, id)).isEqualByComparingTo("998");
    }
    @ParameterizedTest @ValueSource(ints = {2, 1})
    void tc099_terminalOrderCannotBeModified(int terminal) {
        String f = login(base.front()); long id = front(f, true); jdbc.update("update room_order set status = ? where id = ?", terminal, id);
        Map<String, Object> before = row(id); rejected(modify(f, id, "R2", in(0), out(3))); assertThat(row(id)).isEqualTo(before);
    }
    @ParameterizedTest @CsvSource({"A101,list", "0101,list", "A101,detail", "0101,detail", "A101,wall", "0101,wall"})
    void tc100_roomNumbersRemainStrings(String number, String endpoint) {
        long a = fx.room(0, "A101", base.room("R1").type(), 1, 0), b = fx.room(0, "0101", base.room("R1").type(), 1, 0);
        long id = number.equals("A101") ? a : b; String path = endpoint.equals("list") ? "/rooms?page=1&pageSize=100" : endpoint.equals("detail") ? "/rooms/" + id : "/rooms/status-wall";
        Resp r = get(path, login(base.front())); ok(r); JsonNode room = r.data();
        if (!endpoint.equals("detail")) {
            JsonNode list = endpoint.equals("list") ? r.data().path("list") : r.data(); room = null;
            for (JsonNode item : list) if (item.path("id").asLong() == id) room = item;
        }
        assertThat(room).isNotNull(); assertThat(room.path("roomNumber").isTextual()).isTrue(); assertThat(room.path("roomNumber").asText()).isEqualTo(number);
    }
    @Test void tc104_restaurantCanCancelNewMealOrder() {
        long id = meal(login(base.user("A"))); ok(put("/restaurant/status?id=" + id + "&status=3", login(base.restaurant()), null));
        assertThat(jdbc.queryForObject("select order_status from meal_order where id = ?", Integer.class, id)).isEqualTo(3);
    }
    @Test void tc105_userCannotCancelProgressedMealOrder() {
        String token = login(base.user("A")); long id = meal(token); ok(put("/restaurant/status?id=" + id + "&status=1", login(base.restaurant()), null));
        rejected(put("/order/meal-order/" + id + "/cancel", token, null)); assertThat(jdbc.queryForObject("select order_status from meal_order where id = ?", Integer.class, id)).isEqualTo(1);
    }
    @Test void tc106_userCannotCancelOthersNewMealOrder() {
        long id = meal(login(base.user("B"))); rejected(put("/order/meal-order/" + id + "/cancel", login(base.user("A")), null));
        assertThat(jdbc.queryForObject("select order_status from meal_order where id = ?", Integer.class, id)).isZero();
    }
    private int listRows(Resp r, String endpoint) { ok(r); return endpoint.equals("wall") ? r.data().size() : r.data().path("list").size(); }
    @ParameterizedTest @ValueSource(strings = {"orders", "wall", "rooms"})
    void tc117_listSqlCountDoesNotGrowWithRows(String endpoint) {
        String a = login(base.user("A")), manager = login(base.manager()), front = login(base.front());
        String path = endpoint.equals("orders") ? "/order/query?page=1&limit=50" : endpoint.equals("wall") ? "/rooms/status-wall" : "/rooms?page=1&pageSize=100";
        String token = endpoint.equals("orders") ? manager : front;
        Resp baseline = get("/rooms?page=1&pageSize=100", front); ok(baseline); int k = baseline.data().path("total").asInt();
        order(a); sql.reset(); Resp small = get(path, token); int nSmall = sql.count(); assertThat(listRows(small, endpoint)).isEqualTo(endpoint.equals("orders") ? 1 : k);
        for (int j = 1; j <= 49; j++) {
            ok(post("/rooms", manager, Map.of("roomNumber", String.valueOf(9000 + j), "roomType", base.room("R1").type(), "floor", 1, "status", 0, "capacity", 2)));
            order(a, "R1", in(2 * j), out(2 * j + 1));
        }
        sql.reset(); Resp large = get(path, token); int nLarge = sql.count(); assertThat(listRows(large, endpoint)).isEqualTo(endpoint.equals("orders") ? 50 : k + 49);
        if (endpoint.equals("rooms")) assertThat(large.data().path("total").asInt()).isEqualTo(k + 49);
        assertThat(nLarge).as("small=%s, large SQL=%s", nSmall, sql.statements()).isEqualTo(nSmall);
    }
    @Test void tc122_pageSize100Works() {
        Resp r = get("/rooms?page=1&pageSize=100", login(base.front())); ok(r); assertThat(r.data().path("list").size()).isLessThanOrEqualTo(100);
    }
    @ParameterizedTest @ValueSource(strings = {"/rooms?page=1&pageSize=0", "/rooms?page=1&pageSize=101", "/order/query?page=0&limit=10", "/order/query?page=1&limit=101", "/staff/list?page=1&pageSize=101"})
    void tc123_paginationOutOfBoundsIs400(String path) {
        Resp r = get(path, login(path.startsWith("/rooms") ? base.front() : base.manager()));
        assertThat(r.status()).as("%s", r.body()).isEqualTo(400); if (path.contains("page=0")) assertThat(r.msg()).contains("page");
    }

    @Test void tc076_paidFutureCancellationRefundsAndExcludesRevenue() {
        String token = login(base.user("A")); long id = order(token); paid(token, id);
        ok(post("/order/cancel?id=" + id, token, null)); state(id, 2, 2);
        Resp stats = get("/business/revenue/stats?date=" + d, login(base.manager())); ok(stats);
        assertThat(stats.data().path("today").decimalValue()).isEqualByComparingTo("0");
    }
    @Test void tc080_nightInsertFailureRollsBackGuestOrderAndNights() {
        int people = fx.count("individual"); String token = login(base.user("A"));
        jdbc.execute("create trigger fail_night_insert before insert on room_order_night for each row signal sqlstate '45000' set message_text = 'injected night failure'");
        try {
            Map<String, Object> body = Fixtures.roomOrderBody(base.room("R1").number(), in(0), out(2)); body.putAll(Fixtures.guest("P9"));
            assertThat(post("/order", token, body).code()).isNotZero();
            assertThat(fx.count("individual")).isEqualTo(people); assertThat(fx.count("room_order")).isZero(); assertThat(fx.count("room_order_night")).isZero();
        } finally { jdbc.execute("drop trigger fail_night_insert"); }
    }
    @Test void tc097_nightRewriteFailureRollsBackModificationAndOriginalNights() {
        String f = login(base.front()); long id = front(f, true); Map<String, Object> before = row(id);
        List<Map<String, Object>> nights = jdbc.queryForList("select * from room_order_night where room_order_id = ? order by night", id);
        jdbc.execute("create trigger fail_night_rewrite before insert on room_order_night for each row signal sqlstate '45000' set message_text = 'injected night failure'");
        try {
            assertThat(modify(f, id, "S1", in(0), out(3)).code()).isNotZero(); assertThat(row(id)).isEqualTo(before);
            assertThat(jdbc.queryForList("select * from room_order_night where room_order_id = ? order by night", id)).isEqualTo(nights);
        } finally { jdbc.execute("drop trigger fail_night_rewrite"); }
    }
    @Test void tc088_unpaidFrontOrderIsEnabledAndReleasedByTime() {
        long id = front(login(base.front()), false); state(id, 0, 0);
        jdbc.update("update room_order set checkin_time = ? where id = ?", LocalDateTime.now().minusMinutes(1), id);
        scheduler.flushRoomStatus(); assertThat(roomState("R1")).isEqualTo(1); state(id, 0, 0);
        jdbc.update("update room_order set checkout_time = ? where id = ?", LocalDateTime.now().minusSeconds(1), id);
        scheduler.releaseExpiredRooms(); assertThat(roomState("R1")).isEqualTo(2); state(id, 1, 0);
    }
    @Test void tc092_expiredOrderCannotBeEnabled() {
        long id = occupiedOrder(); expired(id, "R1"); roomState("R1", 0);
        scheduler.flushRoomStatus(); assertThat(roomState("R1")).isZero(); state(id, 0, 1);
    }
    @Test void tc093_successfulReleaseNeedsCleaningBeforeAvailable() {
        long id = occupiedOrder(); expired(id, "R1"); scheduler.releaseExpiredRooms(); state(id, 1, 1);
        assertThat(roomState("R1")).isEqualTo(2); ok(put("/rooms", login(base.front()), Map.of("id", base.room("R1").id(), "status", 0)));
        assertThat(roomState("R1")).isZero();
    }
    @ParameterizedTest @ValueSource(ints = {2, 3})
    void tc090_emptyRoomCanBeMarkedCleaningOrRepair(int status) {
        ok(put("/rooms", login(base.front()), Map.of("id", base.room("R2").id(), "status", status)));
        assertThat(roomState("R2")).isEqualTo(status); assertThat(fx.count("room_order")).isZero();
    }
    @ParameterizedTest @ValueSource(strings = {"status", "create", "info"})
    void tc091_invalidRoomStatusRejectedAtEveryWriteEntry(String entry) {
        String manager = login(base.manager()); Map<String, Object> body = new java.util.HashMap<>(Map.of("id", base.room("R2").id(),
                "roomNumber", entry.equals("create") ? "test-invalid" : base.room("R2").number(), "roomType", base.room("R2").type(), "floor", 1, "capacity", 2, "status", 9));
        Resp r = entry.equals("create") ? post("/rooms", manager, body) : put(entry.equals("status") ? "/rooms" : "/rooms/" + base.room("R2").id(), manager, body);
        assertThat(r.status()).as("%s", r.body()).isEqualTo(400); assertThat(roomState("R2")).isZero(); assertThat(fx.count("room")).isEqualTo(10);
    }
    @Test void tc105_userCanCancelOwnNewMealOrderOnlyOnce() {
        String token = login(base.user("A")); long id = meal(token); ok(put("/order/meal-order/" + id + "/cancel", token, null));
        assertThat(jdbc.queryForObject("select order_status from meal_order where id = ?", Integer.class, id)).isEqualTo(3);
        rejected(put("/order/meal-order/" + id + "/cancel", token, null));
    }
}
