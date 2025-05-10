package com.winniethepooh.hotelsystembackend.service;


import com.winniethepooh.hotelsystembackend.dto.CommentOrderDTO;
import com.winniethepooh.hotelsystembackend.dto.InsertRoomOrderDTO;
import com.winniethepooh.hotelsystembackend.vo.GetAllRoomOrderVO;
import com.winniethepooh.hotelsystembackend.vo.OrderQueryVO;

import java.time.LocalDate;
import java.util.List;

public interface OrderService {
    OrderQueryVO queryOrderService(LocalDate startDate, LocalDate endDate, Integer id);

    void commentOrderService(CommentOrderDTO commentOrderDTO);

    List<GetAllRoomOrderVO> getAllRoomOrderService();

    void insertRoomOrderService(InsertRoomOrderDTO insertRoomOrderDTO);
}
