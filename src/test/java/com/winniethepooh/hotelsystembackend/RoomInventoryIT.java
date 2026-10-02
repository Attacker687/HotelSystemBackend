package com.winniethepooh.hotelsystembackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.winniethepooh.hotelsystembackend.agent.AgentItem;
import com.winniethepooh.hotelsystembackend.agent.FakeLlmClient;
import com.winniethepooh.hotelsystembackend.mapper.OrderMapper;
import com.winniethepooh.hotelsystembackend.service.CustomTaskScheduler;
import com.winniethepooh.hotelsystembackend.service.OrderService;
import com.winniethepooh.hotelsystembackend.support.Fixtures;
import com.winniethepooh.hotelsystembackend.support.IntegrationTestBase;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.http.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.mybatis.spring.SqlSessionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** M2: real HTTP writes and persisted per-night stock. */
class RoomInventoryIT extends IntegrationTestBase {
    private final LocalDate d = LocalDate.now().plusDays(10);
    @Autowired private FakeLlmClient fake;
    @Autowired private ObjectMapper json;
    @Autowired private CustomTaskScheduler scheduler;
    @Autowired private OrderService orders;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private SqlSessionTemplate sessions;
    @SpyBean private OrderMapper mapper;

    @Test void tc018_fiftyBookingsLeaveOnlyOneOrderNightAndNewGuest() throws Exception {
        String token = login(base.userA()); int people = fx.count("individual");
        Map<String, Object> body = Fixtures.roomOrderBody("1101", in(1), out(2)); body.putAll(Fixtures.guest("P9"));
        List<Callable<Resp>> jobs = new ArrayList<>();
        for (int i = 0; i < 50; i++) jobs.add(() -> post("/order", token, body));
        List<Resp> responses = together(jobs);
        assertThat(responses.stream().filter(r -> r.status() == 200)).hasSize(1);
        for (Resp r : responses) { if (r.status() == 200) success(r); else conflict(r, "已被预订"); }
        long id = responses.stream().filter(r -> r.status() == 200).findFirst().orElseThrow().data().asLong();
        assertThat(fx.count("room_order", "status=0")).isEqualTo(1);
        assertThat(fx.count("room_order_night")).isEqualTo(1);
        inventory(id, "R1", d.plusDays(1), d.plusDays(2));
        assertThat(fx.count("room_inventory")).isEqualTo(1);
        assertThat(fx.count("individual")).isEqualTo(people + 1);
        assertThat(fx.count("individual", "id_card_number=?", Fixtures.guest("P9").get("idCard"))).isEqualTo(1);
        assertInventoryConsistent();
    }

    @Test void tc019_twentyPartiallyOverlappingRacesEachCommitOnlyThreeNights() throws Exception {
        String a = login(base.userA()), b = login(base.userB());
        for (int k = 1; k <= 20; k++) {
            int offset = k * 5;
            List<Resp> responses = together(List.of(() -> book(a, "R1", offset + 1, offset + 4), () -> book(b, "R1", offset + 2, offset + 5)));
            assertThat(responses.stream().filter(r -> r.status() == 200)).as("round %s", k).hasSize(1);
            for (Resp r : responses) {
                if (r.status() == 200) success(r);
                else { assertThat(r.status()).as("%s", r.body()).isEqualTo(409); assertThat(r.msg()).containsAnyOf("已被预订", "预订繁忙"); }
            }
            long id = responses.stream().filter(r -> r.status() == 200).findFirst().orElseThrow().data().asLong();
            inventory(id, "R1", jdbc.queryForObject("select checkin_time from room_order where id=?", LocalDateTime.class, id).toLocalDate(),
                    jdbc.queryForObject("select checkout_time from room_order where id=?", LocalDateTime.class, id).toLocalDate());
            assertThat(fx.count("room_order", "status=0 and checkin_time>=? and checkin_time<?", in(offset), in(offset + 5))).isEqualTo(1);
            assertThat(fx.count("room_inventory", "stay_date>=? and stay_date<?", d.plusDays(offset), d.plusDays(offset + 5))).isEqualTo(3);
            assertInventoryConsistent();
        }
    }

