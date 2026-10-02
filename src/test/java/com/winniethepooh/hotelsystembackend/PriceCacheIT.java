package com.winniethepooh.hotelsystembackend;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.winniethepooh.hotelsystembackend.agent.AgentItem;
import com.winniethepooh.hotelsystembackend.agent.FakeLlmClient;
import com.winniethepooh.hotelsystembackend.service.HotCache;
import com.winniethepooh.hotelsystembackend.support.Fixtures;
import com.winniethepooh.hotelsystembackend.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** M3 price calendar cache through real HTTP, MySQL and Redis. */
class PriceCacheIT extends IntegrationTestBase {
    @Autowired private ObjectMapper json;
    @Autowired private FakeLlmClient fake;
    @SpyBean private StringRedisTemplate cacheRedis;
    private final LocalDate d = LocalDate.now().plusDays(10);
    @AfterEach void restoreSpies() { reset(cacheRedis); fake.reset(); }

    @Test void tc040_calendarFullyHitsAfterOneRangeQueryAndPreservesEveryFieldAndNull() {
        fx.price(0, d, new BigDecimal("350.00")); fx.price(0, d.plusDays(2), new BigDecimal("330.00"));
        String manager = login(base.manager()); ValueOperations<String, String> values = spyValues();
        sql.reset(); Resp cold = calendar(manager, d, d.plusDays(2)); assertCalendar(cold, d, d.plusDays(2));
        assertThat(sql.count()).isEqualTo(1); verifyMget(values, priceKeys(d, d.plusDays(2))); clearInvocations(values);
        sql.reset(); Resp hot = calendar(manager, d, d.plusDays(2)); assertCalendar(hot, d, d.plusDays(2));
        assertThat(hot.data()).isEqualTo(cold.data()); assertThat(sql.count()).as("%s", sql.statements()).isZero();
        verifyMget(values, priceKeys(d, d.plusDays(2)));
    }

    @Test void tc040_partialCalendarExtendsRangeWithOneSqlAndFillsOnlyMissingDaysIncludingNull() {
        fx.price(0, d, new BigDecimal("350.00")); fx.price(0, d.plusDays(2), new BigDecimal("330.00"));
        String manager = login(base.manager()); assertCalendar(calendar(manager, d, d.plusDays(1)), d, d.plusDays(1));
        List<String> keys = priceKeys(d, d.plusDays(3));
        String first = redis.opsForValue().get(keys.get(0)), second = redis.opsForValue().get(keys.get(1));
        assertThat(redis.hasKey(keys.get(2))).isFalse(); assertThat(redis.hasKey(keys.get(3))).isFalse();
        ValueOperations<String, String> values = spyValues(); sql.reset();
        assertCalendar(calendar(manager, d, d.plusDays(3)), d, d.plusDays(3));
        assertThat(sql.statements()).singleElement().satisfies(s -> assertThat(s.toLowerCase()).contains("from price_calendar", "date between ? and ?"));
        verifyMget(values, keys);
        verify(values, never()).set(eq(keys.get(0)), anyString(), any(Duration.class));
        verify(values, never()).set(eq(keys.get(1)), anyString(), any(Duration.class));
        assertThat(redis.opsForValue().get(keys.get(0))).isEqualTo(first); assertThat(redis.opsForValue().get(keys.get(1))).isEqualTo(second);
        assertThat(redis.hasKey(keys.get(2))).isTrue(); assertThat(redis.opsForValue().get(keys.get(3))).isEqualTo("NULL");
        assertThat(redis.getExpire(keys.get(3))).isBetween(1L, 300L);
        clearInvocations(values); sql.reset(); assertCalendar(calendar(manager, d, d.plusDays(3)), d, d.plusDays(3));
        assertThat(sql.count()).isZero(); verifyMget(values, keys);
    }

