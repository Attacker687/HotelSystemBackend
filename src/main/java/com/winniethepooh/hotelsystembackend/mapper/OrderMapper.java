package com.winniethepooh.hotelsystembackend.mapper;

import com.winniethepooh.hotelsystembackend.dto.InsertMealOrderDTO;
import com.winniethepooh.hotelsystembackend.dto.MealOrderStatusCountDTO;
import com.winniethepooh.hotelsystembackend.dto.ModifyRoomOrderDTO;
import com.winniethepooh.hotelsystembackend.dto.TimeCheckDTO;
import com.winniethepooh.hotelsystembackend.entity.Individual;
import com.winniethepooh.hotelsystembackend.entity.MealOrder;
import com.winniethepooh.hotelsystembackend.entity.MealOrderItem;
import com.winniethepooh.hotelsystembackend.entity.RoomOrder;
import com.winniethepooh.hotelsystembackend.vo.DishTop10VO;
import com.winniethepooh.hotelsystembackend.vo.RevenueStatsVO;
import org.apache.ibatis.annotations.Mapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OrderMapper {

    List<MealOrder> getMealOrdersByDate(LocalDate startDate, LocalDate endDate, Integer id);

    List<RoomOrder> getRoomOrdersByDate(LocalDate startDate, LocalDate endDate, Integer id);

    void insertRoomComment(Integer id, String comment, Integer commentStar);

    void insertMealComment(Integer id, String comment, Integer commentStar);

    List<RoomOrder> getAllRoomOrderList(Integer offset, Integer limit);

    void insertRoomOrderV1(RoomOrder roomOrder);

    void modifyRoomOrder(Integer id, ModifyRoomOrderDTO modifyRoomOrderDTO);

    void deleteRoomOrder(Integer id);

    BigDecimal getTodayStats(LocalDate date);

    BigDecimal getThisMonthStats(LocalDate date);

    BigDecimal getTodayAvgRoomPrice(LocalDate date);

    BigDecimal getThisTypeRoomRevenueDuringTheTime(int roomType, LocalDate startDate, LocalDate endDate);

    List<DishTop10VO> getTop10Dishes(LocalDate startDate, LocalDate endDate);

    Integer getTodayOccupiedRoomCount(LocalDate date);

    Integer getRoomsOccupiedOnAFloor(Integer floor, LocalDate date);

    List<RoomOrder> findRoomOrdersToRelease(LocalDateTime now);

    void modifyRoomOrderStatus(Long id, int status);

    List<Individual> getIndividualByRoomIdAndDate(Long roomId, LocalDate date);

    int getAllRoomOrderCount();

    TimeCheckDTO getCheckTimeByRoomIdAndTime(Integer roomId, LocalDateTime now);

    void insertRoomOrderV2(RoomOrder roomOrder);

    List<RoomOrder> findRoomOrdersToEnable(LocalDateTime now);

    RoomOrder getRoomOrderByRoomIdAndTime(Integer id, LocalDateTime now);

    void modifyRoomOrderPayStatus(Long id, int status);

    void flushExpiredRoomOrders();

    RoomOrder getRoomOrderById(Long id);

    MealOrderStatusCountDTO getLiveMealOrderStatusCount();

    List<MealOrder> getLiveMealOrderList();

    List<MealOrderItem> getMealOrderItemsByOrderId(Integer mealOrderId);

    MealOrder getMealOrderByOrderId(Integer mealOrderId);

    void modifyMealOrderStatus(Integer id, Integer status);

    void insertMealOrder(InsertMealOrderDTO insertMealOrderDTO);

    void insertMealOrderItem(MealOrderItem item);
}
