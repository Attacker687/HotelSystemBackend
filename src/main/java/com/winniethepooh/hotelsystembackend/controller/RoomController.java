package com.winniethepooh.hotelsystembackend.controller;

import com.winniethepooh.hotelsystembackend.dto.InsertRoomOrderDTO;
import com.winniethepooh.hotelsystembackend.dto.ModifyRoomStatusDTO;
import com.winniethepooh.hotelsystembackend.entity.Result;
import com.winniethepooh.hotelsystembackend.service.RoomService;
import com.winniethepooh.hotelsystembackend.vo.GetAllRoomsVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/rooms")
public class RoomController {
    @Autowired
    private RoomService roomService;

    @GetMapping
    public Result getAllRoomsController() {
        List<GetAllRoomsVO> allRoomsVOList = roomService.getAllRoomsService();
        return Result.success(allRoomsVOList);
    }

    @PutMapping("/{roomNumber}/status")
    public Result modifyRoomStatusController(@PathVariable Integer roomNumber, @RequestBody ModifyRoomStatusDTO roomStatusDTO) {
        roomService.modifyRoomStatusService(roomNumber, roomStatusDTO.getStatus());
        return Result.success();
    }


}
