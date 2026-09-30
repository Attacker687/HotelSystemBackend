package com.winniethepooh.hotelsystembackend.mapper;

import com.winniethepooh.hotelsystembackend.dto.DailyGuestDTO;
import com.winniethepooh.hotelsystembackend.dto.DailyRevenueDTO;
import com.winniethepooh.hotelsystembackend.dto.InsertMealOrderDTO;
import com.winniethepooh.hotelsystembackend.dto.MealOrderStatusCountDTO;
import com.winniethepooh.hotelsystembackend.dto.TimeCheckDTO;
import com.winniethepooh.hotelsystembackend.entity.Individual;
import com.winniethepooh.hotelsystembackend.entity.MealOrder;
import com.winniethepooh.hotelsystembackend.entity.MealOrderItem;
import com.winniethepooh.hotelsystembackend.entity.RoomOrder;
import com.winniethepooh.hotelsystembackend.vo.DishTop10VO;
import com.winniethepooh.hotelsystembackend.vo.GetAllRoomOrderVO;
import org.apache.ibatis.annotations.Mapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface OrderMapper {

    List<MealOrder> getMealOrdersByDate(LocalDate startDate, LocalDate endDate, Integer id);

    List<RoomOrder> getRoomOrdersByDate(LocalDate startDate, LocalDate endDate, Integer id);

    int insertRoomComment(Integer id, String comment, Integer commentStar, Integer userId);

    int insertMealComment(Integer id, String comment, Integer commentStar, Integer userId);

    List<GetAllRoomOrderVO> getAllRoomOrderList(Integer offset, Integer limit);

    void insertRoomOrderV1(RoomOrder roomOrder);

    int modifyRoomOrder(RoomOrder order);

    RoomOrder getRoomOrderByIdForUpdate(Long id);

    Long findOverlappingOrder(Long roomId, LocalDateTime checkin, LocalDateTime checkout, Long excludeId);

    void insertRoomOrderNights(Long orderId, Map<LocalDate, BigDecimal> nights);

    void deleteRoomOrderNights(Long orderId);

    void deleteRoomOrder(Integer id);

    /** 区间内每晚的营收与售出间夜数（按晚拆分，只计已支付、未取消、未删除的订单）。 */
    List<DailyRevenueDTO> getNightRevenueByDate(LocalDate startDate, LocalDate endDate);

    BigDecimal getThisTypeRoomRevenueDuringTheTime(int roomType, LocalDate startDate, LocalDate endDate);

    List<DishTop10VO> getTop10Dishes(LocalDate startDate, LocalDate endDate);

    /** 在 [startDate, endDate] 内至少占用一天的有效订单（已支付、未取消、未删除），只含 id、room_id、入住和离店时间。 */
    List<RoomOrder> getOccupyingRoomOrders(LocalDate startDate, LocalDate endDate);

    List<DailyGuestDTO> getCheckinGuestCountByDate(LocalDate startDate, LocalDate endDate);

    List<RoomOrder> findRoomOrdersToRelease(LocalDateTime now);

    void modifyRoomOrderStatus(Long id, int status);

    List<Individual> getIndividualByRoomIdAndDate(Long roomId, LocalDate date);

    int getAllRoomOrderCount();

    TimeCheckDTO getCheckTimeByRoomIdAndTime(Integer roomId, LocalDateTime now);

    void insertRoomOrderV2(RoomOrder roomOrder);

    List<RoomOrder> findRoomOrdersToEnable(LocalDateTime now);

    RoomOrder getRoomOrderByRoomIdAndTime(Integer id, LocalDateTime now);

    int payRoomOrder(Long id, Integer userId);

    int cancelRoomOrder(Long id, Integer userId, LocalDateTime now);

    void ensureTaskLock(String taskName);

    int claimTaskLock(String taskName, String owner);

    void flushExpiredRoomOrders();

    RoomOrder getRoomOrderById(Long id);

    MealOrderStatusCountDTO getLiveMealOrderStatusCount();

    List<MealOrder> getLiveMealOrderList();

    List<MealOrderItem> getMealOrderItemsByOrderId(Integer mealOrderId);

    MealOrder getMealOrderByOrderId(Integer mealOrderId);

    int modifyMealOrderStatus(Integer id, Integer status, Integer originalStatus);

    int cancelMealOrder(Integer id, Integer userId);

    void insertMealOrder(InsertMealOrderDTO insertMealOrderDTO);

    void insertMealOrderItem(MealOrderItem item);
}
