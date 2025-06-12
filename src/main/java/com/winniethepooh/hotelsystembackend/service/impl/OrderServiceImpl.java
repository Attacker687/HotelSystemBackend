package com.winniethepooh.hotelsystembackend.service.impl;

import com.winniethepooh.hotelsystembackend.constant.RoomOrderPayStatusConstant;
import com.winniethepooh.hotelsystembackend.constant.RoomOrderStatusConstant;
import com.winniethepooh.hotelsystembackend.constant.RoomStatusConstant;
import com.winniethepooh.hotelsystembackend.constant.RoomTypeConstant;
import com.winniethepooh.hotelsystembackend.context.BaseContext;
import com.winniethepooh.hotelsystembackend.dto.CommentOrderDTO;
import com.winniethepooh.hotelsystembackend.dto.InsertMealOrderDTO;
import com.winniethepooh.hotelsystembackend.dto.InsertRoomOrderDTO;
import com.winniethepooh.hotelsystembackend.dto.ModifyRoomOrderDTO;
import com.winniethepooh.hotelsystembackend.entity.*;
import com.winniethepooh.hotelsystembackend.exception.DuplicatedException;
import com.winniethepooh.hotelsystembackend.exception.MismatchException;
import com.winniethepooh.hotelsystembackend.exception.UnknownOrderTypeException;
import com.winniethepooh.hotelsystembackend.mapper.OrderMapper;
import com.winniethepooh.hotelsystembackend.mapper.RoomMapper;
import com.winniethepooh.hotelsystembackend.mapper.UserMapper;
import com.winniethepooh.hotelsystembackend.service.OrderService;
import com.winniethepooh.hotelsystembackend.utils.LocalDateUtil;
import com.winniethepooh.hotelsystembackend.vo.GetAllRoomOrderVO;
import com.winniethepooh.hotelsystembackend.vo.OrderQueryVO;
import com.winniethepooh.hotelsystembackend.vo.PageBean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@Slf4j
public class OrderServiceImpl implements OrderService {
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private RoomMapper roomMapper;

    @Override
    public OrderQueryVO queryOrderService(LocalDate startDate, LocalDate endDate, Integer id) {
        OrderQueryVO orderQueryVO = new OrderQueryVO();
        orderQueryVO.setMealOrderList(orderMapper.getMealOrdersByDate(startDate, endDate, id));
        orderQueryVO.setRoomOrderList(orderMapper.getRoomOrdersByDate(startDate, endDate, id));
        return orderQueryVO;
    }

    @Override
    public void commentOrderService(CommentOrderDTO commentOrderDTO) {
        if (commentOrderDTO.getType().equals("room")) {
            orderMapper.insertRoomComment(commentOrderDTO.getId(), commentOrderDTO.getComment(), commentOrderDTO.getCommentStar());
        } else if (commentOrderDTO.getType().equals("meal")) {
            orderMapper.insertMealComment(commentOrderDTO.getId(), commentOrderDTO.getComment(), commentOrderDTO.getCommentStar());
        } else {
            throw new UnknownOrderTypeException("未知的订单类型");
        }
    }

    @Override
    public PageBean<GetAllRoomOrderVO> getAllRoomOrderService(Integer page, Integer pageSize) {
        PageBean<GetAllRoomOrderVO> pageBean = new PageBean<>();
        Integer offset = (page - 1) * pageSize;
        List<RoomOrder> roomOrderList = orderMapper.getAllRoomOrderList(offset, pageSize);
        List<GetAllRoomOrderVO> allRoomOrderVOList = new ArrayList<>();
        for (RoomOrder roomOrder : roomOrderList) {
            GetAllRoomOrderVO getAllRoomOrderVO = new GetAllRoomOrderVO();
            if (roomOrder.getUserId() != null) { // 订单的用户在本系统注册过
                User user = userMapper.findUserById(roomOrder.getUserId());
                getAllRoomOrderVO.setName(user.getName());
                getAllRoomOrderVO.setPhone(user.getPhone());
                getAllRoomOrderVO.setIdCard(user.getIdCardNumber());
            } else { // 用户未注册的情况
                Individual individual = userMapper.findIndividualById(roomOrder.getIndividualId());
                getAllRoomOrderVO.setName(individual.getName());
                getAllRoomOrderVO.setIdCard(individual.getIdCardNumber());
                getAllRoomOrderVO.setPhone(individual.getPhone());
            }
            getAllRoomOrderVO.setId(Math.toIntExact(roomOrder.getId()));
            getAllRoomOrderVO.setRoomNumber(roomMapper.queryRoomById(Math.toIntExact(roomOrder.getRoomId()), true).getRoomNumber());
            getAllRoomOrderVO.setCheckInTime(roomOrder.getCheckinTime());
            getAllRoomOrderVO.setCheckOutTime(roomOrder.getCheckoutTime());
            getAllRoomOrderVO.setStatus(roomOrder.getStatus());
            getAllRoomOrderVO.setComment(roomOrder.getComment());
            getAllRoomOrderVO.setCommentStar(roomOrder.getCommentStar());
            allRoomOrderVOList.add(getAllRoomOrderVO);
        }
        int total = orderMapper.getAllRoomOrderCount();
        pageBean.setList(allRoomOrderVOList);
        pageBean.setTotal(total);
        return pageBean;
    }

