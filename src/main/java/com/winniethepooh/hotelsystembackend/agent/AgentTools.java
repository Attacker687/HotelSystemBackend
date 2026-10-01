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
import com.winniethepooh.hotelsystembackend.entity.User;
import com.winniethepooh.hotelsystembackend.entity.Dish;
import com.winniethepooh.hotelsystembackend.exception.BusinessException;
import com.winniethepooh.hotelsystembackend.mapper.RoomMapper;
import com.winniethepooh.hotelsystembackend.mapper.OrderMapper;
import com.winniethepooh.hotelsystembackend.mapper.UserMapper;
import com.winniethepooh.hotelsystembackend.mapper.FoodMapper;
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
import java.time.format.DateTimeFormatter;
import java.math.BigDecimal;
import java.util.ArrayList;
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
    private final OrderMapper orderMapper;
    private final UserMapper users;
    private final FoodMapper dishes;
    private final PendingActionService pending;

    public AgentTools(OrderService orders, FoodService food, RoomMapper rooms, ObjectMapper json,
                      OrderMapper orderMapper, UserMapper users, FoodMapper dishes, PendingActionService pending) {
        this.orders = orders; this.food = food; this.rooms = rooms;
        this.json = json.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.orderMapper = orderMapper; this.users = users; this.dishes = dishes; this.pending = pending;
    }

    public record ToolContext(Integer userId, String sessionId) {}

    public record ToolResult(String output, Map<String, Object> card) {}

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

    @JsonTypeName("propose_booking")
    @JsonClassDescription("为当前住客本人生成预订确认卡片，只提议，点击确认后才下单。")
    public record ProposeBooking(
            @JsonPropertyDescription("房号") String roomNumber,
            @JsonPropertyDescription("入住日期，yyyy-MM-dd") String checkInDate,
            @JsonPropertyDescription("离店日期，yyyy-MM-dd") String checkOutDate) {}

    @JsonTypeName("propose_payment")
    @JsonClassDescription("生成本人客房订单的支付确认卡片，确认前不支付。")
    public record ProposePayment(@JsonPropertyDescription("本人客房订单号") Long orderId) {}

    @JsonTypeName("propose_cancel")
    @JsonClassDescription("生成本人尚未入住客房订单的取消确认卡片，不支持餐饮单，确认前不取消。")
    public record ProposeCancel(@JsonPropertyDescription("本人客房订单号") Long orderId) {}

    public record MealItem(@JsonPropertyDescription("菜品id") Long dishId, @JsonPropertyDescription("数量，1至20") Integer quantity) {}

    @JsonTypeName("propose_meal_order")
    @JsonClassDescription("按上架菜品的服务端价格生成点餐确认卡片，确认前不创建订单。")
    public record ProposeMealOrder(
            @JsonPropertyDescription("明细，1至20行") List<MealItem> items,
            @JsonPropertyDescription("送餐地址，非空且最多255字") String address,
            @Schema(nullable = true) @JsonPropertyDescription("备注，可空且最多500字") String remarks) {}

    public ToolResult execute(String name, String arguments, ToolContext ctx) {
        long started = System.nanoTime();
        String safeArgs = "{}";
        Map<String, Object> result;
        Map<String, Object> card = null;
        Integer current = BaseContext.getCurrentId();
        if (!Objects.equals(BaseContext.getCurrentRole(), RoleConstant.USER) || current == null || current <= 0
                || ctx == null || !current.equals(ctx.userId())) {
            log.warn("agent.tool denied user={} tool={}", current, name);
            return new ToolResult(encode(Map.of("ok", false, "error", "无权限")), null);
        }
        try {
            Class<?> type = switch (name == null ? "" : name) {
                case "search_available_rooms" -> SearchAvailableRooms.class;
                case "get_price_quote" -> GetPriceQuote.class;
                case "list_my_orders" -> ListMyOrders.class;
                case "list_menu" -> ListMenu.class;
                case "propose_booking" -> ProposeBooking.class;
                case "propose_payment" -> ProposePayment.class;
                case "propose_cancel" -> ProposeCancel.class;
                case "propose_meal_order" -> ProposeMealOrder.class;
                default -> throw invalid("未知工具");
            };
            Object params = parse(arguments, type);
            safeArgs = json.writeValueAsString(params);
            Object data = switch (name) {
                case "search_available_rooms" -> search((SearchAvailableRooms) params);
                case "get_price_quote" -> price((GetPriceQuote) params);
                case "list_my_orders" -> myOrders(ctx);
                case "list_menu" -> menu();
                case "propose_booking" -> booking((ProposeBooking) params, ctx);
                case "propose_payment" -> orderAction(((ProposePayment) params).orderId(), ctx, PendingAction.Type.PAYMENT);
                case "propose_cancel" -> orderAction(((ProposeCancel) params).orderId(), ctx, PendingAction.Type.CANCEL);
                case "propose_meal_order" -> meal((ProposeMealOrder) params, ctx);
                default -> throw invalid("未知工具");
            };
            if (data instanceof PendingAction action) {
                card = action.card();
                result = Map.of("ok", true, "data", fields("actionId", action.id(), "summary", card.get("title"),
                        "total", action.total(), "expiresInSeconds", card.get("ttlSeconds")),
                        "note", "确认卡片已展示给用户，用户点击「确认」后才会执行，不要声称已经完成");
            } else result = Map.of("ok", true, "data", data);
        } catch (BusinessException e) { result = Map.of("ok", false, "error", e.getMessage()); }
        catch (Exception e) {
            log.error("agent.tool user={} tool={} failed", current, name, e);
            result = Map.of("ok", false, "error", "系统繁忙，请稍后再试");
        }
        log.info("agent.tool user={} tool={} args={} ms={} result={}", current, name,
                safeArgs.substring(0, Math.min(200, safeArgs.length())), (System.nanoTime() - started) / 1_000_000,
                result.getOrDefault("error", "ok"));
        return new ToolResult(encode(result), card);
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
        validateRoomNumber(p.roomNumber());
        return quoteData(orders.quoteRoomService(p.roomNumber(), date(p.checkInDate(), "checkInDate").atTime(14, 0),
                date(p.checkOutDate(), "checkOutDate").atTime(12, 0)));
    }

    private Map<String, Object> quoteData(RoomQuoteVO q) {
        return fields("roomNumber", q.getRoomNumber(), "roomType", roomType(q.getRoomType()),
                "floor", q.getFloor(), "nights", q.getNights(), "total", q.getTotal());
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

    private PendingAction booking(ProposeBooking p, ToolContext ctx) {
        validateRoomNumber(p.roomNumber());
        RoomQuoteVO quote = orders.quoteRoomService(p.roomNumber(), date(p.checkInDate(), "checkInDate").atTime(14, 0),
                date(p.checkOutDate(), "checkOutDate").atTime(12, 0));
        User user = users.findUserById(ctx.userId());
        if (user == null) throw new BusinessException(HttpStatus.NOT_FOUND, "用户不存在");
        Map<String, Object> params = fields("roomNumber", quote.getRoomNumber(), "checkIn", quote.getCheckIn(), "checkOut", quote.getCheckOut(),
                "guestName", user.getName(), "guestPhone", user.getPhone(), "guestIdCard", user.getIdCardNumber());
        List<List<String>> lines = List.of(List.of("房间", quote.getRoomNumber() + " · " + roomType(quote.getRoomType()) + " · " + quote.getFloor() + " 楼"),
                List.of("入住", time(quote.getCheckIn())), List.of("离店", time(quote.getCheckOut())), List.of("入住人", "本人（" + user.getName() + "）"));
        List<List<String>> details = quote.getNights().entrySet().stream().map(night -> List.of(night.getKey().toString(), PendingActionService.money(night.getValue()))).toList();
        return pending.create(ctx, PendingAction.Type.BOOKING, params, quote.getTotal(), lines, details);
    }

    private PendingAction orderAction(Long id, ToolContext ctx, PendingAction.Type type) {
        if (id == null || id <= 0) throw invalid("orderId 必须为有效订单号");
        RoomOrder order = orderMapper.getRoomOrderById(id);
        if (order == null) throw new BusinessException(HttpStatus.NOT_FOUND, "订单不存在");
        if (!Objects.equals(order.getUserId(), ctx.userId())) throw new BusinessException(HttpStatus.FORBIDDEN, "无权限操作他人的订单");
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        if (type == PendingAction.Type.PAYMENT) {
            if (!Objects.equals(order.getStatus(), 0) || !Objects.equals(order.getPayStatus(), 0)) throw invalid("只能支付进行中、未支付的订单");
            if (order.getCreatedAt().isBefore(now.minusMinutes(15))) throw invalid("已超过支付期限");
        } else if (!Objects.equals(order.getStatus(), 0) || !(Objects.equals(order.getPayStatus(), 0) || Objects.equals(order.getPayStatus(), 1)) || !order.getCheckinTime().isAfter(now))
            throw invalid("只能取消尚未入住的进行中订单");
        Room room = rooms.queryRoomById(Math.toIntExact(order.getRoomId()), true);
        List<List<String>> lines = new ArrayList<>(List.of(List.of("订单号", id.toString()), List.of("房间", room == null ? "" : room.getRoomNumber()),
                List.of("入住", time(order.getCheckinTime())), List.of("离店", time(order.getCheckoutTime()))));
        if (type == PendingAction.Type.CANCEL && Objects.equals(order.getPayStatus(), 1)) lines.add(List.of("提示", "已付款项将标记为已退款"));
        return pending.create(ctx, type, Map.of("orderId", id), order.getTotalAmount(), lines, List.of());
    }

    private PendingAction meal(ProposeMealOrder p, ToolContext ctx) {
        if (p.items() == null || p.items().isEmpty() || p.items().size() > 20) throw invalid("点餐明细必须为1至20行");
        if (p.address() == null || p.address().isBlank() || p.address().length() > 255) throw invalid("送餐地址不能为空且不能超过255字");
        if (p.remarks() != null && p.remarks().length() > 500) throw invalid("备注不能超过500字");
        for (MealItem item : p.items()) {
            if (item == null || item.dishId() == null || item.dishId() <= 0 || item.quantity() == null || item.quantity() < 1 || item.quantity() > 20)
                throw invalid("菜品id不能为空且数量必须为1至20");
        }
        BigDecimal total = BigDecimal.ZERO;
        List<List<String>> details = new ArrayList<>();
        for (MealItem item : p.items()) {
            Dish dish = dishes.getDishById(item.dishId());
            if (dish == null) throw new BusinessException(HttpStatus.NOT_FOUND, "菜品不存在");
            if (!Objects.equals(dish.getStatus(), 1)) throw invalid("菜品已下架");
            BigDecimal subtotal = dish.getPrice().multiply(BigDecimal.valueOf(item.quantity())); total = total.add(subtotal);
            details.add(List.of(dish.getName() + " × " + item.quantity(), PendingActionService.money(subtotal)));
        }
        return pending.create(ctx, PendingAction.Type.MEAL_ORDER, fields("items", p.items(), "address", p.address(), "remarks", p.remarks()), total,
                List.of(List.of("送餐地址", p.address()), List.of("备注", p.remarks() == null ? "" : p.remarks())), details);
    }

    private String roomType(Integer type) { return switch (type) { case 0 -> "单人间"; case 1 -> "双人间"; case 2 -> "套房"; default -> "未知"; }; }

    private String time(LocalDateTime value) { return value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")); }

    private void validateRoomNumber(String value) {
        if (value == null || value.isBlank() || value.length() > 20) throw invalid("roomNumber 不能为空且不能超过20字");
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
