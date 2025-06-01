package com.winniethepooh.hotelsystembackend.controller;

import com.winniethepooh.hotelsystembackend.dto.InsertRoomDTO;
import com.winniethepooh.hotelsystembackend.dto.ModifyRoomStatusDTO;
import com.winniethepooh.hotelsystembackend.entity.Result;
import com.winniethepooh.hotelsystembackend.service.RoomService;
import com.winniethepooh.hotelsystembackend.vo.QueryRoomsVO;
import com.winniethepooh.hotelsystembackend.vo.PageBean;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/rooms")
public class RoomController {
    @Autowired
    private RoomService roomService;

    @GetMapping
    public Result queryRoomsController(@RequestParam(required = false, defaultValue = "1") Integer page, @RequestParam(required = false, defaultValue = "10") Integer pageSize,
                                       @RequestParam(required = false) Integer roomNumber, @RequestParam(required = false) Integer roomType,
                                       @RequestParam(required = false) Integer status) {

        List<QueryRoomsVO> queryRoomsVOList = roomService.queryRoomsService(page, pageSize, roomNumber, roomType, status);
        PageBean<QueryRoomsVO> pageBean = new PageBean<>();
        pageBean.setTotal(queryRoomsVOList.size());
        pageBean.setList(queryRoomsVOList);
        return Result.success(pageBean);
    }

    @GetMapping("/{id}")
    public Result queryRoomByIdController(@PathVariable Integer id) {
        QueryRoomsVO queryRoomsVO = roomService.queryRoomByIdService(id);
        return Result.success(queryRoomsVO);
    }

    @PatchMapping("/{id}/status")
    public Result modifyRoomStatusController(@PathVariable Integer id, @RequestBody ModifyRoomStatusDTO roomStatusDTO) {
        roomService.modifyRoomStatusService(id, roomStatusDTO.getStatus());
        return Result.success();
    }

    @PutMapping
    public Result modifyRoomStatusController(@RequestParam Integer id, @RequestParam Integer status) {
        roomService.modifyRoomStatusService(id, status);
        return Result.success();
    }

    @PostMapping
    public Result insertRoomController(@RequestBody InsertRoomDTO insertRoomDTO) {
        roomService.insertRoomService(insertRoomDTO);
        return Result.success();
    }

    @PutMapping("/{id}")
    public Result modifyRoomInfoController(@RequestBody InsertRoomDTO insertRoomDTO, @PathVariable Integer id) {
        roomService.modifyRoomInfoService(insertRoomDTO, id);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result deleteRoomController(@PathVariable Integer id) {
        roomService.deleteRoomService(id);
        return Result.success();
    }


}
