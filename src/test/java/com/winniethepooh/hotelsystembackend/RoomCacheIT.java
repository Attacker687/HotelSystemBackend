package com.winniethepooh.hotelsystembackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.winniethepooh.hotelsystembackend.support.IntegrationTestBase;
import com.winniethepooh.hotelsystembackend.support.Fixtures;
import com.winniethepooh.hotelsystembackend.service.HotCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** M3 cache behavior through the existing HTTP endpoints and real Redis/MySQL. */
class RoomCacheIT extends IntegrationTestBase {
    @Autowired private ObjectMapper json;
    @Autowired private HotCache cache;
    @Autowired private RedisProperties redisProperties;
    @Autowired private Environment environment;
    @SpyBean private StringRedisTemplate cacheRedis;
    private final LocalDate d = LocalDate.now().plusDays(10);
    @AfterEach void restoreCache() { ReflectionTestUtils.setField(cache, "enabled", true); reset(cacheRedis); }

    @Test void tc039_detailHitReadsOnlyCurrentStatusAndPreservesEveryResponseField() throws Exception {
        jdbc.update("update room set description=? where id=?", "安静朝南，可看庭院", base.room("R1").id());
        String a = login(base.userA()), key = "room:detail:" + base.room("R1").id();
        sql.reset(); Resp cold = get("/rooms/" + base.room("R1").id(), a); success(cold);
        assertThat(sql.count()).isEqualTo(1);
        sql.reset(); Resp hot = get("/rooms/" + base.room("R1").id(), a); success(hot);
        assertThat(hot.data()).isEqualTo(cold.data());
        assertThat(hot.data().path("image").asText()).isEqualTo("https://hotelsystem.oss-cn-chengdu.aliyuncs.com/sample.jpeg");
        assertThat(sql.statements()).singleElement().satisfies(s -> {
            assertThat(s.toLowerCase()).startsWith("select status from room").contains("is_deleted = 0").doesNotContain("*", "description", "image");
        });
        JsonNode cached = json.readTree(redis.opsForValue().get(key));
        assertThat(cached.size()).isEqualTo(6);
        assertThat(cached.has("status")).isFalse(); assertThat(cached.has("id")).isFalse(); assertThat(cached.has("price")).isFalse();
        assertThat(cached.path("description").asText()).isEqualTo("安静朝南，可看庭院");
        jdbc.update("update room set is_deleted=1 where id=?", base.room("R1").id());
        sql.reset(); Resp deleted = get("/rooms/" + base.room("R1").id(), a); success(deleted);
        assertThat(deleted.data().isNull()).isTrue(); assertThat(sql.count()).isEqualTo(1);
    }

    @Test void tc044_missingRoomCachesNullForFiveMinutesAndHotReadHasNoSql() {
        String a = login(base.userA()), key = "room:detail:99999";
        Resp cold = get("/rooms/99999", a); success(cold); assertThat(cold.data().isNull()).isTrue();
        assertThat(redis.opsForValue().get(key)).isEqualTo("NULL");
        assertThat(redis.getExpire(key)).isBetween(1L, 300L);
        sql.reset(); Resp hot = get("/rooms/99999", a); success(hot);
        assertThat(hot.data().isNull()).isTrue(); assertThat(sql.count()).isZero();
    }

    @Test void tc039_existingRoomWithNullStatusKeepsItsOriginalVoOnColdAndHotReads() {
        jdbc.update("update room set status=null where id=?", base.room("R1").id()); String a = login(base.userA());
        Resp cold = get("/rooms/" + base.room("R1").id(), a); success(cold);
        assertThat(cold.data().isNull()).isFalse(); assertThat(cold.data().path("status").isNull()).isTrue();
        sql.reset(); Resp hot = get("/rooms/" + base.room("R1").id(), a); success(hot);
        assertThat(hot.data()).isEqualTo(cold.data()); assertThat(sql.count()).isEqualTo(1);
        assertThat(sql.statements()).singleElement().satisfies(s -> assertThat(s.toLowerCase()).startsWith("select status from room").contains("is_deleted = 0"));
    }

