package com.winniethepooh.hotelsystembackend.controller;

import com.winniethepooh.hotelsystembackend.annotation.RoleRequired;
import com.winniethepooh.hotelsystembackend.constant.RoleConstant;
import com.winniethepooh.hotelsystembackend.entity.Result;
import com.winniethepooh.hotelsystembackend.service.RestaurantService;
import com.winniethepooh.hotelsystembackend.vo.LiveMealOrderVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RoleRequired({RoleConstant.RESTAURANT})
@RestController
@RequestMapping("/restaurant")
public class RestaurantController {
    @Autowired
    private RestaurantService restaurantService;

    @GetMapping("/live-order")
    public Result getLiveMealOrderController() {
        LiveMealOrderVO liveMealOrderVO = restaurantService.getLiveMealOrderService();
        return Result.success(liveMealOrderVO);
    }

    @PutMapping("/status")
    public Result modifyMealOrderStatusController(@RequestParam Integer id, @RequestParam Integer status) {
        restaurantService.modifyMealOrderStatusService(id, status);
        return Result.success();
    }
}