package com.winniethepooh.hotelsystembackend.service.impl;

import com.winniethepooh.hotelsystembackend.constant.MealOrderStatusConstant;
import com.winniethepooh.hotelsystembackend.dto.MealOrderStatusCountDTO;
import com.winniethepooh.hotelsystembackend.entity.MealOrder;
import com.winniethepooh.hotelsystembackend.entity.MealOrderItem;
import com.winniethepooh.hotelsystembackend.exception.MismatchException;
import com.winniethepooh.hotelsystembackend.mapper.OrderMapper;
import com.winniethepooh.hotelsystembackend.service.RestaurantService;
import com.winniethepooh.hotelsystembackend.vo.LiveMealOrderVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RestaurantServiceImpl implements RestaurantService {
    @Autowired
    private OrderMapper orderMapper;

    private void modifyStatusCheck(Integer originalStatus, Integer newStatus) {
        // 不允许修改已完成或已取消的订单
        if (originalStatus == MealOrderStatusConstant.DONE ||
                originalStatus == MealOrderStatusConstant.CANCELLED) {
            throw new MismatchException("不能修改已完成或已取消的订单");
        }

        if (newStatus != originalStatus + 1) {
            throw new MismatchException("订单状态修改不合法，只能按顺序推进");
        }
    }


    @Override
    public LiveMealOrderVO getLiveMealOrderService() {
        LiveMealOrderVO liveMealOrderVO = new LiveMealOrderVO();
        MealOrderStatusCountDTO countDTO = orderMapper.getLiveMealOrderStatusCount();
        liveMealOrderVO.setNewOrderCount(countDTO.getNewOrderCount());
        liveMealOrderVO.setPendingOrderCount(countDTO.getPendingOrderCount());
        liveMealOrderVO.setDoneOrderCount(countDTO.getDoneOrderCount());

        List<MealOrder> liveMealOrderList = orderMapper.getLiveMealOrderList();
        for (MealOrder mealOrder : liveMealOrderList) {
            List<MealOrderItem> itemList = orderMapper.getMealOrderItemsByOrderId(mealOrder.getId());
            mealOrder.setItemList(itemList);
        }
        liveMealOrderVO.setMealOrderList(liveMealOrderList);
        return liveMealOrderVO;
    }

    @Override
    public void modifyMealOrderStatusService(Integer id, Integer status) {
        MealOrder mealOrder = orderMapper.getMealOrderByOrderId(id);
        modifyStatusCheck(mealOrder.getOrderStatus(), status);
        orderMapper.modifyMealOrderStatus(id, status);
    }
}
