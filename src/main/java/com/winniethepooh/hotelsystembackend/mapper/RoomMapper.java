package com.winniethepooh.hotelsystembackend.mapper;

import com.winniethepooh.hotelsystembackend.dto.InsertRoomDTO;
import com.winniethepooh.hotelsystembackend.entity.PriceCalendar;
import com.winniethepooh.hotelsystembackend.entity.Room;
import com.winniethepooh.hotelsystembackend.vo.PageBean;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Mapper
public interface RoomMapper {

    List<Room> queryRooms(Integer limit, Integer offset, Integer roomNumber, Integer roomType, Integer status);

    void modifyRoomStatus(Integer id, Integer status);

    Long getRoomIdByRoomNumber(String roomNumber);

    Room queryRoomById(Integer id, boolean containDeleted);

    void insertRoom(InsertRoomDTO insertRoomDTO);

    void modifyRoomInfo(InsertRoomDTO dto, Integer id);

    void deleteRoom(Integer id);

    List<Integer> getExistFloors();

    Integer getRoomCount(LocalDate date);

    Integer getRoomCountOnAFloor(Integer floor, LocalDate date);

    boolean existByRoomNumber(String roomNumber);

    PriceCalendar getPriceCalendarByRoomTypeAndDate(Integer roomType, LocalDate date);

    void insertPriceCalendar(PriceCalendar priceCalendar);

    void modifyPriceCalendar(PriceCalendar priceCalendar);

    BigDecimal getRoomPriceByTypeAndDate(Integer roomType, LocalDate date);

    int queryRoomsCount(Integer roomNumber, Integer roomType, Integer status);

    Room getRoomByRoomNumber(String roomNumber);
}
