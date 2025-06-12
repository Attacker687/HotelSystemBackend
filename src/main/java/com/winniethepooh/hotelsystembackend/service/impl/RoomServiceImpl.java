package com.winniethepooh.hotelsystembackend.service.impl;

import com.winniethepooh.hotelsystembackend.constant.RoomOrderStatusConstant;
import com.winniethepooh.hotelsystembackend.constant.RoomStatusConstant;
import com.winniethepooh.hotelsystembackend.constant.RoomTypeConstant;
import com.winniethepooh.hotelsystembackend.dto.InsertRoomDTO;
import com.winniethepooh.hotelsystembackend.dto.TimeCheckDTO;
import com.winniethepooh.hotelsystembackend.entity.Individual;
import com.winniethepooh.hotelsystembackend.entity.Room;
import com.winniethepooh.hotelsystembackend.entity.RoomOrder;
import com.winniethepooh.hotelsystembackend.exception.RoomNumberDuplicatedException;
import com.winniethepooh.hotelsystembackend.exception.UnknownRoomTypeException;
import com.winniethepooh.hotelsystembackend.mapper.OrderMapper;
import com.winniethepooh.hotelsystembackend.mapper.RoomMapper;
import com.winniethepooh.hotelsystembackend.mapper.UserMapper;
import com.winniethepooh.hotelsystembackend.service.RoomService;
import com.winniethepooh.hotelsystembackend.vo.PageBean;
import com.winniethepooh.hotelsystembackend.vo.QueryRoomsVO;
import com.winniethepooh.hotelsystembackend.vo.RoomStatusWallVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class RoomServiceImpl implements RoomService {
    @Autowired
    private RoomMapper roomMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private OrderMapper orderMapper;

    private void roomTypeValid(Integer roomType) {
        if (roomType != RoomTypeConstant.SINGLE && roomType != RoomTypeConstant.DOUBLE && roomType != RoomTypeConstant.SUITE)
            throw new UnknownRoomTypeException("未知的房型");
    }

    private QueryRoomsVO convertToVO(Room room) {
        if (room == null) return null;
        QueryRoomsVO vo = new QueryRoomsVO();
        vo.setId(Math.toIntExact(room.getId()));
        vo.setRoomNumber(Integer.valueOf(room.getRoomNumber()));
        vo.setStatus(room.getStatus());
        vo.setRoomType(room.getRoomType());
        vo.setImage(room.getImage());
        vo.setDescription(room.getDescription());
        vo.setFloor(room.getFloor());
        vo.setCapacity(room.getCapacity());
        if (vo.getImage() == null || vo.getImage().isEmpty()) vo.setImage("https://hotelsystem.oss-cn-chengdu.aliyuncs.com/sample.jpeg");
        return vo;
    }

    @Override
    @Transactional
    public void modifyRoomStatusService(Integer id, Integer status) {
        RoomOrder order = orderMapper.getRoomOrderByRoomIdAndTime(id, LocalDateTime.now());
        Room room = roomMapper.queryRoomById(Math.toIntExact(order.getRoomId()), false);
        if (room.getStatus() == RoomStatusConstant.OCCUPIED && status == RoomStatusConstant.AVAILABLE)
            orderMapper.modifyRoomOrderStatus(order.getId(), RoomOrderStatusConstant.DONE);
        roomMapper.modifyRoomStatus(id, status);
    }

    @Override
    public QueryRoomsVO queryRoomByIdService(Integer id) {
        Room room = roomMapper.queryRoomById(id, false);
        return convertToVO(room);
    }

    @Override
    public void insertRoomService(InsertRoomDTO insertRoomDTO) {
        if (roomMapper.existByRoomNumber(insertRoomDTO.getRoomNumber())) throw new RoomNumberDuplicatedException("房间号已存在");
        roomTypeValid(insertRoomDTO.getRoomType());
        roomMapper.insertRoom(insertRoomDTO);
    }

    @Override
    public void modifyRoomInfoService(InsertRoomDTO insertRoomDTO, Integer id) {
        Room room = roomMapper.queryRoomById(id, false);
        if (!Objects.equals(insertRoomDTO.getRoomNumber(), room.getRoomNumber()))
            if (roomMapper.existByRoomNumber(insertRoomDTO.getRoomNumber())) throw new RoomNumberDuplicatedException("房间号已存在");
        roomTypeValid(insertRoomDTO.getRoomType());
        roomMapper.modifyRoomInfo(insertRoomDTO, id);
    }

    @Override
    public void deleteRoomService(Integer id) {
        roomMapper.deleteRoom(id);
    }

    @Override
    public List<RoomStatusWallVO> getRoomStatusWallService() {
        List<Room> rooms = roomMapper.queryRooms(null, null, null, null, null);
        List<RoomStatusWallVO> roomStatusWallVOList = new ArrayList<>();
        for (Room room : rooms) {
            RoomStatusWallVO roomStatusWallVO = new RoomStatusWallVO();
            roomStatusWallVO.setId(Math.toIntExact(room.getId()));
            roomStatusWallVO.setRoomType(room.getRoomType());
            roomStatusWallVO.setStatus(room.getStatus());
            roomStatusWallVO.setRoomNumber(Integer.valueOf(room.getRoomNumber()));
            BigDecimal price = roomMapper.getRoomPriceByTypeAndDate(room.getRoomType(), LocalDate.now());
            if (price == null) price = RoomTypeConstant.getDefaultPrice(room.getRoomType());
            roomStatusWallVO.setPrice(price);
            List<Individual> individualList = orderMapper.getIndividualByRoomIdAndDate(room.getId(), LocalDate.now());
            Individual individual = new Individual();
            if (individualList != null && !individualList.isEmpty())
                individual = individualList.get(0);
            roomStatusWallVO.setIndividual(individual);
            roomStatusWallVOList.add(roomStatusWallVO);
        }

        for (RoomStatusWallVO roomStatusWallVO : roomStatusWallVOList) {
            if (roomStatusWallVO.getStatus() == RoomStatusConstant.OCCUPIED) {
                TimeCheckDTO timeCheckDTO = orderMapper.getCheckTimeByRoomIdAndTime(roomStatusWallVO.getId(), LocalDateTime.now());
                if (timeCheckDTO != null) {
                    roomStatusWallVO.setCheckInTime(timeCheckDTO.getCheckInTime());
                    roomStatusWallVO.setCheckOutTime(timeCheckDTO.getCheckOutTime());
                }
            }
        }
        return roomStatusWallVOList;
    }

    @Override
    public PageBean<QueryRoomsVO> queryRoomsService(Integer page, Integer pageSize, Integer roomNumber, Integer roomType, Integer status, LocalDate date) {
        if (page == null || page < 1) page = 1;
        if (pageSize == null || pageSize < 1) pageSize = 10;
        List<QueryRoomsVO> voList = new ArrayList<>();
        PageBean<QueryRoomsVO> pageBean = new PageBean<>();
        Integer offset = (page - 1) * pageSize;
        List<Room> roomList = roomMapper.queryRooms(pageSize, offset, roomNumber, roomType, status);
        int count = roomMapper.queryRoomsCount(roomNumber, roomType, status);
        for (Room room : roomList) {
            QueryRoomsVO queryRoomsVO = convertToVO(room);
            BigDecimal price = roomMapper.getRoomPriceByTypeAndDate(room.getRoomType(), date);
            if (price == null) price = RoomTypeConstant.getDefaultPrice(room.getRoomType());
            queryRoomsVO.setPrice(price);
            voList.add(queryRoomsVO);
        }
        pageBean.setTotal(count);
        pageBean.setList(voList);
        return pageBean;
    }
}
