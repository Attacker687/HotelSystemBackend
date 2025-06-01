package com.winniethepooh.hotelsystembackend.mapper;

import com.winniethepooh.hotelsystembackend.dto.CreateDishDTO;
import com.winniethepooh.hotelsystembackend.entity.Dish;
import com.winniethepooh.hotelsystembackend.vo.DishVO;
import com.winniethepooh.hotelsystembackend.vo.FoodCategoryVO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface FoodMapper {

    List<FoodCategoryVO> getFoodCategory();

    void createFoodCategory(String name);

    List<DishVO> getDishesByCategory(Integer id);

    void createDish(CreateDishDTO createDishDTO);

    void modifyDish(Dish dish);

    void deleteDish(Integer id);

    boolean existCategoryName(String name);

    List<DishVO> getAllDishes();
}