    @ParameterizedTest @ValueSource(ints = {3, 1})
    void tc020_conflictOnLastOrFirstNightRollsBackAllNewRows(int occupied) {
        order(login(base.userB()), "R1", occupied, occupied + 1);
        Map<String, List<Map<String, Object>>> before = snapshot();
        Map<String, Object> body = Fixtures.roomOrderBody("1101", in(1), out(4)); body.putAll(Fixtures.guest("P9"));
        conflict(post("/order", login(base.userA()), body), "已被预订");
        assertThat(snapshot()).isEqualTo(before); assertInventoryConsistent();
    }

    @ParameterizedTest @CsvSource({"3,4,10,200", "0,1,14,200", "2,4,14,409", "0,2,14,409"})
    void tc021_nightBoundariesAllowAdjacentStaysEvenBeforeCheckoutTime(int start, int end, int hour, int status) {
        long old = order(login(base.userB()), "R1", 1, 3);
        Resp r = post("/order", login(base.userA()), Fixtures.roomOrderBody("1101", d.plusDays(start).atTime(hour, 0), out(end)));
        if (status == 200) { success(r); inventory(r.data().asLong(), "R1", d.plusDays(start), d.plusDays(end)); }
        else conflict(r, "已被预订");
        inventory(old, "R1", d.plusDays(1), d.plusDays(3));
        assertThat(fx.count("room_order")).isEqualTo(status == 200 ? 2 : 1); assertInventoryConsistent();
    }

    @ParameterizedTest
    @CsvSource({"USER,1", "USER,3", "USER,30", "FRONT,1", "FRONT,3", "FRONT,30", "ASSISTANT,3", "USER,31"})
    void tc022_eachBookingEntryPersistsEveryNight(String entry, int nights) throws Exception {
        String token = login(entry.equals("FRONT") ? base.front() : base.userA());
        Resp r = entry.equals("ASSISTANT") ? confirm(token, proposal(token, "propose_booking", stay("1101", 0, nights)))
                : post("/order", token, Fixtures.roomOrderBody("1101", d.atTime(14, 0), d.plusDays(nights).atTime(12, 0)));
        if (nights == 31) {
            assertThat(r.status()).isEqualTo(400); assertThat(r.code()).isEqualTo(1);
            assertThat(fx.count("room_order")).isZero(); assertThat(fx.count("room_inventory")).isZero(); assertInventoryConsistent(); return;
        }
        success(r);
        if (entry.equals("FRONT")) assertThat(r.data().isNull()).isTrue();
        if (entry.equals("ASSISTANT")) assertThat(r.data().path("status").asText()).isEqualTo("CONFIRMED");
        long id = jdbc.queryForObject("select id from room_order", Long.class);
        assertThat(fx.count("room_order_night", "room_order_id=?", id)).isEqualTo(nights);
        assertThat(fx.count("room_inventory", "order_id=?", id)).isEqualTo(nights);
        assertThat(jdbc.queryForList("select cast(stay_date as char) from room_inventory where order_id=? order by stay_date", String.class, id))
                .isEqualTo(d.datesUntil(d.plusDays(nights)).map(LocalDate::toString).toList());
        assertThat(jdbc.queryForList("select room_id from room_inventory where order_id=?", Long.class, id))
                .containsOnly(base.room("R1").id());
        assertInventoryConsistent();
    }

