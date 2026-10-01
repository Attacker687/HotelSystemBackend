package com.winniethepooh.hotelsystembackend.agent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(prefix = "hotel.agent", name = "provider", havingValue = "fake")
public class FakeLlmClient implements LlmClient {
    private record Script(Duration delay, List<AgentItem> output, LlmException error) {}
    private final Queue<Script> scripts = new ConcurrentLinkedQueue<>();
    private final List<List<AgentItem>> inputs = new ArrayList<>();
    private final ObjectMapper json;
    private final Clock clock;

    public FakeLlmClient() { this(new ObjectMapper(), Clock.system(ZoneId.of("Asia/Shanghai"))); }
    FakeLlmClient(ObjectMapper json, Clock clock) { this.json = json; this.clock = clock; }

    public void enqueue(AgentItem... output) { enqueueDelayed(Duration.ZERO, output); }
    public void enqueueDelayed(Duration delay, AgentItem... output) { scripts.add(new Script(delay, List.of(output), null)); }
    public void enqueueError(LlmException error) { scripts.add(new Script(Duration.ZERO, List.of(), error)); }
    public synchronized List<List<AgentItem>> inputs() { return List.copyOf(inputs); }
    public synchronized void reset() { scripts.clear(); inputs.clear(); }
    @Override public boolean available() { return true; }

