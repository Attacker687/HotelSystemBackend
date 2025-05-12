package com.winniethepooh.hotelsystembackend.service.impl;

import com.winniethepooh.hotelsystembackend.constant.RoomTypeConstant;
import com.winniethepooh.hotelsystembackend.mapper.OrderMapper;
import com.winniethepooh.hotelsystembackend.service.BusinessService;
import com.winniethepooh.hotelsystembackend.utils.LocalDateUtil;
import com.winniethepooh.hotelsystembackend.vo.RevenueRoomTypeVO;
import com.winniethepooh.hotelsystembackend.vo.RevenueStatsVO;
import com.winniethepooh.hotelsystembackend.vo.RevenueTrendVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class BusinessServiceImpl implements BusinessService {
    @Autowired
    private OrderMapper orderMapper;
    /** 计算 BigDecimal 类型的同比变化百分比 */
    private double calcChange(BigDecimal current, BigDecimal previous) {
        if (previous == null || previous.compareTo(BigDecimal.ZERO) == 0) return 0.0;
        return current.subtract(previous)
                .divide(previous, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();
    }

    /** 计算 Double 类型的同比变化百分比 */
    private double calcChange(Double current, Double previous) {
        if (previous == null || previous == 0.0 || previous.isNaN() || previous.isInfinite()) return 0.0;
        return (current - previous) / previous * 100;
    }

    @Override
    public RevenueStatsVO getRevenueStatsService(LocalDate date) {
        RevenueStatsVO revenueStatsVO = new RevenueStatsVO();

        BigDecimal todayStats = Optional.ofNullable(orderMapper.getTodayStats(date)).orElse(BigDecimal.ZERO);
        BigDecimal yesterdayStats = Optional.ofNullable(orderMapper.getTodayStats(date.minusDays(1))).orElse(BigDecimal.ZERO);
        BigDecimal thisMonthStats = Optional.ofNullable(orderMapper.getThisMonthStats(date)).orElse(BigDecimal.ZERO);
        BigDecimal lastMonthStats = Optional.ofNullable(orderMapper.getThisMonthStats(date.minusMonths(1))).orElse(BigDecimal.ZERO);
        BigDecimal todayAvgRoomPrice = Optional.ofNullable(orderMapper.getTodayAvgRoomPrice(date)).orElse(BigDecimal.ZERO);
        BigDecimal yesterdayAvgRoomPrice = Optional.ofNullable(orderMapper.getTodayAvgRoomPrice(date.minusDays(1))).orElse(BigDecimal.ZERO);

        Double todayOccupancyRate = orderMapper.getTodayOccupancyRate(date);
        Double yesterdayOccupancyRate = orderMapper.getTodayOccupancyRate(date.minusDays(1));

        revenueStatsVO.setToday(todayStats);
        revenueStatsVO.setMonth(thisMonthStats);
        revenueStatsVO.setAvgPrice(todayAvgRoomPrice);
        revenueStatsVO.setOccupancyRate(todayOccupancyRate);

        revenueStatsVO.setTodayChange(calcChange(todayStats, yesterdayStats));
        revenueStatsVO.setMonthChange(calcChange(thisMonthStats, lastMonthStats));
        revenueStatsVO.setAvgPriceChange(calcChange(todayAvgRoomPrice, yesterdayAvgRoomPrice));
        revenueStatsVO.setOccupancyRateChange(calcChange(todayOccupancyRate, yesterdayOccupancyRate));

        return revenueStatsVO;
    }

    @Override
    public RevenueTrendVO getRevenueTrendService(LocalDate startDate, LocalDate endDate) {
        List<LocalDate> dateList = LocalDateUtil.getDatesBetween(startDate, endDate);
        List<BigDecimal> revenueList = new ArrayList<>(dateList.size());
        List<Double> occupancyList = new ArrayList<>(dateList.size());
        for (int i = 0; i < dateList.size(); i++) {
            revenueList.set(i, orderMapper.getTodayStats(dateList.get(i)));
            occupancyList.set(i, orderMapper.getTodayOccupancyRate(dateList.get(i)));
        }
        RevenueTrendVO revenueTrendVO = new RevenueTrendVO();
        revenueTrendVO.setDates(dateList);
        revenueTrendVO.setRevenue(revenueList);
        revenueTrendVO.setOccupancyRate(occupancyList);
        return revenueTrendVO;
    }

    @Override
    public List<RevenueRoomTypeVO> getEachRoomTypeRevenueService(LocalDate startDate, LocalDate endDate) {
        RevenueRoomTypeVO singleRoom = new RevenueRoomTypeVO();
        RevenueRoomTypeVO doubleRoom = new RevenueRoomTypeVO();
        RevenueRoomTypeVO suiteRoom = new RevenueRoomTypeVO();
        singleRoom.setName("单人间");
        doubleRoom.setName("双人间");
        suiteRoom.setName("套房");
        singleRoom.setValue(orderMapper.getThisTypeRoomRevenueDuringTheTime(RoomTypeConstant.SINGLE, startDate, endDate));
        doubleRoom.setValue(orderMapper.getThisTypeRoomRevenueDuringTheTime(RoomTypeConstant.DOUBLE, startDate, endDate));
        suiteRoom.setValue(orderMapper.getThisTypeRoomRevenueDuringTheTime(RoomTypeConstant.SUITE, startDate, endDate));
        List<RevenueRoomTypeVO> voList = new ArrayList<>();
        voList.add(singleRoom);
        voList.add(doubleRoom);
        voList.add(suiteRoom);
        return voList;
    }
}
