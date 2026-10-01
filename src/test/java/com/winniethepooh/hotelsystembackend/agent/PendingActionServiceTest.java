package com.winniethepooh.hotelsystembackend.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PendingActionServiceTest {
    @Test
    void s04ac1_createWritesExactOwnerParamsCardAndTtlInOneSet() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        StringRedisTemplate redis = mock(StringRedisTemplate.class); ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var service = new PendingActionService(redis, json, new AgentProperties());
        var ctx = new AgentTools.ToolContext(7, UUID.randomUUID().toString());
        var params = Map.<String, Object>of("orderId", 123L);
        var lines = List.of(List.of("订单号", "123"));
        var action = service.create(ctx, PendingAction.Type.PAYMENT, params, new BigDecimal("199.00"), lines, List.of());
        assertThat(UUID.fromString(action.id()).toString()).isEqualTo(action.id());
        assertThat(action.userId()).isEqualTo(7); assertThat(action.sessionId()).isEqualTo(ctx.sessionId());
        assertThat(action.type()).isEqualTo(PendingAction.Type.PAYMENT); assertThat(action.params()).isEqualTo(params);
        assertThat(action.total()).isEqualByComparingTo("199.00");
        assertThat(action.card()).containsEntry("actionId", action.id()).containsEntry("type", "PAYMENT").containsEntry("title", "支付确认")
                .containsEntry("status", "PENDING").containsEntry("ttlSeconds", 600L).containsEntry("lines", lines).containsEntry("details", List.of()).containsEntry("total", "199.00");
        assertThat(LocalDateTime.parse(action.card().get("expiresAt").toString())).isBetween(LocalDateTime.now().plusMinutes(9), LocalDateTime.now().plusMinutes(11));
        var saved = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq("agent:action:" + action.id()), saved.capture(), eq(Duration.ofMinutes(10)));
        assertThat(json.readTree(saved.getValue())).isEqualTo(json.readTree(json.writeValueAsString(action)));
        verifyNoMoreInteractions(values);
    }
}
