package com.winniethepooh.hotelsystembackend.service.impl;

import com.winniethepooh.hotelsystembackend.context.BaseContext;
import com.winniethepooh.hotelsystembackend.dto.UserLoginDTO;
import com.winniethepooh.hotelsystembackend.dto.RegisterDTO;
import com.winniethepooh.hotelsystembackend.dto.UserInfoChangeDTO;
import com.winniethepooh.hotelsystembackend.entity.Individual;
import com.winniethepooh.hotelsystembackend.entity.User;
import com.winniethepooh.hotelsystembackend.exception.*;
import com.winniethepooh.hotelsystembackend.mapper.UserMapper;
import com.winniethepooh.hotelsystembackend.service.RedisService;
import com.winniethepooh.hotelsystembackend.service.UserService;
import com.winniethepooh.hotelsystembackend.vo.QueryUserVO;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl implements UserService {
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private RedisService redisService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void registerService(RegisterDTO registerDTO) {
        // 姓名、身份证、手机号、邮箱、密码的格式由 RegisterDTO 上的约束注解在进入 Service 前校验
        User user = userMapper.findUserByPhone(registerDTO.getPhone());
        if (user != null) throw new DuplicatedException("该手机号已被注册");
        registerDTO.setPassword(DigestUtils.md5Hex(registerDTO.getPassword()));
        userMapper.createUser(registerDTO);
        Individual individual = new Individual();
        individual.setName(registerDTO.getName());
        individual.setPhone(registerDTO.getPhone());
        individual.setIdCardNumber(registerDTO.getIdCardNumber());
        userMapper.createIndividual(individual);
    }

    @Override
    public User loginService(UserLoginDTO userLoginDTO) {
        if (userMapper.findUserByPhone(userLoginDTO.getPhone()) == null)
            throw new UserNotFoundException("该用户未注册");
        userLoginDTO.setPassword(DigestUtils.md5Hex(userLoginDTO.getPassword()));
        User user = userMapper.findUserByPhoneAndPassword(userLoginDTO.getPhone(), userLoginDTO.getPassword());
        if (user == null) throw new PasswordIncorrectException("密码错误，请重新输入");
        userMapper.updateLastLoginTime(user.getId());
        return user;
    }

    @Override
    public void changeInfoService(UserInfoChangeDTO userInfoChangeDTO) {
        boolean passwordChanged = false;
        if (!userMapper.findUserById(BaseContext.getCurrentId()).getPhone().equals(userInfoChangeDTO.getPhone()))
            throw new UnknownException("登录账号与即将修改的账号不一致，操作失败，请联系管理员");
        if (userInfoChangeDTO.getOriginPassword() != null && userInfoChangeDTO.getPasswordToChange() != null) {
            if (userMapper.findUserByPhoneAndPassword(userInfoChangeDTO.getPhone(), DigestUtils.md5Hex(userInfoChangeDTO.getOriginPassword())) == null)
                throw new PasswordIncorrectException("原密码错误，操作失败");
            userInfoChangeDTO.setPasswordToChange(DigestUtils.md5Hex(userInfoChangeDTO.getPasswordToChange()));
            passwordChanged = true;
        }

        userMapper.modifyUserInfo(userInfoChangeDTO);
        if (passwordChanged) redisService.deleteKeysByValue(String.valueOf(BaseContext.getCurrentId()));
    }

    @Override
    public QueryUserVO queryUserByIdService(Integer id) {
        return userMapper.findUserByIdV2(id);
    }
}
