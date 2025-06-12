package com.winniethepooh.hotelsystembackend.vo;

import com.winniethepooh.hotelsystembackend.entity.MealOrder;
import lombok.Data;

import java.util.List;

@Data
public class LiveMealOrderVO {
    private Integer newOrderCount;
    private Integer pendingOrderCount;
    private Integer doneOrderCount;
    private List<MealOrder> mealOrderList;
}
