package com.winniethepooh.hotelsystembackend.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class RoomOrder {

    private Long id;                       // 订单唯一标识符
    private Integer userId;               // 关联的用户id
    private Integer individualId;         // 关联的个体id
    private Integer reservationId;        // 关联的预订ID（可为空）
    private Long roomId;                  // 关联的房间ID
    private LocalDateTime checkinTime;    // 实际入住时间
    private LocalDateTime checkoutTime;   // 实际退房时间
    private BigDecimal totalAmount;       // 总金额
    private Integer payStatus;            // 支付进度（0未支付，1已支付，2已退款）
    private Integer status;               // 订单状态（0进行中，1已完成）
    private LocalDateTime createdAt;      // 创建时间
    private LocalDateTime updatedAt;      // 更新时间
    private String comment;               // 用户对此次订单的评价
    private Integer commentStar;
}

