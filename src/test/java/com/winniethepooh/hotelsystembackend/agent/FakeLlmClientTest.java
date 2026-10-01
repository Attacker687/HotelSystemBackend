package com.winniethepooh.hotelsystembackend.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

class FakeLlmClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneId.of("Asia/Shanghai"));

    static Stream<Arguments> rules() throws Exception {
        String orders = JSON.writeValueAsString(Map.of("ok", true, "data", Map.of("roomOrders", List.of(
                order(105, "已支付", "进行中", LocalDateTime.now(CLOCK).minusHours(1)),
                order(104, "已支付", "进行中", LocalDateTime.now(CLOCK).plusDays(1)),
                order(103, "待支付", "进行中", LocalDateTime.now(CLOCK).plusDays(1)),
                order(102, "待支付", "已取消", LocalDateTime.now(CLOCK).plusDays(1)),
                order(101, "待支付", "进行中", LocalDateTime.now(CLOCK).plusDays(1))), "mealOrders", List.of())));
        String menu = "{\"ok\":true,\"data\":{\"dishes\":[{\"dishId\":1,\"name\":\"测试菜品X\",\"price\":\"38.00\",\"category\":\"测试分类\"}]}}";
        return Stream.of(
                row("剧本优先", "查一下13800000002订单", null, null, null, null, "剧本优先"),
                row("显式日期订房", "2026-10-09 到 2026-10-11 订 302", null, null, "propose_booking", Map.of("roomNumber", "302", "checkInDate", "2026-10-09", "checkOutDate", "2026-10-11"), null),
                row("缺日期订房", "订 302", null, null, "propose_booking", Map.of("roomNumber", "302", "checkInDate", "2026-10-02", "checkOutDate", "2026-10-03"), null),
                row("空房默认日期", "双人有空房吗", null, null, "search_available_rooms", Map.of("roomType", 1, "checkInDate", "2026-10-02", "checkOutDate", "2026-10-03"), null),
                row("空房显式日期", "2026-10-09 到 2026-10-11 单人有空房吗", null, null, "search_available_rooms", Map.of("roomType", 0, "checkInDate", "2026-10-09", "checkOutDate", "2026-10-11"), null),
                row("套房", "套房有空房吗", null, null, "search_available_rooms", Map.of("roomType", 2, "checkInDate", "2026-10-02", "checkOutDate", "2026-10-03"), null),
                row("手机号拒绝", "查一下13800000002的订单", null, null, null, null, "只能查看和操作您本人的订单"),
                row("他人拒绝", "查询他人订单", null, null, null, null, "只能查看和操作您本人的订单"),
                row("付款第一步", "把它付了", null, null, "list_my_orders", Map.of(), null),
                row("取消第一步", "取消这单", null, null, "list_my_orders", Map.of(), null),
                row("订单列表后付款", "把它付了", "list_my_orders", orders, "propose_payment", Map.of("orderId", 103), null),
                row("订单列表后取消", "取消这单", "list_my_orders", orders, "propose_cancel", Map.of("orderId", 104), null),
                row("点餐第一步", "来两份测试菜品X送到 1101", null, null, "list_menu", Map.of(), null),
                row("菜单后中文数量", "来两份测试菜品X送到 1101", "list_menu", menu, "propose_meal_order", Map.of("items", List.of(Map.of("dishId", 1, "quantity", 2)), "address", "1101"), null),
                row("菜单后数字数量", "来3份测试菜品X送到 1101", "list_menu", menu, "propose_meal_order", Map.of("items", List.of(Map.of("dishId", 1, "quantity", 3)), "address", "1101"), null),
                row("其他工具失败", "询价", "get_price_quote", "{\"ok\":false,\"error\":\"房间不存在\"}", null, null, "房间不存在"),
                row("提议结果", "订 1101", "propose_booking", "{\"ok\":true,\"data\":{\"actionId\":\"id\",\"total\":\"199.00\"},\"note\":\"确认后才执行\"}", null, null, "请核对卡片后点击确认"),
                row("其他消息", "你好", null, null, null, null, "查房询价|预订|支付|取消|查订单|点餐"));
    }

    private static Arguments row(String row, String user, String previous, String data, String next, Map<String, Object> args, String text) {
        return Arguments.of(row, user, previous, data, next, args, text);
    }
    private static Map<String, Object> order(int id, String pay, String status, LocalDateTime in) {
        return Map.of("orderId", id, "roomNumber", "1101", "checkIn", in.toString(), "checkOut", in.plusDays(1).toString(), "total", "199.00", "status", status, "payStatus", pay);
    }
    private static List<AgentItem> input(String user, String tool, String data) {
        if (tool == null) return List.of(AgentItem.user(user));
        return List.of(AgentItem.user(user), new AgentItem(AgentItem.Type.FUNCTION_CALL, null, "c1", tool, "{}", null, null),
                new AgentItem(AgentItem.Type.FUNCTION_CALL_OUTPUT, null, "c1", null, null, data, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rules")
    void tc050_allEighteenApprovedRules(String row, String user, String previous, String data, String next, Map<String, Object> args, String text) throws Exception {
        FakeLlmClient fake = new FakeLlmClient(JSON, CLOCK); fake.reset();
        if (row.equals("剧本优先")) fake.enqueue(AgentItem.assistant("剧本优先"));
        List<String> deltas = new ArrayList<>();
        List<AgentItem> items = input(user, previous, data);
        List<AgentItem> result = fake.respond("固定指令", items, deltas::add, Duration.ofSeconds(5));
        assertThat(fake.inputs()).containsExactly(items);
        assertThat(result).hasSize(1);
        if (next != null) {
            AgentItem call = result.get(0);
            assertThat(call.type()).isEqualTo(AgentItem.Type.FUNCTION_CALL); assertThat(call.callId()).isNotBlank(); assertThat(call.name()).isEqualTo(next);
            var actual = JSON.readTree(call.arguments());
            for (var field : args.entrySet()) assertThat(actual.path(field.getKey())).isEqualTo(JSON.valueToTree(field.getValue()));
            if (args.isEmpty()) assertThat(actual).isEqualTo(JSON.readTree("{}"));
            assertThat(deltas).isEmpty();
        } else {
            assertThat(result.get(0).type()).isEqualTo(AgentItem.Type.ASSISTANT);
            assertThat(String.join("", deltas)).isEqualTo(result.get(0).text());
            for (String part : text.split("\\|")) assertThat(result.get(0).text()).contains(part);
            assertThat(deltas).allSatisfy(delta -> assertThat(delta.length()).isBetween(2, 4));
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"get_price_quote", "search_available_rooms"})
    void s06ac5_quotesDescribeEveryNightAndTotal(String tool) throws Exception {
        String quote = "{\"roomNumber\":\"1101\",\"nights\":{\"2026-10-02\":\"199.00\",\"2026-10-03\":\"209.00\"},\"total\":\"408.00\"}";
        String data = "{\"ok\":true,\"data\":" + (tool.equals("get_price_quote") ? quote : "{\"rooms\":[" + quote + "]}") + "}";
        List<String> deltas = new ArrayList<>();
        var output = new FakeLlmClient(JSON, CLOCK).respond("指令", input("多少钱", tool, data), deltas::add, Duration.ofSeconds(5));
        assertThat(output.get(0).type()).isEqualTo(AgentItem.Type.ASSISTANT);
        assertThat(String.join("", deltas)).isEqualTo(output.get(0).text()).contains("1101", "2026-10-02", "199.00", "2026-10-03", "209.00", "408.00");
        assertThat(deltas).allSatisfy(delta -> assertThat(delta.length()).isBetween(2, 4));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"get_price_quote", "search_available_rooms"})
    void s07ac2_numericQuotesKeepTwoDecimalNightlyAndTotal(String tool) {
        String quote = "{\"roomNumber\":\"2101\",\"nights\":{\"2026-10-02\":299,\"2026-10-03\":299},\"total\":598}";
        String data = "{\"ok\":true,\"data\":" + (tool.equals("get_price_quote") ? quote : "{\"rooms\":[" + quote + "]}") + "}";
        List<String> deltas = new ArrayList<>();
        var output = new FakeLlmClient(JSON, CLOCK).respond("指令", input("多少钱", tool, data), deltas::add, Duration.ofSeconds(5));
        assertThat(String.join("", deltas)).isEqualTo(output.get(0).text())
                .contains("2101", "2026-10-02 299.00", "2026-10-03 299.00", "合计 598.00");
    }

    @Test
    void s06ac5_usesLatestUserAndLastItemRatherThanOldCall() {
        var fake = new FakeLlmClient(JSON, CLOCK);
        var items = new ArrayList<>(input("取消这单", "list_my_orders", "{\"ok\":true,\"data\":{\"roomOrders\":[]}}"));
        items.add(AgentItem.user("订 302"));
        assertThat(fake.respond("指令", items, ignored -> {}, Duration.ofSeconds(5)).get(0).name()).isEqualTo("propose_booking");
    }

    @Test
    void s06ac4_delaysRespectPassedBudgetWithoutEmittingLateText() {
        var fake = new FakeLlmClient(JSON, CLOCK); List<String> text = new ArrayList<>();
        fake.enqueueDelayed(Duration.ofMillis(150), AgentItem.assistant("迟到回复"));
        long start = System.nanoTime();
        assertThatThrownBy(() -> fake.respond("指令", List.of(AgentItem.user("你好")), text::add, Duration.ofMillis(30)))
                .isInstanceOfSatisfying(LlmException.class, error -> assertThat(error.isTimeout()).isTrue());
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(130));
        assertThat(text).isEmpty();
    }

    @Test
    void s06ac4_errorQueueHasPriorityAndResetClearsFaults() {
        var fake = new FakeLlmClient(JSON, CLOCK); LlmException error = new LlmException(false, new IllegalStateException("fault"));
        fake.enqueueError(error);
        assertThatThrownBy(() -> fake.respond("指令", List.of(AgentItem.user("订 302")), ignored -> {}, Duration.ofSeconds(5))).isSameAs(error);
        fake.enqueueError(error); fake.reset();
        assertThat(fake.inputs()).isEmpty();
        assertThat(fake.respond("指令", List.of(AgentItem.user("你好")), ignored -> {}, Duration.ofSeconds(5)).get(0).type()).isEqualTo(AgentItem.Type.ASSISTANT);
    }
}
