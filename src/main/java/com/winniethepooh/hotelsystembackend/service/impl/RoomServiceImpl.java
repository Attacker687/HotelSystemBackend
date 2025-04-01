package com.winniethepooh.hotelsystembackend.service.impl;

import com.winniethepooh.hotelsystembackend.dto.InsertRoomOrderDTO;
import com.winniethepooh.hotelsystembackend.entity.Room;
import com.winniethepooh.hotelsystembackend.mapper.RoomMapper;
import com.winniethepooh.hotelsystembackend.mapper.UserMapper;
import com.winniethepooh.hotelsystembackend.service.RoomService;
import com.winniethepooh.hotelsystembackend.vo.QueryRoomsVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class RoomServiceImpl implements RoomService {
    @Autowired
    private RoomMapper roomMapper;
    @Autowired
    private UserMapper userMapper;

    private QueryRoomsVO convertToVO(Room room) {
        QueryRoomsVO vo = new QueryRoomsVO();
        vo.setId(Math.toIntExact(room.getId()));
        vo.setNumber(Integer.valueOf(room.getRoomNumber()));
        vo.setPrice(room.getPrice());
        vo.setStatus(room.getStatus());
        vo.setType(roomMapper.getTypeNameByRoomId(room.getId()));
        return vo;
    }

    @Override
    public List<QueryRoomsVO> queryRoomsService(Integer page, Integer pageSize, Integer roomNumber, Integer roomTypeId, Integer status) {
        if (page == null || page < 1) page = 1;
        if (pageSize == null || pageSize < 1) pageSize = 10;
        List<QueryRoomsVO> voList = new ArrayList<>();
        Integer offset = (page - 1) * pageSize;
        List<Room> roomList = roomMapper.queryRooms(pageSize, offset, roomNumber, roomTypeId, status);
        for (Room room : roomList) voList.add(convertToVO(room));
        return voList;
    }

    @Override
    public void modifyRoomStatusService(Integer roomNumber, Integer status) {
        roomMapper.modifyRoomStatus(roomNumber, status);
    }

    @Override
    public QueryRoomsVO queryRoomByIdService(Integer id) {
        Room room = roomMapper.queryRoomById(id);
        return convertToVO(room);
    }

    @Override
    public void insertRoomService(InsertRoomOrderDTO insertRoomOrderDTO) {
        roomMapper.insertRoom(insertRoomOrderDTO);
    }
}
