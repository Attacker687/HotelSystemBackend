package com.winniethepooh.hotelsystembackend.service;

import com.winniethepooh.hotelsystembackend.dto.InsertRoomDTO;
import com.winniethepooh.hotelsystembackend.vo.PageBean;
import com.winniethepooh.hotelsystembackend.vo.QueryRoomsVO;
import com.winniethepooh.hotelsystembackend.vo.RoomStatusWallVO;

import java.time.LocalDate;
import java.util.List;

public interface RoomService {
    void modifyRoomStatusService(Integer id, Integer status);

    QueryRoomsVO queryRoomByIdService(Integer id);

    void insertRoomService(InsertRoomDTO insertRoomDTO);

    void modifyRoomInfoService(InsertRoomDTO insertRoomDTO, Integer id);

    void deleteRoomService(Integer id);

    List<RoomStatusWallVO> getRoomStatusWallService();

    PageBean<QueryRoomsVO> queryRoomsService(Integer page, Integer pageSize, Integer roomNumber, Integer roomType, Integer status, LocalDate date);
}
