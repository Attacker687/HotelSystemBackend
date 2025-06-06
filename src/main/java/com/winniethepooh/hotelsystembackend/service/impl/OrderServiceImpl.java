package com.winniethepooh.hotelsystembackend.service.impl;

import com.winniethepooh.hotelsystembackend.constant.RoomStatusConstant;
import com.winniethepooh.hotelsystembackend.dto.CommentOrderDTO;
import com.winniethepooh.hotelsystembackend.dto.InsertRoomOrderDTO;
import com.winniethepooh.hotelsystembackend.dto.ModifyRoomOrderDTO;
import com.winniethepooh.hotelsystembackend.entity.Individual;
import com.winniethepooh.hotelsystembackend.entity.RoomOrder;
import com.winniethepooh.hotelsystembackend.entity.User;
import com.winniethepooh.hotelsystembackend.exception.UnknownOrderTypeException;
import com.winniethepooh.hotelsystembackend.mapper.OrderMapper;
import com.winniethepooh.hotelsystembackend.mapper.RoomMapper;
import com.winniethepooh.hotelsystembackend.mapper.UserMapper;
import com.winniethepooh.hotelsystembackend.service.OrderService;
import com.winniethepooh.hotelsystembackend.vo.GetAllRoomOrderVO;
import com.winniethepooh.hotelsystembackend.vo.OrderQueryVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
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
    public List<GetAllRoomOrderVO> getAllRoomOrderService() {
        List<GetAllRoomOrderVO> allRoomOrderVOList = new ArrayList<>();
        List<RoomOrder> roomOrderList = orderMapper.getAllRoomOrderList();
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
            getAllRoomOrderVO.setRoomNumber(roomMapper.queryRoomById(Math.toIntExact(roomOrder.getRoomId())).getRoomNumber());
            getAllRoomOrderVO.setCheckInTime(roomOrder.getCheckinTime());
            getAllRoomOrderVO.setCheckOutTime(roomOrder.getCheckoutTime());
            getAllRoomOrderVO.setStatus(roomOrder.getStatus());
            getAllRoomOrderVO.setComment(roomOrder.getComment());
            getAllRoomOrderVO.setCommentStar(roomOrder.getCommentStar());
            allRoomOrderVOList.add(getAllRoomOrderVO);
        }
        return allRoomOrderVOList;
    }

    @Override
    @Transactional
    public void insertRoomOrderService(InsertRoomOrderDTO insertRoomOrderDTO) {
        Individual individual = userMapper.findIndividual
                (insertRoomOrderDTO.getName(), insertRoomOrderDTO.getPhone(), insertRoomOrderDTO.getIdCard());
        if (individual == null) {
            individual = new Individual();
            individual.setIdCardNumber(insertRoomOrderDTO.getIdCard());
            individual.setName(insertRoomOrderDTO.getName());
            individual.setPhone(insertRoomOrderDTO.getPhone());
            userMapper.createIndividual(individual);
        }
        RoomOrder roomOrder = new RoomOrder();
        roomOrder.setIndividualId(individual.getId());
        roomOrder.setCheckinTime(insertRoomOrderDTO.getCheckInTime());
        roomOrder.setCheckoutTime(insertRoomOrderDTO.getCheckOutTime());
        Long roomId = roomMapper.getRoomIdByRoomNumber(insertRoomOrderDTO.getRoomNumber());
        roomOrder.setRoomId(roomId);
        orderMapper.insertRoomOrderV1(roomOrder);
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


}
