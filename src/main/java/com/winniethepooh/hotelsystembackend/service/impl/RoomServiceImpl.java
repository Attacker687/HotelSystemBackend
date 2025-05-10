package com.winniethepooh.hotelsystembackend.service.impl;

import com.winniethepooh.hotelsystembackend.dto.InsertRoomOrderDTO;
import com.winniethepooh.hotelsystembackend.entity.Individual;
import com.winniethepooh.hotelsystembackend.entity.Room;
import com.winniethepooh.hotelsystembackend.entity.RoomOrder;
import com.winniethepooh.hotelsystembackend.enums.RoomStatus;
import com.winniethepooh.hotelsystembackend.mapper.RoomMapper;
import com.winniethepooh.hotelsystembackend.mapper.UserMapper;
import com.winniethepooh.hotelsystembackend.service.RoomService;
import com.winniethepooh.hotelsystembackend.vo.GetAllRoomsVO;
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

    @Override
    public List<GetAllRoomsVO> getAllRoomsService() {
        List<GetAllRoomsVO> allRoomsVOList = new ArrayList<>();
        List<Room> roomList = roomMapper.getAllRooms();
        for (Room room : roomList) {
            GetAllRoomsVO getAllRoomsVO = new GetAllRoomsVO();
            getAllRoomsVO.setId(Math.toIntExact(room.getId()));
            getAllRoomsVO.setNumber(Integer.valueOf(room.getRoomNumber()));
            getAllRoomsVO.setPrice(room.getPrice());
            getAllRoomsVO.setStatus(RoomStatus.getDescriptionByCode(room.getStatus()));
            getAllRoomsVO.setType(roomMapper.getTypeNameByRoomId(room.getId()));
            allRoomsVOList.add(getAllRoomsVO);
        }
        return allRoomsVOList;
    }

    @Override
    public void modifyRoomStatusService(Integer roomNumber, String status) {
        roomMapper.modifyRoomStatus(roomNumber, RoomStatus.getCodeByDescription(status));
    }


}
