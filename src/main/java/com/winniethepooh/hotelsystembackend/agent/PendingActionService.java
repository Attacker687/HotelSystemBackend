package com.winniethepooh.hotelsystembackend.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.winniethepooh.hotelsystembackend.dto.InsertMealOrderDTO;
import com.winniethepooh.hotelsystembackend.dto.InsertRoomOrderDTO;
import com.winniethepooh.hotelsystembackend.entity.BookingRequest;
import com.winniethepooh.hotelsystembackend.entity.MealOrderItem;
import com.winniethepooh.hotelsystembackend.entity.RoomOrder;
import com.winniethepooh.hotelsystembackend.exception.BusinessException;
import com.winniethepooh.hotelsystembackend.mapper.OrderMapper;
import com.winniethepooh.hotelsystembackend.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@Slf4j
public class PendingActionService {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final AgentProperties props;
    private final OrderMapper mapper;
    private final OrderService orders;
    private final TransactionTemplate tx;
    private final SessionStore sessions;

    public PendingActionService(StringRedisTemplate redis, ObjectMapper json, AgentProperties props,
                                OrderMapper mapper, OrderService orders, TransactionTemplate tx, SessionStore sessions) {
        this.redis = redis; this.json = json; this.props = props;
        this.mapper = mapper; this.orders = orders; this.tx = tx; this.sessions = sessions;
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

    public Map<String, Object> confirm(String id, Integer userId) {
        BookingRequest existing = mapper.findBookingRequest(id);
        if (existing != null) return byRecord(existing, userId, true);
        PendingAction action = readAction(id);
        if (action == null) {
            existing = mapper.findBookingRequest(id);
            if (existing != null) return byRecord(existing, userId, true);
            throw invalid();
        }
        requireOwner(action.userId(), userId);
        Long orderId;
        try {
            orderId = tx.execute(status -> {
                mapper.insertBookingRequest(id, userId, action.type().name(), "PROCESSING");
                Long result = execute(action, userId);
                if (mapper.markBookingRequestSuccess(id, result) != 1)
                    throw new BusinessException(HttpStatus.CONFLICT, "确认请求冲突，请让助手重新生成确认卡片");
                return result;
            });
        } catch (DuplicateKeyException | PessimisticLockingFailureException e) {
            // execute has rolled back before the reread; never catch these within the callback.
            existing = mapper.findBookingRequest(id);
            if (existing != null) return byRecord(existing, userId, true);
            BusinessException conflict = new BusinessException(HttpStatus.CONFLICT, "确认请求冲突，请让助手重新生成确认卡片");
            invalidate(action, conflict); throw conflict;
        } catch (BusinessException e) { invalidate(action, e); throw e; }
        Map<String, Object> result = confirmed(id, action.type(), orderId);
        deleteSafely(action);
        noteSafely(action, "[系统通知] 住客已确认：" + result.get("message") + "，订单号 " + orderId);
        return result;
    }

    public Map<String, Object> cancel(String id, Integer userId) {
        BookingRequest existing = mapper.findBookingRequest(id);
        if (existing != null) return byRecord(existing, userId, false);
        PendingAction action = readAction(id);
        if (action == null) {
            existing = mapper.findBookingRequest(id);
            if (existing != null) return byRecord(existing, userId, false);
            throw invalid();
        }
        requireOwner(action.userId(), userId);
        try { mapper.insertBookingRequest(id, userId, action.type().name(), "CANCELLED"); }
        catch (DuplicateKeyException | PessimisticLockingFailureException e) {
            existing = mapper.findBookingRequest(id);
            if (existing != null) return byRecord(existing, userId, false);
            throw new BusinessException(HttpStatus.CONFLICT, "操作冲突，请重试");
        }
        deleteSafely(action);
        return cancelled(id);
    }

    private Long execute(PendingAction action, Integer userId) {
        Map<String, Object> p = action.params();
        return switch (action.type()) {
            case BOOKING -> {
                InsertRoomOrderDTO dto = new InsertRoomOrderDTO();
                dto.setRoomNumber((String) p.get("roomNumber")); dto.setCheckInTime(LocalDateTime.parse((String) p.get("checkIn"))); dto.setCheckOutTime(LocalDateTime.parse((String) p.get("checkOut")));
                dto.setName((String) p.get("guestName")); dto.setPhone((String) p.get("guestPhone")); dto.setIdCard((String) p.get("guestIdCard"));
                Long id = orders.insertRoomOrderByUserService(dto);
                compareTotal(action.total(), mapper.getRoomOrderById(id).getTotalAmount()); yield id;
            }
            case PAYMENT -> {
                Long id = ((Number) p.get("orderId")).longValue();
                // Reuse the existing row lock so a front-desk reschedule cannot change the
                // amount between this read and the existing conditional payment UPDATE.
                RoomOrder order = mapper.getRoomOrderByIdForUpdate(id);
                if (order == null) throw new BusinessException(HttpStatus.NOT_FOUND, "订单不存在");
                if (!Objects.equals(order.getUserId(), userId)) throw new BusinessException(HttpStatus.FORBIDDEN, "无权限操作他人的订单");
                compareTotal(action.total(), order.getTotalAmount()); orders.payRoomOrderService(id); yield id;
            }
            case CANCEL -> {
                Long id = ((Number) p.get("orderId")).longValue(); orders.cancelRoomOrderService(id); yield id;
            }
            case MEAL_ORDER -> {
                InsertMealOrderDTO dto = new InsertMealOrderDTO(); dto.setAddress((String) p.get("address")); dto.setRemarks((String) p.get("remarks"));
                List<AgentTools.MealItem> items = json.convertValue(p.get("items"), new TypeReference<>() {});
                dto.setItemList(items.stream().map(item -> { MealOrderItem row = new MealOrderItem(); row.setDishId(item.dishId()); row.setQuantity(item.quantity()); return row; }).toList());
                orders.insertMealOrderService(dto); compareTotal(action.total(), dto.getTotalAmount()); yield dto.getId().longValue();
            }
        };
    }

    private void compareTotal(BigDecimal quoted, BigDecimal current) {
        boolean different = quoted == null ? current != null : current == null || quoted.compareTo(current) != 0;
        if (different) throw new BusinessException(HttpStatus.CONFLICT,
                "价格已变化：原报价 " + money(quoted) + " 元，当前 " + money(current) + " 元，请重新询价");
    }

    private Map<String, Object> byRecord(BookingRequest record, Integer userId, boolean confirm) {
        requireOwner(record.getUserId(), userId);
        if ("SUCCESS".equals(record.getStatus())) {
            if (!confirm) throw new BusinessException(HttpStatus.CONFLICT, "已确认，不能取消");
            return confirmed(record.getRequestId(), PendingAction.Type.valueOf(record.getActionType()), record.getOrderId());
        }
        if ("CANCELLED".equals(record.getStatus())) { if (confirm) throw invalid(); return cancelled(record.getRequestId()); }
        throw new BusinessException(HttpStatus.CONFLICT, "确认请求冲突，请让助手重新生成确认卡片");
    }

    private Map<String, Object> confirmed(String id, PendingAction.Type type, Long orderId) {
        String message = switch (type) {
            case BOOKING -> "预订成功，订单号 " + orderId + "，请在 15 分钟内支付";
            case PAYMENT -> "支付成功";
            case CANCEL -> {
                RoomOrder order = mapper.getRoomOrderById(orderId);
                yield order != null && Objects.equals(order.getPayStatus(), 2) ? "订单已取消，已支付款项标记为已退款" : "订单已取消";
            }
            case MEAL_ORDER -> "点餐成功，订单号 " + orderId;
        };
        return Map.of("actionId", id, "type", type.name(), "status", "CONFIRMED", "orderId", orderId, "message", message);
    }

    private Map<String, Object> cancelled(String id) { return Map.of("actionId", id, "status", "CANCELLED"); }
    private PendingAction readAction(String id) {
        String raw = redis.opsForValue().get("agent:action:" + id);
        if (raw == null) return null;
        try { return json.readValue(raw, PendingAction.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("无法读取确认卡片", e); }
    }
    private void requireOwner(Integer owner, Integer userId) {
        if (userId == null || !Objects.equals(owner, userId)) throw new BusinessException(HttpStatus.FORBIDDEN, "无权限操作该确认卡片");
    }
    private BusinessException invalid() { return new BusinessException(HttpStatus.NOT_FOUND, "确认卡片已失效"); }
    private void invalidate(PendingAction action, BusinessException e) { deleteSafely(action); noteSafely(action, "[系统通知] 确认失败：" + e.getMessage()); }
    private void deleteSafely(PendingAction action) {
        try { redis.delete("agent:action:" + action.id()); }
        catch (RuntimeException e) { log.warn("agent action cleanup failed user={} type={} cause={}", action.userId(), action.type(), e.getClass().getSimpleName()); }
    }
    private void noteSafely(PendingAction action, String text) {
        try { sessions.appendNote(action.userId(), action.sessionId(), text); }
        catch (RuntimeException e) { log.warn("agent action notification failed user={} type={} cause={}", action.userId(), action.type(), e.getClass().getSimpleName()); }
    }

    static String money(BigDecimal amount) { return amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP).toPlainString(); }
}