    @ParameterizedTest @ValueSource(strings = {"unset", "null-price"})
    void tc044_calendarPreservesMissingDayOrNullPriceRowAndHotReadHasNoSql(String kind) {
        if (kind.equals("null-price")) { fx.price(0, d, new BigDecimal("350.00")); jdbc.update("update price_calendar set price=null where room_type=0 and date=?", d); }
        String manager = login(base.manager()); String key = priceKey(d);
        sql.reset(); Resp cold = calendar(manager, d, d); assertCalendar(cold, d, d); assertThat(sql.count()).isEqualTo(1);
        if (kind.equals("unset")) {
            assertThat(cold.data().get(0).isNull()).isTrue(); assertThat(redis.opsForValue().get(key)).isEqualTo("NULL");
            assertThat(redis.getExpire(key)).isBetween(1L, 300L);
        } else { assertThat(cold.data().get(0).path("price").isNull()).isTrue(); assertThat(redis.opsForValue().get(key)).contains("\"price\":null"); }
        sql.reset(); Resp hot = calendar(manager, d, d); assertCalendar(hot, d, d);
        assertThat(hot.data()).isEqualTo(cold.data()); assertThat(sql.count()).isZero();
    }

    @Test void tc041_calendarAndListShareThreePreheatedKeysAndOneDelMakesBothReadNewPrices() {
        String a = login(base.userA()), manager = login(base.manager()); List<String> keys = priceKeys(d, d.plusDays(2));
        for (LocalDate date : d.datesUntil(d.plusDays(3)).toList()) { fx.price(0, date, new BigDecimal("350.00")); assertList(list(a, date), "350.00"); }
        assertThat(keys).allSatisfy(key -> assertThat(redis.hasKey(key)).isTrue());
        sql.reset(); assertCalendar(calendar(manager, d, d.plusDays(2)), d, d.plusDays(2)); assertThat(sql.count()).isZero();
        clearInvocations(cacheRedis); success(setPrice(manager, d, d.plusDays(2), "420.00"));
        verify(cacheRedis, times(1)).delete(keys); assertThat(keys).allSatisfy(key -> assertThat(redis.hasKey(key)).isFalse());
        sql.reset(); Resp updated = calendar(manager, d, d.plusDays(2)); assertCalendar(updated, d, d.plusDays(2)); assertThat(sql.count()).isEqualTo(1);
        updated.data().forEach(row -> assertThat(row.path("price").decimalValue()).isEqualByComparingTo("420.00"));
        assertThat(keys).allSatisfy(key -> assertThat(redis.hasKey(key)).isTrue());
        for (LocalDate date : d.datesUntil(d.plusDays(3)).toList()) {
            sql.reset(); assertList(list(a, date), "420.00"); assertThat(sql.count()).isEqualTo(2);
            assertThat(sql.statements()).noneMatch(s -> s.toLowerCase().contains("price_calendar"));
        }
    }

    @Test void tc043_orderAndModificationReadNewDatabasePricesAndOnlyDisplayKeysAreAdded() {
        String a = login(base.userA()), manager = login(base.manager()), front = login(base.front()); Set<String> before = Set.copyOf(redis.keys("*"));
        assertCalendar(calendar(manager, d, d.plusDays(2)), d, d.plusDays(2)); success(get("/rooms/" + base.room("R1").id(), a));
        success(setPrice(manager, d, d.plusDays(2), "420.00")); assertCalendar(calendar(manager, d, d.plusDays(2)), d, d.plusDays(2));
        sql.reset(); Resp placed = place(a); success(placed); long id = placed.data().asLong(); assertPriceSql(); assertOrder(id, d, d.plusDays(1), 0);
        sql.reset(); success(put("/order/" + id, front, Map.of("checkInTime", Fixtures.iso(d.plusDays(1).atTime(14, 0)), "checkOutTime", Fixtures.iso(d.plusDays(3).atTime(12, 0)))));
        assertPriceSql(); assertOrder(id, d.plusDays(1), d.plusDays(3), 0);
        assertThat(jdbc.queryForObject("select count(*) from room_inventory where room_id=? and stay_date=?", Integer.class, base.room("R1").id(), d)).isZero();
        success(get("/order/user/query?startDate=" + d.minusDays(1) + "&endDate=" + d.plusDays(4), a)); success(get("/order/query", manager));
        Set<String> added = new HashSet<>(redis.keys("*")); added.removeAll(before);
        assertThat(added).isNotEmpty().allMatch(PriceCacheIT::cacheKey);
    }

