package com.winniethepooh.hotelsystembackend.service;


import com.winniethepooh.hotelsystembackend.dto.LoginDTO;
import com.winniethepooh.hotelsystembackend.dto.RegisterDTO;
import com.winniethepooh.hotelsystembackend.dto.UserInfoChangeDTO;
import com.winniethepooh.hotelsystembackend.entity.User;
import com.winniethepooh.hotelsystembackend.vo.QueryUserVO;

public interface UserService {
    void registerService(RegisterDTO registerDTO);

    User loginService(LoginDTO loginDTO);

    void changeInfoService(UserInfoChangeDTO userInfoChangeDTO);

    QueryUserVO queryUserByIdService(Integer id);
}
