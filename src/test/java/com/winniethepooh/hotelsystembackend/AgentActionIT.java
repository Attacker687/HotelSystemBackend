package com.winniethepooh.hotelsystembackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.winniethepooh.hotelsystembackend.agent.AgentItem;
import com.winniethepooh.hotelsystembackend.agent.FakeLlmClient;
import com.winniethepooh.hotelsystembackend.support.Fixtures;
import com.winniethepooh.hotelsystembackend.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AgentActionIT extends IntegrationTestBase {
    @Autowired private FakeLlmClient fake;
    @Autowired private ObjectMapper json;
    private String token;
    private LocalDate today;
    private record Action(String id, String session, JsonNode card) {}

    @BeforeEach
    void prepare() { fake.reset(); token = login(base.userA()); today = LocalDate.now(ZoneId.of("Asia/Shanghai")); }

    @ParameterizedTest
    @ValueSource(strings = {"BOOKING", "PAYMENT", "CANCEL", "MEAL_ORDER"})
    void tc010_tc023_tc030_tc059_fourTypesConfirmExactlyOnceEvenWithoutRedisKey(String type) throws Exception {
        long original = 0; Map<String, Object> args;
        if (type.equals("BOOKING")) {
            args = stay("1101", 1, 2);
            Map<String, Object> b = user(base.userB().id());
            args.putAll(Map.of("userId", base.userB().id(), "guestName", b.get("name"), "guestPhone", b.get("phone"), "guestIdCard", b.get("id_card_number"), "phone", b.get("phone")));
        } else if (type.equals("MEAL_ORDER")) {
            args = meal(); args.put("items", List.of(Map.of("dishId", base.dish("X").id(), "quantity", 2, "price", 1, "unitPrice", 1, "totalPrice", 1))); args.put("totalAmount", 1);
        } else { original = order(base.userA().id(), 0); args = Map.of("orderId", original); }
        Action action = propose(type, args);
        assertThat(action.card().path("total").asText()).isEqualTo(type.equals("MEAL_ORDER") ? "76.00" : "199.00");
        Resp first = confirm(action.id(), token); confirmed(first, action, type);
        long id = first.data().path("orderId").asLong();
        if (original != 0) assertThat(id).isEqualTo(original);
        assertThat(redis.hasKey(key(action))).isFalse(); redis.delete(key(action));
        Map<String, List<Map<String, Object>>> before = businessSnapshot();
        Resp second = confirm(action.id(), token); confirmed(second, action, type);
        assertThat(second.data()).isEqualTo(first.data()); assertThat(businessSnapshot()).isEqualTo(before);
        request(action, "SUCCESS", id);
        if (type.equals("MEAL_ORDER")) {
            assertThat(fx.count("meal_order")).isEqualTo(1); assertThat(fx.count("meal_order_item")).isEqualTo(1);
            Map<String, Object> meal = jdbc.queryForMap("select * from meal_order where id=?", id);
            assertThat(meal.get("user_id")).isEqualTo(base.userA().id()); assertThat((BigDecimal) meal.get("total_amount")).isEqualByComparingTo("76.00");
            Map<String, Object> item = jdbc.queryForMap("select * from meal_order_item where meal_order_id=?", id);
            assertThat(((Number) item.get("dish_id")).longValue()).isEqualTo(base.dish("X").id()); assertThat(item.get("quantity")).isEqualTo(2);
            assertThat((BigDecimal) item.get("unit_price")).isEqualByComparingTo("38.00"); assertThat((BigDecimal) item.get("total_price")).isEqualByComparingTo("76.00");
            assertThat(first.data().path("message").asText()).contains("点餐成功", Long.toString(id));
        } else {
            assertThat(fx.count("room_order")).isEqualTo(1);
            Map<String, Object> room = jdbc.queryForMap("select * from room_order where id=?", id);
            assertThat(room.get("user_id")).isEqualTo(base.userA().id()); assertThat((BigDecimal) room.get("total_amount")).isEqualByComparingTo("199.00");
            assertThat(room.get("status")).isEqualTo(type.equals("CANCEL") ? 2 : 0); assertThat(room.get("pay_status")).isEqualTo(type.equals("PAYMENT") ? 1 : 0);
            if (type.equals("BOOKING")) {
                Map<String, Object> guest = jdbc.queryForMap("select * from individual where id=?", room.get("individual_id")); Map<String, Object> a = user(base.userA().id());
                assertThat(guest.get("name")).isEqualTo(a.get("name")); assertThat(guest.get("phone")).isEqualTo(a.get("phone")); assertThat(guest.get("id_card_number")).isEqualTo(a.get("id_card_number"));
                assertThat(fx.count("room_order", "user_id=?", base.userB().id())).isZero();
                assertThat(jdbc.queryForObject("select price from room_order_night where room_order_id=?", BigDecimal.class, id)).isEqualByComparingTo("199.00");
                assertThat(fx.count("room_order_night")).isEqualTo(1); assertThat(first.data().path("message").asText()).contains(Long.toString(id), "15 分钟");
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"PAYMENT,0,0,1,支付成功", "CANCEL,0,2,0,订单已取消", "CANCEL,1,2,2,订单已取消，已支付款项标记为已退款"})
    void tc060_statesAndExactMessagesIncludingRepeatedRefund(String type, int paid, int status, int payAfter, String message) throws Exception {
        long id = order(base.userA().id(), paid); Action action = propose(type, Map.of("orderId", id));
        Resp result = confirm(action.id(), token); confirmed(result, action, type);
        assertThat(result.data().path("orderId").asLong()).isEqualTo(id); assertThat(result.data().path("message").asText()).isEqualTo(message);
        Map<String, Object> row = jdbc.queryForMap("select * from room_order where id=?", id);
        assertThat(row.get("status")).isEqualTo(status); assertThat(row.get("pay_status")).isEqualTo(payAfter); assertThat(fx.count("room_order")).isEqualTo(1);
        request(action, "SUCCESS", id); Resp again = confirm(action.id(), token); confirmed(again, action, type); assertThat(again.data()).isEqualTo(result.data());
    }

    @Test
    void tc006_cancelledActionIsDurableAndRepeatedCancelDoesNotCreateAnOrder() throws Exception {
        Action a = propose("BOOKING", stay("1101", 1, 2));
        for (int i = 0; i < 2; i++) {
            Resp r = cancel(a.id(), token); success(r); assertThat(r.data()).isEqualTo(json.valueToTree(Map.of("actionId", a.id(), "status", "CANCELLED")));
        }
        request(a, "CANCELLED", null); assertThat(redis.hasKey(key(a))).isFalse(); rejected(confirm(a.id(), token), 404, "已失效"); assertThat(fx.count("room_order")).isZero();
    }

    @Test
    void tc007_actualExpiryWithoutRecordReturnsNotFoundForBothEndpoints() throws Exception {
        Action a = propose("BOOKING", stay("1101", 1, 2)); redis.expire(key(a), Duration.ofSeconds(1));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (Boolean.TRUE.equals(redis.hasKey(key(a))) && System.nanoTime() < deadline) Thread.sleep(40);
        assertThat(redis.hasKey(key(a))).isFalse(); rejected(confirm(a.id(), token), 404, "已失效"); rejected(cancel(a.id(), token), 404, "已失效");
        assertThat(fx.count("room_order")).isZero(); assertThat(fx.count("booking_request")).isZero();
    }

    @ParameterizedTest
    @CsvSource({"confirm,A,SUCCESS,200", "confirm,A,CANCELLED,404", "cancel,A,SUCCESS,409", "cancel,A,CANCELLED,200",
            "confirm,B,SUCCESS,403", "confirm,B,CANCELLED,403", "cancel,B,SUCCESS,403", "cancel,B,CANCELLED,403"})
    void tc008_recordDecisionsTakePriorityOverRedisOwnership(String endpoint, String owner, String state, int status) throws Exception {
        Action a = fixtureAction(base.userB().id()); String raw = redis.opsForValue().get(key(a));
        Map<String, Object> row = new LinkedHashMap<>(Map.of("request_id", a.id(), "user_id", owner.equals("A") ? base.userA().id() : base.userB().id(), "action_type", "BOOKING", "status", state));
        row.put("order_id", state.equals("SUCCESS") ? 123L : null); fx.insert("booking_request", row);
        Resp r = endpoint.equals("confirm") ? confirm(a.id(), token) : cancel(a.id(), token);
        if (status == 200) {
            success(r); assertThat(r.data().path("status").asText()).isEqualTo(endpoint.equals("confirm") ? "CONFIRMED" : "CANCELLED");
            if (endpoint.equals("confirm")) assertThat(r.data().path("orderId").asLong()).isEqualTo(123L);
        } else rejected(r, status, status == 403 ? "无权限" : status == 404 ? "已失效" : "已确认");
        assertThat(redis.opsForValue().get(key(a))).isEqualTo(raw); assertThat(fx.count("room_order")).isZero(); assertThat(fx.count("booking_request")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"confirm", "cancel"})
    void tc008_noRecordOrKeyReturnsSpecificInvalidMessage(String endpoint) {
        String id = UUID.randomUUID().toString(); rejected(endpoint.equals("confirm") ? confirm(id, token) : cancel(id, token), 404, "已失效");
        assertThat(fx.count("room_order")).isZero(); assertThat(fx.count("booking_request")).isZero();
    }

    @Test
    void tc020_otherUserCannotConsumeActionAndOwnerCanStillConfirm() throws Exception {
        Action a = propose("BOOKING", stay("1101", 1, 2)); String raw = redis.opsForValue().get(key(a)); String other = login(base.userB());
        rejected(confirm(a.id(), other), 403, "无权限"); rejected(cancel(a.id(), other), 403, "无权限");
        assertThat(redis.opsForValue().get(key(a))).isEqualTo(raw); assertThat(fx.count("room_order")).isZero(); assertThat(fx.count("booking_request")).isZero();
        Resp r = confirm(a.id(), token); confirmed(r, a, "BOOKING"); request(a, "SUCCESS", r.data().path("orderId").asLong());
        rejected(confirm(a.id(), other), 403, "无权限"); rejected(cancel(a.id(), other), 403, "无权限");
        rejected(cancel(a.id(), token), 409, "已确认"); assertThat(fx.count("room_order")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"350.00", "250.00"})
    void tc013_tc015_priceIncreaseAndDecreaseRollbackNewGuestOrderAndNights(String price) throws Exception {
        Action a = propose("BOOKING", stay("2101", 1, 3)); assertThat(a.card().path("total").asText()).isEqualTo("598.00"); calendar(price);
        // Force the existing service's create-individual branch; failed confirmation must roll it back too.
        jdbc.update("delete from individual where id_card_number=?", user(base.userA().id()).get("id_card_number"));
        Map<String, List<Map<String, Object>>> before = businessSnapshot();
        Resp r = confirm(a.id(), token); rejected(r, 409, "价格已变化"); assertThat(r.msg()).contains("598.00", price.equals("350.00") ? "649.00" : "549.00");
        invalidated(a, before);
    }

    @ParameterizedTest
    @CsvSource({"1101,2", "2101,3"})
    void tc014_tc015_anotherUserOccupiesRoomAndOnlyTheirCommittedOrderRemains(String room, int out) throws Exception {
        Action a = propose("BOOKING", stay(room, 1, out));
        Resp booking = post("/order", login(base.userB()), Fixtures.roomOrderBody(room, today.plusDays(1).atTime(14, 0), today.plusDays(out).atTime(12, 0))); success(booking);
        long id = booking.data().asLong(); Map<String, List<Map<String, Object>>> before = businessSnapshot();
        rejected(confirm(a.id(), token), 409, "已被预订"); invalidated(a, before);
        assertThat(fx.count("room_order")).isEqualTo(1); assertThat(jdbc.queryForObject("select user_id from room_order where id=?", Integer.class, id)).isEqualTo(base.userB().id());
        assertThat(fx.count("room_order", "user_id=?", base.userA().id())).isZero();
    }

    @Test
    void tc016_fiveConcurrentPriceConflictsNeverLeak500OrLeaveWrites() throws Exception {
        Action a = propose("BOOKING", stay("2101", 1, 3)); calendar("350.00"); Map<String, List<Map<String, Object>>> before = businessSnapshot();
        List<Callable<Resp>> jobs = new ArrayList<>(); for (int i = 0; i < 5; i++) jobs.add(() -> confirm(a.id(), token));
        for (Resp r : together(jobs)) { assertThat(r.status()).isIn(404, 409); assertThat(r.code()).isEqualTo(1); assertThat(r.msg()).containsAnyOf("价格已变化", "确认请求冲突", "已失效"); }
        invalidated(a, before);
    }

    @Test
    void tc009_tenConcurrentConfirmationsAllReturnOneCommittedOrder() throws Exception {
        Action a = propose("BOOKING", stay("1101", 1, 2)); List<Callable<Resp>> jobs = new ArrayList<>(); for (int i = 0; i < 10; i++) jobs.add(() -> confirm(a.id(), token));
        List<Resp> results = together(jobs); long id = results.get(0).data().path("orderId").asLong();
        for (Resp r : results) { confirmed(r, a, "BOOKING"); assertThat(r.data().path("orderId").asLong()).isEqualTo(id); }
        assertThat(id).isPositive(); assertThat(fx.count("room_order")).isEqualTo(1); assertThat(fx.count("room_order_night")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select total_amount from room_order where id=?", BigDecimal.class, id)).isEqualByComparingTo("199.00");
        assertThat(jdbc.queryForObject("select user_id from room_order where id=?", Integer.class, id)).isEqualTo(base.userA().id()); request(a, "SUCCESS", id);
    }

    @ParameterizedTest
    @ValueSource(ints = {1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20})
    void tc012_twentyIndependentConfirmCancelRacesHaveExactlyOneWinner(int iteration) throws Exception {
        Action a = fixtureAction(base.userA().id()); assertThat(fx.count("room_order")).isZero();
        List<Resp> r = together(List.of(() -> confirm(a.id(), token), () -> cancel(a.id(), token)));
        assertThat(r.stream().filter(x -> x.status() == 200).count()).isEqualTo(1);
        if (r.get(0).status() == 200) {
            confirmed(r.get(0), a, "BOOKING"); rejected(r.get(1), 409, "已确认"); assertThat(fx.count("room_order")).isEqualTo(1); request(a, "SUCCESS", r.get(0).data().path("orderId").asLong());
        } else { success(r.get(1)); rejected(r.get(0), 404, "已失效"); assertThat(fx.count("room_order")).isZero(); request(a, "CANCELLED", null); }
        assertThat(redis.hasKey(key(a))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void tc017_successAndFailureAppendNoteAfterProposalBeforeNextUserWithoutModelOrRateUse(boolean success) throws Exception {
        Action a = propose("BOOKING", stay("2101", 1, 3)); String historyKey = sessionKey(a);
        List<String> proposal = redis.opsForList().range(historyKey, 0, -1); assertThat(proposal).hasSize(4);
        if (!success) calendar("350.00");
        int calls = fake.inputs().size(); String rateKey = "agent:rate:" + base.userA().id(); redis.opsForValue().set(rateKey, "5", Duration.ofMinutes(1));
        Resp r = confirm(a.id(), token); if (success) confirmed(r, a, "BOOKING"); else rejected(r, 409, "价格已变化");
        assertThat(fake.inputs()).hasSize(calls); assertThat(redis.opsForValue().get(rateKey)).isEqualTo("5");
        List<String> rows = redis.opsForList().range(historyKey, 0, -1); assertThat(rows).hasSize(5); assertThat(rows.subList(0, 4)).isEqualTo(proposal);
        JsonNode note = json.readTree(rows.get(4)); assertThat(note.path("type").asText()).isEqualTo("NOTE");
        if (success) assertThat(note.path("text").asText()).startsWith("[系统通知] 住客已确认").contains(r.data().path("orderId").asText());
        else assertThat(note.path("text").asText()).contains("确认失败", "价格已变化");
        fake.enqueue(AgentItem.assistant("收到通知")); chat(a.session(), "下一轮"); assertThat(fake.inputs()).hasSize(calls + 1);
        List<AgentItem> next = fake.inputs().get(calls); assertThat(next).hasSize(6);
        assertThat(next.get(4).type()).isEqualTo(AgentItem.Type.NOTE); assertThat(next.get(4).text()).isEqualTo(note.path("text").asText());
        assertThat(next.get(5)).isEqualTo(AgentItem.user("下一轮"));
    }

    @Test
    void tc035_completeTurnAndConfirmationNoteRefreshThirtyMinuteTtl() throws Exception {
        String session = session(); fake.enqueue(AgentItem.assistant("首轮回复")); chat(session, "首轮");
        String historyKey = "agent:session:" + base.userA().id() + ":" + session; assertThat(redis.getExpire(historyKey)).isBetween(1700L, 1800L);
        Action a = propose("BOOKING", stay("1101", 1, 2), session); redis.expire(historyKey, Duration.ofSeconds(60)); assertThat(redis.getExpire(historyKey)).isBetween(55L, 60L);
        Resp r = confirm(a.id(), token); confirmed(r, a, "BOOKING"); assertThat(redis.getExpire(historyKey)).isBetween(1700L, 1800L);
        JsonNode last = json.readTree(redis.opsForList().index(historyKey, -1)); assertThat(last.path("type").asText()).isEqualTo("NOTE"); assertThat(last.path("text").asText()).contains(r.data().path("orderId").asText());
    }

    @Test
    void tc028_paymentExpiryAfterProposalRollsBackAndInvalidates() throws Exception {
        long id = order(base.userA().id(), 0); Action a = propose("PAYMENT", Map.of("orderId", id)); fx.createdMinutesAgo("room_order", id, 16);
        Map<String, List<Map<String, Object>>> before = businessSnapshot(); rejected(confirm(a.id(), token), 409, "超过支付期限"); invalidated(a, before);
    }

    @Test
    void tc029_frontDeskReschedulePriceChangePreventsPaymentAndPreservesReschedule() throws Exception {
        Resp created = post("/order", token, Fixtures.roomOrderBody("2101", today.plusDays(1).atTime(14, 0), today.plusDays(3).atTime(12, 0))); success(created); long id = created.data().asLong();
        Action a = propose("PAYMENT", Map.of("orderId", id)); assertThat(a.card().path("total").asText()).isEqualTo("598.00");
        success(put("/order/" + id, login(base.front()), Map.of("roomId", String.valueOf(base.room("R4").id()), "checkInTime", Fixtures.iso(today.plusDays(1).atTime(14, 0)), "checkOutTime", Fixtures.iso(today.plusDays(4).atTime(12, 0)))));
        assertThat(jdbc.queryForObject("select total_amount from room_order where id=?", BigDecimal.class, id)).isEqualByComparingTo("897.00");
        Map<String, List<Map<String, Object>>> before = businessSnapshot(); rejected(confirm(a.id(), token), 409, "价格已变化"); invalidated(a, before);
        assertThat(jdbc.queryForObject("select pay_status from room_order where id=?", Integer.class, id)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"offShelf", "price"})
    void tc032_mealChangesRollbackMasterAndDetails(String change) throws Exception {
        Action a = propose("MEAL_ORDER", meal()); assertThat(a.card().path("total").asText()).isEqualTo("76.00");
        if (change.equals("offShelf")) jdbc.update("update dish set status=0 where id=?", base.dish("X").id()); else jdbc.update("update dish set price=40 where id=?", base.dish("X").id());
        Map<String, List<Map<String, Object>>> before = businessSnapshot(); Resp r = confirm(a.id(), token); rejected(r, 409, change.equals("offShelf") ? "下架" : "价格已变化");
        if (change.equals("price")) assertThat(r.msg()).contains("76.00", "80.00"); invalidated(a, before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"roomDeleted", "dishDeleted", "pastDate", "paidElsewhere"})
    void tc061_deletedResourcesPastDateAndExternalPaymentInvalidateWithoutNewWrites(String change) throws Exception {
        long id = change.equals("paidElsewhere") ? order(base.userA().id(), 0) : 0;
        Action a = propose(change.equals("dishDeleted") ? "MEAL_ORDER" : change.equals("paidElsewhere") ? "PAYMENT" : "BOOKING",
                change.equals("dishDeleted") ? meal() : change.equals("paidElsewhere") ? Map.of("orderId", id) : stay("1101", 1, 2));
        String error; int status;
        switch (change) {
            case "roomDeleted" -> { success(delete("/rooms/" + base.room("R1").id(), login(base.manager()))); error = "房间不存在"; status = 404; }
            case "dishDeleted" -> { success(delete("/food/dish?id=" + base.dish("X").id(), login(base.manager()))); error = "菜品不存在"; status = 404; }
            case "pastDate" -> { ObjectNode raw = (ObjectNode) json.readTree(redis.opsForValue().get(key(a))); ((ObjectNode) raw.path("params")).put("checkIn", Fixtures.iso(today.minusDays(1).atTime(14, 0))); redis.opsForValue().set(key(a), json.writeValueAsString(raw), Duration.ofSeconds(redis.getExpire(key(a)))); error = "早于今天"; status = 400; }
            default -> { success(post("/order/pay?id=" + id, token, null)); error = "已支付"; status = 409; }
        }
        Map<String, List<Map<String, Object>>> before = businessSnapshot(); rejected(confirm(a.id(), token), status, error); invalidated(a, before);
        if (id != 0) assertThat(jdbc.queryForObject("select pay_status from room_order where id=?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void tc027_nullTotalOnlyRequiresUninterruptedChatAndNon500Confirmation() throws Exception {
        long id = order(base.userA().id(), 0); jdbc.update("update room_order set total_amount=null where id=?", id);
        String session = session(); fake.enqueue(new AgentItem(AgentItem.Type.FUNCTION_CALL, null, "c1", "propose_payment", json.writeValueAsString(Map.of("orderId", id)), null, null)); fake.enqueue(AgentItem.assistant("测试收尾"));
        List<JsonNode> cards = chat(session, "执行测试"); String action;
        if (!cards.isEmpty()) action = cards.get(0).path("actionId").asText();
        else {
            action = UUID.randomUUID().toString(); Map<String, Object> raw = new LinkedHashMap<>(Map.of("id", action, "userId", base.userA().id(), "sessionId", session, "type", "PAYMENT", "params", Map.of("orderId", id)));
            raw.put("total", null); raw.put("card", Map.of("actionId", action, "type", "PAYMENT", "status", "PENDING", "ttlSeconds", 600)); redis.opsForValue().set("agent:action:" + action, json.writeValueAsString(raw), Duration.ofMinutes(10));
        }
        assertThat(confirm(action, token).status()).isNotEqualTo(500);
    }

    @ParameterizedTest
    @CsvSource({"PAYMENT,B", "PAYMENT,front", "CANCEL,B", "CANCEL,front"})
    void s05ac4_orderOwnershipIsRecheckedForExternalUserAndNullUser(String type, String owner) throws Exception {
        long id = order(base.userA().id(), 0); Action a = propose(type, Map.of("orderId", id));
        jdbc.update("update room_order set user_id=? where id=?", owner.equals("B") ? base.userB().id() : null, id);
        Map<String, List<Map<String, Object>>> before = businessSnapshot(); rejected(confirm(a.id(), token), 403, "无权限"); invalidated(a, before);
    }

    @Test
    void s05ac4_cancelRechecksCheckinTimeAfterProposal() throws Exception {
        long id = order(base.userA().id(), 1); Action a = propose("CANCEL", Map.of("orderId", id)); jdbc.update("update room_order set checkin_time=? where id=?", LocalDateTime.now().minusMinutes(1), id);
        Map<String, List<Map<String, Object>>> before = businessSnapshot(); rejected(confirm(a.id(), token), 409, "尚未入住"); invalidated(a, before);
    }

    private Action propose(String type, Map<String, Object> args) throws Exception { return propose(type, args, session()); }
    private Action propose(String type, Map<String, Object> args, String session) throws Exception {
        String tool = switch (type) { case "BOOKING" -> "propose_booking"; case "PAYMENT" -> "propose_payment"; case "CANCEL" -> "propose_cancel"; default -> "propose_meal_order"; };
        fake.enqueue(new AgentItem(AgentItem.Type.FUNCTION_CALL, null, "c1", tool, json.writeValueAsString(args), null, null)); fake.enqueue(AgentItem.assistant("测试收尾"));
        List<JsonNode> cards = chat(session, "执行测试"); assertThat(cards).hasSize(1); JsonNode card = cards.get(0); assertThat(card.path("type").asText()).isEqualTo(type);
        List<AgentItem> input = fake.inputs().get(fake.inputs().size() - 1); AgentItem output = input.stream().filter(i -> i.type() == AgentItem.Type.FUNCTION_CALL_OUTPUT).reduce((a, b) -> b).orElseThrow();
        assertThat(json.readTree(output.output()).path("ok").asBoolean()).isTrue(); return new Action(card.path("actionId").asText(), session, card);
    }
    private List<JsonNode> chat(String session, String message) throws Exception {
        HttpHeaders headers = new HttpHeaders(); headers.set("token", token); headers.setContentType(MediaType.APPLICATION_JSON);
        var r = rest.exchange("/agent/chat", HttpMethod.POST, new HttpEntity<>(Map.of("sessionId", session, "message", message), headers), String.class); assertThat(r.getStatusCode().value()).isEqualTo(200);
        List<String> events = new ArrayList<>(); List<JsonNode> cards = new ArrayList<>();
        for (String event : r.getBody().split("\n\n")) {
            String[] lines = event.split("\n"); assertThat(lines).hasSize(2); String name = lines[0].substring("event: ".length()); JsonNode data = json.readTree(lines[1].substring("data: ".length())); events.add(name);
            if (name.equals("card")) cards.add(data);
        }
        assertThat(events).startsWith("status").endsWith("done").doesNotContain("error"); return cards;
    }
    private String session() { Resp r = post("/agent/sessions", token, null); success(r); return r.data().path("sessionId").asText(); }
    private Action fixtureAction(int owner) throws Exception {
        String id = UUID.randomUUID().toString(), session = session(); Map<String, Object> u = user(owner);
        var params = Map.of("roomNumber", "1101", "checkIn", Fixtures.iso(today.plusDays(1).atTime(14, 0)), "checkOut", Fixtures.iso(today.plusDays(2).atTime(12, 0)), "guestName", u.get("name"), "guestPhone", u.get("phone"), "guestIdCard", u.get("id_card_number"));
        var card = Map.of("actionId", id, "type", "BOOKING", "title", "预订确认", "status", "PENDING", "ttlSeconds", 600, "expiresAt", LocalDateTime.now().plusMinutes(10).toString(),
                "lines", List.of(List.of("房间", "1101 · 单人间 · 1 楼"), List.of("入住", today.plusDays(1) + " 14:00"), List.of("离店", today.plusDays(2) + " 12:00"), List.of("入住人", "本人（" + u.get("name") + "）")), "details", List.of(List.of(today.plusDays(1).toString(), "199.00")), "total", "199.00");
        redis.opsForValue().set("agent:action:" + id, json.writeValueAsString(Map.of("id", id, "userId", owner, "sessionId", session, "type", "BOOKING", "params", params, "total", new BigDecimal("199.00"), "card", card)), Duration.ofMinutes(10));
        return new Action(id, session, json.valueToTree(card));
    }
    private Map<String, Object> stay(String room, int in, int out) { return new LinkedHashMap<>(Map.of("roomNumber", room, "checkInDate", today.plusDays(in).toString(), "checkOutDate", today.plusDays(out).toString())); }
    private Map<String, Object> meal() { return new LinkedHashMap<>(Map.of("items", List.of(Map.of("dishId", base.dish("X").id(), "quantity", 2)), "address", "1101", "remarks", "")); }
    private Map<String, Object> user(int id) { return jdbc.queryForMap("select * from user where id=?", id); }
    private long order(Integer owner, int paid) { return fx.roomOrder(owner, jdbc.queryForObject("select id from individual where id_card_number=?", Long.class, user(base.userA().id()).get("id_card_number")), base.room("R1").id(), today.plusDays(1).atTime(14, 0), today.plusDays(2).atTime(12, 0), new BigDecimal("199.00"), paid, 0); }
    private void calendar(String price) { success(post("/business/calendar", login(base.manager()), Map.of("roomType", 1, "startDate", today.plusDays(1).toString(), "endDate", today.plusDays(1).toString(), "price", new BigDecimal(price)))); }
    private String key(Action a) { return "agent:action:" + a.id(); }
    private String sessionKey(Action a) { return "agent:session:" + base.userA().id() + ":" + a.session(); }
    private Resp confirm(String id, String auth) { return post("/agent/actions/" + id + "/confirm", auth, null); }
    private Resp cancel(String id, String auth) { return post("/agent/actions/" + id + "/cancel", auth, null); }
    private void success(Resp r) { assertThat(r.status()).as("%s", r.body()).isEqualTo(200); assertThat(r.code()).as("%s", r.body()).isZero(); }
    private void rejected(Resp r, int status, String message) { assertThat(r.status()).as("%s", r.body()).isEqualTo(status); assertThat(r.code()).isEqualTo(1); assertThat(r.msg()).contains(message); }
    private void confirmed(Resp r, Action a, String type) { success(r); assertThat(r.data().fieldNames()).toIterable().containsExactlyInAnyOrder("actionId", "type", "status", "orderId", "message"); assertThat(r.data().path("actionId").asText()).isEqualTo(a.id()); assertThat(r.data().path("type").asText()).isEqualTo(type); assertThat(r.data().path("status").asText()).isEqualTo("CONFIRMED"); assertThat(r.data().path("orderId").asLong()).isPositive(); }
    private void request(Action a, String state, Long id) { assertThat(fx.count("booking_request")).isEqualTo(1); Map<String, Object> r = jdbc.queryForMap("select * from booking_request where request_id=?", a.id()); assertThat(r.get("user_id")).isEqualTo(base.userA().id()); assertThat(r.get("status")).isEqualTo(state); assertThat(r.get("order_id")).isEqualTo(id); }
    private Map<String, List<Map<String, Object>>> businessSnapshot() { Map<String, List<Map<String, Object>>> r = new LinkedHashMap<>(); for (String table : List.of("room_order", "room_order_night", "individual", "meal_order", "meal_order_item")) r.put(table, jdbc.queryForList("select * from " + table + " order by id")); return r; }
    private void invalidated(Action a, Map<String, List<Map<String, Object>>> before) { assertThat(businessSnapshot()).isEqualTo(before); assertThat(fx.count("booking_request")).isZero(); assertThat(redis.hasKey(key(a))).isFalse(); rejected(confirm(a.id(), token), 404, "已失效"); assertThat(businessSnapshot()).isEqualTo(before); }
    private <T> List<T> together(List<Callable<T>> jobs) throws Exception {
        var pool = Executors.newFixedThreadPool(jobs.size()); CountDownLatch ready = new CountDownLatch(jobs.size()), go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>(); for (Callable<T> job : jobs) futures.add(pool.submit(() -> { ready.countDown(); go.await(); return job.call(); }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); go.countDown(); List<T> results = new ArrayList<>(); for (Future<T> future : futures) results.add(future.get(60, TimeUnit.SECONDS)); return results;
        } finally { go.countDown(); pool.shutdownNow(); }
    }
}
