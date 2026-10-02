package com.winniethepooh.hotelsystembackend.service.impl;

import com.winniethepooh.hotelsystembackend.constant.RoomTypeConstant;
import com.winniethepooh.hotelsystembackend.dto.DailyGuestDTO;
import com.winniethepooh.hotelsystembackend.dto.DailyRevenueDTO;
import com.winniethepooh.hotelsystembackend.dto.DynamicUpdatePriceDTO;
import com.winniethepooh.hotelsystembackend.entity.PriceCalendar;
import com.winniethepooh.hotelsystembackend.entity.Room;
import com.winniethepooh.hotelsystembackend.entity.RoomOrder;
import com.winniethepooh.hotelsystembackend.exception.ArgumentInvalidException;
import com.winniethepooh.hotelsystembackend.mapper.OrderMapper;
import com.winniethepooh.hotelsystembackend.mapper.RoomMapper;
import com.winniethepooh.hotelsystembackend.mapper.UserMapper;
import com.winniethepooh.hotelsystembackend.service.BusinessService;
import com.winniethepooh.hotelsystembackend.service.HotCache;
import com.winniethepooh.hotelsystembackend.utils.LocalDateUtil;
import com.winniethepooh.hotelsystembackend.vo.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 经营统计口径（D1–D5、D7）：
 * <ul>
 *   <li>营收按晚拆分：room_order_night 每晚一行，按 night 汇总；ADR = 当晚收入 ÷ 售出间夜数，保留 2 位小数。</li>
 *   <li>只计已支付（pay_status=1）、未取消（status≠2）、未删除的订单，营收和入住率一致。</li>
 *   <li>某天被占用：DATE(入住) ≤ 当天 &lt; DATE(离店)，离店当天不算。</li>
 * </ul>
 * 每个接口的 SQL 条数固定：一次取出区间内的数据，按天、按楼层在内存里汇总（P3）。
 */
@Service
public class BusinessServiceImpl implements BusinessService {
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private RoomMapper roomMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private HotCache hotCache;

    /** 统计和价格日历一次最多查询或设置的天数（含首尾，P3、B12，GAP-18）。 */
    static final int MAX_SPAN_DAYS = 366;

    /** 区间内的全部日期；跨度超过 MAX_SPAN_DAYS 时返回 400（开始晚于结束由 LocalDateUtil 返回 400）。 */
    private static List<LocalDate> checkedDates(LocalDate startDate, LocalDate endDate) {
        if (ChronoUnit.DAYS.between(startDate, endDate) + 1 > MAX_SPAN_DAYS)
            throw new ArgumentInvalidException("日期跨度不能超过 " + MAX_SPAN_DAYS + " 天");
        return LocalDateUtil.getDatesBetween(startDate, endDate);
    }

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

    private static double percent(long part, long whole) {
        return whole == 0 ? 0.0 : (double) part / whole * 100.0;
    }

    private Map<LocalDate, DailyRevenueDTO> revenueByDate(LocalDate startDate, LocalDate endDate) {
        return orderMapper.getNightRevenueByDate(startDate, endDate).stream()
                .collect(Collectors.toMap(DailyRevenueDTO::getDate, d -> d));
    }

    private static BigDecimal revenue(DailyRevenueDTO day) {
        return day == null ? BigDecimal.ZERO : day.getRevenue();
    }

    /** ADR：当晚客房收入 ÷ 当晚售出间夜数，保留 2 位小数；没有售出时为 0（D3）。 */
    private static BigDecimal avgPrice(DailyRevenueDTO day) {
        return day == null ? BigDecimal.ZERO
                : day.getRevenue().divide(BigDecimal.valueOf(day.getNightCount()), 2, RoundingMode.HALF_UP);
    }

    /** date 当天被占用的房间 id：DATE(入住) ≤ date &lt; DATE(离店)（D5）。orders 已只含有效订单（D4）。 */
    private static Set<Long> occupiedRoomIds(List<RoomOrder> orders, LocalDate date) {
        return orders.stream()
                .filter(o -> !o.getCheckinTime().toLocalDate().isAfter(date) && o.getCheckoutTime().toLocalDate().isAfter(date))
                .map(RoomOrder::getRoomId)
                .collect(Collectors.toSet());
    }

