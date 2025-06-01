package com.winniethepooh.hotelsystembackend.controller;

import com.winniethepooh.hotelsystembackend.dto.CreateDishDTO;
import com.winniethepooh.hotelsystembackend.dto.CreateFoodCategoryDTO;
import com.winniethepooh.hotelsystembackend.entity.Dish;
import com.winniethepooh.hotelsystembackend.entity.Result;
import com.winniethepooh.hotelsystembackend.service.FoodService;
import com.winniethepooh.hotelsystembackend.vo.DishVO;
import com.winniethepooh.hotelsystembackend.vo.FoodCategoryVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/food")
public class FoodController {
    @Autowired
    private FoodService foodService;

    @GetMapping("/category")
    public Result getFoodCategoryController() {
        List<FoodCategoryVO> voList = foodService.getFoodCategoryService();
        return Result.success(voList);
    }

    @PostMapping("/category")
    public Result createFoodCategoryController(@RequestBody CreateFoodCategoryDTO createFoodCategoryDTO) {
        foodService.createFoodCategoryService(createFoodCategoryDTO.getName());
        return Result.success();
    }

    @GetMapping("/category/{id}")
    public Result getDishesByCategoryIdController(@PathVariable String id) {
        List<DishVO> voList = foodService.getDishesByCategoryService(Integer.valueOf(id));
        return Result.success(voList);
    }

    @PostMapping("/dish")
    public Result createDishController(@RequestBody CreateDishDTO createDishDTO) {
        foodService.createDishService(createDishDTO);
        return Result.success();
    }

    @PutMapping("/dish")
    public Result modifyDishController(@RequestBody Dish dish) {
        foodService.modifyDishService(dish);
        return Result.success();
    }

    @DeleteMapping("/dish")
    public Result deleteDishController(@RequestParam Integer id) {
        foodService.deleteDishService(id);
        return Result.success();
    }

    @GetMapping("/dish/list")
    public Result getAllDishesController() {
        List<DishVO> voList = foodService.getAllDishesService();
        return Result.success(voList);
    }
}
