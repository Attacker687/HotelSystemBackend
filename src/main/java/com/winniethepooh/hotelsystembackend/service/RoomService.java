package com.winniethepooh.hotelsystembackend.service;

import com.winniethepooh.hotelsystembackend.dto.InsertRoomOrderDTO;
import com.winniethepooh.hotelsystembackend.vo.GetAllRoomsVO;

import java.util.List;

public interface RoomService {
    List<GetAllRoomsVO> getAllRoomsService();

    void modifyRoomStatusService(Integer roomNumber, String status);

}
