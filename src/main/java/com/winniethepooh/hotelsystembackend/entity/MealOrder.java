package com.winniethepooh.hotelsystembackend.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class MealOrder {

    private Integer id;                  // 订单id
    private Integer userId;              // 关联的用户id
    private Integer tableNumber;         // 餐桌号
    private BigDecimal totalAmount;      // 总金额
    private Integer orderStatus;         // 订单状态（0准备中，1已送达，2已完成，3已取消）
    private String remarks;              // 订单备注
    private LocalDateTime createdAt;     // 创建时间
    private LocalDateTime updatedAt;     // 更新时间
    private String comment;              // 用户对这次订单的评价
}

