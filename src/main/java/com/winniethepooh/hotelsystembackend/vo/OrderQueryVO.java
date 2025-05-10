package com.winniethepooh.hotelsystembackend.vo;

import com.winniethepooh.hotelsystembackend.entity.MealOrder;
import com.winniethepooh.hotelsystembackend.entity.RoomOrder;
import lombok.Data;

import java.util.List;

@Data
public class OrderQueryVO {
    private List<MealOrder> mealOrderList;
    private List<RoomOrder> roomOrderList;
}
