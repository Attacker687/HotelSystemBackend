package com.winniethepooh.hotelsystembackend.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class BookingRequest {
    private Long id;
    private String requestId;
    private Integer userId;
    private Integer requesterRole;
    private String requestHash;
    private Integer failStatus;
    private String failMessage;
    private String actionType;
    private Long orderId;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