    @ParameterizedTest @ValueSource(strings = {"unpaid", "paid", "assistant", "expiry", "delete"})
    void tc023_allReleaseEntriesAllowAnotherGuestToRebook(String action) throws Exception {
        String a = login(base.userA()), b = login(base.userB()); long id = order(a, "R1", 1, 3);
        assertThat(fx.count("room_inventory", "order_id=?", id)).isEqualTo(2);
        if (action.equals("paid")) success(post("/order/pay?id=" + id, a, null));
        switch (action) {
            case "assistant" -> { Resp r = confirm(a, proposal(a, "propose_cancel", Map.of("orderId", id))); success(r); assertThat(r.data().path("status").asText()).isEqualTo("CONFIRMED"); }
            case "expiry" -> {
                long front = frontOrder("R2", 1, 3), paid = order(a, "R3", 1, 3);
                success(post("/order/pay?id=" + paid, a, null));
                for (long old : List.of(id, front, paid)) fx.createdMinutesAgo("room_order", old, 16);
                List<Map<String, Object>> retained = jdbc.queryForList("select * from room_inventory where order_id in (?,?) order by id", front, paid);
                sql.reset(); scheduler.flushExpiredRoomOrders();
                assertThat(sql.statements()).anyMatch(s -> s.toLowerCase().contains("select id from room_order") && s.toLowerCase().contains("for update"));
                assertThat(row(front).get("status")).isEqualTo(0); assertThat(row(paid).get("status")).isEqualTo(0);
                assertThat(jdbc.queryForList("select * from room_inventory where order_id in (?,?) order by id", front, paid)).isEqualTo(retained);
                assertThat(post("/order/pay?id=" + id, a, null).status()).isEqualTo(409);
            }
            case "delete" -> success(delete("/order/" + id, login(base.manager())));
            default -> success(post("/order/cancel?id=" + id, a, null));
        }
        if (action.equals("delete")) assertThat(row(id).get("is_deleted")).isEqualTo(1);
        else { assertThat(row(id).get("status")).isEqualTo(2); assertThat(row(id).get("pay_status")).isEqualTo(action.equals("paid") ? 2 : 0); }
        assertThat(fx.count("room_inventory", "order_id=?", id)).isZero();
        assertThat(fx.count("room_order_night", "room_order_id=?", id)).isEqualTo(2); assertInventoryConsistent();
        Map<String, Object> body = Fixtures.roomOrderBody("1101", in(1), out(3)); body.putAll(Fixtures.guest("P1"));
        Resp second = post("/order", b, body); success(second);
        inventory(second.data().asLong(), "R1", d.plusDays(1), d.plusDays(3)); assertInventoryConsistent();
    }

