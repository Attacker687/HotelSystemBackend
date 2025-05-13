package com.winniethepooh.hotelsystembackend.controller;

import com.winniethepooh.hotelsystembackend.entity.Result;
import com.winniethepooh.hotelsystembackend.service.BusinessService;
import com.winniethepooh.hotelsystembackend.vo.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/business")
public class BusinessController {
    @Autowired
    private BusinessService businessService;

    @GetMapping("/revenue/stats")
    public Result getRevenueStatsController(@RequestParam(required = false) LocalDate date) {
        if (date == null) date = LocalDate.now();
        RevenueStatsVO revenueStatsVO = businessService.getRevenueStatsService(date);
        return Result.success(revenueStatsVO);
    }

    @GetMapping("/revenue/trend")
    public Result getRevenueTrendController(@RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate startDate,
                                            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate endDate) {
        RevenueTrendVO revenueTrendVO = businessService.getRevenueTrendService(startDate, endDate);
        return Result.success(revenueTrendVO);
    }

    @GetMapping("/revenue/room-type")
    public Result getEachRoomTypeRevenueController(@RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate startDate,
                                                   @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate endDate) {
        List<RevenueRoomTypeVO> revenueRoomTypeVOList = businessService.getEachRoomTypeRevenueService(startDate, endDate);
        return Result.success(revenueRoomTypeVOList);
    }

    @GetMapping("/occupancy/heatmap")
    public Result getEachFloorOccupancyController(@RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate startDate,
                                                  @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate endDate,
                                                  @RequestParam(required = false) Integer floor){
        OccupancyHeatmapVO occupancyHeatmapVO = businessService.getEachFloorOccupancyService(startDate, endDate, floor);
        return Result.success(occupancyHeatmapVO);
    }

    @GetMapping("/dish/top10")
    public Result getTop10DishesController(@RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate startDate,
                                           @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate endDate) {
        DishTop10VO dishTop10VO = businessService.getTop10DishesService(startDate, endDate);
        return Result.success(dishTop10VO);
    }

    @GetMapping("/detail")
    public Result getStatsDetail(@RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate startDate,
                                 @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate endDate) {
        List<BusinessDetailVO> businessDetailVOList = businessService.getBusinessDetail(startDate, endDate);
        PageBean<BusinessDetailVO> pageBean = new PageBean<>();
        pageBean.setTotal(businessDetailVOList.size());
        pageBean.setList(businessDetailVOList);
        return Result.success(pageBean);
    }
}
