package com.winniethepooh.hotelsystembackend.service;


import com.winniethepooh.hotelsystembackend.dto.LoginDTO;
import com.winniethepooh.hotelsystembackend.dto.RegisterDTO;
import com.winniethepooh.hotelsystembackend.entity.User;

public interface UserService {
    void registerService(RegisterDTO registerDTO);

    User loginService(LoginDTO loginDTO);
}
