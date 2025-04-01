package com.winniethepooh.hotelsystembackend.service;

import com.winniethepooh.hotelsystembackend.dto.InsertRoomOrderDTO;
import com.winniethepooh.hotelsystembackend.vo.QueryRoomsVO;

import java.util.List;

public interface RoomService {
    List<QueryRoomsVO> queryRoomsService(Integer page, Integer pageSize, Integer roomNumber, Integer roomTypeId, Integer status);

    void modifyRoomStatusService(Integer roomNumber, Integer status);

    QueryRoomsVO queryRoomByIdService(Integer id);

    void insertRoomService(InsertRoomOrderDTO insertRoomOrderDTO);
}
