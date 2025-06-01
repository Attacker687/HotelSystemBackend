package com.winniethepooh.hotelsystembackend.service;

import com.winniethepooh.hotelsystembackend.constant.RoomOrderStatusConstant;
import com.winniethepooh.hotelsystembackend.constant.RoomStatusConstant;
import com.winniethepooh.hotelsystembackend.entity.RoomOrder;
import com.winniethepooh.hotelsystembackend.mapper.OrderMapper;
import com.winniethepooh.hotelsystembackend.mapper.RoomMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class CustomTaskScheduler {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private RoomMapper roomMapper;

    /**
     * 每分钟执行一次，检查超时房间
     */
    @Scheduled(cron = "0 * * * * ?")
    public void releaseExpiredRooms() {
        LocalDateTime now = LocalDateTime.now();
        List<RoomOrder> expiredOrders = orderMapper.findRoomOrdersToRelease(now);

        for (RoomOrder order : expiredOrders) {
            roomMapper.modifyRoomStatus(Math.toIntExact(order.getRoomId()), RoomStatusConstant.AVAILABLE);
            orderMapper.modifyRoomOrderStatus(order.getId(), RoomOrderStatusConstant.DONE);
            System.out.println("释放房间ID: " + order.getRoomId());
        }
    }
}

