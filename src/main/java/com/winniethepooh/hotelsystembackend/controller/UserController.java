package com.winniethepooh.hotelsystembackend.controller;

import com.winniethepooh.hotelsystembackend.dto.LoginDTO;
import com.winniethepooh.hotelsystembackend.dto.RegisterDTO;
import com.winniethepooh.hotelsystembackend.dto.UserInfoChangeDTO;
import com.winniethepooh.hotelsystembackend.entity.Result;
import com.winniethepooh.hotelsystembackend.entity.User;
import com.winniethepooh.hotelsystembackend.service.RedisService;
import com.winniethepooh.hotelsystembackend.service.UserService;
import com.winniethepooh.hotelsystembackend.utils.JwtUtils;
import com.winniethepooh.hotelsystembackend.vo.LoginVO;
import com.winniethepooh.hotelsystembackend.vo.QueryUserVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/user")
@Slf4j
public class UserController {
    @Autowired
    private UserService userService;
    @Autowired
    private RedisService redisService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @PostMapping("/register")
    public Result registerController(@RequestBody RegisterDTO registerDTO) {
        userService.registerService(registerDTO);
        return Result.success();
    }

    @PostMapping("/login")
    public Result loginController(@RequestBody LoginDTO loginDTO) {
        log.info("用户登录:{}", loginDTO.getPhone());
        User user = userService.loginService(loginDTO);
        Map<String, Object> claims = new HashMap<>();
        claims.put("id", user.getId());
        String token = JwtUtils.generateJwt(claims);

        redisService.deleteKeysByValue(String.valueOf(user.getId()));
        ValueOperations<String, String> ops = stringRedisTemplate.opsForValue();
        ops.set(token, String.valueOf(user.getId()), 3, TimeUnit.HOURS);
        // 更新redis里的token, 保证同一用户的token只有一个

        LoginVO loginVO = new LoginVO();
        loginVO.setToken(token);
        loginVO.setId(user.getId());
        return Result.success(loginVO);
    }

    @PostMapping("/change")
    public Result changeInfoController(@RequestBody UserInfoChangeDTO userInfoChangeDTO) {
        userService.changeInfoService(userInfoChangeDTO);
        return Result.success();
    }

    @GetMapping("/{id}")
    public Result queryUserByIdController(@PathVariable Integer id) {
        QueryUserVO queryUserVO = userService.queryUserByIdService(id);
        return Result.success(queryUserVO);
    }
}