    @Test void tc040_listPricesUseOneMgetForThreeTypesAndHotListHasOnlyTwoNonJoinSqls() throws Exception {
        fx.price(0, d, new BigDecimal("350.00")); String a = login(base.userA()); ValueOperations<String, String> values = spyValues();
        sql.reset(); Resp cold = list(a, d); assertListPrices(cold, "350.00"); assertThat(sql.count()).isEqualTo(5);
        assertThat(json.readTree(redis.opsForValue().get(priceKey(d))).path("date").asText()).isEqualTo(d.toString());
        assertThat(redis.opsForValue().get("price:1:" + d)).isEqualTo("NULL"); assertThat(redis.opsForValue().get("price:2:" + d)).isEqualTo("NULL");
        clearInvocations(values); sql.reset(); Resp hot = list(a, d); assertListPrices(hot, "350.00");
        assertThat(hot.data()).isEqualTo(cold.data()); assertThat(sql.count()).isEqualTo(2);
        assertThat(sql.statements()).allSatisfy(s -> assertThat(s.toLowerCase()).doesNotContain("join", "price_calendar"));
        ArgumentCaptor<Collection<String>> keys = ArgumentCaptor.forClass(Collection.class);
        verify(values, times(1)).multiGet(keys.capture());
        assertThat(keys.getValue()).hasSize(3).containsExactlyInAnyOrder("price:0:" + d, "price:1:" + d, "price:2:" + d);
    }

    @Test void tc041_threePreheatedPriceKeysAreDeletedInOneBatchAfterUpdateAndListShowsNewPrice() {
        String a = login(base.userA()), manager = login(base.manager());
        List<LocalDate> dates = d.datesUntil(d.plusDays(3)).toList(); List<String> keys = dates.stream().map(this::priceKey).toList();
        for (LocalDate date : dates) { fx.price(0, date, new BigDecimal("350.00")); assertListPrices(list(a, date), "350.00"); }
        assertThat(keys).allSatisfy(key -> assertThat(redis.hasKey(key)).isTrue()); clearInvocations(cacheRedis);
        success(setPrice(manager, d, d.plusDays(2), "420.00"));
        verify(cacheRedis, times(1)).delete(keys);
        assertThat(keys).allSatisfy(key -> assertThat(redis.hasKey(key)).isFalse());
        for (LocalDate date : dates) assertListPrices(list(a, date), "420.00");
        Resp calendar = get("/business/calendar?roomType=0&startDate=" + d + "&endDate=" + d.plusDays(2), manager); success(calendar);
        assertThat(calendar.data()).hasSize(3); calendar.data().forEach(row -> assertThat(row.path("price").decimalValue()).isEqualByComparingTo("420.00"));
    }

    @ParameterizedTest @ValueSource(strings = {"update", "delete"})
    void tc041_roomWritersEvictPreheatedDetailAndImmediateReadShowsDatabaseResult(String operation) {
        String a = login(base.userA()), manager = login(base.manager()); long id = base.room("R1").id(); String key = "room:detail:" + id;
        success(get("/rooms/" + id, a)); assertThat(redis.hasKey(key)).isTrue(); clearInvocations(cacheRedis);
        if (operation.equals("update")) success(put("/rooms/" + id, manager, roomBody("新描述-M3")));
        else success(delete("/rooms/" + id, manager));
        verify(cacheRedis, times(1)).delete(List.of(key)); assertThat(redis.hasKey(key)).isFalse();
        Resp current = get("/rooms/" + id, a); success(current);
        if (operation.equals("update")) assertThat(current.data().path("description").asText()).isEqualTo("新描述-M3");
        else assertThat(current.data().isNull()).isTrue();
    }

