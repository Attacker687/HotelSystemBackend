package com.winniethepooh.hotelsystembackend.mapper;

import com.winniethepooh.hotelsystembackend.dto.InsertRoomDTO;
import com.winniethepooh.hotelsystembackend.entity.Room;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface RoomMapper {

    List<Room> queryRooms(Integer limit, Integer offset, Integer roomNumber, Integer roomType, Integer status);

    void modifyRoomStatus(Integer id, Integer status);

    Long getRoomIdByRoomNumber(String roomNumber);

    Room queryRoomById(Integer id);

    void insertRoom(InsertRoomDTO insertRoomDTO);

    void modifyRoomInfo(InsertRoomDTO dto, Integer id);

    void deleteRoom(Integer id);

    List<Integer> getExistFloors();

    Integer getRoomCount(LocalDate date);
}
