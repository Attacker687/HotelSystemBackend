package com.winniethepooh.hotelsystembackend.service.impl;

import com.winniethepooh.hotelsystembackend.constant.RoomOrderStatusConstant;
import com.winniethepooh.hotelsystembackend.constant.RoomStatusConstant;
import com.winniethepooh.hotelsystembackend.constant.RoomTypeConstant;
import com.winniethepooh.hotelsystembackend.dto.InsertRoomDTO;
import com.winniethepooh.hotelsystembackend.entity.Individual;
import com.winniethepooh.hotelsystembackend.entity.Room;
import com.winniethepooh.hotelsystembackend.entity.RoomOrder;
import com.winniethepooh.hotelsystembackend.entity.PriceCalendar;
import com.winniethepooh.hotelsystembackend.exception.RoomNumberDuplicatedException;
import com.winniethepooh.hotelsystembackend.exception.UnknownRoomTypeException;
import com.winniethepooh.hotelsystembackend.exception.BusinessException;
import org.springframework.http.HttpStatus;
import com.winniethepooh.hotelsystembackend.mapper.OrderMapper;
import com.winniethepooh.hotelsystembackend.mapper.RoomMapper;
import com.winniethepooh.hotelsystembackend.service.RoomService;
import com.winniethepooh.hotelsystembackend.service.HotCache;
import com.winniethepooh.hotelsystembackend.vo.PageBean;
import com.winniethepooh.hotelsystembackend.vo.QueryRoomsVO;
import com.winniethepooh.hotelsystembackend.vo.RoomStatusWallVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Service
public class RoomServiceImpl implements RoomService {
    @Autowired
    private RoomMapper roomMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private HotCache hotCache;

    private record RoomStatic(String roomNumber, Integer roomType, Integer floor, Integer capacity, String description, String image) {}

    private void roomTypeValid(Integer roomType) {
        if (roomType == null || (roomType != RoomTypeConstant.SINGLE && roomType != RoomTypeConstant.DOUBLE && roomType != RoomTypeConstant.SUITE))
            throw new UnknownRoomTypeException("未知的房型");
    }

    private void statusValid(Integer status) {
        if (status == null || status < RoomStatusConstant.AVAILABLE || status > RoomStatusConstant.REPAIRING)
            throw new BusinessException(HttpStatus.BAD_REQUEST, "房态 status 必须在0到3之间");
    }

    private QueryRoomsVO convertToVO(Room room) {
        if (room == null) return null;
        QueryRoomsVO vo = new QueryRoomsVO();
        vo.setId(Math.toIntExact(room.getId()));
        vo.setRoomNumber(room.getRoomNumber());
        vo.setStatus(room.getStatus());
        vo.setRoomType(room.getRoomType());
        vo.setImage(room.getImage());
        vo.setDescription(room.getDescription());
        vo.setFloor(room.getFloor());
        vo.setCapacity(room.getCapacity());
        vo.setPrice(room.getPrice());
        if (vo.getImage() == null || vo.getImage().isEmpty()) vo.setImage("https://hotelsystem.oss-cn-chengdu.aliyuncs.com/sample.jpeg");
        return vo;
    }

    @Override
    @Transactional
    public void modifyRoomStatusService(Integer id, Integer status) {
        statusValid(status);
        Room room = roomMapper.lockRoomById(id);
        if (room == null) throw new BusinessException(HttpStatus.NOT_FOUND, "房间不存在");
        if (room.getStatus() == RoomStatusConstant.OCCUPIED && status == RoomStatusConstant.AVAILABLE) {
            RoomOrder order = orderMapper.getRoomOrderByRoomIdAndTime(id, LocalDateTime.now());
            if (order != null) {
                orderMapper.modifyRoomOrderStatus(order.getId(), RoomOrderStatusConstant.DONE);
                orderMapper.deleteRoomInventoryFromDate(order.getId(), LocalDate.now());
            }
        }
        roomMapper.modifyRoomStatus(id, status);
    }

