package com.winniethepooh.hotelsystembackend.mapper;

import com.winniethepooh.hotelsystembackend.dto.InsertRoomOrderDTO;
import com.winniethepooh.hotelsystembackend.entity.Room;
import com.winniethepooh.hotelsystembackend.vo.QueryRoomsVO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface RoomMapper {

    List<Room> queryRooms(Integer limit, Integer offset, Integer roomNumber, Integer roomTypeId, Integer status);

    String getTypeNameByRoomId(Long id);

    void modifyRoomStatus(Integer roomNumber, Integer status);

    Long getRoomIdByRoomNumber(String roomNumber);

    Room queryRoomById(Integer id);

    void insertRoom(InsertRoomOrderDTO insertRoomOrderDTO);
}