    @Test void tc042_statusWriteLeavesStaticJsonUnchangedAndHotDetailReadsNewStatus() throws Exception {
        String a = login(base.userA()), front = login(base.front()); String key = "room:detail:" + base.room("R1").id();
        Resp cold = get("/rooms/" + base.room("R1").id(), a); success(cold); assertThat(cold.data().path("status").asInt()).isZero();
        String cached = redis.opsForValue().get(key); assertThat(json.readTree(cached).has("status")).isFalse();
        success(put("/rooms", front, Map.of("id", base.room("R1").id(), "status", 3)));
        assertThat(redis.opsForValue().get(key)).isEqualTo(cached); sql.reset(); Resp hot = get("/rooms/" + base.room("R1").id(), a); success(hot);
        assertThat(hot.data().path("status").asInt()).isEqualTo(3); assertThat(sql.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from room where id=?", Integer.class, base.room("R1").id())).isEqualTo(3);
    }

    @Test void tc043_orderPricingStillReadsDatabaseAndOnlyDisplayPrefixesAreAdded() {
        String a = login(base.userA()), manager = login(base.manager()); Set<String> before = Set.copyOf(redis.keys("*"));
        success(get("/rooms/" + base.room("R1").id(), a)); assertListPrices(list(a, d), "199.00"); success(setPrice(manager, d, d, "420.00"));
        sql.reset(); Resp placed = place(a); success(placed); assertOrder(placed.data().asLong(), 0);
        assertThat(sql.statements()).anySatisfy(s -> assertThat(s.toLowerCase()).contains("from price_calendar"));
        success(post("/order/pay?id=" + placed.data().asLong(), a, null)); assertOrder(placed.data().asLong(), 1);
        success(get("/order/user/query?startDate=" + d.minusDays(1) + "&endDate=" + d.plusDays(1), a)); success(get("/order/query", manager));
        Set<String> added = new HashSet<>(redis.keys("*")); added.removeAll(before);
        assertThat(added).isNotEmpty().allMatch(key -> key.startsWith("room:") || key.startsWith("price:"));
    }

    @Test void tc044_unsetListPriceUsesNullCacheAndSecondListAvoidsPriceSql() {
        String a = login(base.userA()); Resp cold = get("/rooms?roomType=0&date=" + d, a); success(cold);
        assertThat(redis.opsForValue().get(priceKey(d))).isEqualTo("NULL"); assertThat(redis.getExpire(priceKey(d))).isBetween(1L, 300L);
        cold.data().path("list").forEach(room -> assertThat(room.path("price").decimalValue()).isEqualByComparingTo("199.00"));
        sql.reset(); Resp hot = get("/rooms?roomType=0&date=" + d, a); success(hot);
        assertThat(hot.data()).isEqualTo(cold.data()); assertThat(sql.count()).isEqualTo(2);
        assertThat(sql.statements()).noneMatch(s -> s.toLowerCase().contains("price_calendar"));
    }

    @ParameterizedTest @ValueSource(strings = {"detail", "list", "calendar", "update", "order", "pay"})
    void tc046_cachePrefixReadWriteDeleteFailuresKeepSixHttpOperationsCorrectAndTokenValid(String operation) {
        fx.price(0, d, new BigDecimal("350.00")); String a = login(base.userA()), manager = login(base.manager());
        success(get("/rooms/" + base.room("R1").id(), a)); assertListPrices(list(a, d), "350.00");
        ValueOperations<String, String> real = cacheRedis.opsForValue(); injectCacheFaults();
        switch (operation) {
            case "detail" -> { Resp r = get("/rooms/" + base.room("R1").id(), a); success(r); assertThat(r.data().path("roomNumber").asText()).isEqualTo("1101"); assertThat(r.data().path("status").asInt()).isZero(); }
            case "list" -> assertListPrices(list(a, d), "350.00");
            case "calendar" -> { Resp r = get("/business/calendar?roomType=0&startDate=" + d + "&endDate=" + d.plusDays(1), manager); success(r); assertThat(r.data()).hasSize(2); assertThat(r.data().get(0).path("price").decimalValue()).isEqualByComparingTo("350.00"); assertThat(r.data().get(1).isNull()).isTrue(); }
            default -> {
                success(setPrice(manager, d, d, "420.00")); assertThat(jdbc.queryForObject("select price from price_calendar where room_type=0 and date=?", BigDecimal.class, d)).isEqualByComparingTo("420.00");
                // The failed DEL really leaves the old display value; pricing must still use MySQL.
                assertThat(real.get(priceKey(d))).contains("350.00");
                if (operation.equals("update")) assertListPrices(list(a, d), "420.00");
                else {
                    sql.reset(); Resp placed = place(a); success(placed); long id = placed.data().asLong(); assertOrder(id, 0);
                    assertThat(sql.statements()).anySatisfy(s -> assertThat(s.toLowerCase()).contains("from price_calendar"));
                    if (operation.equals("pay")) { success(post("/order/pay?id=" + id, a, null)); assertOrder(id, 1); }
                }
            }
        }
    }

    @Test void tc049_disabledHttpReadsBypassStaleValuesWithoutCacheIoAndAllThreeWritersStillEvict() {
        assertThat(environment.getProperty("hotel.cache.enabled", Boolean.class)).isTrue(); assertThat(redisProperties.getTimeout()).isEqualTo(Duration.ofSeconds(1));
        fx.price(0, d, new BigDecimal("350.00")); String a = login(base.userA()), manager = login(base.manager());
        success(get("/rooms/" + base.room("R1").id(), a)); success(get("/rooms/" + base.room("R2").id(), a)); assertListPrices(list(a, d), "350.00");
        jdbc.update("update room set description=? where id=?", "直接库中新描述", base.room("R1").id()); jdbc.update("update price_calendar set price=420 where room_type=0 and date=?", d);
        ValueOperations<String, String> values = spyValues(); clearInvocations(values); ReflectionTestUtils.setField(cache, "enabled", false);
        Resp detail = get("/rooms/" + base.room("R1").id(), a); success(detail); assertThat(detail.data().path("description").asText()).isEqualTo("直接库中新描述"); assertListPrices(list(a, d), "420.00");
        success(put("/rooms/" + base.room("R1").id(), manager, roomBody("写入口新描述"))); success(delete("/rooms/" + base.room("R2").id(), manager)); success(setPrice(manager, d, d, "430.00"));
        verify(values, never()).get(argThat(RoomCacheIT::cacheKey)); verify(values, never()).multiGet(anyCollection());
        verify(values, never()).set(argThat(RoomCacheIT::cacheKey), anyString(), any(Duration.class));
        for (String key : List.of("room:detail:" + base.room("R1").id(), "room:detail:" + base.room("R2").id(), priceKey(d))) {
            verify(cacheRedis).delete(List.of(key)); assertThat(redis.hasKey(key)).isFalse();
        }
    }

    private ValueOperations<String, String> spyValues() { ValueOperations<String, String> values = spy(cacheRedis.opsForValue()); doReturn(values).when(cacheRedis).opsForValue(); return values; }
    private void injectCacheFaults() {
        ValueOperations<String, String> values = spyValues();
        doAnswer(inv -> { if (cacheKey(inv.getArgument(0))) throw new RedisConnectionFailureException("injected"); return inv.callRealMethod(); }).when(values).get(any());
        doAnswer(inv -> { Collection<String> keys = inv.getArgument(0); if (keys.stream().anyMatch(RoomCacheIT::cacheKey)) throw new RedisConnectionFailureException("injected"); return inv.callRealMethod(); }).when(values).multiGet(anyCollection());
        doAnswer(inv -> { if (cacheKey(inv.getArgument(0))) throw new RedisConnectionFailureException("injected"); return inv.callRealMethod(); }).when(values).set(anyString(), anyString(), any(Duration.class));
        doAnswer(inv -> { Collection<String> keys = inv.getArgument(0); if (keys.stream().anyMatch(RoomCacheIT::cacheKey)) throw new RedisConnectionFailureException("injected"); return inv.callRealMethod(); }).when(cacheRedis).delete(anyCollection());
    }
    private static boolean cacheKey(Object key) { return key instanceof String s && (s.startsWith("room:") || s.startsWith("price:")); }
    private String priceKey(LocalDate date) { return "price:0:" + date; }
    private Resp list(String token, LocalDate date) { return get("/rooms?date=" + date, token); }
    private void assertListPrices(Resp r, String singlePrice) { success(r); assertThat(r.data().path("total").asInt()).isEqualTo(10); r.data().path("list").forEach(room -> assertThat(room.path("price").decimalValue()).isEqualByComparingTo(switch (room.path("roomType").asInt()) { case 0 -> singlePrice; case 1 -> "299.00"; default -> "499.00"; })); }
    private Resp setPrice(String manager, LocalDate start, LocalDate end, String price) { return post("/business/calendar", manager, Map.of("roomType", 0, "startDate", start.toString(), "endDate", end.toString(), "price", price)); }
    private Map<String, Object> roomBody(String description) { return Map.of("roomNumber", "1101", "roomType", 0, "floor", 1, "capacity", 2, "description", description); }
    private Resp place(String a) { return post("/order", a, Fixtures.roomOrderBody("1101", d.atTime(14, 0), d.plusDays(1).atTime(12, 0))); }
    private void assertOrder(long id, int paid) {
        assertThat(jdbc.queryForObject("select total_amount from room_order where id=?", BigDecimal.class, id)).isEqualByComparingTo("420.00");
        assertThat(jdbc.queryForObject("select price from room_order_night where room_order_id=?", BigDecimal.class, id)).isEqualByComparingTo("420.00");
        assertThat(jdbc.queryForObject("select pay_status from room_order where id=?", Integer.class, id)).isEqualTo(paid);
        assertThat(jdbc.queryForObject("select count(*) from room_inventory where order_id=? and room_id=? and stay_date=?", Integer.class, id, base.room("R1").id(), d)).isEqualTo(1);
    }

    private void success(Resp r) { assertThat(r.status()).as("%s", r.body()).isEqualTo(200); assertThat(r.code()).as("%s", r.body()).isZero(); }
}
