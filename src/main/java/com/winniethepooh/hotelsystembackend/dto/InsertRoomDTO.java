package com.winniethepooh.hotelsystembackend.dto;

import lombok.Data;


@Data
public class InsertRoomDTO {
    private String roomNumber;     // 房间号
    private Integer roomType;      // 房型
    private Integer floor;         // 楼层
    private Integer status;        // 状态
    private Integer capacity;      // 容纳人数
    private String description;    // 房间描述
    private String image;
}
