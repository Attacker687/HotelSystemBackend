package com.winniethepooh.hotelsystembackend.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class SessionStore {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final AgentProperties props;

    public SessionStore(StringRedisTemplate redis, ObjectMapper json, AgentProperties props) {
        this.redis = redis;
        this.json = json;
        this.props = props;
    }

    public void append(Integer userId, String sessionId, List<AgentItem> items) {
        if (items.isEmpty()) return;
        List<String> rows = new ArrayList<>();
        try {
            for (AgentItem item : items) rows.add(json.writeValueAsString(item));
        } catch (JsonProcessingException e) { throw new IllegalStateException("会话内容无法序列化", e); }
        String key = key(userId, sessionId);
        redis.opsForList().rightPushAll(key, rows);
        redis.expire(key, Duration.ofMinutes(props.getSessionTtlMinutes()));
    }

    public List<AgentItem> window(Integer userId, String sessionId) {
        String key = key(userId, sessionId);
        List<String> rows = redis.opsForList().range(key, 0, -1);
        if (rows == null || rows.isEmpty()) return List.of();
        List<AgentItem> items = new ArrayList<>();
        try {
            for (String row : rows) items.add(json.readValue(row, AgentItem.class));
        } catch (JsonProcessingException e) { throw new IllegalStateException("会话记录无法读取", e); }
        int turns = 0, start = 0;
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).type() == AgentItem.Type.USER && ++turns == props.getMaxTurns()) start = i;
        }
        if (turns > props.getMaxTurns()) {
            redis.opsForList().trim(key, start, -1);
            items = items.subList(start, items.size());
        }
        Map<String, Integer> calls = new HashMap<>(), outputs = new HashMap<>();
        for (AgentItem item : items) {
            if (item.callId() == null) continue;
            if (item.type() == AgentItem.Type.FUNCTION_CALL) calls.merge(item.callId(), 1, Integer::sum);
            if (item.type() == AgentItem.Type.FUNCTION_CALL_OUTPUT) outputs.merge(item.callId(), 1, Integer::sum);
        }
        return items.stream().filter(item -> item.type() != AgentItem.Type.FUNCTION_CALL
                && item.type() != AgentItem.Type.FUNCTION_CALL_OUTPUT
                || calls.getOrDefault(item.callId(), 0) == 1 && outputs.getOrDefault(item.callId(), 0) == 1).toList();
    }

    private String key(Integer userId, String sessionId) { return "agent:session:" + userId + ":" + sessionId; }
}
