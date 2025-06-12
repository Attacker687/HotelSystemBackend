package com.winniethepooh.hotelsystembackend.controller;

import com.winniethepooh.hotelsystembackend.annotation.RoleRequired;
import com.winniethepooh.hotelsystembackend.constant.RoleConstant;
import com.winniethepooh.hotelsystembackend.context.BaseContext;
import com.winniethepooh.hotelsystembackend.dto.ModifyStatusDTO;
import com.winniethepooh.hotelsystembackend.dto.StaffLoginDTO;
import com.winniethepooh.hotelsystembackend.dto.StaffRegisterDTO;
import com.winniethepooh.hotelsystembackend.entity.Result;
import com.winniethepooh.hotelsystembackend.entity.Staff;
import com.winniethepooh.hotelsystembackend.service.RedisService;
import com.winniethepooh.hotelsystembackend.service.StaffService;
import com.winniethepooh.hotelsystembackend.utils.JwtUtils;
import com.winniethepooh.hotelsystembackend.vo.LoginVO;
import com.winniethepooh.hotelsystembackend.vo.PageBean;
import com.winniethepooh.hotelsystembackend.vo.StaffVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/staff")

public class StaffController {
    @Autowired
    private StaffService staffService;
    @Autowired
    private RedisService redisService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @PostMapping("/login")
    public Result staffLoginController(@RequestBody StaffLoginDTO staffLoginDTO) {
        Staff staff = staffService.staffLoginService(staffLoginDTO);
        Map<String, Object> claims = new HashMap<>();
        claims.put("id", staff.getId());
        claims.put("role", staff.getRole());
        String token = JwtUtils.generateJwt(claims);

        redisService.deleteKeysByValue(RoleConstant.convertToStringConstant
                (staff.getRole()) + staff.getId());
        ValueOperations<String, String> ops = stringRedisTemplate.opsForValue();
        ops.set(token, RoleConstant.convertToStringConstant(staff.getRole()) + "_" + staff.getId());

        LoginVO loginVO = new LoginVO();
        loginVO.setToken(token);
        loginVO.setId(staff.getId());
        loginVO.setRole(staff.getRole());
        return Result.success(loginVO);
    }

    @RoleRequired({RoleConstant.MANAGER, RoleConstant.FRONT, RoleConstant.RESTAURANT})
    @GetMapping("/logout")
    public Result staffLogoutController() {
        Integer id = BaseContext.getCurrentId();
        Integer role = BaseContext.getCurrentRole();
        redisService.deleteKeysByValue(RoleConstant.convertToStringConstant
                (role) + "_" + id);
        return Result.success();
    }

    @RoleRequired({RoleConstant.MANAGER})
    @PostMapping("/register")
    public Result staffRegisterController(@RequestBody StaffRegisterDTO staffRegisterDTO) {
        staffService.staffRegisterService(staffRegisterDTO);
        return Result.success();
    }

    @RoleRequired({RoleConstant.MANAGER})
    @PostMapping("/status")
    public Result modifyStatusController(@RequestBody ModifyStatusDTO modifyStatusDTO) {
        staffService.modifyStatusService(modifyStatusDTO);
        return Result.success();
    }

    @RoleRequired({RoleConstant.MANAGER})
    @DeleteMapping
    public Result deleteStaffController(@RequestParam Integer id) {
        staffService.deleteStaffService(id);
        return Result.success();
    }

    @RoleRequired({RoleConstant.MANAGER})
    @GetMapping("/list")
    public Result getStaffListController(@RequestParam Integer page, @RequestParam Integer pageSize,
                                         @RequestParam(required = false) Integer role,
                                         @RequestParam(required = false) String account) {
        PageBean<StaffVO> pageBean = staffService.getStaffListService(page, pageSize, role, account);
        return Result.success(pageBean);
    }
}
