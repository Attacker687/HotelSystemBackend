package com.winniethepooh.hotelsystembackend.controller;

import com.winniethepooh.hotelsystembackend.annotation.RoleRequired;
import com.winniethepooh.hotelsystembackend.constant.RoleConstant;
import com.winniethepooh.hotelsystembackend.context.BaseContext;
import com.winniethepooh.hotelsystembackend.dto.CommentOrderDTO;
import com.winniethepooh.hotelsystembackend.dto.InsertMealOrderDTO;
import com.winniethepooh.hotelsystembackend.dto.InsertRoomOrderDTO;
import com.winniethepooh.hotelsystembackend.dto.ModifyRoomOrderDTO;
import com.winniethepooh.hotelsystembackend.entity.Result;
import com.winniethepooh.hotelsystembackend.service.OrderService;
import com.winniethepooh.hotelsystembackend.service.OrderRequestService;
import com.winniethepooh.hotelsystembackend.vo.GetAllRoomOrderVO;
import com.winniethepooh.hotelsystembackend.vo.OrderQueryVO;
import com.winniethepooh.hotelsystembackend.vo.PageBean;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/order")
@Slf4j

public class OrderController {
    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRequestService orderRequestService;

    @RoleRequired({RoleConstant.USER})
    @GetMapping("/user/query")
    public Result queryOrderController(@RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate startDate,
                                       @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate endDate) {
        OrderQueryVO orderQueryVO = orderService.queryOrderService(startDate, endDate, BaseContext.getCurrentId());
        return Result.success(orderQueryVO);
    }

    @RoleRequired({RoleConstant.USER})
    @PostMapping("/user/comment")
    public Result commentOrderController(@Valid @RequestBody CommentOrderDTO commentOrderDTO) {
        orderService.commentOrderService(commentOrderDTO);
        return Result.success();
    }

    @RoleRequired({RoleConstant.MANAGER, RoleConstant.FRONT})
    @GetMapping("/query")
    public Result getAllRoomOrderController(@RequestParam(required = false, defaultValue = "1") @Min(value = 1, message = "page 必须大于等于1") Integer page,
                                            @RequestParam(required = false, defaultValue = "10") @Min(value = 1, message = "limit 必须在1到100之间") @Max(value = 100, message = "limit 必须在1到100之间") Integer limit) {
        PageBean<GetAllRoomOrderVO> pageBean = orderService.getAllRoomOrderService(page, limit);
        return Result.success(pageBean);
    }

    @PostMapping
    @RoleRequired({RoleConstant.FRONT, RoleConstant.USER})
    public Result insertRoomOrderController(@Valid @RequestBody InsertRoomOrderDTO insertRoomOrderDTO,
                                            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        Long id = orderRequestService.placeOrder(key, insertRoomOrderDTO);
        return BaseContext.getCurrentRole() == RoleConstant.USER ? Result.success(id) : Result.success();
    }

    @PutMapping("/{id}")
    @RoleRequired({RoleConstant.FRONT})
    public Result modifyRoomOrderController(@PathVariable Integer id, @RequestBody ModifyRoomOrderDTO modifyRoomOrderDTO) {
        orderService.modifyRoomOrderService(id, modifyRoomOrderDTO);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @RoleRequired({RoleConstant.MANAGER})
    public Result deleteRoomOrderController(@PathVariable Integer id) {
        orderService.deleteRoomOrderService(id);
        return Result.success();
    }

    @RoleRequired({RoleConstant.USER})
    @PostMapping("/pay")
    public Result payRoomOrderController(@RequestParam Long id) {
        orderService.payRoomOrderService(id);
        return Result.success();
    }

    @RoleRequired({RoleConstant.USER})
    @PostMapping("/cancel")
    public Result cancelRoomOrderController(@RequestParam Long id) {
        orderService.cancelRoomOrderService(id);
        return Result.success();
    }

    @RoleRequired({RoleConstant.USER})
    @PostMapping("/meal-order")
    public Result insertMealOrderController(@Valid @RequestBody InsertMealOrderDTO insertMealOrderDTO) {
        orderService.insertMealOrderService(insertMealOrderDTO);
        return Result.success();
    }

    @RoleRequired({RoleConstant.USER})
    @PutMapping("/meal-order/{id}/cancel")
    public Result cancelMealOrderController(@PathVariable Integer id) {
        orderService.cancelMealOrderService(id);
        return Result.success();
    }

}
