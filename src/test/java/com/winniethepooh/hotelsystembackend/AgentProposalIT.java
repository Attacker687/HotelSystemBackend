package com.winniethepooh.hotelsystembackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AgentProposalIT extends IntegrationTestBase {
    @Autowired private FakeLlmClient fake;
    private final ObjectMapper json = new ObjectMapper();
    private String token;
    private LocalDate today;

    @BeforeEach
    void prepareModelAndUser() {
        fake.reset(); token = login(base.userA()); today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"BOOKING", "PAYMENT", "CANCEL", "MEAL_ORDER"})
    void tc005_fourProposalsCreatePendingCardsAndDoNotWriteOrders(String type) throws Exception {
        String tool; Map<String, Object> args;
        switch (type) {
            case "BOOKING" -> { tool = "propose_booking"; args = stay("1101", 1, 2); }
            case "PAYMENT", "CANCEL" -> {
                long order = order(base.userA().id(), 0, 0, new BigDecimal("199.00"));
                tool = type.equals("PAYMENT") ? "propose_payment" : "propose_cancel"; args = Map.of("orderId", order);
            }
            default -> { tool = "propose_meal_order"; args = meal(2); }
        }
        Chat chat = chat(List.of(tool), List.of(args));
        JsonNode action = pending(chat, type);
        assertThat(action.path("total").decimalValue()).isEqualByComparingTo(type.equals("MEAL_ORDER") ? "76.00" : "199.00");
        assertThat(action.path("card").path("total").asText()).isEqualTo(type.equals("MEAL_ORDER") ? "76.00" : "199.00");
        if (type.equals("BOOKING")) assertThat(action.path("params").path("roomNumber").asText()).isEqualTo("1101");
        if (type.equals("MEAL_ORDER")) {
            assertThat(action.path("card").path("details").toString()).isEqualTo("[[\"测试菜品X × 2\",\"76.00\"]]");
            assertThat(action.path("params").path("address").asText()).isEqualTo("1101");
        }
    }

    @ParameterizedTest
    @CsvSource({"payment,other,0,0", "payment,other,0,1", "payment,other,2,0", "payment,front,0,0",
            "cancel,other,0,0", "cancel,other,0,1", "cancel,other,2,0", "cancel,front,0,0"})
    void tc021_ownershipIsCheckedBeforeStateForBothProposalTools(String tool, String owner, int status, int paid) throws Exception {
        long id = order(owner.equals("front") ? null : base.userB().id(), status, paid, new BigDecimal("199.00"));
        Chat chat = chat(List.of("propose_" + tool), List.of(Map.of("orderId", id)));
        assertThat(chat.outputs().get(0).path("error").asText()).isEqualTo("无权限操作他人的订单");
        rejected(chat);
    }

    @ParameterizedTest
    @CsvSource({"payment,missing,订单不存在", "payment,paid,只能支付进行中、未支付的订单", "payment,cancelled,只能支付进行中、未支付的订单",
            "payment,finished,只能支付进行中、未支付的订单", "payment,expired,已超过支付期限", "cancel,missing,订单不存在",
            "cancel,checked-in,只能取消尚未入住的进行中订单", "cancel,cancelled,只能取消尚未入住的进行中订单", "cancel,finished,只能取消尚未入住的进行中订单"})
    void tc027_paymentAndCancelPrechecksReturnSpecificToolErrors(String tool, String state, String message) throws Exception {
        long id = state.equals("missing") ? 999999 : order(base.userA().id(), state.equals("cancelled") ? 2 : state.equals("finished") ? 1 : 0,
                state.equals("paid") || state.equals("finished") || state.equals("checked-in") ? 1 : 0, new BigDecimal("199.00"));
        if (state.equals("expired")) fx.createdMinutesAgo("room_order", id, 16);
        if (state.equals("checked-in")) jdbc.update("update room_order set checkin_time=? where id=?", LocalDateTime.now().minusMinutes(1), id);
        Chat chat = chat(List.of("propose_" + tool), List.of(Map.of("orderId", id)));
        assertThat(chat.outputs().get(0).path("error").asText()).contains(message);
        rejected(chat);
    }

    @Test
    void tc027_nullTotalProposalOnlyRequiresUninterruptedChat() throws Exception {
        long id = order(base.userA().id(), 0, 0, null);
        chat(List.of("propose_payment"), List.of(Map.of("orderId", id)));
        // 批准GAP-12：不补是否出卡/ok/金额口径；确认HTTP非500留S05。
    }

    @Test
    void tc023_bookingGuestIsCurrentUserDespiteForgedIdentity() throws Exception {
        Map<String, Object> b = jdbc.queryForMap("select * from user where id=?", base.userB().id());
        Map<String, Object> args = stay("1101", 1, 2);
        args.putAll(Map.of("userId", base.userB().id(), "guestName", b.get("name"), "guestPhone", b.get("phone"),
                "guestIdCard", b.get("id_card_number"), "phone", b.get("phone"), "idCard", b.get("id_card_number")));
        Chat chat = chat(List.of("propose_booking"), List.of(args));
        JsonNode action = pending(chat, "BOOKING");
        Map<String, Object> a = jdbc.queryForMap("select * from user where id=?", base.userA().id());
        assertThat(action.path("params").path("guestName").asText()).isEqualTo(a.get("name"));
        assertThat(action.path("params").path("guestPhone").asText()).isEqualTo(a.get("phone"));
        assertThat(action.path("params").path("guestIdCard").asText()).isEqualTo(a.get("id_card_number"));
        assertThat(chat.stream()).doesNotContain(a.get("id_card_number").toString(), b.get("id_card_number").toString(), a.get("password").toString());
        assertThat(chat.outputs().get(0).toString()).doesNotContain("guestIdCard", "phone", "idCard", "individualId", "password");
    }

    @Test
    void tc025_orderLookupAndBookingOutputsAndCardsContainNoSensitiveFields() throws Exception {
        order(base.userA().id(), 0, 0, new BigDecimal("199.00"));
        Chat chat = chat(List.of("list_my_orders", "propose_booking"), List.of(Map.of(), stay("2101", 1, 3)));
        pending(chat, "BOOKING");
        Map<String, Object> a = jdbc.queryForMap("select * from user where id=?", base.userA().id());
        List<String> forbidden = List.of("individualId", "individual_id", "password", "idCard", "idCardNumber", "id_card_number", "guestIdCard");
        for (JsonNode output : chat.outputs()) {
            assertThat(output.toString()).doesNotContain(a.get("id_card_number").toString(), a.get("password").toString());
            for (String field : forbidden) assertThat(output.toString()).doesNotContain("\"" + field + "\"");
        }
        assertThat(chat.stream()).doesNotContain(a.get("id_card_number").toString(), a.get("password").toString());
        for (JsonNode card : chat.cards()) for (String field : forbidden) assertThat(card.toString()).doesNotContain("\"" + field + "\"");
        assertThat(chat.outputs().get(0).path("data").path("roomOrders").get(0).path("roomNumber").asText()).isEqualTo("1101");
    }

    @Test
    void tc030_mealCardUsesServerPricesDespiteOneYuanClientPrices() throws Exception {
        Map<String, Object> args = meal(2);
        args.put("items", List.of(Map.of("dishId", base.dish("X").id(), "quantity", 2, "price", 1, "unitPrice", 1, "totalPrice", 1)));
        args.put("totalAmount", 1); args.put("total", 1);
        JsonNode action = pending(chat(List.of("propose_meal_order"), List.of(args)), "MEAL_ORDER");
        assertThat(action.path("total").decimalValue()).isEqualByComparingTo("76.00");
        assertThat(action.path("card").path("total").asText()).isEqualTo("76.00");
        assertThat(action.path("card").path("details").toString()).isEqualTo("[[\"测试菜品X × 2\",\"76.00\"]]");
        assertThat(action.path("params").path("items").get(0).size()).isEqualTo(2);
    }

    @ParameterizedTest
    @CsvSource({"Z,菜品已下架", "Y,菜品不存在", "missing,菜品不存在"})
    void tc031_offShelfDeletedOrMissingDishesCannotBeProposed(String dish, String error) throws Exception {
        Map<String, Object> args = meal(1); args.put("items", List.of(Map.of("dishId", dish.equals("missing") ? 999 : base.dish(dish).id(), "quantity", 1)));
        Chat chat = chat(List.of("propose_meal_order"), List.of(args));
        assertThat(chat.outputs().get(0).path("error").asText()).contains(error);
        rejected(chat);
    }

    @Test
    void tc062_overlappingBookingCannotCreateAnAction() throws Exception {
        long id = order(base.userA().id(), 0, 0, new BigDecimal("398.00"));
        jdbc.update("update room_order set checkout_time=? where id=?", today.plusDays(3).atTime(12, 0), id);
        Chat chat = chat(List.of("propose_booking"), List.of(stay("1101", 2, 4)));
        assertThat(chat.outputs().get(0).path("error").asText()).contains("已被预订"); rejected(chat);
    }

    @Test
    void tc064_bookingCardExactlyMatchesQuotationAndAllDisplayFields() throws Exception {
        Map<String, Object> args = stay("2101", 1, 3);
        Chat chat = chat(List.of("get_price_quote", "propose_booking"), List.of(args, args));
        JsonNode action = pending(chat, "BOOKING"), card = action.path("card"), quote = chat.outputs().get(0).path("data");
        assertThat(card.path("total").asText()).isEqualTo("598.00");
        assertThat(action.path("total").decimalValue()).isEqualByComparingTo(quote.path("total").decimalValue());
        assertThat(card.path("lines")).isEqualTo(json.valueToTree(List.of(List.of("房间", "2101 · 双人间 · 2 楼"),
                List.of("入住", today.plusDays(1) + " 14:00"), List.of("离店", today.plusDays(3) + " 12:00"), List.of("入住人", "本人（测试住客甲）"))));
        assertThat(card.path("details")).isEqualTo(json.valueToTree(List.of(List.of(today.plusDays(1).toString(), "299.00"), List.of(today.plusDays(2).toString(), "299.00"))));
        for (JsonNode detail : card.path("details")) assertThat(new BigDecimal(detail.get(1).asText()))
                .isEqualByComparingTo(quote.path("nights").path(detail.get(0).asText()).decimalValue());
        assertThat(action.path("params").path("checkIn").asText()).isEqualTo(Fixtures.iso(today.plusDays(1).atTime(14, 0)));
        assertThat(action.path("params").path("checkOut").asText()).isEqualTo(Fixtures.iso(today.plusDays(3).atTime(12, 0)));
    }

    @Test
    void tc064_paidCancelCardExplainsRefundWithoutChangingOrder() throws Exception {
        long id = order(base.userA().id(), 0, 1, new BigDecimal("199.00"));
        JsonNode action = pending(chat(List.of("propose_cancel"), List.of(Map.of("orderId", id))), "CANCEL");
        assertThat(action.path("card").path("lines").toString()).contains("已付款项将标记为已退款");
        assertThat(action.path("params").path("orderId").asLong()).isEqualTo(id);
    }

    private record Chat(String session, String stream, List<JsonNode> cards, List<JsonNode> outputs) {}

    private Chat chat(List<String> tools, List<Map<String, Object>> args) throws Exception {
        Map<String, List<Map<String, Object>>> before = snapshots();
        Resp session = post("/agent/sessions", token, null); assertThat(session.status()).isEqualTo(200); assertThat(session.code()).isZero();
        String id = session.data().path("sessionId").asText();
        for (int i = 0; i < tools.size(); i++) fake.enqueue(new AgentItem(AgentItem.Type.FUNCTION_CALL, null, "c" + (i + 1), tools.get(i), json.writeValueAsString(args.get(i)), null, null));
        fake.enqueue(AgentItem.assistant("测试收尾"));
        HttpHeaders headers = new HttpHeaders(); headers.set("token", token); headers.setContentType(MediaType.APPLICATION_JSON);
        var response = rest.exchange("/agent/chat", HttpMethod.POST, new HttpEntity<>(Map.of("sessionId", id, "message", "执行测试"), headers), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        List<String> events = new ArrayList<>(); List<JsonNode> cards = new ArrayList<>(); StringBuilder text = new StringBuilder();
        for (String event : response.getBody().split("\n\n")) {
            String[] lines = event.split("\n"); assertThat(lines).hasSize(2);
            String type = lines[0].substring("event: ".length()); JsonNode data = json.readTree(lines[1].substring("data: ".length())); events.add(type);
            if (type.equals("card")) cards.add(data);
            if (type.equals("delta")) text.append(data.path("text").asText());
            if (type.equals("done")) assertThat(data.path("toolCalls").asInt()).isEqualTo(tools.size());
        }
        assertThat(events).startsWith("status").endsWith("done").doesNotContain("error"); assertThat(text).hasToString("测试收尾");
        assertThat(fake.inputs()).hasSize(tools.size() + 1);
        List<JsonNode> outputs = new ArrayList<>();
        for (AgentItem item : fake.inputs().get(tools.size())) if (item.type() == AgentItem.Type.FUNCTION_CALL_OUTPUT) outputs.add(json.readTree(item.output()));
        assertThat(outputs).hasSize(tools.size()); assertThat(snapshots()).isEqualTo(before);
        return new Chat(id, response.getBody(), cards, outputs);
    }

    private JsonNode pending(Chat chat, String type) throws Exception {
        JsonNode output = chat.outputs().get(chat.outputs().size() - 1);
        assertThat(output.path("ok").asBoolean()).as("%s", output).isTrue();
        assertThat(output.path("note").asText()).contains("确认", "才会执行"); assertThat(chat.cards()).hasSize(1);
        JsonNode card = chat.cards().get(0); String id = card.path("actionId").asText(); assertThat(UUID.fromString(id).toString()).isEqualTo(id);
        assertThat(card.path("type").asText()).isEqualTo(type); assertThat(card.path("status").asText()).isEqualTo("PENDING");
        assertThat(card.path("ttlSeconds").asInt()).isEqualTo(600);
        assertThat(LocalDateTime.parse(card.path("expiresAt").asText())).isBetween(LocalDateTime.now().plusMinutes(9), LocalDateTime.now().plusMinutes(11));
        assertThat(card.path("title").asText()).isEqualTo(switch (type) { case "BOOKING" -> "预订确认"; case "PAYMENT" -> "支付确认"; case "CANCEL" -> "取消确认"; default -> "点餐确认"; });
        List<String> fields = new ArrayList<>(); card.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("actionId", "type", "title", "status", "ttlSeconds", "expiresAt", "lines", "details", "total");
        assertThat(redis.keys("agent:action:*")).containsExactly("agent:action:" + id);
        assertThat(redis.getExpire("agent:action:" + id)).isBetween(590L, 600L);
        JsonNode action = json.readTree(redis.opsForValue().get("agent:action:" + id));
        assertThat(action.path("id").asText()).isEqualTo(id); assertThat(action.path("userId").asInt()).isEqualTo(base.userA().id());
        assertThat(action.path("sessionId").asText()).isEqualTo(chat.session()); assertThat(action.path("type").asText()).isEqualTo(type);
        assertThat(action.path("card")).isEqualTo(card); assertThat(output.path("data").path("actionId").asText()).isEqualTo(id);
        assertThat(output.path("data").path("expiresInSeconds").asInt()).isEqualTo(600);
        assertThat(jdbc.queryForObject("select count(*) from booking_request", Integer.class)).isZero();
        return action;
    }

    private void rejected(Chat chat) {
        assertThat(chat.outputs().get(0).path("ok").asBoolean()).isFalse(); assertThat(chat.cards()).isEmpty();
        assertThat(redis.keys("agent:action:*")).isEmpty(); assertThat(jdbc.queryForObject("select count(*) from booking_request", Integer.class)).isZero();
    }

    private Map<String, Object> stay(String room, int from, int to) {
        Map<String, Object> args = new LinkedHashMap<>(); args.put("roomNumber", room);
        args.put("checkInDate", today.plusDays(from).toString()); args.put("checkOutDate", today.plusDays(to).toString()); return args;
    }

    private Map<String, Object> meal(int quantity) {
        Map<String, Object> args = new LinkedHashMap<>(); args.put("items", List.of(Map.of("dishId", base.dish("X").id(), "quantity", quantity)));
        args.put("address", "1101"); args.put("remarks", ""); return args;
    }

    private long order(Integer user, int status, int paid, BigDecimal total) {
        String phone = user != null && user.equals(base.userB().id()) ? base.userB().login() : base.userA().login();
        long individual = jdbc.queryForObject("select id from individual where phone=?", Long.class, phone);
        return fx.roomOrder(user, individual, base.room("R1").id(), today.plusDays(1).atTime(14, 0), today.plusDays(2).atTime(12, 0), total, paid, status);
    }

    private Map<String, List<Map<String, Object>>> snapshots() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String table : List.of("room_order", "room_order_night", "meal_order", "meal_order_item")) result.put(table, jdbc.queryForList("select * from " + table + " order by id"));
        return result;
    }
}