    @ParameterizedTest @ValueSource(strings = {"get_price_quote", "search_available_rooms"})
    void tc043_assistantPricingUsesDatabaseDespiteStaleDisplayValuesAndAvailabilityUpdatesImmediately(String name) throws Exception {
        for (LocalDate date : d.datesUntil(d.plusDays(3)).toList()) fx.price(0, date, new BigDecimal("350.00"));
        String a = login(base.userA()), manager = login(base.manager()); assertCalendar(calendar(manager, d, d.plusDays(2)), d, d.plusDays(2));
        List<String> keys = priceKeys(d, d.plusDays(2)); List<String> old = redis.opsForValue().multiGet(keys);
        success(setPrice(manager, d, d.plusDays(2), "420.00"));
        // Reproduce an accepted stale-display race; the real pricing path must still read MySQL.
        for (int i = 0; i < keys.size(); i++) redis.opsForValue().set(keys.get(i), old.get(i), Duration.ofMinutes(30));
        Map<String, Object> args = name.equals("get_price_quote") ? Map.of("roomNumber", "1101", "checkInDate", d.toString(), "checkOutDate", d.plusDays(3).toString())
                : Map.of("roomType", 0, "checkInDate", d.toString(), "checkOutDate", d.plusDays(3).toString());
        sql.reset(); JsonNode result = tool(a, name, args); assertThat(result.path("ok").asBoolean()).as("%s", result).isTrue(); assertPriceSql();
        if (name.equals("get_price_quote")) assertQuote(result.path("data"));
        else { assertThat(result.path("data").path("rooms")).isNotEmpty(); result.path("data").path("rooms").forEach(this::assertQuote); }
        Resp placed = place(a); success(placed); assertOrder(placed.data().asLong(), d, d.plusDays(1), 0);
        JsonNode occupied = tool(a, name, args);
        if (name.equals("get_price_quote")) { assertThat(occupied.path("ok").asBoolean()).isFalse(); assertThat(occupied.path("error").asText()).contains("已被预订"); }
        else { assertThat(occupied.path("ok").asBoolean()).isTrue(); assertThat(occupied.path("data").path("rooms")).extracting(room -> room.path("roomNumber").asText()).doesNotContain("1101"); }
        assertThat(redis.opsForValue().multiGet(keys)).isEqualTo(old); assertThat(redis.keys("order*")).isEmpty(); assertThat(redis.keys("inventory*")).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"detail", "list", "calendar", "update", "order", "pay"})
    void tc046_cacheOnlyFaultsKeepAllSixHttpRowsCorrectWithRealTokenReadsAndSafeWarnings(String operation) {
        fx.price(0, d, new BigDecimal("350.00")); String a = login(base.userA()), manager = login(base.manager());
        success(get("/rooms/" + base.room("R1").id(), a)); assertList(list(a, d), "350.00"); assertCalendar(calendar(manager, d, d.plusDays(1)), d, d.plusDays(1));
        ValueOperations<String, String> real = cacheRedis.opsForValue();
        Logger logger = (Logger) LoggerFactory.getLogger(HotCache.class); ListAppender<ILoggingEvent> logs = new ListAppender<>(); logs.start(); logger.addAppender(logs);
        try {
            ValueOperations<String, String> values = injectCacheFaults();
            switch (operation) {
                case "detail" -> { Resp r = get("/rooms/" + base.room("R1").id(), a); success(r); assertThat(r.data().path("roomNumber").asText()).isEqualTo("1101"); assertThat(r.data().path("status").asInt()).isZero(); }
                case "list" -> assertList(list(a, d), "350.00");
                case "calendar" -> { sql.reset(); assertCalendar(calendar(manager, d, d.plusDays(1)), d, d.plusDays(1)); assertThat(sql.count()).isEqualTo(1); }
                default -> {
                    success(setPrice(manager, d, d, "420.00"));
                    assertThat(jdbc.queryForObject("select price from price_calendar where room_type=0 and date=?", BigDecimal.class, d)).isEqualByComparingTo("420.00");
                    assertThat(real.get(priceKey(d))).contains("350.00");
                    if (operation.equals("update")) { assertCalendar(calendar(manager, d, d.plusDays(1)), d, d.plusDays(1)); assertList(list(a, d), "420.00"); }
                    else {
                        sql.reset(); Resp placed = place(a); success(placed); long id = placed.data().asLong(); assertPriceSql(); assertOrder(id, d, d.plusDays(1), 0);
                        if (operation.equals("pay")) { var stock = jdbc.queryForList("select * from room_inventory where order_id=? order by id", id); success(post("/order/pay?id=" + id, a, null)); assertOrder(id, d, d.plusDays(1), 1); assertThat(jdbc.queryForList("select * from room_inventory where order_id=? order by id", id)).isEqualTo(stock); }
                    }
                }
            }
            verify(values, atLeastOnce()).get(operation.equals("calendar") || operation.equals("update") ? manager : a);
            assertThat(logs.list.stream().filter(e -> e.getLevel() == Level.WARN).toList()).isNotEmpty().allSatisfy(e -> {
                assertThat(e.getFormattedMessage()).matches("cache (room:|price:) RedisConnectionFailureException").doesNotContain("入住人", "17000000001", "injected");
                assertThat(e.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    private Resp calendar(String token, LocalDate start, LocalDate end) {
        return get("/business/calendar?roomType=0&startDate=" + start + "&endDate=" + end, token);
    }
    private String priceKey(LocalDate date) { return "price:0:" + date; }
    private List<String> priceKeys(LocalDate start, LocalDate end) { return start.datesUntil(end.plusDays(1)).map(this::priceKey).toList(); }
    private Resp setPrice(String manager, LocalDate start, LocalDate end, String price) { return post("/business/calendar", manager, Map.of("roomType", 0, "startDate", start.toString(), "endDate", end.toString(), "price", price)); }
    private Resp list(String token, LocalDate date) { return get("/rooms?date=" + date, token); }
    private Resp place(String token) { return post("/order", token, Fixtures.roomOrderBody("1101", d.atTime(14, 0), d.plusDays(1).atTime(12, 0))); }
    private void assertList(Resp r, String price) {
        success(r); assertThat(r.data().path("total").asInt()).isEqualTo(10);
        r.data().path("list").forEach(room -> assertThat(room.path("price").decimalValue()).isEqualByComparingTo(switch (room.path("roomType").asInt()) { case 0 -> price; case 1 -> "299.00"; default -> "499.00"; }));
    }
    private void assertPriceSql() { assertThat(sql.statements().stream().filter(s -> s.toLowerCase().contains("from price_calendar")).toList()).hasSize(1); }
    private void assertOrder(long id, LocalDate start, LocalDate end, int paid) {
        assertThat(jdbc.queryForObject("select total_amount from room_order where id=?", BigDecimal.class, id)).isEqualByComparingTo(BigDecimal.valueOf(420 * (end.toEpochDay() - start.toEpochDay())));
        assertThat(jdbc.queryForObject("select pay_status from room_order where id=?", Integer.class, id)).isEqualTo(paid);
        var nights = jdbc.queryForList("select night, price from room_order_night where room_order_id=? order by night", id);
        assertThat(nights).extracting(row -> row.get("night").toString()).containsExactlyElementsOf(start.datesUntil(end).map(LocalDate::toString).toList());
        assertThat(nights).allSatisfy(row -> assertThat((BigDecimal) row.get("price")).isEqualByComparingTo("420.00"));
        var stock = jdbc.queryForList("select room_id, stay_date from room_inventory where order_id=? order by stay_date", id);
        assertThat(stock).extracting(row -> row.get("stay_date").toString()).containsExactlyElementsOf(start.datesUntil(end).map(LocalDate::toString).toList());
        assertThat(stock).allSatisfy(row -> assertThat(((Number) row.get("room_id")).longValue()).isEqualTo(base.room("R1").id()));
    }
    private void assertQuote(JsonNode quote) {
        List<String> dates = new ArrayList<>(); quote.path("nights").fieldNames().forEachRemaining(dates::add);
        assertThat(dates).containsExactlyElementsOf(d.datesUntil(d.plusDays(3)).map(LocalDate::toString).toList());
        quote.path("nights").forEach(price -> assertThat(price.decimalValue()).isEqualByComparingTo("420.00"));
        assertThat(quote.path("total").decimalValue()).isEqualByComparingTo("1260.00");
    }
    private JsonNode tool(String token, String name, Map<String, Object> args) throws Exception {
        fake.reset(); Resp session = post("/agent/sessions", token, null); success(session);
        fake.enqueue(new AgentItem(AgentItem.Type.FUNCTION_CALL, null, "c1", name, json.writeValueAsString(args), null, null)); fake.enqueue(AgentItem.assistant("测试收尾"));
        HttpHeaders headers = new HttpHeaders(); headers.set("token", token); headers.setContentType(MediaType.APPLICATION_JSON);
        var r = rest.exchange("/agent/chat", HttpMethod.POST, new HttpEntity<>(Map.of("sessionId", session.data().path("sessionId").asText(), "message", "执行价格测试"), headers), String.class);
        assertThat(r.getStatusCode().value()).isEqualTo(200); assertThat(r.getBody()).contains("event: done").doesNotContain("event: error");
        AgentItem output = fake.inputs().get(fake.inputs().size() - 1).stream().filter(i -> i.type() == AgentItem.Type.FUNCTION_CALL_OUTPUT).reduce((a, b) -> b).orElseThrow();
        return json.readTree(output.output());
    }
    private ValueOperations<String, String> spyValues() { ValueOperations<String, String> values = spy(cacheRedis.opsForValue()); doReturn(values).when(cacheRedis).opsForValue(); return values; }
    private void verifyMget(ValueOperations<String, String> values, List<String> expected) {
        ArgumentCaptor<Collection<String>> keys = ArgumentCaptor.forClass(Collection.class); verify(values, times(1)).multiGet(keys.capture()); assertThat(keys.getValue()).containsExactlyElementsOf(expected);
    }
    private ValueOperations<String, String> injectCacheFaults() {
        ValueOperations<String, String> values = spyValues();
        doAnswer(inv -> { if (cacheKey(inv.getArgument(0))) throw new RedisConnectionFailureException("injected 入住人17000000001"); return inv.callRealMethod(); }).when(values).get(any());
        doAnswer(inv -> { Collection<String> keys = inv.getArgument(0); if (keys.stream().anyMatch(PriceCacheIT::cacheKey)) throw new RedisConnectionFailureException("injected 入住人17000000001"); return inv.callRealMethod(); }).when(values).multiGet(anyCollection());
        doAnswer(inv -> { if (cacheKey(inv.getArgument(0))) throw new RedisConnectionFailureException("injected 入住人17000000001"); return inv.callRealMethod(); }).when(values).set(anyString(), anyString(), any(Duration.class));
        doAnswer(inv -> { Collection<String> keys = inv.getArgument(0); if (keys.stream().anyMatch(PriceCacheIT::cacheKey)) throw new RedisConnectionFailureException("injected 入住人17000000001"); return inv.callRealMethod(); }).when(cacheRedis).delete(anyCollection());
        return values;
    }
    private static boolean cacheKey(Object key) { return key instanceof String s && (s.startsWith("room:") || s.startsWith("price:")); }
    private void assertCalendar(Resp r, LocalDate start, LocalDate end) {
        success(r); assertThat(r.data()).hasSize((int) (end.toEpochDay() - start.toEpochDay() + 1));
        for (int i = 0; i < r.data().size(); i++) {
            LocalDate date = start.plusDays(i);
            var rows = jdbc.queryForList("select id, room_type, date, price from price_calendar where room_type=0 and date=? and is_deleted=0", date);
            var actual = r.data().get(i);
            if (rows.isEmpty()) assertThat(actual.isNull()).isTrue();
            else {
                Map<String, Object> expected = rows.get(0); assertThat(actual.size()).isEqualTo(4);
                assertThat(actual.path("id").asInt()).isEqualTo(((Number) expected.get("id")).intValue());
                assertThat(actual.path("roomType").asInt()).isZero(); assertThat(actual.path("date").asText()).isEqualTo(date.toString());
                if (expected.get("price") == null) assertThat(actual.path("price").isNull()).isTrue();
                else assertThat(actual.path("price").decimalValue()).isEqualByComparingTo((BigDecimal) expected.get("price"));
            }
        }
    }
    private void success(Resp r) { assertThat(r.status()).as("%s", r.body()).isEqualTo(200); assertThat(r.code()).as("%s", r.body()).isZero(); }
}