    @Override
    @Transactional
    public void insertRoomOrderByFrontService(InsertRoomOrderDTO insertRoomOrderDTO) {
        Individual individual = findIndividualOrElseCreate(insertRoomOrderDTO);
        RoomOrder roomOrder = new RoomOrder();
        roomOrder.setIndividualId(individual.getId());
        roomOrder.setCheckinTime(insertRoomOrderDTO.getCheckInTime());
        roomOrder.setCheckoutTime(insertRoomOrderDTO.getCheckOutTime());
        Long roomId = roomMapper.getRoomIdByRoomNumber(insertRoomOrderDTO.getRoomNumber());
        roomOrder.setRoomId(roomId);
        orderMapper.insertRoomOrderV1(roomOrder);
        if (Objects.equals(roomOrder.getCheckinTime().toLocalDate(), LocalDate.now()))
            roomMapper.modifyRoomStatus(Math.toIntExact(roomId), RoomStatusConstant.OCCUPIED);
    }

    @Override
    public void modifyRoomOrderService(Integer id, ModifyRoomOrderDTO modifyRoomOrderDTO) {
        orderMapper.modifyRoomOrder(id, modifyRoomOrderDTO);
    }

    @Override
    public void deleteRoomOrderService(Integer id) {
        orderMapper.deleteRoomOrder(id);
    }

    @Override
    public Long insertRoomOrderByUserService(InsertRoomOrderDTO insertRoomOrderDTO) {
        BigDecimal totalAmount = new BigDecimal("0.0");
        Room room = roomMapper.getRoomByRoomNumber(insertRoomOrderDTO.getRoomNumber());
        Individual individual = findIndividualOrElseCreate(insertRoomOrderDTO);
        RoomOrder roomOrder = new RoomOrder();
        roomOrder.setUserId(BaseContext.getCurrentId());
        roomOrder.setIndividualId(individual.getId());
        roomOrder.setCheckinTime(insertRoomOrderDTO.getCheckInTime());
        roomOrder.setCheckoutTime(insertRoomOrderDTO.getCheckOutTime());
        LocalDate startDate = insertRoomOrderDTO.getCheckInTime().toLocalDate();
        LocalDate endDate = insertRoomOrderDTO.getCheckOutTime().toLocalDate();
        List<LocalDate> dateList = LocalDateUtil.getDatesBetween(startDate, endDate.minusDays(1));
        for (LocalDate date : dateList) {
            BigDecimal price = roomMapper.getRoomPriceByTypeAndDate(room.getRoomType(), date);
            if (price == null) price = RoomTypeConstant.getDefaultPrice(room.getRoomType());
            totalAmount = totalAmount.add(price);
        }
        roomOrder.setTotalAmount(totalAmount);
        Long roomId = roomMapper.getRoomIdByRoomNumber(insertRoomOrderDTO.getRoomNumber());
        roomOrder.setRoomId(roomId);
        orderMapper.insertRoomOrderV2(roomOrder);
        return roomOrder.getId();
    }

    @Override
    public void payRoomOrderService(Long id) {
        RoomOrder roomOrder = orderMapper.getRoomOrderById(id);
        if (roomOrder.getPayStatus() != RoomOrderPayStatusConstant.UNPAID)
            throw new DuplicatedException("支付失败，房间已被预订");
        if (!Objects.equals(roomOrder.getUserId(), BaseContext.getCurrentId()))
            throw new MismatchException("数据不匹配，操作失败");
        orderMapper.modifyRoomOrderPayStatus(id, RoomOrderPayStatusConstant.PAID);
    }

    @Override
    public void cancelRoomOrderService(Long id) {
        RoomOrder roomOrder = orderMapper.getRoomOrderById(id);
        if (!Objects.equals(roomOrder.getUserId(), BaseContext.getCurrentId()))
            throw new MismatchException("数据不匹配，操作失败");
        orderMapper.modifyRoomOrderStatus(id, RoomOrderStatusConstant.CANCELLED);
    }

    @Override
    @Transactional
    public void insertMealOrderService(InsertMealOrderDTO insertMealOrderDTO) {
        insertMealOrderDTO.setUserId(BaseContext.getCurrentId());
        List<MealOrderItem> itemList = insertMealOrderDTO.getItemList();
        if (CollectionUtils.isEmpty(itemList)) {
            throw new RuntimeException("订单明细不能为空");
        }
        orderMapper.insertMealOrder(insertMealOrderDTO);
        if (insertMealOrderDTO.getId() == null) {
            log.error("主键id回填失败");
            throw new RuntimeException("主订单ID获取失败");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (MealOrderItem item : itemList) {
            total = total.add(item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
            item.setMealOrderId(insertMealOrderDTO.getId());
            orderMapper.insertMealOrderItem(item);
        }
        if (total.compareTo(insertMealOrderDTO.getTotalAmount()) != 0) {
            log.error("餐饮订单金额校验失败：前端 = {}, 实际 = {}", insertMealOrderDTO.getTotalAmount(), total);
            throw new RuntimeException("订单金额校验失败");
        }
    }


    private Individual findIndividualOrElseCreate(InsertRoomOrderDTO insertRoomOrderDTO) {
        Individual individual = userMapper.findIndividual
                (insertRoomOrderDTO.getName(), insertRoomOrderDTO.getPhone(), insertRoomOrderDTO.getIdCard());
        if (individual == null) {
            individual = new Individual();
            individual.setIdCardNumber(insertRoomOrderDTO.getIdCard());
            individual.setName(insertRoomOrderDTO.getName());
            individual.setPhone(insertRoomOrderDTO.getPhone());
            userMapper.createIndividual(individual);
        }
        return individual;
    }
}