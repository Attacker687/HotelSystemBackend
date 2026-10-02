package com.winniethepooh.hotelsystembackend.service;

import com.winniethepooh.hotelsystembackend.constant.RoomOrderStatusConstant;
import com.winniethepooh.hotelsystembackend.entity.RoomOrder;
import com.winniethepooh.hotelsystembackend.mapper.OrderMapper;
import com.winniethepooh.hotelsystembackend.mapper.RoomMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class CustomTaskScheduler {
    private final String owner = UUID.randomUUID().toString();

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private RoomMapper roomMapper;

    /**
     * 每分钟执行一次，检查超时房间
     */
    @Scheduled(cron = "0 * * * * ?")
    @Transactional
    public void releaseExpiredRooms() {
        // ponytail: 整批退房持有一行锁；批次变大时改为每张订单抢占并提交。
        orderMapper.ensureTaskLock("releaseExpiredRooms");
        if (orderMapper.claimTaskLock("releaseExpiredRooms", owner) == 0) return;
        LocalDateTime now = LocalDateTime.now();
        List<RoomOrder> expiredOrders = orderMapper.findRoomOrdersToRelease(now);

        for (RoomOrder order : expiredOrders) {
            roomMapper.releaseOccupiedRoom(Math.toIntExact(order.getRoomId()));
            orderMapper.modifyRoomOrderStatus(order.getId(), RoomOrderStatusConstant.DONE);
        }
    }

    @Scheduled(cron = "1 * * * * *")
    @Transactional
    public void flushRoomStatus() {
        LocalDateTime now = LocalDateTime.now();
        List<RoomOrder> ordersNeedTobeEnable = orderMapper.findRoomOrdersToEnable(now);
        for (RoomOrder roomOrder : ordersNeedTobeEnable) {
            roomMapper.enableAvailableRoom(Math.toIntExact(roomOrder.getRoomId()));
        }
    }

    @Scheduled(cron = "2 * * * * *")
    @Transactional
    public void flushExpiredRoomOrders() {
        for (Long id : orderMapper.findExpiredRoomOrderIdsForUpdate()) {
            orderMapper.modifyRoomOrderStatus(id, RoomOrderStatusConstant.CANCELLED);
            orderMapper.deleteRoomInventory(id);
        }
    }

    @Scheduled(cron = "0 30 3 * * ?")
    @Transactional
    public void cleanBookingRequests() {
        // ponytail: 单条 DELETE 适合演示数据量；清理拖慢时改为分批。
        log.info("expired booking requests deleted={}", orderMapper.deleteExpiredBookingRequests());
    }

}