    @ParameterizedTest @ValueSource(strings = {"other-owner", "already-cancelled", "checked-in", "missing"})
    void tc023_failedCancellationPreservesInventory(String action) {
        String a = login(base.userA()); long id = order(a, "R1", 1, 3);
        if (action.equals("already-cancelled")) success(post("/order/cancel?id=" + id, a, null));
        if (action.equals("checked-in")) jdbc.update("update room_order set checkin_time=? where id=?", LocalDateTime.now().minusHours(1), id);
        Map<String, List<Map<String, Object>>> before = snapshot();
        Resp r = post("/order/cancel?id=" + (action.equals("missing") ? 999999 : id), action.equals("other-owner") ? login(base.userB()) : a, null);
        assertThat(r.status()).isIn(403, 404, 409); assertThat(r.code()).isEqualTo(1); assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"early", "normal", "pay"})
    void tc024_earlyCheckoutReleasesFutureOnlyNormalCheckoutAndPayPreserveIds(String action) {
        LocalDate today = LocalDate.now();
        if (action.equals("pay")) {
            String a = login(base.userA()); long id = order(a, "R1", 1, 3);
            List<Map<String, Object>> before = stock(); success(post("/order/pay?id=" + id, a, null));
            assertThat(row(id).get("pay_status")).isEqualTo(1); assertThat(stock()).isEqualTo(before);
        } else {
            LocalDate start = today.minusDays(action.equals("early") ? 1 : 3), end = today.plusDays(action.equals("early") ? 2 : -1);
            String alias = action.equals("early") ? "R1" : "R2";
            long id = seed(alias, start, end, 1, 0); jdbc.update("update room set status=1 where id=?", base.room(alias).id());
            List<Map<String, Object>> before = stock();
            if (action.equals("early")) {
                success(put("/rooms", login(base.front()), Map.of("id", base.room(alias).id(), "status", 0)));
                assertThat(stock()).isEqualTo(before.stream().filter(r -> r.get("stay_date").equals(today.minusDays(1).toString())).toList());
                Resp r = post("/order", login(base.userB()), Fixtures.roomOrderBody("1101", today.atTime(14, 0), today.plusDays(1).atTime(12, 0)));
                success(r); inventory(r.data().asLong(), "R1", today, today.plusDays(1));
            } else {
                scheduler.releaseExpiredRooms(); assertThat(stock()).isEqualTo(before);
                assertThat(jdbc.queryForObject("select status from room where id=?", Integer.class, base.room(alias).id())).isEqualTo(2);
            }
            assertThat(row(id).get("status")).isEqualTo(1);
        }
        assertInventoryConsistent();
    }

    @ParameterizedTest @CsvSource({"extend,1,3,1,4,R1", "shrink,1,4,1,3,R1", "move,1,3,2,4,R1", "switch,1,3,1,3,R5"})
    void tc025_rescheduleChangesOnlyNightDifferenceOrSwitchesRoom(String action, int oldStart, int oldEnd, int start, int end, String room) {
        long id = order(login(base.userA()), "R1", oldStart, oldEnd); List<Map<String, Object>> before = stock();
        success(modify(id, room, start, end)); inventory(id, room, d.plusDays(start), d.plusDays(end));
        if (!action.equals("switch")) for (Map<String, Object> kept : before) {
            LocalDate date = LocalDate.parse((String) kept.get("stay_date"));
            if (!date.isBefore(d.plusDays(start)) && date.isBefore(d.plusDays(end))) assertThat(stock()).contains(kept);
        }
        assertThat(fx.count("room_order_night", "room_order_id=?", id)).isEqualTo(end - start);
        assertThat(jdbc.queryForList("select cast(night as char) from room_order_night where room_order_id=? order by night", String.class, id))
                .isEqualTo(d.plusDays(start).datesUntil(d.plusDays(end)).map(LocalDate::toString).toList());
        assertThat((BigDecimal) row(id).get("total_amount")).isEqualByComparingTo(BigDecimal.valueOf(199L * (end - start)));
        assertInventoryConsistent();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void tc026_conflictingExtensionOrRoomSwitchRestoresEveryOriginalRowAndId(boolean switchRoom) {
        long id = order(login(base.userA()), "R1", 1, 3);
        order(login(base.userB()), switchRoom ? "R5" : "R1", switchRoom ? 1 : 3, switchRoom ? 3 : 4);
        Map<String, List<Map<String, Object>>> before = snapshot();
        conflict(modify(id, switchRoom ? "R5" : "R1", 1, switchRoom ? 3 : 4), "已被预订");
        assertThat(snapshot()).isEqualTo(before); assertInventoryConsistent();
    }

    @ParameterizedTest @CsvSource({"search_available_rooms,1101,2,4,true", "search_available_rooms,1101,3,4,true",
            "get_price_quote,1101,2,4,false", "propose_booking,1101,2,4,false", "get_price_quote,1101,3,4,true",
            "propose_booking,1101,3,4,true", "search_available_rooms,5101,2,4,true", "get_price_quote,5101,2,4,true"})
    void tc027_agentSearchQuoteAndProposalUseInventoryWithoutWritingIt(String tool, String room, int start, int end, boolean allowed) throws Exception {
        seed("R1", d.plusDays(1), d.plusDays(3), 1, 0);
        Map<String, List<Map<String, Object>>> before = snapshot(); String a = login(base.userA());
        Chat chat = tool(a, tool, stay(room, start, end));
        assertThat(chat.output().path("ok").asBoolean()).as("%s", chat.output()).isEqualTo(allowed);
        if (!allowed) { assertThat(chat.output().path("error").asText()).contains("已被预订"); assertThat(chat.cards()).isEmpty(); }
        else if (tool.equals("search_available_rooms")) {
            List<String> numbers = new ArrayList<>(); chat.output().path("data").path("rooms").forEach(r -> numbers.add(r.path("roomNumber").asText()));
            if (room.equals("5101")) assertThat(numbers).contains("5101");
            else if (start == 2) assertThat(numbers).doesNotContain("1101").contains("1102", "1103", "2102");
            else assertThat(numbers).contains("1101");
        } else if (tool.equals("get_price_quote")) assertThat(chat.output().path("data").path("total").decimalValue()).isEqualByComparingTo(room.equals("5101") ? "998.00" : "199.00");
        else { assertThat(chat.cards()).hasSize(1); assertThat(chat.cards().get(0).path("type").asText()).isEqualTo("BOOKING"); }
        assertThat(snapshot()).isEqualTo(before); assertInventoryConsistent();
    }

    @Test void tc028_agentQuoteAndProposalNeverSelectForUpdate() throws Exception {
        String a = login(base.userA()); sql.reset();
        assertThat(tool(a, "get_price_quote", stay("1101", 1, 2)).output().path("ok").asBoolean()).isTrue();
        assertThat(tool(a, "propose_booking", stay("1102", 1, 2)).output().path("ok").asBoolean()).isTrue();
        assertThat(sql.statements()).noneMatch(s -> s.toLowerCase().contains("for update"));
        assertThat(fx.count("room_order")).isZero(); assertThat(stock()).isEmpty();
    }

    @Test void tc028_openQuoteTransactionDoesNotBlockBookingForThreeSeconds() {
        String a = login(base.userA()); var pool = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                assertThat(orders.quoteRoomService("1101", in(1), out(2)).getTotal()).isEqualByComparingTo("199.00");
                try { success(pool.submit(() -> book(a, "R1", 1, 2)).get(3, TimeUnit.SECONDS)); }
                catch (Exception e) { throw new AssertionError("booking must commit while quote transaction stays open", e); }
            });
            assertThat(fx.count("room_order")).isEqualTo(1); assertThat(fx.count("room_inventory")).isEqualTo(1); assertInventoryConsistent();
        } finally { pool.shutdownNow(); }
    }

    @ParameterizedTest @ValueSource(strings = {"trigger", "deadlock"})
    void tc033_secondInventoryNightFailureRollsBackGuestOrderNightsAndFirstInventoryNight(String fault) {
        String a = login(base.userA()); Map<String, List<Map<String, Object>>> before = snapshot();
        AtomicInteger calls = new AtomicInteger();
        if (fault.equals("trigger")) jdbc.execute("create trigger fail_second_inventory before insert on room_inventory for each row begin if new.stay_date='" + d.plusDays(2) + "' then signal sqlstate '45000' set message_text='injected inventory failure'; end if; end");
        else {
            // MyBatis 的 Mapper 是接口代理；第一晚委托同事务的真实 Mapper，第二晚才注入死锁。
            OrderMapper real = sessions.getMapper(OrderMapper.class);
            doAnswer(inv -> { if (calls.incrementAndGet() == 2) throw new DeadlockLoserDataAccessException("injected deadlock", new RuntimeException()); real.insertRoomInventory(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)); return null; })
                    .when(mapper).insertRoomInventory(anyLong(), any(), anyLong());
        }
        try {
            Map<String, Object> body = Fixtures.roomOrderBody("1101", in(1), out(4)); body.putAll(Fixtures.guest("P9"));
            Resp r = post("/order", a, body);
            assertThat(r.status()).isGreaterThanOrEqualTo(400); assertThat(r.code()).isNotZero();
            if (fault.equals("deadlock")) { conflict(r, "该时段预订繁忙，请稍后重试"); assertThat(r.msg()).isEqualTo("该时段预订繁忙，请稍后重试"); assertThat(calls).hasValue(2); }
            assertThat(snapshot()).isEqualTo(before); assertInventoryConsistent();
        } finally { if (fault.equals("trigger")) jdbc.execute("drop trigger fail_second_inventory"); else reset(mapper); }
    }

    @Test void tc031_lockFailureOutsideOccupyReturnsGeneric409AndRollsBackCancellation() {
        String a = login(base.userA()); long id = order(a, "R1", 1, 3); Map<String, List<Map<String, Object>>> before = snapshot();
        doThrow(new CannotAcquireLockException("injected release timeout")).when(mapper).deleteRoomInventory(id);
        try {
            Resp r = post("/order/cancel?id=" + id, a, null); conflict(r, "系统繁忙，请稍后重试");
            assertThat(r.msg()).isEqualTo("系统繁忙，请稍后重试"); assertThat(snapshot()).isEqualTo(before); assertInventoryConsistent();
        } finally { reset(mapper); }
    }

    @Test void tc034_refundRetainsNightPricesAndExcludesRevenueWhileReleasingInventory() {
        String a = login(base.userA()), manager = login(base.manager()); long id = order(a, "R1", 1, 3);
        success(post("/order/pay?id=" + id, a, null));
        assertThat(fx.count("room_order_night")).isEqualTo(2); assertThat(fx.count("room_inventory")).isEqualTo(2);
        Resp before = get("/business/revenue/stats?date=" + d.plusDays(1), manager); success(before); assertThat(before.data().path("today").decimalValue()).isEqualByComparingTo("199.00");
        List<Map<String, Object>> nights = jdbc.queryForList("select * from room_order_night order by id");
        success(post("/order/cancel?id=" + id, a, null));
        assertThat(row(id).get("status")).isEqualTo(2); assertThat(row(id).get("pay_status")).isEqualTo(2);
        assertThat(jdbc.queryForList("select * from room_order_night order by id")).isEqualTo(nights); assertThat(stock()).isEmpty();
        Resp after = get("/business/revenue/stats?date=" + d.plusDays(1), manager); success(after); assertThat(after.data().path("today").decimalValue()).isEqualByComparingTo("0"); assertInventoryConsistent();
    }

    @Test void tc036_fixtureWritesOnlyOngoingNightsAndIgnoresIntentionalSeedOverlap() {
        long ongoing = seed("R1", d.plusDays(1), d.plusDays(4), 0, 0);
        long cancelled = seed("R2", d.plusDays(1), d.plusDays(3), 0, 2), done = seed("R3", d.plusDays(1), d.plusDays(3), 1, 1);
        long overlap = seed("R1", d.plusDays(2), d.plusDays(4), 0, 0);
        inventory(ongoing, "R1", d.plusDays(1), d.plusDays(4));
        for (long id : List.of(cancelled, done, overlap)) assertThat(fx.count("room_inventory", "order_id=?", id)).isZero();
        assertThat(stock()).hasSize(3);
    }

    private LocalDateTime in(int day) { return d.plusDays(day).atTime(14, 0); }
    private LocalDateTime out(int day) { return d.plusDays(day).atTime(12, 0); }
    private Resp book(String token, String room, int start, int end) { return post("/order", token, Fixtures.roomOrderBody(base.room(room).number(), in(start), out(end))); }
    private long order(String token, String room, int start, int end) { Resp r = book(token, room, start, end); success(r); return r.data().asLong(); }
    private long frontOrder(String room, int start, int end) { success(book(login(base.front()), room, start, end)); return jdbc.queryForObject("select id from room_order where room_id=?", Long.class, base.room(room).id()); }
    private Resp modify(long id, String room, int start, int end) { return put("/order/" + id, login(base.front()), Map.of("roomId", Long.toString(base.room(room).id()), "checkInTime", Fixtures.iso(in(start)), "checkOutTime", Fixtures.iso(out(end)))); }
    private long seed(String room, LocalDate start, LocalDate end, int paid, int status) { return fx.roomOrder(base.userA().id(), jdbc.queryForObject("select id from individual where phone=?", Long.class, base.userA().login()), base.room(room).id(), start.atTime(14, 0), end.atTime(12, 0), BigDecimal.valueOf(199L * java.time.temporal.ChronoUnit.DAYS.between(start, end)), paid, status); }
    private Map<String, Object> row(long id) { return jdbc.queryForMap("select * from room_order where id=?", id); }
    private List<Map<String, Object>> stock() { return jdbc.queryForList("select id,room_id,cast(stay_date as char) as stay_date,order_id from room_inventory order by id"); }
    private Map<String, List<Map<String, Object>>> snapshot() { Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>(); for (String table : List.of("individual", "room_order", "room_order_night", "room_inventory")) result.put(table, jdbc.queryForList("select * from " + table + " order by id")); return result; }
    private void inventory(long orderId, String room, LocalDate start, LocalDate end) {
        List<Map<String, Object>> rows = stock().stream().filter(r -> ((Number) r.get("order_id")).longValue() == orderId).toList();
        assertThat(rows).extracting(r -> r.get("stay_date")).containsExactlyElementsOf(start.datesUntil(end).map(LocalDate::toString).toList());
        assertThat(rows).extracting(r -> ((Number) r.get("room_id")).longValue()).containsOnly(base.room(room).id());
    }
    private record Chat(JsonNode output, List<JsonNode> cards) {}
    private Chat tool(String token, String name, Map<String, Object> args) throws Exception {
        fake.reset(); Resp session = post("/agent/sessions", token, null); success(session);
        fake.enqueue(new AgentItem(AgentItem.Type.FUNCTION_CALL, null, "c1", name, json.writeValueAsString(args), null, null)); fake.enqueue(AgentItem.assistant("测试收尾"));
        HttpHeaders headers = new HttpHeaders(); headers.set("token", token); headers.setContentType(MediaType.APPLICATION_JSON);
        var r = rest.exchange("/agent/chat", HttpMethod.POST, new HttpEntity<>(Map.of("sessionId", session.data().path("sessionId").asText(), "message", "执行库存测试"), headers), String.class);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        List<String> events = new ArrayList<>(); List<JsonNode> cards = new ArrayList<>();
        for (String event : r.getBody().split("\n\n")) { String[] lines = event.split("\n"); String type = lines[0].substring(7); events.add(type); if (type.equals("card")) cards.add(json.readTree(lines[1].substring(6))); }
        assertThat(events).endsWith("done").doesNotContain("error");
        AgentItem output = fake.inputs().get(fake.inputs().size() - 1).stream().filter(i -> i.type() == AgentItem.Type.FUNCTION_CALL_OUTPUT).reduce((a, b) -> b).orElseThrow();
        return new Chat(json.readTree(output.output()), cards);
    }
    private String proposal(String token, String tool, Map<String, Object> args) throws Exception { Chat result = tool(token, tool, args); assertThat(result.output().path("ok").asBoolean()).as("%s", result.output()).isTrue(); assertThat(result.cards()).hasSize(1); return result.cards().get(0).path("actionId").asText(); }
    private Resp confirm(String token, String actionId) { return post("/agent/actions/" + actionId + "/confirm", token, null); }
    private Map<String, Object> stay(String room, int start, int end) { return Map.of("roomNumber", room, "checkInDate", d.plusDays(start).toString(), "checkOutDate", d.plusDays(end).toString()); }
    private void conflict(Resp r, String message) { assertThat(r.status()).as("%s", r.body()).isEqualTo(409); assertThat(r.code()).isEqualTo(1); assertThat(r.msg()).contains(message); }
    private <T> List<T> together(List<Callable<T>> jobs) throws Exception {
        var pool = Executors.newFixedThreadPool(jobs.size()); CountDownLatch ready = new CountDownLatch(jobs.size()), go = new CountDownLatch(1);
        try { List<Future<T>> futures = new ArrayList<>(); for (Callable<T> job : jobs) futures.add(pool.submit(() -> { ready.countDown(); go.await(); return job.call(); })); assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); go.countDown(); List<T> result = new ArrayList<>(); for (Future<T> f : futures) result.add(f.get(60, TimeUnit.SECONDS)); return result; }
        finally { go.countDown(); pool.shutdownNow(); }
    }
    private void assertInventoryConsistent() {
        assertThat(jdbc.queryForList("""
                SELECT o.id FROM room_order o
                WHERE o.status = 0 AND o.is_deleted = 0 AND o.room_id IS NOT NULL AND o.checkout_time > NOW()
                  AND (SELECT COUNT(*) FROM room_inventory i WHERE i.order_id = o.id AND i.room_id = o.room_id
                         AND i.stay_date >= DATE(o.checkin_time) AND i.stay_date < DATE(o.checkout_time))
                      <> DATEDIFF(DATE(o.checkout_time), DATE(o.checkin_time))
                """)).as("I1: effective orders have all their nights").isEmpty();
        assertThat(jdbc.queryForList("""
                SELECT i.id FROM room_inventory i LEFT JOIN room_order o ON o.id = i.order_id
                WHERE o.id IS NULL OR o.is_deleted = 1 OR o.status = 2
                """)).as("I2: no orphan, deleted or cancelled occupancy").isEmpty();
    }

    private void success(Resp r) {
        assertThat(r.status()).as("%s", r.body()).isEqualTo(200);
        assertThat(r.code()).as("%s", r.body()).isZero();
    }
}
