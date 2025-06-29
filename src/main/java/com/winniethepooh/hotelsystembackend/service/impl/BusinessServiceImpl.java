package com.winniethepooh.hotelsystembackend.service.impl;

import com.winniethepooh.hotelsystembackend.constant.RoomTypeConstant;
import com.winniethepooh.hotelsystembackend.dto.DynamicUpdatePriceDTO;
import com.winniethepooh.hotelsystembackend.entity.PriceCalendar;
import com.winniethepooh.hotelsystembackend.mapper.OrderMapper;
import com.winniethepooh.hotelsystembackend.mapper.RoomMapper;
import com.winniethepooh.hotelsystembackend.mapper.UserMapper;
import com.winniethepooh.hotelsystembackend.service.BusinessService;
import com.winniethepooh.hotelsystembackend.utils.LocalDateUtil;
import com.winniethepooh.hotelsystembackend.vo.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class BusinessServiceImpl implements BusinessService {
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private RoomMapper roomMapper;
    @Autowired
    private UserMapper userMapper;
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

    private double getTodayOccupiedRate(LocalDate date) {
        Integer todayOccupiedRoom = orderMapper.getTodayOccupiedRoomCount(date);
        Integer todayRoomCount = roomMapper.getRoomCount(date);
        double todayOccupancyRate;
        if (todayRoomCount == 0)  todayOccupancyRate = 0.0;
        else todayOccupancyRate = (double) todayOccupiedRoom / todayRoomCount;
        return todayOccupancyRate * 100.0;
    }

    private double getFloorTodayOccupancy(LocalDate date, Integer floor) {
        Integer roomsOnFloor = roomMapper.getRoomCountOnAFloor(floor, date);
        Integer roomsOccupiedOnAFloor = orderMapper.getRoomsOccupiedOnAFloor(floor, date);
        return roomsOnFloor == 0 ? 0.0 : (double) roomsOccupiedOnAFloor / roomsOnFloor * 100.0;
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
        double todayOccupancyRate = getTodayOccupiedRate(date);
        double yesterdayOccupancyRate = getTodayOccupiedRate(date.minusDays(1));
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
        for (LocalDate date : dateList) {
            revenueList.add(orderMapper.getTodayStats(date));
            occupancyList.add(getTodayOccupiedRate(date));
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

    @Override
    public OccupancyHeatmapVO getEachFloorOccupancyService(LocalDate startDate, LocalDate endDate, Integer floor) {
        OccupancyHeatmapVO occupancyHeatmapVO = new OccupancyHeatmapVO();
        List<Integer> floors = new ArrayList<>();
        List<LocalDate> dateList = LocalDateUtil.getDatesBetween(startDate, endDate);
        if (floor == null) floors = roomMapper.getExistFloors();
        else floors.add(floor);
        occupancyHeatmapVO.setDates(dateList);
        occupancyHeatmapVO.setFloors(floors.stream().map(i -> i + "楼").collect(Collectors.toList()));
        List<List<Object>> data = new ArrayList<>();
        for (int i = 0; i < dateList.size(); i++) {
            for (int j = 0; j < floors.size(); j++) {
                List<Object> detail = new ArrayList<>(3);
                detail.add(i); // 横轴索引：日期
                detail.add(j); // 纵轴索引：楼层
                Double occupancy = getFloorTodayOccupancy(dateList.get(i), floors.get(j));
                detail.add(occupancy);
                data.add(detail);
            }
        }
        occupancyHeatmapVO.setData(data);
        return occupancyHeatmapVO;
    }

    @Override
    public List<DishTop10VO> getTop10DishesService(LocalDate startDate, LocalDate endDate) {
        return orderMapper.getTop10Dishes(startDate, endDate);
    }

    @Override
    public PageBean<BusinessDetailVO> getBusinessDetail(LocalDate startDate, LocalDate endDate) {
        PageBean<BusinessDetailVO> pageBean = new PageBean<>();
        List<BusinessDetailVO> detailVOList = new ArrayList<>();
        List<LocalDate> dateList = LocalDateUtil.getDatesBetween(startDate, endDate);
        for (LocalDate date : dateList) {
            BusinessDetailVO businessDetailVO = new BusinessDetailVO();
            businessDetailVO.setDate(date);
            businessDetailVO.setRevenue(orderMapper.getTodayStats(date));
            businessDetailVO.setRoomCount(roomMapper.getRoomCount(date));
            businessDetailVO.setOccupiedCount(orderMapper.getTodayOccupiedRoomCount(date));
            businessDetailVO.setOccupancyRate(getTodayOccupiedRate(date));
            businessDetailVO.setAvgPrice(orderMapper.getTodayAvgRoomPrice(date));


            Integer customerCount = userMapper.getCustomerCountSince(date);
            Integer newCustomerCount = userMapper.getNewCustomerCount(date);
            Integer todayCustomerCount = userMapper.getTodayCustomerCount(date);

            businessDetailVO.setCustomerCount(customerCount);
            businessDetailVO.setNewCustomerCount(newCustomerCount);
            if (todayCustomerCount == 0) businessDetailVO.setRepeatCustomerRate(0.0);
            else businessDetailVO.setRepeatCustomerRate((double) ((customerCount - newCustomerCount) / todayCustomerCount) * 100.0);
            detailVOList.add(businessDetailVO);
        }
        pageBean.setList(detailVOList);
        pageBean.setTotal(detailVOList.size());
        return pageBean;
    }

    @Override
    @Transactional
    public void updateRoomPriceService(DynamicUpdatePriceDTO dynamicUpdatePriceDTO) {
        List<LocalDate> dateList = LocalDateUtil.getDatesBetween
                (dynamicUpdatePriceDTO.getStartDate(), dynamicUpdatePriceDTO.getEndDate());
        for (LocalDate date : dateList) {
            PriceCalendar priceCalendar = new PriceCalendar();
            priceCalendar.setRoomType(dynamicUpdatePriceDTO.getRoomType());
            priceCalendar.setDate(date);
            priceCalendar.setPrice(dynamicUpdatePriceDTO.getPrice());
            PriceCalendar priceCalendarInDatabase = roomMapper.getPriceCalendarByRoomTypeAndDate
                    (priceCalendar.getRoomType(), priceCalendar.getDate());
            if (priceCalendarInDatabase == null) roomMapper.insertPriceCalendar(priceCalendar);
            else roomMapper.modifyPriceCalendar(priceCalendar);
        }
    }

    @Override
    public List<PriceCalendar> getPriceCalendarService(LocalDate startDate, LocalDate endDate, Integer roomType) {
        List<LocalDate> dateList = LocalDateUtil.getDatesBetween(startDate, endDate);
        List<PriceCalendar> priceCalendarList = new ArrayList<>();
        for (LocalDate date : dateList) {
            PriceCalendar priceCalendar = roomMapper.getPriceCalendarByRoomTypeAndDate(roomType, date);
            priceCalendarList.add(priceCalendar);
        }
        return priceCalendarList;
    }
}
