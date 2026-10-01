package com.winniethepooh.hotelsystembackend;

import com.winniethepooh.hotelsystembackend.agent.OpenAiLlmClient;
import com.winniethepooh.hotelsystembackend.support.Fixtures;
import com.winniethepooh.hotelsystembackend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {"hotel.agent.provider=openai", "hotel.agent.openai.api-key="})
class AgentUnavailableIT extends IntegrationTestBase {
    @Autowired private OpenAiLlmClient llm;

    @Test
    void tc046_emptyKeyOnlyDisablesChatBeforeRateLimit() {
        String token = login(base.userA());
        assertThat(ReflectionTestUtils.getField(llm, "client")).isNull();
        Resp session = post("/agent/sessions", token, null);
        assertThat(session.status()).as("%s", session.body()).isEqualTo(200);
        assertThat(session.code()).isZero();
        String id = session.data().path("sessionId").asText();
        assertThat(UUID.fromString(id).toString()).isEqualTo(id);

        Resp chat = post("/agent/chat", token, Map.of("sessionId", id, "message", "你好"));
        assertThat(chat.status()).isEqualTo(503);
        assertThat(chat.code()).isEqualTo(1);
        assertThat(chat.msg()).contains("暂不可用");
        assertThat(redis.hasKey("agent:rate:" + base.userA().id())).isFalse();
        redis.opsForValue().set("agent:rate:" + base.userA().id(), "11", Duration.ofMinutes(1));
        assertThat(post("/agent/chat", token, Map.of("sessionId", id, "message", "你好")).status()).isEqualTo(503);
        assertThat(redis.opsForValue().get("agent:rate:" + base.userA().id())).isEqualTo("11");
        assertThat(ReflectionTestUtils.getField(llm, "client")).isNull();

        Resp rooms = get("/rooms", token);
        assertThat(rooms.status()).isEqualTo(200);
        assertThat(rooms.code()).isZero();
        LocalDate tomorrow = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1);
        Resp order = post("/order", token, Fixtures.roomOrderBody(base.room("R1").number(),
                tomorrow.atTime(14, 0), tomorrow.plusDays(1).atTime(12, 0)));
        assertThat(order.status()).isEqualTo(200);
        assertThat(order.code()).isZero();
        assertThat(jdbc.queryForObject("select total_amount from room_order where id = ?", BigDecimal.class,
                order.data().asLong())).isEqualByComparingTo("199.00");
    }
}
