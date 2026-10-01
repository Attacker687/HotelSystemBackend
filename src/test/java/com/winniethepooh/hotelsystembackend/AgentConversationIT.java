package com.winniethepooh.hotelsystembackend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.winniethepooh.hotelsystembackend.agent.AgentItem;
import com.winniethepooh.hotelsystembackend.agent.AgentProperties;
import com.winniethepooh.hotelsystembackend.agent.FakeLlmClient;
import com.winniethepooh.hotelsystembackend.context.BaseContext;
import com.winniethepooh.hotelsystembackend.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentConversationIT extends IntegrationTestBase {
    @SpyBean private FakeLlmClient fake;
    @Autowired private AgentProperties props;

    @BeforeEach
    void resetModel() {
        reset(fake);
        fake.reset();
    }

    @Test
    void s01ac1_userCreatesSessionAndReceivesTextEvents() {
        String token = login(base.userA());
        Resp session = post("/agent/sessions", token, null);
        assertThat(session.status()).as("%s", session.body()).isEqualTo(200);
        assertThat(session.code()).isZero();
        String id = session.data().path("sessionId").asText();
        assertThat(UUID.fromString(id).toString()).isEqualTo(id);
        assertThat(redis.hasKey("agent:session:" + base.userA().id() + ":" + id)).isFalse();

        fake.enqueue(AgentItem.assistant("剧本优先\n您好"));
        doAnswer(invocation -> {
            assertThat(BaseContext.getCurrentId()).isEqualTo(base.userA().id());
            assertThat(BaseContext.getCurrentRole()).isZero();
            assertThat(Thread.currentThread().getName()).contains("exec-");
            return invocation.callRealMethod();
        }).when(fake).respond(any(), any(), any(), any());

        HttpHeaders headers = new HttpHeaders();
        headers.set("token", token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        var response = rest.exchange("/agent/chat", HttpMethod.POST,
                new HttpEntity<>(Map.of("sessionId", id, "message", "你好", "userId", base.userB().id()), headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("text/event-stream;charset=UTF-8"));
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-cache");
        assertThat(response.getBody()).startsWith("event: status\ndata: {\"text\":\"正在思考…\"}\n\n")
                .contains("event: delta\ndata: ")
                .endsWith("event: done\ndata: {\"toolCalls\":0}\n\n");
        StringBuilder text = new StringBuilder();
        int deltaCount = 0;
        for (String event : response.getBody().split("\n\n")) {
            if (event.startsWith("event: delta\n")) {
                try {
                    text.append(new ObjectMapper().readTree(event.substring("event: delta\ndata: ".length())).path("text").asText());
                } catch (Exception e) { throw new AssertionError("SSE data must be single-line JSON", e); }
                deltaCount++;
            }
        }
        assertThat(deltaCount).isGreaterThan(1);
        assertThat(text).hasToString("剧本优先\n您好");
        assertThat(fake.inputs()).containsExactly(List.of(AgentItem.user("你好")));
        assertThat(props.getProvider()).isEqualTo("fake");
        assertThat(props.getTimeoutSeconds()).isEqualTo(5);
    }

    @Test
    void s01ac1_authenticationAndClassRoleApplyToBothEndpoints() {
        assertThat(post("/agent/sessions", null, null).status()).isEqualTo(401);
        Map<String, Object> message = Map.of("sessionId", UUID.randomUUID().toString(), "message", "你好");
        assertThat(post("/agent/chat", null, message).status()).isEqualTo(401);
        for (var staff : List.of(base.manager(), base.front(), base.restaurant())) {
            String token = login(staff);
            assertThat(post("/agent/sessions", token, null).status()).isEqualTo(403);
            assertThat(post("/agent/chat", token, message).status()).isEqualTo(403);
        }
        verify(fake, never()).respond(any(), any(), any(), any());
    }

    @Test
    void s01ac1_validationRejectsInvalidUuidBlankAndTooLongMessages() {
        String token = login(base.userA());
        for (var body : List.of(Map.of("sessionId", "wrong", "message", "你好"),
                Map.of("sessionId", UUID.randomUUID().toString(), "message", " "),
                Map.of("sessionId", UUID.randomUUID().toString(), "message", "中".repeat(501)))) {
            Resp result = post("/agent/chat", token, body);
            assertThat(result.status()).isEqualTo(400);
            assertThat(result.code()).isEqualTo(1);
        }
        verify(fake, never()).respond(any(), any(), any(), any());
    }

    @Test
    void tc024_reusingAnotherUsersSessionCannotReadOrChangeTheirHistory() throws Exception {
        String a = login(base.userA());
        String b = login(base.userB());
        String id = session(a);
        fake.enqueue(AgentItem.assistant("A历史回复"));
        chat(a, id, "A私有历史");
        List<AgentItem> aHistory = history(base.userA().id(), id);
        assertThat(aHistory).containsExactly(AgentItem.user("A私有历史"), AgentItem.assistant("A历史回复"));
        fake.reset();
        fake.enqueue(AgentItem.assistant("B回复"));

        chat(b, id, "B本轮");

        assertThat(fake.inputs()).containsExactly(List.of(AgentItem.user("B本轮")));
        assertThat(history(base.userA().id(), id)).isEqualTo(aHistory);
        assertThat(history(base.userB().id(), id)).containsExactly(AgentItem.user("B本轮"), AgentItem.assistant("B回复"));
    }

    @Test
    void tc036_newSessionStartsEmptyAndPreservesPreviousSession() throws Exception {
        String token = login(base.userA());
        String old = session(token);
        fake.enqueue(AgentItem.assistant("旧回复一"));
        chat(token, old, "旧消息一");
        fake.enqueue(AgentItem.assistant("旧回复二"));
        chat(token, old, "旧消息二");
        List<AgentItem> previous = history(base.userA().id(), old);
        assertThat(previous).containsExactly(AgentItem.user("旧消息一"), AgentItem.assistant("旧回复一"),
                AgentItem.user("旧消息二"), AgentItem.assistant("旧回复二"));
        String fresh = session(token);
        assertThat(fresh).isNotEqualTo(old);
        assertThat(redis.hasKey(key(base.userA().id(), fresh))).isFalse();
        fake.reset();
        fake.enqueue(AgentItem.assistant("新回复"));

        chat(token, fresh, "新消息");

        assertThat(fake.inputs()).containsExactly(List.of(AgentItem.user("新消息")));
        assertThat(history(base.userA().id(), old)).isEqualTo(previous);
        assertThat(history(base.userA().id(), fresh)).containsExactly(AgentItem.user("新消息"), AgentItem.assistant("新回复"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void s02ac1_missingAndExpiredHistoryAreEmpty(boolean expired) throws Exception {
        String token = login(base.userA());
        String id = session(token);
        if (expired) {
            seed(base.userA().id(), id, List.of(AgentItem.user("已到期历史"), AgentItem.assistant("旧回复")));
            redis.expire(key(base.userA().id(), id), Duration.ZERO);
            assertThat(redis.hasKey(key(base.userA().id(), id))).isFalse();
        }
        fake.enqueue(AgentItem.assistant("本轮回复"));

        chat(token, id, "本轮");

        assertThat(fake.inputs()).containsExactly(List.of(AgentItem.user("本轮")));
        assertThat(history(base.userA().id(), id)).containsExactly(AgentItem.user("本轮"), AgentItem.assistant("本轮回复"));
    }

    @Test
    void tc035_textTurnCreatesAndRefreshesThirtyMinuteTtl() throws Exception {
        String token = login(base.userA());
        String id = session(token);
        fake.enqueue(AgentItem.assistant("首轮回复"));
        chat(token, id, "首轮");
        assertThat(redis.getExpire(key(base.userA().id(), id))).isBetween(1700L, 1800L);
        redis.expire(key(base.userA().id(), id), Duration.ofSeconds(60));
        assertThat(redis.getExpire(key(base.userA().id(), id))).isBetween(1L, 60L);
        fake.enqueue(AgentItem.assistant("第二轮回复"));

        chat(token, id, "第二轮");

        assertThat(redis.getExpire(key(base.userA().id(), id))).isBetween(1700L, 1800L);
        assertThat(history(base.userA().id(), id)).containsExactly(AgentItem.user("首轮"), AgentItem.assistant("首轮回复"),
                AgentItem.user("第二轮"), AgentItem.assistant("第二轮回复"));
        assertThat(fake.inputs().get(1)).containsExactly(AgentItem.user("首轮"), AgentItem.assistant("首轮回复"), AgentItem.user("第二轮"));
    }

    @Test
    void s02ac2_completeItemsAndRawReasoningSurviveChatAndReplay() throws Exception {
        String token = login(base.userA());
        String id = session(token);
        String reasoningRaw = "{\"type\":\"reasoning\",\"id\":\"r1\",\"summary\":[],\"encrypted_content\":\"sealed==\"}";
        List<AgentItem> previous = List.of(AgentItem.user("旧消息"),
                new AgentItem(AgentItem.Type.REASONING, null, null, null, null, null, reasoningRaw),
                new AgentItem(AgentItem.Type.FUNCTION_CALL, null, "c1", "list_menu", "{}", null,
                        "{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"list_menu\",\"arguments\":\"{}\"}"),
                new AgentItem(AgentItem.Type.FUNCTION_CALL_OUTPUT, null, "c1", null, null, "{\"ok\":true}", null),
                new AgentItem(AgentItem.Type.ASSISTANT, "旧回复", null, null, null, null,
                        "{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"旧回复\"}]}"),
                new AgentItem(AgentItem.Type.NOTE, "[系统通知] 价格已变化", null, null, null, null, null));
        seed(base.userA().id(), id, previous);
        AgentItem reasoning = new AgentItem(AgentItem.Type.REASONING, null, null, null, null, null, reasoningRaw);
        fake.enqueue(reasoning, AgentItem.assistant("新回复"));

        chat(token, id, "继续");

        List<AgentItem> expected = new ArrayList<>(previous);
        expected.add(AgentItem.user("继续"));
        assertThat(fake.inputs()).containsExactly(expected);
        expected.addAll(List.of(reasoning, AgentItem.assistant("新回复")));
        assertThat(history(base.userA().id(), id)).isEqualTo(expected);
        fake.reset();
        fake.enqueue(AgentItem.assistant("再回复"));
        chat(token, id, "再继续");
        expected.add(AgentItem.user("再继续"));
        assertThat(fake.inputs()).containsExactly(expected);
    }

    @Test
    void tc063_twentyOneStoredTurnsAreTrimmedBeforeOneChat() throws Exception {
        String token = login(base.userA());
        String id = session(token);
        List<AgentItem> previous = new ArrayList<>();
        for (int i = 1; i <= 21; i++) previous.addAll(completeTurn(i));
        seed(base.userA().id(), id, previous);
        fake.enqueue(AgentItem.assistant("第22轮回复"));

        chat(token, id, "u22");

        List<AgentItem> expected = new ArrayList<>(previous.subList(5, previous.size()));
        expected.add(AgentItem.user("u22"));
        assertThat(fake.inputs()).containsExactly(expected);
        assertThat(expected.stream().filter(item -> item.type() == AgentItem.Type.USER).count()).isEqualTo(21);
        expected.add(AgentItem.assistant("第22轮回复"));
        assertThat(history(base.userA().id(), id)).isEqualTo(expected);
        assertThat(history(base.userA().id(), id).get(0)).isEqualTo(AgentItem.user("u2"));
    }

    private String session(String token) {
        Resp result = post("/agent/sessions", token, null);
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.code()).isZero();
        String id = result.data().path("sessionId").asText();
        assertThat(UUID.fromString(id).toString()).isEqualTo(id);
        return id;
    }

    private void chat(String token, String id, String message) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("token", token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        var result = rest.exchange("/agent/chat", HttpMethod.POST,
                new HttpEntity<>(Map.of("sessionId", id, "message", message), headers), String.class);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).doesNotContain("event: error").endsWith("event: done\ndata: {\"toolCalls\":0}\n\n");
    }

    private String key(int userId, String id) { return "agent:session:" + userId + ":" + id; }

    private List<AgentItem> history(int userId, String id) throws Exception {
        List<AgentItem> items = new ArrayList<>();
        for (String json : redis.opsForList().range(key(userId, id), 0, -1))
            items.add(new ObjectMapper().readValue(json, AgentItem.class));
        return items;
    }

    private void seed(int userId, String id, List<AgentItem> items) throws Exception {
        List<String> rows = new ArrayList<>();
        for (AgentItem item : items) rows.add(new ObjectMapper().writeValueAsString(item));
        redis.opsForList().rightPushAll(key(userId, id), rows);
        redis.expire(key(userId, id), Duration.ofMinutes(30));
    }

    private List<AgentItem> completeTurn(int i) {
        return List.of(AgentItem.user("u" + i),
                new AgentItem(AgentItem.Type.FUNCTION_CALL, null, "c" + i, "list_menu", "{}", null, null),
                new AgentItem(AgentItem.Type.FUNCTION_CALL_OUTPUT, null, "c" + i, null, null,
                        "{\"ok\":true,\"data\":{\"dishes\":[]}}", null),
                AgentItem.assistant("a" + i),
                new AgentItem(AgentItem.Type.NOTE, "[系统通知] n" + i, null, null, null, null, null));
    }
}
