package com.winniethepooh.hotelsystembackend.service.impl;

import com.winniethepooh.hotelsystembackend.dto.CreateDishDTO;
import com.winniethepooh.hotelsystembackend.entity.Dish;
import com.winniethepooh.hotelsystembackend.exception.CategoryNameDuplicatedException;
import com.winniethepooh.hotelsystembackend.mapper.FoodMapper;
import com.winniethepooh.hotelsystembackend.service.FoodService;
import com.winniethepooh.hotelsystembackend.vo.DishVO;
import com.winniethepooh.hotelsystembackend.vo.FoodCategoryVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class FoodServiceImpl implements FoodService {
    @Autowired
    private FoodMapper foodMapper;

    @Override
    public List<FoodCategoryVO> getFoodCategoryService() {
        return foodMapper.getFoodCategory();
    }

    @Override
    public void createFoodCategoryService(String name) {
        if (!foodMapper.existCategoryName(name))
            foodMapper.createFoodCategory(name);
        else throw new CategoryNameDuplicatedException("此分类名已存在");
    }

    @Override
    public List<DishVO> getDishesByCategoryService(Integer id) {
        return foodMapper.getDishesByCategory(id);
    }

    @Override
    public void createDishService(CreateDishDTO createDishDTO) {
        foodMapper.createDish(createDishDTO);
    }

    @Override
    public void modifyDishService(Dish dish) {
        foodMapper.modifyDish(dish);
    }

    @Override
    public void deleteDishService(Integer id) {
        foodMapper.deleteDish(id);
    }

    @Override
    public List<DishVO> getAllDishesService() {
        return foodMapper.getAllDishes();
    }

    @Override
    @Transactional
    public void deleteCategoryService(String id) {
        List<DishVO> dishes = foodMapper.getDishesByCategory(Integer.valueOf(id));
        for (DishVO dish : dishes) foodMapper.deleteDish(dish.getId());
        foodMapper.deleteCategory(id);
    }
}
