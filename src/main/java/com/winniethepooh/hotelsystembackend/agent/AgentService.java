package com.winniethepooh.hotelsystembackend.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.winniethepooh.hotelsystembackend.exception.BusinessException;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
public class AgentService {
    private final AgentProperties props;
    private final LlmClient llm;
    private final ObjectMapper json;
    private final SessionStore sessions;
    private final AgentTools tools;
    private final StringRedisTemplate redis;
    private final Clock clock;
    private final String fixedPrompt;

    @Autowired
    public AgentService(AgentProperties props, LlmClient llm, ObjectMapper json, SessionStore sessions, AgentTools tools, StringRedisTemplate redis) {
        this(props, llm, json, sessions, tools, redis, Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    AgentService(AgentProperties props, LlmClient llm, ObjectMapper json, SessionStore sessions, AgentTools tools, StringRedisTemplate redis, Clock clock) {
        this.props = props;
        this.llm = llm;
        this.json = json;
        this.sessions = sessions;
        this.tools = tools;
        this.redis = redis;
        this.clock = clock;
        try (var resource = new ClassPathResource("agent/system-prompt.txt").getInputStream()) {
            fixedPrompt = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    String instructions() {
        LocalDate today = LocalDate.now(clock);
        return fixedPrompt + "今天是 " + today + "（" + today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA) + "），时区 Asia/Shanghai";
    }

    public void chat(Integer userId, String sessionId, String message, HttpServletResponse response) {
        if (!llm.available()) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "智能助手暂不可用，请稍后再试");
        String rateKey = "agent:rate:" + userId;
        Long count = redis.opsForValue().increment(rateKey);
        if (count != null && count == 1) redis.expire(rateKey, Duration.ofSeconds(60));
        if (count != null && count > props.getRatePerMinute())
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "消息太频繁，请稍后再试");
        response.setContentType("text/event-stream");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-cache");
        long started = System.nanoTime();
        int toolCalls = 0;
        try {
            send(response, "status", Map.of("text", "正在思考…"));
            try {
                List<AgentItem> turn = new ArrayList<>(List.of(AgentItem.user(message)));
                List<AgentItem> input = new ArrayList<>(sessions.window(userId, sessionId));
                input.addAll(turn);
                AgentTools.ToolContext ctx = new AgentTools.ToolContext(userId, sessionId);
                while (true) {
                    List<AgentItem> output = llm.respond(instructions(), input, text -> {
                        try { send(response, "delta", Map.of("text", text)); }
                        catch (IOException e) { throw new UncheckedIOException(e); }
                    }, remaining(started));
                    remaining(started);
                    turn.addAll(output); input.addAll(output);
                    List<AgentItem> calls = output.stream().filter(item -> item.type() == AgentItem.Type.FUNCTION_CALL).toList();
                    if (calls.isEmpty()) break;
                    boolean limit = false;
                    for (AgentItem call : calls) {
                        remaining(started);
                        String result;
                        if (toolCalls >= props.getMaxToolCalls()) {
                            result = "{\"ok\":false,\"error\":\"未执行：超过本轮工具调用上限\"}";
                            limit = true;
                        } else {
                            send(response, "status", Map.of("tool", call.name(), "text", toolStatus(call.name())));
                            toolCalls++;
                            AgentTools.ToolResult execution = tools.execute(call.name(), call.arguments(), ctx);
                            result = execution.output();
                            if (execution.card() != null) send(response, "card", execution.card());
                        }
                        AgentItem item = new AgentItem(AgentItem.Type.FUNCTION_CALL_OUTPUT, null, call.callId(), null, null, result, null);
                        turn.add(item); input.add(item);
                    }
                    if (limit) {
                        String text = "这个问题需要的步骤太多了，请换种说法或拆成几步问我。";
                        turn.add(AgentItem.assistant(text));
                        send(response, "delta", Map.of("text", text));
                        send(response, "error", Map.of("code", "TOOL_LIMIT", "msg", text));
                        break;
                    }
                }
                remaining(started);
                sessions.append(userId, sessionId, turn);
            } catch (LlmException e) {
                send(response, "error", Map.of("code", e.isTimeout() ? "TIMEOUT" : "MODEL_UNAVAILABLE", "msg", e.getMessage()));
            } catch (UncheckedIOException e) { return; }
            catch (RuntimeException e) {
                log.error("agent.chat user={} failed cause={}", userId, e.getClass().getSimpleName());
                send(response, "error", Map.of("code", "INTERNAL", "msg", "系统繁忙，请稍后再试"));
            }
            send(response, "done", Map.of("toolCalls", toolCalls));
        } catch (IOException e) { /* 浏览器断开，结束本次请求。 */ }
    }

    private Duration remaining(long started) {
        Duration remaining = Duration.ofSeconds(props.getTimeoutSeconds()).minusNanos(System.nanoTime() - started);
        if (remaining.isZero() || remaining.isNegative()) throw new LlmException(true, null);
        return remaining;
    }

    private String toolStatus(String name) {
        return switch (name) {
            case "search_available_rooms" -> "正在查询空房…";
            case "get_price_quote" -> "正在计算价格…";
            case "list_my_orders" -> "正在查询您的订单…";
            case "list_menu" -> "正在查看菜单…";
            case "propose_booking" -> "正在生成预订确认…";
            case "propose_payment" -> "正在生成支付确认…";
            case "propose_cancel" -> "正在生成取消确认…";
            case "propose_meal_order" -> "正在生成点餐确认…";
            default -> "正在执行工具…";
        };
    }

    private void send(HttpServletResponse response, String event, Object data) throws IOException {
        response.getWriter().write("event: " + event + "\ndata: " + json.writeValueAsString(data) + "\n\n");
        response.flushBuffer();
    }
}
