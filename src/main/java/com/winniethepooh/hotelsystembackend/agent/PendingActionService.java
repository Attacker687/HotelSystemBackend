package com.winniethepooh.hotelsystembackend.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PendingActionService {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final AgentProperties props;

    public PendingActionService(StringRedisTemplate redis, ObjectMapper json, AgentProperties props) {
        this.redis = redis; this.json = json; this.props = props;
    }

    public PendingAction create(AgentTools.ToolContext ctx, PendingAction.Type type, Map<String, Object> params,
                                BigDecimal total, List<List<String>> lines, List<List<String>> details) {
        String id = UUID.randomUUID().toString();
        Duration ttl = Duration.ofMinutes(props.getActionTtlMinutes());
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("actionId", id); card.put("type", type.name());
        card.put("title", switch (type) { case BOOKING -> "预订确认"; case PAYMENT -> "支付确认"; case CANCEL -> "取消确认"; case MEAL_ORDER -> "点餐确认"; });
        card.put("status", "PENDING"); card.put("ttlSeconds", ttl.toSeconds());
        card.put("expiresAt", LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plus(ttl).toString());
        card.put("lines", lines); card.put("details", details); card.put("total", money(total));
        PendingAction action = new PendingAction(id, ctx.userId(), ctx.sessionId(), type, params, total, card);
        try { redis.opsForValue().set("agent:action:" + id, json.writeValueAsString(action), ttl); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize pending action", e); }
        return action;
    }

    static String money(BigDecimal amount) { return amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP).toPlainString(); }
}
