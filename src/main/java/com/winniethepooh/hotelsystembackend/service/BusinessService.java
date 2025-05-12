package com.winniethepooh.hotelsystembackend.service;

import com.winniethepooh.hotelsystembackend.vo.RevenueRoomTypeVO;
import com.winniethepooh.hotelsystembackend.vo.RevenueStatsVO;
import com.winniethepooh.hotelsystembackend.vo.RevenueTrendVO;

import java.time.LocalDate;
import java.util.List;

public interface BusinessService {

    RevenueStatsVO getRevenueStatsService(LocalDate date);

    RevenueTrendVO getRevenueTrendService(LocalDate startDate, LocalDate endDate);

    List<RevenueRoomTypeVO> getEachRoomTypeRevenueService(LocalDate startDate, LocalDate endDate);
}
