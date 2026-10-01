package com.winniethepooh.hotelsystembackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.winniethepooh.hotelsystembackend.agent.*;
import com.winniethepooh.hotelsystembackend.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.*;
import com.winniethepooh.hotelsystembackend.service.FoodService;

import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentRepairIT extends IntegrationTestBase {
    @Autowired private FakeLlmClient fake;
    @Autowired private ObjectMapper json;
    @SpyBean private AgentTools tools;
    @SpyBean private SessionStore sessions;
    @SpyBean private FoodService food;
    private String token, session;

    @BeforeEach
    void prepare() {
        reset(tools, sessions, food); fake.reset(); token = login(base.userA());
        session = post("/agent/sessions", token, null).data().path("sessionId").asText();
    }

    @ParameterizedTest
    @ValueSource(strings = {"20", "21", "2147483648", "9999999999"})
    void edgeF3_fakeQuantityBoundariesAreExplainedWithoutInternalOrWrites(String quantity) throws Exception {
        String body = chat("来" + quantity + "份测试菜品X送到1101");
        assertThat(body).doesNotContain("INTERNAL", "event: error").endsWith("event: done\ndata: {\"toolCalls\":" + (quantity.equals("20") ? 2 : 1) + "}\n\n");
        String text = deltas(body);
        if (quantity.equals("20")) {
            assertThat(body).contains("event: card\n"); var keys = redis.keys("agent:action:*"); assertThat(keys).hasSize(1);
            assertThat(json.readTree(redis.opsForValue().get(keys.iterator().next())).path("card").path("total").asText()).isEqualTo("760.00");
            assertThat(text).contains("点击确认");
        } else { assertThat(text).contains("数量", "1", "20"); assertThat(redis.keys("agent:action:*")).isEmpty(); }
        assertThat(fx.count("meal_order")).isZero(); assertThat(fx.count("booking_request")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"history", "tool", "commit"})
    void edgeF2_slowBoundariesDoNotEmitLateCardOrSaveExpiredTurns(String boundary) throws Exception {
        fake.enqueue(AgentItem.assistant("已有回复")); chat("已有历史");
        String key = "agent:session:" + base.userA().id() + ":" + session;
        List<String> previous = redis.opsForList().range(key, 0, -1); redis.expire(key, Duration.ofSeconds(60));
        if (boundary.equals("history")) {
            doAnswer(i -> { pauseRedis(6000); return i.callRealMethod(); }).when(sessions).window(any(), any());
            fake.enqueue(AgentItem.assistant("失败回复"));
        } else {
            fake.enqueueDelayed(Duration.ofSeconds(2), boundary.equals("tool")
                    ? new AgentItem(AgentItem.Type.FUNCTION_CALL, null, "c1", "list_menu", "{}", null, null) : AgentItem.assistant("本轮回复"));
            if (boundary.equals("tool")) doAnswer(i -> {
                jdbc.queryForObject("SELECT SLEEP(4)", Integer.class); return i.callRealMethod();
            }).when(food).getAllDishesService();
            else doAnswer(i -> { pauseRedis(4000); return i.callRealMethod(); }).when(sessions).append(any(), any(), any());
        }
        long start = System.nanoTime(); String body = chat("慢边界");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(5900));
        assertThat(body).contains("TIMEOUT").doesNotContain("event: card").endsWith("event: done\ndata: {\"toolCalls\":" + (boundary.equals("tool") ? 1 : 0) + "}\n\n");
        assertThat(redis.opsForList().range(key, 0, -1)).isEqualTo(previous); assertThat(redis.getExpire(key)).isBetween(1L, 60L);
    }

    private void pauseRedis(int milliseconds) throws Exception {
        var result = REDIS.execInContainer("redis-cli", "CLIENT", "PAUSE", Integer.toString(milliseconds), "ALL");
        assertThat(result.getExitCode()).isZero(); assertThat(result.getStdout().trim()).isEqualTo("OK");
    }

    private String chat(String message) {
        HttpHeaders h = new HttpHeaders(); h.set("token", token); h.setContentType(MediaType.APPLICATION_JSON);
        var r = rest.exchange("/agent/chat", HttpMethod.POST, new HttpEntity<>(Map.of("sessionId", session, "message", message), h), String.class);
        assertThat(r.getStatusCode().value()).isEqualTo(200); return r.getBody();
    }
    private String deltas(String body) throws Exception {
        StringBuilder text = new StringBuilder();
        for (String e : body.split("\n\n")) if (e.startsWith("event: delta\ndata: ")) text.append(json.readTree(e.substring(19)).path("text").asText());
        return text.toString();
    }
}
