package com.winniethepooh.hotelsystembackend.service;


import com.winniethepooh.hotelsystembackend.dto.CommentOrderDTO;
import com.winniethepooh.hotelsystembackend.dto.InsertMealOrderDTO;
import com.winniethepooh.hotelsystembackend.dto.InsertRoomOrderDTO;
import com.winniethepooh.hotelsystembackend.dto.ModifyRoomOrderDTO;
import com.winniethepooh.hotelsystembackend.vo.GetAllRoomOrderVO;
import com.winniethepooh.hotelsystembackend.vo.OrderQueryVO;
import com.winniethepooh.hotelsystembackend.vo.PageBean;
import com.winniethepooh.hotelsystembackend.vo.RoomQuoteVO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface OrderService {
    OrderQueryVO queryOrderService(LocalDate startDate, LocalDate endDate, Integer id);

    RoomQuoteVO quoteRoomService(String roomNumber, LocalDateTime checkin, LocalDateTime checkout);

    List<RoomQuoteVO> searchAvailableRoomsService(Integer roomType, LocalDateTime checkin, LocalDateTime checkout, int limit);

    void commentOrderService(CommentOrderDTO commentOrderDTO);

    PageBean<GetAllRoomOrderVO> getAllRoomOrderService(Integer page, Integer pageSize);

    void insertRoomOrderByFrontService(InsertRoomOrderDTO insertRoomOrderDTO);

    void modifyRoomOrderService(Integer id, ModifyRoomOrderDTO modifyRoomOrderDTO);

    void deleteRoomOrderService(Integer id);

    Long insertRoomOrderByUserService(InsertRoomOrderDTO insertRoomOrderDTO);

    void payRoomOrderService(Long id);

    void cancelRoomOrderService(Long id);

    void insertMealOrderService(InsertMealOrderDTO insertMealOrderDTO);

    void cancelMealOrderService(Integer id);
}
