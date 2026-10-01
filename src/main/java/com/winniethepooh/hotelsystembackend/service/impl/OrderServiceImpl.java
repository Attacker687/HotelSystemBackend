package com.winniethepooh.hotelsystembackend.service.impl;

import com.winniethepooh.hotelsystembackend.constant.*;
import com.winniethepooh.hotelsystembackend.context.BaseContext;
import com.winniethepooh.hotelsystembackend.dto.*;
import com.winniethepooh.hotelsystembackend.entity.*;
import com.winniethepooh.hotelsystembackend.exception.BusinessException;
import com.winniethepooh.hotelsystembackend.exception.UnknownOrderTypeException;
import com.winniethepooh.hotelsystembackend.mapper.FoodMapper;
import com.winniethepooh.hotelsystembackend.mapper.OrderMapper;
import com.winniethepooh.hotelsystembackend.mapper.RoomMapper;
import com.winniethepooh.hotelsystembackend.mapper.UserMapper;
import com.winniethepooh.hotelsystembackend.service.OrderService;
import com.winniethepooh.hotelsystembackend.utils.LocalDateUtil;
import com.winniethepooh.hotelsystembackend.vo.GetAllRoomOrderVO;
import com.winniethepooh.hotelsystembackend.vo.OrderQueryVO;
import com.winniethepooh.hotelsystembackend.vo.PageBean;
import com.winniethepooh.hotelsystembackend.vo.RoomQuoteVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class OrderServiceImpl implements OrderService {
    @Autowired private OrderMapper orderMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private RoomMapper roomMapper;
    @Autowired private FoodMapper foodMapper;

    @Override
    public OrderQueryVO queryOrderService(LocalDate startDate, LocalDate endDate, Integer id) {
        OrderQueryVO vo = new OrderQueryVO();
        vo.setMealOrderList(orderMapper.getMealOrdersByDate(startDate, endDate, id));
        vo.setRoomOrderList(orderMapper.getRoomOrdersByDate(startDate, endDate, id));
        return vo;
    }

    @Override
    public RoomQuoteVO quoteRoomService(String roomNumber, LocalDateTime checkin, LocalDateTime checkout) {
        validateStay(checkin, checkout, true);
        Room room = requireRoom(roomMapper.getRoomByRoomNumber(roomNumber));
        checkOverlap(room.getId(), checkin, checkout, null);
        return quote(room, checkin, checkout, prices(room, checkin, checkout));
    }

    @Override
    public List<RoomQuoteVO> searchAvailableRoomsService(Integer roomType, LocalDateTime checkin, LocalDateTime checkout, int limit) {
        validateStay(checkin, checkout, true);
        Map<Integer, Map<LocalDate, BigDecimal>> byType = new HashMap<>();
        return roomMapper.findAvailableRooms(roomType, checkin, checkout, limit).stream().map(room ->
                quote(room, checkin, checkout, byType.computeIfAbsent(room.getRoomType(), ignored -> prices(room, checkin, checkout))))
                .toList();
    }

    private static RoomQuoteVO quote(Room room, LocalDateTime checkin, LocalDateTime checkout, Map<LocalDate, BigDecimal> nights) {
        RoomQuoteVO quote = new RoomQuoteVO();
        quote.setRoomNumber(room.getRoomNumber()); quote.setRoomType(room.getRoomType()); quote.setFloor(room.getFloor());
        quote.setCheckIn(checkin); quote.setCheckOut(checkout); quote.setNights(new LinkedHashMap<>(nights)); quote.setTotal(total(nights));
        return quote;
    }

    @Override
    public void commentOrderService(CommentOrderDTO dto) {
        int changed;
        if ("room".equals(dto.getType()))
            changed = orderMapper.insertRoomComment(dto.getId(), dto.getComment(), dto.getCommentStar(), BaseContext.getCurrentId());
        else if ("meal".equals(dto.getType()))
            changed = orderMapper.insertMealComment(dto.getId(), dto.getComment(), dto.getCommentStar(), BaseContext.getCurrentId());
        else throw new UnknownOrderTypeException("未知的订单类型");
        if (changed == 0) throw new BusinessException(HttpStatus.CONFLICT, "只能评价本人已完成的订单");
    }

    @Override
    public PageBean<GetAllRoomOrderVO> getAllRoomOrderService(Integer page, Integer pageSize) {
        PageBean<GetAllRoomOrderVO> result = new PageBean<>();
        result.setList(orderMapper.getAllRoomOrderList((page - 1) * pageSize, pageSize));
        result.setTotal(orderMapper.getAllRoomOrderCount());
        return result;
    }

    @Override
    @Transactional
    public void insertRoomOrderByFrontService(InsertRoomOrderDTO dto) {
        insertRoomOrder(dto, null, Boolean.TRUE.equals(dto.getPaid()));
    }

    @Override
    @Transactional
    public Long insertRoomOrderByUserService(InsertRoomOrderDTO dto) {
        return insertRoomOrder(dto, BaseContext.getCurrentId(), false);
    }

    private Long insertRoomOrder(InsertRoomOrderDTO dto, Integer userId, boolean paid) {
        validateStay(dto.getCheckInTime(), dto.getCheckOutTime(), true);
        Room room = requireRoom(roomMapper.lockRoomByNumber(dto.getRoomNumber()));
        checkOverlap(room.getId(), dto.getCheckInTime(), dto.getCheckOutTime(), null);
        Map<LocalDate, BigDecimal> nights = prices(room, dto.getCheckInTime(), dto.getCheckOutTime());
        Individual individual = findIndividualOrElseCreate(dto);
        RoomOrder order = new RoomOrder();
        order.setUserId(userId);
        order.setIndividualId(individual.getId());
        order.setRoomId(room.getId());
        order.setCheckinTime(dto.getCheckInTime());
        order.setCheckoutTime(dto.getCheckOutTime());
        order.setTotalAmount(total(nights));
        order.setPayStatus(paid ? RoomOrderPayStatusConstant.PAID : RoomOrderPayStatusConstant.UNPAID);
        if (userId == null) orderMapper.insertRoomOrderV1(order);
        else orderMapper.insertRoomOrderV2(order);
        orderMapper.insertRoomOrderNights(order.getId(), nights);
        if (userId == null && !LocalDateTime.now().isBefore(order.getCheckinTime()))
            roomMapper.enableAvailableRoom(Math.toIntExact(room.getId()));
        return order.getId();
    }

    @Override
    @Transactional
    public void modifyRoomOrderService(Integer id, ModifyRoomOrderDTO dto) {
        RoomOrder original = requireOrder(orderMapper.getRoomOrderById(id.longValue()));
        int target;
        try { target = dto.getRoomId() == null ? Math.toIntExact(original.getRoomId()) : Integer.parseInt(dto.getRoomId()); }
        catch (NumberFormatException e) { throw new BusinessException(HttpStatus.BAD_REQUEST, "roomId 必须是房间 id"); }
        int source = Math.toIntExact(original.getRoomId());
        // 下单、改期都先锁房间；换房按 id 顺序锁两间，避免两张订单互换时死锁。
        Room first = requireRoom(roomMapper.lockRoomById(Math.min(source, target)));
        Room last = source == target ? first : requireRoom(roomMapper.lockRoomById(Math.max(source, target)));
        Room room = target == Math.min(source, target) ? first : last;
        RoomOrder order = requireOrder(orderMapper.getRoomOrderByIdForUpdate(id.longValue()));
        if (order.getStatus() != RoomOrderStatusConstant.ONGOING || !Objects.equals(order.getRoomId(), original.getRoomId()))
            throw new BusinessException(HttpStatus.CONFLICT, "订单已结束或已变更，不能修改");
        LocalDateTime checkin = dto.getCheckInTime() == null ? order.getCheckinTime() : dto.getCheckInTime();
        LocalDateTime checkout = dto.getCheckOutTime() == null ? order.getCheckoutTime() : dto.getCheckOutTime();
        validateStay(checkin, checkout, false);
        checkOverlap(room.getId(), checkin, checkout, order.getId());
        Map<LocalDate, BigDecimal> nights = prices(room, checkin, checkout);
        order.setRoomId(room.getId());
        order.setCheckinTime(checkin);
        order.setCheckoutTime(checkout);
        order.setTotalAmount(total(nights));
        if (orderMapper.modifyRoomOrder(order) == 0) throw new BusinessException(HttpStatus.CONFLICT, "订单状态已变更");
        orderMapper.deleteRoomOrderNights(order.getId());
        orderMapper.insertRoomOrderNights(order.getId(), nights);
        if (source != target && !LocalDateTime.now().isBefore(checkin) && LocalDateTime.now().isBefore(checkout)) {
            roomMapper.releaseOccupiedRoom(source);
            roomMapper.enableAvailableRoom(target);
        }
    }

    @Override
    public void deleteRoomOrderService(Integer id) { orderMapper.deleteRoomOrder(id); }

    @Override
    public void payRoomOrderService(Long id) {
        if (orderMapper.payRoomOrder(id, BaseContext.getCurrentId()) == 0) {
            requireOwnedOrder(id);
            throw new BusinessException(HttpStatus.CONFLICT, "订单已支付、已结束或超过支付期限");
        }
    }

    @Override
    public void cancelRoomOrderService(Long id) {
        if (orderMapper.cancelRoomOrder(id, BaseContext.getCurrentId(), LocalDateTime.now()) == 0) {
            requireOwnedOrder(id);
            throw new BusinessException(HttpStatus.CONFLICT, "只能取消尚未入住的进行中订单");
        }
    }

    private RoomOrder requireOwnedOrder(Long id) {
        RoomOrder order = requireOrder(orderMapper.getRoomOrderById(id));
        if (!Objects.equals(order.getUserId(), BaseContext.getCurrentId()))
            throw new BusinessException(HttpStatus.FORBIDDEN, "无权限操作他人的订单");
        return order;
    }

    private static RoomOrder requireOrder(RoomOrder order) {
        if (order == null) throw new BusinessException(HttpStatus.NOT_FOUND, "订单不存在");
        return order;
    }

    private static Room requireRoom(Room room) {
        if (room == null) throw new BusinessException(HttpStatus.NOT_FOUND, "房间不存在");
        return room;
    }

    private static void validateStay(LocalDateTime checkin, LocalDateTime checkout, boolean newBooking) {
        if (checkin == null || checkout == null) throw new BusinessException(HttpStatus.BAD_REQUEST, "入住和离店时间不能为空");
        long nights = ChronoUnit.DAYS.between(checkin.toLocalDate(), checkout.toLocalDate());
        if (!checkout.isAfter(checkin) || nights < 1) throw new BusinessException(HttpStatus.BAD_REQUEST, "离店日期必须晚于入住日期");
        if (nights > 30) throw new BusinessException(HttpStatus.BAD_REQUEST, "入住不能超过30晚");
        if (newBooking && checkin.toLocalDate().isBefore(LocalDate.now()))
            throw new BusinessException(HttpStatus.BAD_REQUEST, "入住日期不能早于今天");
    }

    private void checkOverlap(Long roomId, LocalDateTime checkin, LocalDateTime checkout, Long excludeId) {
        if (orderMapper.findOverlappingOrder(roomId, checkin, checkout, excludeId) != null)
            throw new BusinessException(HttpStatus.CONFLICT, "房间在该时段已被预订");
    }

    private Map<LocalDate, BigDecimal> prices(Room room, LocalDateTime checkin, LocalDateTime checkout) {
        LocalDate start = checkin.toLocalDate(), end = checkout.toLocalDate().minusDays(1);
        Map<LocalDate, BigDecimal> calendar = roomMapper.getPriceCalendars(room.getRoomType(), start, end).stream()
                .collect(Collectors.toMap(PriceCalendar::getDate, PriceCalendar::getPrice));
        Map<LocalDate, BigDecimal> nights = new LinkedHashMap<>();
        for (LocalDate date : LocalDateUtil.getDatesBetween(start, end))
            nights.put(date, calendar.getOrDefault(date, RoomTypeConstant.getDefaultPrice(room.getRoomType())));
        return nights;
    }

    private static BigDecimal total(Map<LocalDate, BigDecimal> nights) {
        return nights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    @Transactional
    public void insertMealOrderService(InsertMealOrderDTO dto) {
        List<MealOrderItem> items = dto.getItemList();
        if (CollectionUtils.isEmpty(items)) throw new BusinessException(HttpStatus.BAD_REQUEST, "订单明细不能为空");
        BigDecimal total = BigDecimal.ZERO;
        for (MealOrderItem item : items) {
            if (item == null || item.getDishId() == null || item.getQuantity() == null || item.getQuantity() < 1)
                throw new BusinessException(HttpStatus.BAD_REQUEST, "菜品和数量不能为空且数量必须大于0");
            Dish dish = foodMapper.getDishById(item.getDishId());
            if (dish == null) throw new BusinessException(HttpStatus.NOT_FOUND, "菜品不存在");
            if (!Objects.equals(dish.getStatus(), 1)) throw new BusinessException(HttpStatus.CONFLICT, "菜品已下架");
            item.setUnitPrice(dish.getPrice());
            item.setTotalPrice(dish.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
            total = total.add(item.getTotalPrice());
        }
        dto.setId(null);
        dto.setUserId(BaseContext.getCurrentId());
        dto.setTotalAmount(total);
        orderMapper.insertMealOrder(dto);
        for (MealOrderItem item : items) {
            item.setMealOrderId(dto.getId());
            orderMapper.insertMealOrderItem(item);
        }
    }

    @Override
    public void cancelMealOrderService(Integer id) {
        if (orderMapper.cancelMealOrder(id, BaseContext.getCurrentId()) == 0)
            throw new BusinessException(HttpStatus.CONFLICT, "只能取消本人的新餐饮订单");
    }

    private Individual findIndividualOrElseCreate(InsertRoomOrderDTO dto) {
        Individual individual = userMapper.findIndividual(dto.getName(), dto.getPhone(), dto.getIdCard());
        if (individual == null) {
            individual = new Individual();
            individual.setIdCardNumber(dto.getIdCard());
            individual.setName(dto.getName());
            individual.setPhone(dto.getPhone());
            userMapper.createIndividual(individual);
        }
        return individual;
    }
}
