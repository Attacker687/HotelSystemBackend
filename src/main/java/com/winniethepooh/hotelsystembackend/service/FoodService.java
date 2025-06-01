package com.winniethepooh.hotelsystembackend.service;

import com.winniethepooh.hotelsystembackend.dto.CreateDishDTO;
import com.winniethepooh.hotelsystembackend.entity.Dish;
import com.winniethepooh.hotelsystembackend.vo.DishVO;
import com.winniethepooh.hotelsystembackend.vo.FoodCategoryVO;

import java.util.List;

public interface FoodService {
    List<FoodCategoryVO> getFoodCategoryService();

    void createFoodCategoryService(String name);

    List<DishVO> getDishesByCategoryService(Integer id);

    void createDishService(CreateDishDTO createDishDTO);

    void modifyDishService(Dish dish);

    void deleteDishService(Integer id);

    List<DishVO> getAllDishesService();
}
