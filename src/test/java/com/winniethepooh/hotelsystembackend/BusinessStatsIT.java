package com.winniethepooh.hotelsystembackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.winniethepooh.hotelsystembackend.support.Fixtures;
import com.winniethepooh.hotelsystembackend.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W4 统计口径：D1–D8、P3、P4。订单直接写库构造（金额与按晚明细由 W2 的下单流程负责写入，这里不依赖下单接口）。
 * 房间、入住人按 strategy.data 别名取：R1～R3、R5 单人间，R4 双人间，S1、D1（id=1）套房；入住人 P0～P4。
 */
class BusinessStatsIT extends IntegrationTestBase {

    private final LocalDate d = LocalDate.now().plusDays(10);
    private long guest;
    private String manager;

    @BeforeEach
    void setUp() {
        guest = guest("P0");
        manager = login(base.manager());
    }

    private long room(String alias) {
        return base.room(alias).id();
    }

    /** 直接写一条入住人（strategy.data 的 P0～P4，不预置）。 */
    private long guest(String alias) {
        Map<String, Object> g = Fixtures.guest(alias);
        return fx.individual((String) g.get("name"), (String) g.get("phone"), (String) g.get("idCard"));
    }

    /** 已支付、进行中的客房订单：firstNight 14:00 入住，住 prices.length 晚后 12:00 离店；每晚一行 room_order_night。 */
    private long paidOrder(long roomId, long individualId, LocalDate firstNight, String... prices) {
        return order(roomId, individualId, firstNight, 1, 0, prices);
    }

    private long order(long roomId, long individualId, LocalDate firstNight, int payStatus, int status, String... prices) {
        BigDecimal total = BigDecimal.ZERO;
        for (String p : prices) total = total.add(new BigDecimal(p));
        long id = fx.roomOrder(base.userA().id(), individualId, roomId, firstNight.atTime(14, 0),
                firstNight.plusDays(prices.length).atTime(12, 0), total, payStatus, status);
        for (int i = 0; i < prices.length; i++) {
            fx.insert("room_order_night", Map.of("room_order_id", id, "night", firstNight.plusDays(i),
                    "price", new BigDecimal(prices[i])));
        }
        return id;
    }

    private static BigDecimal num(JsonNode n) {
        assertThat(n.isNumber()).as("应为数值：%s", n).isTrue();
        return n.decimalValue();
    }

    private static Map<String, JsonNode> byField(JsonNode array, String key) {
        Map<String, JsonNode> m = new HashMap<>();
        array.forEach(e -> m.put(e.path(key).asText(), e));
        return m;
    }

    private Resp ok(Resp r) {
        assertThat(r.status()).as("%s", r.body()).isEqualTo(200);
        assertThat(r.code()).as("%s", r.body()).isZero();
        return r;
    }

    // ---------- D1 按房型营收 ----------

    @Test
    void tc107_roomTypeRevenueSumsByRoomsRoomType() {
        paidOrder(room("R1"), guest, d, "199");
        paidOrder(room("R2"), guest, d, "199");
        paidOrder(room("R4"), guest, d, "299");
        paidOrder(room("S1"), guest, d, "499");

        Map<String, JsonNode> v = byField(ok(get("/business/revenue/room-type?startDate=" + d + "&endDate=" + d, manager)).data(), "name");

        assertThat(num(v.get("单人间").path("value"))).isEqualByComparingTo("398.00");
        assertThat(num(v.get("双人间").path("value"))).isEqualByComparingTo("299.00");
        assertThat(num(v.get("套房").path("value"))).isEqualByComparingTo("499.00");
    }

    @Test
    void tc108_suiteWithRoomIdOneCountsOnlyAsSuite() {
        assertThat(room("D1")).isEqualTo(1);
        paidOrder(room("D1"), guest, d, "499");

        Map<String, JsonNode> v = byField(ok(get("/business/revenue/room-type?startDate=" + d + "&endDate=" + d, manager)).data(), "name");

        assertThat(num(v.get("套房").path("value"))).isEqualByComparingTo("499.00");
        assertThat(num(v.get("单人间").path("value"))).isEqualByComparingTo("0");
        assertThat(num(v.get("双人间").path("value"))).isEqualByComparingTo("0");
    }