    /** date 当天已建好的房间（未删除且创建日期不晚于 date）。 */
    private static List<Room> roomsOn(List<Room> rooms, LocalDate date) {
        return rooms.stream().filter(r -> !r.getCreatedAt().toLocalDate().isAfter(date)).toList();
    }

    private static double occupancyRate(List<RoomOrder> orders, List<Room> rooms, LocalDate date) {
        return percent(occupiedRoomIds(orders, date).size(), roomsOn(rooms, date).size());
    }

    private List<Room> allRooms() {
        return roomMapper.queryRooms(null, null, null, null, null, null);
    }

    @Override
    public RevenueStatsVO getRevenueStatsService(LocalDate date) {
        LocalDate yesterday = date.minusDays(1);
        YearMonth thisMonth = YearMonth.from(date);
        YearMonth lastMonth = thisMonth.minusMonths(1);
        Map<LocalDate, DailyRevenueDTO> days = revenueByDate(lastMonth.atDay(1), thisMonth.atEndOfMonth());
        BigDecimal thisMonthStats = BigDecimal.ZERO;
        BigDecimal lastMonthStats = BigDecimal.ZERO;
        for (DailyRevenueDTO day : days.values()) {
            if (YearMonth.from(day.getDate()).equals(thisMonth)) thisMonthStats = thisMonthStats.add(day.getRevenue());
            else lastMonthStats = lastMonthStats.add(day.getRevenue());
        }
        BigDecimal todayStats = revenue(days.get(date));
        BigDecimal yesterdayStats = revenue(days.get(yesterday));
        BigDecimal todayAvgRoomPrice = avgPrice(days.get(date));
        BigDecimal yesterdayAvgRoomPrice = avgPrice(days.get(yesterday));

        List<RoomOrder> orders = orderMapper.getOccupyingRoomOrders(yesterday, date);
        List<Room> rooms = allRooms();
        double todayOccupancyRate = occupancyRate(orders, rooms, date);
        double yesterdayOccupancyRate = occupancyRate(orders, rooms, yesterday);

        RevenueStatsVO revenueStatsVO = new RevenueStatsVO();
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
        List<LocalDate> dateList = checkedDates(startDate, endDate);
        Map<LocalDate, DailyRevenueDTO> days = revenueByDate(startDate, endDate);
        List<RoomOrder> orders = orderMapper.getOccupyingRoomOrders(startDate, endDate);
        List<Room> rooms = allRooms();
        List<BigDecimal> revenueList = new ArrayList<>(dateList.size());
        List<Double> occupancyList = new ArrayList<>(dateList.size());
        for (LocalDate date : dateList) {
            revenueList.add(revenue(days.get(date)));
            occupancyList.add(occupancyRate(orders, rooms, date));
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
        List<LocalDate> dateList = checkedDates(startDate, endDate);
        List<Room> rooms = allRooms();
        List<RoomOrder> orders = orderMapper.getOccupyingRoomOrders(startDate, endDate);
        List<Integer> floors = floor != null ? List.of(floor)
                : rooms.stream().map(Room::getFloor).filter(Objects::nonNull).distinct().sorted().toList();
        OccupancyHeatmapVO occupancyHeatmapVO = new OccupancyHeatmapVO();
        occupancyHeatmapVO.setDates(dateList);
        occupancyHeatmapVO.setFloors(floors.stream().map(i -> i + "楼").collect(Collectors.toList()));
        List<List<Object>> data = new ArrayList<>();
        for (int i = 0; i < dateList.size(); i++) {
            Set<Long> occupied = occupiedRoomIds(orders, dateList.get(i));
            List<Room> existing = roomsOn(rooms, dateList.get(i));
            for (int j = 0; j < floors.size(); j++) {
                Integer f = floors.get(j);
                List<Room> onFloor = existing.stream().filter(r -> f.equals(r.getFloor())).toList();
                long occupiedOnFloor = onFloor.stream().filter(r -> occupied.contains(r.getId())).count();
                List<Object> detail = new ArrayList<>(3);
                detail.add(i); // 横轴索引：日期
                detail.add(j); // 纵轴索引：楼层
                detail.add(percent(occupiedOnFloor, onFloor.size()));
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
        List<LocalDate> dateList = checkedDates(startDate, endDate);
        Map<LocalDate, DailyRevenueDTO> days = revenueByDate(startDate, endDate);
        List<RoomOrder> orders = orderMapper.getOccupyingRoomOrders(startDate, endDate);
        List<Room> rooms = allRooms();
        Map<LocalDate, DailyGuestDTO> guests = orderMapper.getCheckinGuestCountByDate(startDate, endDate).stream()
                .collect(Collectors.toMap(DailyGuestDTO::getDate, g -> g));
        Map<LocalDate, Long> newCustomers = userMapper.getIndividualCreatedTimes(startDate, endDate).stream()
                .collect(Collectors.groupingBy(LocalDateTime::toLocalDate, Collectors.counting()));
        int customerCount = userMapper.getCustomerCountBefore(startDate);

        List<BusinessDetailVO> detailVOList = new ArrayList<>();
        for (LocalDate date : dateList) {
            int newCustomerCount = newCustomers.getOrDefault(date, 0L).intValue();
            customerCount += newCustomerCount;
            int roomCount = roomsOn(rooms, date).size();
            int occupiedCount = occupiedRoomIds(orders, date).size();
            DailyGuestDTO guest = guests.get(date);

            BusinessDetailVO businessDetailVO = new BusinessDetailVO();
            businessDetailVO.setDate(date);
            businessDetailVO.setRevenue(revenue(days.get(date)));
            businessDetailVO.setRoomCount(roomCount);
            businessDetailVO.setOccupiedCount(occupiedCount);
            businessDetailVO.setOccupancyRate(percent(occupiedCount, roomCount));
            businessDetailVO.setAvgPrice(avgPrice(days.get(date)));
            businessDetailVO.setCustomerCount(customerCount);
            businessDetailVO.setNewCustomerCount(newCustomerCount);
            // 复住率：当天入住的客人里此前已有入住记录的比例，按入住人识别，保留 1 位小数（D7，GAP-21）
            businessDetailVO.setRepeatCustomerRate(guest == null ? 0.0
                    : BigDecimal.valueOf(percent(guest.getRepeatGuestCount(), guest.getGuestCount()))
                    .setScale(1, RoundingMode.HALF_UP).doubleValue());
            detailVOList.add(businessDetailVO);
        }
        PageBean<BusinessDetailVO> pageBean = new PageBean<>();
        pageBean.setList(detailVOList);
        pageBean.setTotal(detailVOList.size());
        return pageBean;
    }

    @Override
    public void updateRoomPriceService(DynamicUpdatePriceDTO dto) {
        List<LocalDate> dates = checkedDates(dto.getStartDate(), dto.getEndDate());
        roomMapper.upsertPriceCalendar(dto.getRoomType(), dto.getPrice(), dates);
        hotCache.evictAfterCommit(dates.stream().map(date -> "price:" + dto.getRoomType() + ":" + date).toList());
    }

    /** 每个日期一项，没有设价的日期为 null（与原接口一致）。 */
    @Override
    public List<PriceCalendar> getPriceCalendarService(LocalDate startDate, LocalDate endDate, Integer roomType) {
        List<LocalDate> dateList = checkedDates(startDate, endDate);
        Map<LocalDate, PriceCalendar> byDate = roomMapper.getPriceCalendars(roomType, startDate, endDate).stream()
                .collect(Collectors.toMap(PriceCalendar::getDate, p -> p));
        return dateList.stream().map(byDate::get).collect(Collectors.toList());
    }
}
