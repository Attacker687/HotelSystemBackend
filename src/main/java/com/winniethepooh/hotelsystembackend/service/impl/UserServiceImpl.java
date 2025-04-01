package com.winniethepooh.hotelsystembackend.service.impl;

import cn.hutool.core.lang.Validator;
import cn.hutool.core.util.IdcardUtil;
import cn.hutool.core.util.PhoneUtil;
import com.winniethepooh.hotelsystembackend.dto.LoginDTO;
import com.winniethepooh.hotelsystembackend.dto.RegisterDTO;
import com.winniethepooh.hotelsystembackend.entity.User;
import com.winniethepooh.hotelsystembackend.exception.*;
import com.winniethepooh.hotelsystembackend.mapper.UserMapper;
import com.winniethepooh.hotelsystembackend.service.UserService;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl implements UserService {
    @Autowired
    private UserMapper userMapper;

    @Override
    public void registerService(RegisterDTO registerDTO) {
        if (registerDTO.getName().isBlank()) throw new UserNameInvalidException("用户姓名不能为空");
        if (registerDTO.getName().length() > 16) throw new UserNameInvalidException("用户姓名不能超过16个字");
        if (!IdcardUtil.isValidCard(registerDTO.getIdCardNumber()))
            throw new UserIDCardInvalidException("请输入正确的身份证");
        if (!PhoneUtil.isMobile(registerDTO.getPhone())) throw new UserPhoneInvalidException("请输入正确的手机号");
        if (!Validator.isEmail(registerDTO.getEmail())) throw new UserEmailInvalidException("请输入正确的邮箱");
        if (registerDTO.getPassword().length() < 6) throw new UserPasswordInvalidException("密码不得少于6位");
        if (registerDTO.getPassword().length() > 16) throw new UserPasswordInvalidException("密码不得多于16位");
        if (!registerDTO.getPassword().matches("^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d)(?=.*[!@#$%^&*()_+\\-={}\\[\\]:;\"'<>,.?/~`|\\\\]).{8,20}$"
        ))
            throw new UserPasswordInvalidException("密码必须包含大小写字母、数字和特殊字符");
        User user = userMapper.findUserByPhone(registerDTO.getPhone());
        if (user != null) throw new UserDuplicatedException("该手机号已被注册");
        registerDTO.setPassword(DigestUtils.md5Hex(registerDTO.getPassword()));
        userMapper.createUser(registerDTO);
    }

    @Override
    public User loginService(LoginDTO loginDTO) {
        if (userMapper.findUserByPhone(loginDTO.getPhone()) == null)
            throw new UserNotFoundException("该用户未注册");
        loginDTO.setPassword(DigestUtils.md5Hex(loginDTO.getPassword()));
        User user = userMapper.findUserByPhoneAndPassword(loginDTO);
        if (user == null) throw new PasswordIncorrectException("密码错误，请重新输入");
        userMapper.updateLastLoginTime(user.getId());
        return user;
    }
}
