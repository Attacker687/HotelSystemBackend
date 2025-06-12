package com.winniethepooh.hotelsystembackend.service;

import com.winniethepooh.hotelsystembackend.vo.LiveMealOrderVO;

public interface RestaurantService {
    LiveMealOrderVO getLiveMealOrderService();

    void modifyMealOrderStatusService(Integer id, Integer status);
}
