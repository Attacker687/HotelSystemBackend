package com.winniethepooh.hotelsystembackend.mapper;

import com.winniethepooh.hotelsystembackend.dto.LoginDTO;
import com.winniethepooh.hotelsystembackend.dto.RegisterDTO;
import com.winniethepooh.hotelsystembackend.dto.UserInfoChangeDTO;
import com.winniethepooh.hotelsystembackend.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper {

    void createUser(RegisterDTO registerDTO);

    User findUserByPhone(String phone);

    User findUserByPhoneAndPassword(String phone, String password);

    void updateLastLoginTime(int id);

    User findUserById(Integer id);

    void modifyUserInfo(UserInfoChangeDTO userInfoChangeDTO);
}
