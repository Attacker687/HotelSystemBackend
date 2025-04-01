package com.winniethepooh.hotelsystembackend.mapper;

import com.winniethepooh.hotelsystembackend.dto.ModifyRoomOrderDTO;
import com.winniethepooh.hotelsystembackend.entity.MealOrder;
import com.winniethepooh.hotelsystembackend.entity.RoomOrder;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface OrderMapper {

    List<MealOrder> getMealOrdersByDate(LocalDate startDate, LocalDate endDate, Integer id);

    List<RoomOrder> getRoomOrdersByDate(LocalDate startDate, LocalDate endDate, Integer id);

    void insertRoomComment(Integer id, String comment);

    void insertMealComment(Integer id, String comment);

    List<RoomOrder> getAllRoomOrderList();

    void insertRoomOrderV1(RoomOrder roomOrder);

    void modifyRoomOrder(Integer id, ModifyRoomOrderDTO modifyRoomOrderDTO);

    void deleteRoomOrder(Integer id);
}
