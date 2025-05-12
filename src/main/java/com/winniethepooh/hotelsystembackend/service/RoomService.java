package com.winniethepooh.hotelsystembackend.service;

import com.winniethepooh.hotelsystembackend.dto.InsertRoomDTO;
import com.winniethepooh.hotelsystembackend.vo.QueryRoomsVO;

import java.util.List;

public interface RoomService {
    List<QueryRoomsVO> queryRoomsService(Integer page, Integer pageSize, Integer roomNumber, Integer roomTypeId, Integer status);

    void modifyRoomStatusService(Integer id, Integer status);

    QueryRoomsVO queryRoomByIdService(Integer id);

    void insertRoomService(InsertRoomDTO insertRoomDTO);

    void modifyRoomInfoService(InsertRoomDTO insertRoomDTO, Integer id);

    void deleteRoomService(Integer id);
}
