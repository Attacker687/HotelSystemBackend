package com.winniethepooh.hotelsystembackend.controller;

import com.winniethepooh.hotelsystembackend.annotation.RoleRequired;
import com.winniethepooh.hotelsystembackend.constant.RoleConstant;
import com.winniethepooh.hotelsystembackend.dto.DynamicUpdatePriceDTO;
import com.winniethepooh.hotelsystembackend.entity.PriceCalendar;
import com.winniethepooh.hotelsystembackend.entity.Result;
import com.winniethepooh.hotelsystembackend.exception.ArgumentInvalidException;
import com.winniethepooh.hotelsystembackend.service.BusinessService;
import com.winniethepooh.hotelsystembackend.vo.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/business")
@RoleRequired({RoleConstant.MANAGER})
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
        List<DishTop10VO> dishTop10VOList = businessService.getTop10DishesService(startDate, endDate);
        return Result.success(dishTop10VOList);
    }

    @GetMapping("/detail")
    public Result getStatsDetailController(@RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate startDate,
                                 @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate endDate) {
        PageBean<BusinessDetailVO> pageBean = businessService.getBusinessDetail(startDate, endDate);
        return Result.success(pageBean);
    }

    @PostMapping("/calendar")
    public Result updatePriceCalendarController(@RequestBody DynamicUpdatePriceDTO dynamicUpdatePriceDTO) {
        if (dynamicUpdatePriceDTO.getStartDate().isAfter(dynamicUpdatePriceDTO.getEndDate()))
            throw new ArgumentInvalidException("开始日期不能在结束日期之后");
        businessService.updateRoomPriceService(dynamicUpdatePriceDTO);
        return Result.success();
    }

    @GetMapping("/calendar")
    public Result getPriceCalendarController(@RequestParam LocalDate startDate, @RequestParam LocalDate endDate,
                                             @RequestParam Integer roomType) {
        List<PriceCalendar> calendarList = businessService.getPriceCalendarService(startDate, endDate, roomType);
        return Result.success(calendarList);
    }
}
