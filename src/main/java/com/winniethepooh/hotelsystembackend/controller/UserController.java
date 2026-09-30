package com.winniethepooh.hotelsystembackend.controller;

import com.winniethepooh.hotelsystembackend.annotation.RoleRequired;
import com.winniethepooh.hotelsystembackend.constant.RoleConstant;
import com.winniethepooh.hotelsystembackend.dto.UserLoginDTO;
import com.winniethepooh.hotelsystembackend.dto.RegisterDTO;
import com.winniethepooh.hotelsystembackend.dto.UserInfoChangeDTO;
import com.winniethepooh.hotelsystembackend.entity.Result;
import com.winniethepooh.hotelsystembackend.entity.User;
import com.winniethepooh.hotelsystembackend.service.LoginAttemptService;
import com.winniethepooh.hotelsystembackend.service.RedisService;
import com.winniethepooh.hotelsystembackend.service.UserService;
import com.winniethepooh.hotelsystembackend.utils.JwtUtils;
import com.winniethepooh.hotelsystembackend.vo.LoginVO;
import com.winniethepooh.hotelsystembackend.vo.QueryUserVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/user")
@Slf4j
public class UserController {
    @Autowired
    private UserService userService;
    @Autowired
    private RedisService redisService;
    @Autowired
    private JwtUtils jwtUtils;
    @Autowired
    private LoginAttemptService loginAttemptService;

    @PostMapping("/register")
    public Result registerController(@Valid @RequestBody RegisterDTO registerDTO) {
        userService.registerService(registerDTO);
        return Result.success();
    }

    @PostMapping("/login")
    public Result loginController(@RequestBody UserLoginDTO userLoginDTO, HttpServletRequest request) {
        log.info("用户登录:{}", userLoginDTO.getPhone());
        User user = loginAttemptService.guard(userLoginDTO.getPhone(), request.getRemoteAddr(),
                () -> userService.loginService(userLoginDTO));
        Map<String, Object> claims = new HashMap<>();
        claims.put("id", user.getId());
        claims.put("role", RoleConstant.USER);
        String token = jwtUtils.generateJwt(claims);

        // 更新redis里的token, 保证同一用户的token只有一个
        redisService.saveSession(RedisService.principal(RoleConstant.USER, user.getId()), token);

        LoginVO loginVO = new LoginVO();
        loginVO.setToken(token);
        loginVO.setId(user.getId());
        loginVO.setRole(RoleConstant.USER);
        return Result.success(loginVO);
    }

    @RoleRequired({RoleConstant.USER})
    @PostMapping("/change")
    public Result changeInfoController(@Valid @RequestBody UserInfoChangeDTO userInfoChangeDTO) {
        userService.changeInfoService(userInfoChangeDTO);
        return Result.success();
    }

    @RoleRequired({RoleConstant.USER})
    @GetMapping("/{id}")
    public Result queryUserByIdController(@PathVariable Integer id) {
        QueryUserVO queryUserVO = userService.queryUserByIdService(id);
        return Result.success(queryUserVO);
    }
}
