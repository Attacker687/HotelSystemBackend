package com.winniethepooh.hotelsystembackend.agent;

import java.math.BigDecimal;
import java.util.Map;

public record PendingAction(String id, Integer userId, String sessionId, Type type,
                            Map<String, Object> params, BigDecimal total, Map<String, Object> card) {
    public enum Type { BOOKING, PAYMENT, CANCEL, MEAL_ORDER }
}