    // ---------- D2 按晚拆分 / D3 ADR ----------

    @Test
    void tc109_crossMonthOrderRevenueSplitsByNight() {
        LocalDate today = LocalDate.now();
        LocalDate jan30 = LocalDate.of(today.getYear(), 1, 30);
        if (!jan30.isAfter(today)) jan30 = jan30.plusYears(1);
        paidOrder(room("R1"), guest, jan30, "199", "199", "199");

        Resp jan = ok(get("/business/revenue/stats?date=" + jan30.plusDays(1), manager));
        Resp feb = ok(get("/business/revenue/stats?date=" + jan30.plusDays(2), manager));

        assertThat(num(jan.data().path("month"))).isEqualByComparingTo("398.00");
        assertThat(num(feb.data().path("month"))).isEqualByComparingTo("199.00");
    }

    @Test
    void tc110_avgPriceIsRevenuePerRoomNight() {
        ok(post("/business/calendar", manager, Map.of("startDate", d.toString(), "endDate", d.toString(), "roomType", 0, "price", 100)));
        ok(post("/business/calendar", manager, Map.of("startDate", d.toString(), "endDate", d.toString(), "roomType", 1, "price", 200)));
        paidOrder(room("R1"), guest, d, "100");
        paidOrder(room("R4"), guest, d, "200");

        Resp r = ok(get("/business/revenue/stats?date=" + d, manager));

        assertThat(num(r.data().path("avgPrice"))).isEqualByComparingTo("150.00");
    }

    @Test
    void tc111_threeNightOrderAvgPriceIsNightlyPrice() {
        ok(post("/business/calendar", manager, Map.of("startDate", d.toString(), "endDate", d.plusDays(2).toString(), "roomType", 0, "price", 200)));
        paidOrder(room("R1"), guest, d, "200", "200", "200");

        Resp r = ok(get("/business/revenue/stats?date=" + d, manager));

        assertThat(num(r.data().path("avgPrice"))).isEqualByComparingTo("200.00");
    }

    // ---------- D4 已取消订单 ----------

    @Test
    void d4_paidThenCancelledOrderExcludedFromRevenueAndOccupancy() {
        order(room("R1"), guest, d, 1, 2, "199");

        Resp stats = ok(get("/business/revenue/stats?date=" + d, manager));
        Resp detail = ok(get("/business/detail?startDate=" + d + "&endDate=" + d, manager));
        Map<String, JsonNode> type = byField(ok(get("/business/revenue/room-type?startDate=" + d + "&endDate=" + d, manager)).data(), "name");

        assertThat(num(stats.data().path("today"))).isEqualByComparingTo("0");
        assertThat(num(stats.data().path("month"))).isEqualByComparingTo("0");
        assertThat(stats.data().path("occupancyRate").asDouble()).isZero();
        assertThat(detail.data().path("list").get(0).path("occupiedCount").asInt()).isZero();
        assertThat(num(type.get("单人间").path("value"))).isEqualByComparingTo("0");
    }

    // ---------- D5 离店当天不占用 ----------

    @Test
    void tc112_detailDoesNotCountCheckoutDayAsOccupied() {
        paidOrder(room("R1"), guest, d, "199", "199");

        Map<String, JsonNode> byDate = byField(ok(get("/business/detail?startDate=" + d + "&endDate=" + d.plusDays(2), manager))
                .data().path("list"), "date");

        assertThat(byDate.get(d.toString()).path("occupiedCount").asInt()).isEqualTo(1);
        assertThat(byDate.get(d.plusDays(1).toString()).path("occupiedCount").asInt()).isEqualTo(1);
        assertThat(byDate.get(d.plusDays(2).toString()).path("occupiedCount").asInt()).isZero();
    }

    @Test
    void tc113_heatmapDoesNotCountCheckoutDayAsOccupied() {
        paidOrder(room("R1"), guest, d, "199", "199");

        JsonNode data = ok(get("/business/occupancy/heatmap?startDate=" + d + "&endDate=" + d.plusDays(2) + "&floor=1", manager))
                .data();

        JsonNode cell = StreamSupport.stream(data.path("data").spliterator(), false)
                .filter(c -> c.get(0).asInt() == 2 && c.get(1).asInt() == 0).findFirst().orElseThrow();
        assertThat(cell.get(2).asDouble()).isZero();
        JsonNode first = StreamSupport.stream(data.path("data").spliterator(), false)
                .filter(c -> c.get(0).asInt() == 0 && c.get(1).asInt() == 0).findFirst().orElseThrow();
        int floor1Rooms = fx.count("room", "floor = 1 and is_deleted = 0");
        assertThat(first.get(2).asDouble()).as("D 当天 1 楼占 1 间").isCloseTo(100.0 / floor1Rooms, org.assertj.core.data.Offset.offset(1e-6));
    }

