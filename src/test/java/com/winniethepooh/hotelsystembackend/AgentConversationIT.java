package com.winniethepooh.hotelsystembackend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.winniethepooh.hotelsystembackend.agent.AgentItem;
import com.winniethepooh.hotelsystembackend.agent.AgentProperties;
import com.winniethepooh.hotelsystembackend.agent.FakeLlmClient;
import com.winniethepooh.hotelsystembackend.context.BaseContext;
import com.winniethepooh.hotelsystembackend.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

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
}
