package com.winniethepooh.hotelsystembackend.controller;

import com.winniethepooh.hotelsystembackend.context.BaseContext;
import com.winniethepooh.hotelsystembackend.dto.CommentOrderDTO;
import com.winniethepooh.hotelsystembackend.dto.InsertRoomOrderDTO;
import com.winniethepooh.hotelsystembackend.dto.ModifyRoomOrderDTO;
import com.winniethepooh.hotelsystembackend.entity.Result;
import com.winniethepooh.hotelsystembackend.service.OrderService;
import com.winniethepooh.hotelsystembackend.vo.GetAllRoomOrderVO;
import com.winniethepooh.hotelsystembackend.vo.OrderQueryVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/order")
@Slf4j

public class OrderController {
    @Autowired
    private OrderService orderService;

    @GetMapping("/query")
    public Result queryOrderController(@RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate startDate,
                                       @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate endDate) {
        OrderQueryVO orderQueryVO = orderService.queryOrderService(startDate, endDate, BaseContext.getCurrentId());
        return Result.success(orderQueryVO);
    }

    @PostMapping("/comment")
    public Result commentOrderController(@RequestBody CommentOrderDTO commentOrderDTO) {
        orderService.commentOrderService(commentOrderDTO);
        return Result.success();
    }

    @GetMapping("/AllRoomOrder")
    public Result getAllRoomOrderController() {
        List<GetAllRoomOrderVO> allRoomOrderVOList = orderService.getAllRoomOrderService();
        return Result.success(allRoomOrderVOList);
    }

    @PostMapping
    public Result insertRoomOrderController(@RequestBody InsertRoomOrderDTO insertRoomOrderDTO) {
        orderService.insertRoomOrderService(insertRoomOrderDTO);
        return Result.success();
    }

    @PutMapping("/{id}")
    public Result modifyRoomOrderController(@PathVariable Integer id, @RequestBody ModifyRoomOrderDTO modifyRoomOrderDTO) {
        orderService.modifyRoomOrderService(id, modifyRoomOrderDTO);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result deleteRoomOrderController(@PathVariable Integer id) {
        orderService.deleteRoomOrderService(id);
        return Result.success();
    }
}
