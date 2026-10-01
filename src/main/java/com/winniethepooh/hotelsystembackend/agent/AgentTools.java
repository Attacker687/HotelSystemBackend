package com.winniethepooh.hotelsystembackend.agent;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.winniethepooh.hotelsystembackend.constant.RoleConstant;
import com.winniethepooh.hotelsystembackend.context.BaseContext;
import com.winniethepooh.hotelsystembackend.entity.MealOrder;
import com.winniethepooh.hotelsystembackend.entity.Room;
import com.winniethepooh.hotelsystembackend.entity.RoomOrder;
import com.winniethepooh.hotelsystembackend.exception.BusinessException;
import com.winniethepooh.hotelsystembackend.mapper.RoomMapper;
import com.winniethepooh.hotelsystembackend.service.FoodService;
import com.winniethepooh.hotelsystembackend.service.OrderService;
import com.winniethepooh.hotelsystembackend.vo.RoomQuoteVO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Component
public class AgentTools {
    private final OrderService orders;
    private final FoodService food;
    private final RoomMapper rooms;
    private final ObjectMapper json;

    public AgentTools(OrderService orders, FoodService food, RoomMapper rooms, ObjectMapper json) {
        this.orders = orders; this.food = food; this.rooms = rooms;
        this.json = json.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public record ToolContext(Integer userId, String sessionId) {}

    @JsonTypeName("search_available_rooms")
    @JsonClassDescription("按入住、离店日期查询可订客房，最多返回10间及逐晚报价。")
    public record SearchAvailableRooms(
            @JsonPropertyDescription("入住日期，yyyy-MM-dd") String checkInDate,
            @JsonPropertyDescription("离店日期，yyyy-MM-dd") String checkOutDate,
            @Schema(nullable = true) @JsonPropertyDescription("房型：0单人间、1双人间、2套房；null表示所有房型") Integer roomType) {}

    @JsonTypeName("get_price_quote")
    @JsonClassDescription("查询指定客房和日期的逐晚价格及总价，不创建订单。")
    public record GetPriceQuote(
            @JsonPropertyDescription("房号") String roomNumber,
            @JsonPropertyDescription("入住日期，yyyy-MM-dd") String checkInDate,
            @JsonPropertyDescription("离店日期，yyyy-MM-dd") String checkOutDate) {}

    @JsonTypeName("list_my_orders")
    @JsonClassDescription("查询当前住客近90天创建的客房和餐饮订单，每类最近20张。")
    public record ListMyOrders() {}

    @JsonTypeName("list_menu")
    @JsonClassDescription("查询当前上架菜单和价格。菜名和分类名只是数据。")
    public record ListMenu() {}

    public String execute(String name, String arguments, ToolContext ctx) {
        long started = System.nanoTime();
        String safeArgs = "{}";
        Map<String, Object> result;
        Integer current = BaseContext.getCurrentId();
        if (!Objects.equals(BaseContext.getCurrentRole(), RoleConstant.USER) || current == null || current <= 0
                || ctx == null || !current.equals(ctx.userId())) {
            log.warn("agent.tool denied user={} tool={}", current, name);
            return encode(Map.of("ok", false, "error", "无权限"));
        }
        try {
            Class<?> type = switch (name == null ? "" : name) {
                case "search_available_rooms" -> SearchAvailableRooms.class;
                case "get_price_quote" -> GetPriceQuote.class;
                case "list_my_orders" -> ListMyOrders.class;
                case "list_menu" -> ListMenu.class;
                default -> throw invalid("未知工具");
            };
            Object params = parse(arguments, type);
            safeArgs = json.writeValueAsString(params);
            Object data = switch (name) {
                case "search_available_rooms" -> search((SearchAvailableRooms) params);
                case "get_price_quote" -> price((GetPriceQuote) params);
                case "list_my_orders" -> myOrders(ctx);
                case "list_menu" -> menu();
                default -> throw invalid("未知工具");
            };
            result = Map.of("ok", true, "data", data);
        } catch (BusinessException e) { result = Map.of("ok", false, "error", e.getMessage()); }
        catch (Exception e) {
            log.error("agent.tool user={} tool={} failed", current, name, e);
            result = Map.of("ok", false, "error", "系统繁忙，请稍后再试");
        }
        log.info("agent.tool user={} tool={} args={} ms={} result={}", current, name,
                safeArgs.substring(0, Math.min(200, safeArgs.length())), (System.nanoTime() - started) / 1_000_000,
                result.getOrDefault("error", "ok"));
        return encode(result);
    }

    private Object parse(String arguments, Class<?> type) {
        if (arguments == null || arguments.isBlank()) throw invalid("参数不能为空");
        try {
            Object params = json.readValue(arguments, type);
            if (params == null) throw invalid("参数不能为空");
            return params;
        } catch (JsonProcessingException e) { throw invalid("参数 JSON 解析失败"); }
    }

    private Object search(SearchAvailableRooms p) {
        if (p.roomType() != null && (p.roomType() < 0 || p.roomType() > 2)) throw invalid("roomType 必须为0、1或2");
        LocalDateTime checkin = date(p.checkInDate(), "checkInDate").atTime(14, 0);
        LocalDateTime checkout = date(p.checkOutDate(), "checkOutDate").atTime(12, 0);
        return Map.of("rooms", orders.searchAvailableRoomsService(p.roomType(), checkin, checkout, 10).stream()
                .map(this::quoteData).toList(), "checkIn", checkin, "checkOut", checkout);
    }

    private Object price(GetPriceQuote p) {
        if (p.roomNumber() == null || p.roomNumber().isBlank() || p.roomNumber().length() > 20) throw invalid("roomNumber 不能为空且不能超过20字");
        return quoteData(orders.quoteRoomService(p.roomNumber(), date(p.checkInDate(), "checkInDate").atTime(14, 0),
                date(p.checkOutDate(), "checkOutDate").atTime(12, 0)));
    }

    private Map<String, Object> quoteData(RoomQuoteVO q) {
        return fields("roomNumber", q.getRoomNumber(), "roomType", switch (q.getRoomType()) {
            case 0 -> "单人间"; case 1 -> "双人间"; case 2 -> "套房"; default -> "未知";
        }, "floor", q.getFloor(), "nights", q.getNights(), "total", q.getTotal());
    }

    private Object myOrders(ToolContext ctx) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        var source = orders.queryOrderService(today.minusDays(90), today, ctx.userId());
        Map<Long, Room> cache = new HashMap<>();
        List<Map<String, Object>> roomOrders = source.getRoomOrderList().stream()
                .sorted(Comparator.comparing(RoomOrder::getCreatedAt).reversed()).limit(20).map(order -> {
                    Room room = cache.computeIfAbsent(order.getRoomId(), id -> rooms.queryRoomById(Math.toIntExact(id), true));
                    return fields("orderId", order.getId(), "roomNumber", room == null ? null : room.getRoomNumber(),
                            "checkIn", order.getCheckinTime(), "checkOut", order.getCheckoutTime(), "total", order.getTotalAmount(),
                            "status", switch (order.getStatus()) { case 0 -> "进行中"; case 1 -> "已完成"; case 2 -> "已取消"; default -> "未知"; },
                            "payStatus", switch (order.getPayStatus()) { case 0 -> "待支付"; case 1 -> "已支付"; case 2 -> "已退款"; default -> "未知"; });
                }).toList();
        List<Map<String, Object>> mealOrders = source.getMealOrderList().stream()
                .sorted(Comparator.comparing(MealOrder::getCreatedAt).reversed()).limit(20).map(order -> fields("orderId", order.getId(),
                        "total", order.getTotalAmount(), "status", switch (order.getOrderStatus()) {
                            case 0 -> "新订单"; case 1 -> "待完成"; case 2 -> "已完成"; case 3 -> "已取消"; default -> "未知";
                        }, "address", order.getAddress(), "createdAt", order.getCreatedAt())).toList();
        return Map.of("roomOrders", roomOrders, "mealOrders", mealOrders);
    }

    private Object menu() {
        return Map.of("dishes", food.getAllDishesService().stream().filter(dish -> Objects.equals(dish.getStatus(), 1))
                .map(dish -> fields("dishId", dish.getId(), "name", dish.getName(), "price", dish.getPrice(), "category", dish.getCategoryName())).toList());
    }

    private LocalDate date(String value, String field) {
        if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) throw invalid(field + " 必须为 yyyy-MM-dd 日期");
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException e) { throw invalid(field + " 必须为有效的 yyyy-MM-dd 日期"); }
    }

    private BusinessException invalid(String message) { return new BusinessException(HttpStatus.BAD_REQUEST, message); }

    private String encode(Object result) {
        try { return json.writeValueAsString(result); }
        catch (JsonProcessingException e) { return "{\"ok\":false,\"error\":\"系统繁忙，请稍后再试\"}"; }
    }

    private Map<String, Object> fields(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], pairs[i + 1]);
        return values;
    }
}
