package com.winniethepooh.hotelsystembackend.mapper;

import com.winniethepooh.hotelsystembackend.entity.Room;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface RoomMapper {

    List<Room> getAllRooms();

    String getTypeNameByRoomId(Long id);

    void modifyRoomStatus(Integer roomNumber, Integer status);

    Long getRoomIdByRoomNumber(String roomNumber);
}