    // ---------- D7 复住率 ----------

    @Test
    void tc114_repeatCustomerRateIsShareOfTodaysGuestsWithEarlierStay() {
        LocalDate today = LocalDate.now();
        long p1 = guest("P1");
        long p2 = guest("P2");
        long p3 = guest("P3");
        long p4 = guest("P4");
        long history = fx.roomOrder(base.userA().id(), p1, room("R1"), today.minusDays(30).atTime(14, 0),
                today.minusDays(29).atTime(12, 0), new BigDecimal("199"), 1, 1);
        jdbc.update("update room_order set created_at = NOW() - INTERVAL 30 DAY where id = ?", history);
        paidOrder(room("R1"), p1, today, "199");
        paidOrder(room("R2"), p2, today, "199");
        paidOrder(room("R3"), p3, today, "199");
        paidOrder(room("R5"), p4, today, "199");

        JsonNode row = ok(get("/business/detail?startDate=" + today + "&endDate=" + today, manager)).data().path("list").get(0);

        assertThat(row.path("repeatCustomerRate").asDouble()).isEqualTo(25.0);
    }

    // ---------- D6 菜品 Top10 ----------

    @Test
    void d6_top10IncludesEndDateAndExcludesCancelledMealOrders() {
        long ok = fx.mealOrder(base.userA().id(), new BigDecimal("76.00"), 2);
        fx.mealOrderItem(ok, base.dish("X").id(), 2, new BigDecimal("38.00"));
        long cancelled = fx.mealOrder(base.userA().id(), new BigDecimal("190.00"), 3);
        fx.mealOrderItem(cancelled, base.dish("X").id(), 5, new BigDecimal("38.00"));
        LocalDate today = LocalDate.now();

        JsonNode list = ok(get("/business/dish/top10?startDate=" + today + "&endDate=" + today, manager)).data();

        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).path("name").asText()).isEqualTo(base.dish("X").name());
        assertThat(list.get(0).path("value").asInt()).isEqualTo(2);
    }

    // ---------- D8 餐厅实时计数 ----------

    @Test
    void tc115_liveOrderCountExcludesDeletedMealOrders() {
        long kept = fx.mealOrder(base.userA().id(), new BigDecimal("38.00"), 0);
        fx.mealOrderItem(kept, base.dish("X").id(), 1, new BigDecimal("38.00"));
        long deleted = fx.mealOrder(base.userA().id(), new BigDecimal("38.00"), 0);
        fx.mealOrderItem(deleted, base.dish("X").id(), 1, new BigDecimal("38.00"));
        jdbc.update("update meal_order set is_deleted = 1 where id = ?", deleted);

        JsonNode data = ok(get("/restaurant/live-order", login(base.restaurant()))).data();

        long newInList = StreamSupport.stream(data.path("mealOrderList").spliterator(), false)
                .filter(o -> o.path("orderStatus").asInt() == 0).count();
        assertThat(data.path("newOrderCount").asInt()).isEqualTo(1);
        assertThat(newInList).isEqualTo(1);
    }

    // ---------- P3 日期跨度上限 ----------

    private Resp spanRequest(String endpoint, LocalDate start, LocalDate end) {
        String q = "startDate=" + start + "&endDate=" + end;
        return switch (endpoint) {
            case "trend" -> get("/business/revenue/trend?" + q, manager);
            case "detail" -> get("/business/detail?" + q, manager);
            case "heatmap" -> get("/business/occupancy/heatmap?" + q, manager);
            case "getCalendar" -> get("/business/calendar?roomType=0&" + q, manager);
            case "postCalendar" -> post("/business/calendar", manager,
                    Map.of("startDate", start.toString(), "endDate", end.toString(), "roomType", 0, "price", 300));
            default -> throw new IllegalArgumentException(endpoint);
        };
    }

    @ParameterizedTest
    @ValueSource(strings = {"trend", "detail", "heatmap", "getCalendar", "postCalendar"})
    void tc118_span367DaysRejectedWith400(String endpoint) {
        int before = fx.count("price_calendar");

        Resp r = spanRequest(endpoint, d, d.plusDays(366));

        assertThat(r.status()).as("%s", r.body()).isEqualTo(400);
        assertThat(fx.count("price_calendar")).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"trend", "detail", "heatmap", "getCalendar", "postCalendar"})
    void tc119_span366DaysAccepted(String endpoint) {
        Resp r = ok(spanRequest(endpoint, d, d.plusDays(365)));

        if (endpoint.equals("trend")) assertThat(r.data().path("dates").size()).isEqualTo(366);
    }

    // ---------- P3 SQL 条数不随天数、楼层数增长 ----------

    @ParameterizedTest
    @ValueSource(strings = {"trend", "detail", "heatmap"})
    void tc120_statsSqlCountIndependentOfDaysAndFloors(String endpoint) {
        String small = switch (endpoint) {
            case "trend" -> "/business/revenue/trend?startDate=" + d + "&endDate=" + d;
            case "detail" -> "/business/detail?startDate=" + d + "&endDate=" + d;
            default -> "/business/occupancy/heatmap?startDate=" + d + "&endDate=" + d + "&floor=1";
        };
        String large = switch (endpoint) {
            case "trend" -> "/business/revenue/trend?startDate=" + d + "&endDate=" + d.plusDays(29);
            case "detail" -> "/business/detail?startDate=" + d + "&endDate=" + d.plusDays(29);
            default -> "/business/occupancy/heatmap?startDate=" + d + "&endDate=" + d.plusDays(29);
        };

        sql.reset();
        ok(get(small, manager));
        List<String> smallSql = sql.statements();
        sql.reset();
        ok(get(large, manager));
        List<String> largeSql = sql.statements();

        assertThat(largeSql).as("small=%s", smallSql).hasSameSizeAs(smallSql);
    }

    // ---------- P4 改写后的日期条件与原语义一致（回归保护） ----------

    @Test
    void p4_userOrderQueryStillIncludesEndDate() {
        LocalDate today = LocalDate.now();
        long roomOrderId = paidOrder(room("R1"), guest, d, "199");
        long meal = fx.mealOrder(base.userA().id(), new BigDecimal("38.00"), 0);
        long oldMeal = fx.mealOrder(base.userA().id(), new BigDecimal("38.00"), 0);
        fx.createdMinutesAgo("meal_order", oldMeal, 2 * 24 * 60);

        JsonNode data = ok(get("/order/user/query?startDate=" + today.minusDays(1) + "&endDate=" + today, login(base.userA()))).data();

        assertThat(StreamSupport.stream(data.path("roomOrderList").spliterator(), false).map(o -> o.path("id").asLong()))
                .containsExactly(roomOrderId);
        assertThat(StreamSupport.stream(data.path("mealOrderList").spliterator(), false).map(o -> o.path("id").asLong()))
                .containsExactly(meal);
    }

    @Test
    void p4_statusWallShowsGuestUntilCheckoutDay() {
        LocalDateTime now = LocalDateTime.now();
        long leaving = guest("P1");
        long future = guest("P2");
        fx.roomOrder(base.userA().id(), leaving, room("R1"), now.minusDays(1).withHour(14), now.withHour(12).withMinute(0), null, 1, 0);
        fx.roomOrder(base.userA().id(), future, room("R2"), now.plusDays(1).withHour(14), now.plusDays(2).withHour(12), null, 1, 0);

        Map<String, JsonNode> wall = byField(ok(get("/rooms/status-wall", login(base.front()))).data(), "roomNumber");

        JsonNode r1 = wall.get(base.room("R1").number()).path("individual").path("name");
        JsonNode r2 = wall.get(base.room("R2").number()).path("individual").path("name");
        assertThat(r1.asText()).isEqualTo(Fixtures.guest("P1").get("name"));
        assertThat(r2.isMissingNode() || r2.isNull()).as("明天才入住，今天不显示：%s", r2).isTrue();
    }
}
