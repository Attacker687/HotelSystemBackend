package com.winniethepooh.hotelsystembackend.service.impl;

import cn.hutool.core.util.DesensitizedUtil;
import com.winniethepooh.hotelsystembackend.constant.RoleConstant;
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
import com.winniethepooh.hotelsystembackend.utils.PasswordUtils;
import com.winniethepooh.hotelsystembackend.vo.QueryUserVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl implements UserService {
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private RedisService redisService;

    @Override
    public void registerService(RegisterDTO registerDTO) {
        // 姓名、身份证、手机号、邮箱、密码的格式由 RegisterDTO 上的约束注解在进入 Service 前校验
        User user = userMapper.findUserByPhone(registerDTO.getPhone());
        if (user != null) throw new DuplicatedException("该手机号已被注册");
        registerDTO.setPassword(PasswordUtils.hash(registerDTO.getPassword()));
        userMapper.createUser(registerDTO);
        Individual individual = new Individual();
        individual.setName(registerDTO.getName());
        individual.setPhone(registerDTO.getPhone());
        individual.setIdCardNumber(registerDTO.getIdCardNumber());
        userMapper.createIndividual(individual);
    }

    @Override
    public User loginService(UserLoginDTO userLoginDTO) {
        // 账号不存在和密码错误提示相同，不能用来枚举账号
        User user = userMapper.findUserByPhone(userLoginDTO.getPhone());
        if (user == null || !PasswordUtils.matches(userLoginDTO.getPassword(), user.getPassword()))
            throw new PasswordIncorrectException("账号或密码错误");
        // 旧 MD5 哈希在登录成功时迁移为 BCrypt
        if (PasswordUtils.isLegacy(user.getPassword()))
            userMapper.updatePassword(user.getId(), PasswordUtils.hash(userLoginDTO.getPassword()));
        userMapper.updateLastLoginTime(user.getId());
        return user;
    }

    @Override
    public void changeInfoService(UserInfoChangeDTO userInfoChangeDTO) {
        if ((userInfoChangeDTO.getOriginPassword() == null) != (userInfoChangeDTO.getPasswordToChange() == null))
            throw new BusinessException(HttpStatus.BAD_REQUEST, "修改密码必须同时提供原密码和新密码");
        boolean passwordChanged = false;
        User current = userMapper.findUserById(BaseContext.getCurrentId());
        if (!current.getPhone().equals(userInfoChangeDTO.getPhone()))
            throw new UnknownException("登录账号与即将修改的账号不一致，操作失败，请联系管理员");
        if (userInfoChangeDTO.getOriginPassword() != null && userInfoChangeDTO.getPasswordToChange() != null) {
            // 原密码可能仍是未迁移的旧 MD5 哈希，matches 两种都认
            if (!PasswordUtils.matches(userInfoChangeDTO.getOriginPassword(), current.getPassword()))
                throw new PasswordIncorrectException("原密码错误，操作失败");
            userInfoChangeDTO.setPasswordToChange(PasswordUtils.hash(userInfoChangeDTO.getPasswordToChange()));
            passwordChanged = true;
        }

        userMapper.modifyUserInfo(userInfoChangeDTO);
        if (passwordChanged) redisService.revokeSession(RedisService.principal(RoleConstant.USER, BaseContext.getCurrentId()));
    }

    @Override
    public QueryUserVO queryUserByIdService(Integer id) {
        QueryUserVO vo = userMapper.findUserByIdV2(id);
        // 身份证号脱敏：保留前 6 位和后 4 位
        if (vo != null && vo.getIdCardNumber() != null)
            vo.setIdCardNumber(DesensitizedUtil.idCardNum(vo.getIdCardNumber(), 6, 4));
        return vo;
    }
}
