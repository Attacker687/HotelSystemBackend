package com.winniethepooh.hotelsystembackend.controller;

import com.winniethepooh.hotelsystembackend.annotation.RoleRequired;
import com.winniethepooh.hotelsystembackend.constant.RoleConstant;
import com.winniethepooh.hotelsystembackend.dto.InsertRoomDTO;
import com.winniethepooh.hotelsystembackend.dto.ModifyStatusDTO;
import com.winniethepooh.hotelsystembackend.entity.Result;
import com.winniethepooh.hotelsystembackend.service.RoomService;
import com.winniethepooh.hotelsystembackend.vo.QueryRoomsVO;
import com.winniethepooh.hotelsystembackend.vo.PageBean;
import com.winniethepooh.hotelsystembackend.vo.RoomStatusWallVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/rooms")
public class RoomController {
    @Autowired
    private RoomService roomService;

    @GetMapping
    public Result queryRoomsController(@RequestParam(required = false, defaultValue = "1") Integer page, @RequestParam(required = false, defaultValue = "100") Integer pageSize,
                                       @RequestParam(required = false) Integer roomNumber, @RequestParam(required = false) Integer roomType,
                                       @RequestParam(required = false) Integer status, @RequestParam(required = false) LocalDate date) {
        if (date == null) date = LocalDate.now();
        PageBean<QueryRoomsVO> pageBean = roomService.queryRoomsService(page, pageSize, roomNumber, roomType, status, date);
        return Result.success(pageBean);
    }

    @GetMapping("/{id}")
    public Result queryRoomByIdController(@PathVariable Integer id) {
        QueryRoomsVO queryRoomsVO = roomService.queryRoomByIdService(id);
        return Result.success(queryRoomsVO);
    }

    @PutMapping
    @RoleRequired({RoleConstant.MANAGER, RoleConstant.FRONT})
    public Result modifyRoomStatusController(@RequestBody ModifyStatusDTO modifyStatusDTO) {
        roomService.modifyRoomStatusService(modifyStatusDTO.getId(), modifyStatusDTO.getStatus());
        return Result.success();
    }

    @PostMapping
    @RoleRequired({RoleConstant.MANAGER})
    public Result insertRoomController(@RequestBody InsertRoomDTO insertRoomDTO) {
        roomService.insertRoomService(insertRoomDTO);
        return Result.success();
    }

    @PutMapping("/{id}")
    @RoleRequired({RoleConstant.MANAGER})
    public Result modifyRoomInfoController(@RequestBody InsertRoomDTO insertRoomDTO, @PathVariable Integer id) {
        roomService.modifyRoomInfoService(insertRoomDTO, id);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @RoleRequired({RoleConstant.MANAGER})
    public Result deleteRoomController(@PathVariable Integer id) {
        roomService.deleteRoomService(id);
        return Result.success();
    }

    @GetMapping("/status-wall")
    @RoleRequired({RoleConstant.FRONT})
    public Result getRoomStatusWallController() {
        List<RoomStatusWallVO> roomStatusWallVOList = roomService.getRoomStatusWallService();
        return Result.success(roomStatusWallVOList);
    }

}
