package com.winniethepooh.hotelsystembackend.service;

import com.winniethepooh.hotelsystembackend.dto.DynamicUpdatePriceDTO;
import com.winniethepooh.hotelsystembackend.entity.PriceCalendar;
import com.winniethepooh.hotelsystembackend.vo.*;

import java.time.LocalDate;
import java.util.List;

public interface BusinessService {

    RevenueStatsVO getRevenueStatsService(LocalDate date);

    RevenueTrendVO getRevenueTrendService(LocalDate startDate, LocalDate endDate);

    List<RevenueRoomTypeVO> getEachRoomTypeRevenueService(LocalDate startDate, LocalDate endDate);

    OccupancyHeatmapVO getEachFloorOccupancyService(LocalDate startDate, LocalDate endDate, Integer floor);

    List<DishTop10VO> getTop10DishesService(LocalDate startDate, LocalDate endDate);

    PageBean<BusinessDetailVO> getBusinessDetail(LocalDate startDate, LocalDate endDate);

    void updateRoomPriceService(DynamicUpdatePriceDTO dynamicUpdatePriceDTO);

    List<PriceCalendar> getPriceCalendarService(LocalDate startDate, LocalDate endDate, Integer roomType);
}