    @Override
    public QueryRoomsVO queryRoomByIdService(Integer id) {
        Room[] loaded = new Room[1];
        RoomStatic info = hotCache.get("room:detail:" + id, RoomStatic.class, () -> {
            loaded[0] = roomMapper.queryRoomById(id, false);
            Room room = loaded[0];
            return room == null ? null : new RoomStatic(room.getRoomNumber(), room.getRoomType(), room.getFloor(), room.getCapacity(), room.getDescription(), room.getImage());
        });
        if (info == null) return null;
        if (loaded[0] != null) return convertToVO(loaded[0]);
        List<Integer> statuses = roomMapper.getRoomStatus(id);
        if (statuses.isEmpty()) return null;
        Room room = new Room(); room.setId(id.longValue()); room.setStatus(statuses.get(0));
        room.setRoomNumber(info.roomNumber()); room.setRoomType(info.roomType()); room.setFloor(info.floor());
        room.setCapacity(info.capacity()); room.setDescription(info.description()); room.setImage(info.image());
        return convertToVO(room);
    }

    @Override
    public void insertRoomService(InsertRoomDTO insertRoomDTO) {
        if (roomMapper.existByRoomNumber(insertRoomDTO.getRoomNumber())) throw new RoomNumberDuplicatedException("房间号已存在");
        roomTypeValid(insertRoomDTO.getRoomType());
        statusValid(insertRoomDTO.getStatus());
        roomMapper.insertRoom(insertRoomDTO);
    }

    @Override
    public void modifyRoomInfoService(InsertRoomDTO insertRoomDTO, Integer id) {
        Room room = roomMapper.queryRoomById(id, false);
        if (room == null) throw new BusinessException(HttpStatus.NOT_FOUND, "房间不存在");
        if (!Objects.equals(insertRoomDTO.getRoomNumber(), room.getRoomNumber()))
            if (roomMapper.existByRoomNumber(insertRoomDTO.getRoomNumber())) throw new RoomNumberDuplicatedException("房间号已存在");
        roomTypeValid(insertRoomDTO.getRoomType());
        if (insertRoomDTO.getStatus() != null) statusValid(insertRoomDTO.getStatus());
        roomMapper.modifyRoomInfo(insertRoomDTO, id);
        hotCache.evictAfterCommit(List.of("room:detail:" + id));
    }

    @Override
    public void deleteRoomService(Integer id) {
        roomMapper.deleteRoom(id);
        hotCache.evictAfterCommit(List.of("room:detail:" + id));
    }

    @Override
    public List<RoomStatusWallVO> getRoomStatusWallService() {
        List<RoomStatusWallVO> rooms = roomMapper.getRoomStatusWall(LocalDateTime.now());
        for (RoomStatusWallVO room : rooms) if (room.getIndividual() == null) room.setIndividual(new Individual());
        return rooms;
    }

    @Override
    public PageBean<QueryRoomsVO> queryRoomsService(Integer page, Integer pageSize, String roomNumber, Integer roomType, Integer status, LocalDate date) {
        List<QueryRoomsVO> voList = new ArrayList<>();
        PageBean<QueryRoomsVO> pageBean = new PageBean<>();
        Integer offset = (page - 1) * pageSize;
        List<Room> roomList = roomMapper.queryRooms(pageSize, offset, roomNumber, roomType, status, null);
        int count = roomMapper.queryRoomsCount(roomNumber, roomType, status);
        LocalDate priceDate = date == null ? LocalDate.now() : date;
        List<String> keys = roomList.stream().map(Room::getRoomType).distinct().map(type -> "price:" + type + ":" + priceDate).toList();
        Map<String, PriceCalendar> prices = hotCache.getAll(keys, PriceCalendar.class, missing -> {
            Map<String, PriceCalendar> loaded = new LinkedHashMap<>();
            for (String key : missing) {
                int type = Integer.parseInt(key.split(":")[1]);
                List<PriceCalendar> rows = roomMapper.getPriceCalendars(type, priceDate, priceDate);
                loaded.put(key, rows.isEmpty() ? null : rows.get(0));
            }
            return loaded;
        });
        for (Room room : roomList) {
            PriceCalendar price = prices.get("price:" + room.getRoomType() + ":" + priceDate);
            room.setPrice(price == null || price.getPrice() == null ? RoomTypeConstant.getDefaultPrice(room.getRoomType()) : price.getPrice());
            QueryRoomsVO queryRoomsVO = convertToVO(room);
            voList.add(queryRoomsVO);
        }
        pageBean.setTotal(count);
        pageBean.setList(voList);
        return pageBean;
    }
}