    @Override
    public List<AgentItem> respond(String instructions, List<AgentItem> input, Consumer<String> onTextDelta, Duration timeout) {
        synchronized (this) { inputs.add(List.copyOf(input)); }
        long started = System.nanoTime();
        checkBudget(started, timeout);
        Script script = scripts.poll();
        if (script != null && !script.delay().isZero()) {
            long nanos = Math.min(script.delay().toNanos(), timeout.toNanos() - (System.nanoTime() - started));
            if (nanos > 0) {
                try { Thread.sleep(nanos / 1_000_000, (int) (nanos % 1_000_000)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new LlmException(true, e); }
            }
            checkBudget(started, timeout);
        }
        if (script != null && script.error() != null) throw script.error();
        List<AgentItem> output = script == null ? List.of(rule(input)) : script.output();
        for (AgentItem item : output) {
            if (item.type() == AgentItem.Type.ASSISTANT && item.text() != null) {
                for (int start = 0; start < item.text().length();) {
                    checkBudget(started, timeout);
                    int end = item.text().length() - start <= 4 ? item.text().length() : start + 3;
                    onTextDelta.accept(item.text().substring(start, end)); start = end;
                }
            }
        }
        checkBudget(started, timeout);
        return output;
    }

    private void checkBudget(long started, Duration timeout) {
        if (timeout.isNegative() || timeout.isZero() || System.nanoTime() - started >= timeout.toNanos()) throw new LlmException(true, null);
    }

    private AgentItem rule(List<AgentItem> input) {
        String message = input.stream().filter(i -> i.type() == AgentItem.Type.USER).reduce((a, b) -> b).map(AgentItem::text).orElse("");
        AgentItem last = input.isEmpty() ? null : input.get(input.size() - 1);
        if (last != null && last.type() == AgentItem.Type.FUNCTION_CALL_OUTPUT) {
            String tool = input.stream().filter(i -> i.type() == AgentItem.Type.FUNCTION_CALL && last.callId().equals(i.callId()))
                    .reduce((a, b) -> b).map(AgentItem::name).orElse("");
            JsonNode result;
            try { result = json.readTree(last.output()); }
            catch (JsonProcessingException e) { throw new LlmException(false, e); }
            if (!result.path("ok").asBoolean()) return AgentItem.assistant(result.path("error").asText("工具暂不可用"));
            JsonNode data = result.path("data");
            if (tool.equals("list_my_orders") && (message.contains("取消") || message.contains("付"))) {
                boolean cancel = message.contains("取消");
                for (JsonNode order : data.path("roomOrders")) {
                    if (!order.path("status").asText().equals("进行中")) continue;
                    if (cancel ? LocalDateTime.parse(order.path("checkIn").asText()).isAfter(LocalDateTime.now(clock))
                            : order.path("payStatus").asText().equals("待支付"))
                        return call(cancel ? "propose_cancel" : "propose_payment", Map.of("orderId", order.path("orderId").asLong()));
                }
                return AgentItem.assistant(cancel ? "没有可以取消的未入住订单。" : "没有可以支付的待支付订单。");
            }
            if (tool.equals("list_menu")) {
                List<Map<String, Object>> items = new ArrayList<>();
                var quantity = Pattern.compile("(\\d+|[一二两三四五六七八九十])\\s*份").matcher(message);
                int count = 1;
                if (quantity.find()) {
                    String n = quantity.group(1);
                    if (n.matches("\\d+")) {
                        n = n.replaceFirst("^0+(?!$)", "");
                        if (n.length() > 2 || Integer.parseInt(n) < 1 || Integer.parseInt(n) > 20)
                            return AgentItem.assistant("点餐数量必须为1至20份，请重新选择数量。");
                        count = Integer.parseInt(n);
                    } else count = n.equals("两") ? 2 : "一二三四五六七八九十".indexOf(n) + 1;
                }
                for (JsonNode dish : data.path("dishes")) if (message.contains(dish.path("name").asText()))
                    items.add(Map.of("dishId", dish.path("dishId").asLong(), "quantity", count));
                var address = Pattern.compile("送到\\s*(.+)").matcher(message);
                return call("propose_meal_order", Map.of("items", items, "address", address.find() ? address.group(1).trim() : "", "remarks", ""));
            }
            if (tool.startsWith("propose_")) return AgentItem.assistant("请核对卡片后点击确认，确认后才会执行。");
            if (tool.equals("get_price_quote")) return AgentItem.assistant(describeQuote(data));
            if (tool.equals("search_available_rooms")) {
                StringBuilder text = new StringBuilder(); for (JsonNode room : data.path("rooms")) text.append(describeQuote(room)).append("\n");
                return AgentItem.assistant(text.isEmpty() ? "这段日期没有可订的客房。" : text.toString());
            }
            return AgentItem.assistant("已读取您的订单信息，请说明需要支付还是取消。");
        }
        if (Pattern.compile("\\d{11}").matcher(message).find() || message.contains("别人") || message.contains("他人"))
            return AgentItem.assistant("只能查看和操作您本人的订单");
        var booking = Pattern.compile("订\\s*(\\d{3,5})(?!\\d)").matcher(message);
        Map<String, Object> dates = new LinkedHashMap<>();
        var explicit = Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(message); List<String> found = new ArrayList<>();
        while (explicit.find()) found.add(explicit.group());
        LocalDate today = LocalDate.now(clock);
        dates.put("checkInDate", found.size() >= 2 ? found.get(0) : today.plusDays(1).toString());
        dates.put("checkOutDate", found.size() >= 2 ? found.get(1) : today.plusDays(2).toString());
        if (booking.find()) { dates.put("roomNumber", booking.group(1)); return call("propose_booking", dates); }
        if (message.contains("有房") || message.contains("空房") || message.contains("多少钱")) {
            dates.put("roomType", message.contains("单人") ? 0 : message.contains("双人") ? 1 : message.contains("套房") ? 2 : null);
            return call("search_available_rooms", dates);
        }
        if (message.contains("取消") || message.contains("付")) return call("list_my_orders", Map.of());
        if (message.contains("份")) return call("list_menu", Map.of());
        return AgentItem.assistant("我可以帮您查房询价、预订、支付、取消客房订单、查订单和点餐。");
    }

    private String describeQuote(JsonNode quote) {
        StringBuilder text = new StringBuilder("房间 ").append(quote.path("roomNumber").asText()).append("：");
        quote.path("nights").fields().forEachRemaining(night -> text.append(night.getKey()).append(" ").append(night.getValue().asText()).append("元；"));
        return text.append("合计 ").append(quote.path("total").asText()).append("元。").toString();
    }

    private AgentItem call(String name, Map<String, Object> args) {
        try { return new AgentItem(AgentItem.Type.FUNCTION_CALL, null, UUID.randomUUID().toString(), name, json.writeValueAsString(args), null, null); }
        catch (JsonProcessingException e) { throw new LlmException(false, e); }
    }
}
