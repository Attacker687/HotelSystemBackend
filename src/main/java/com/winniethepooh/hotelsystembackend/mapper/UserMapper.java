package com.winniethepooh.hotelsystembackend.mapper;

import com.winniethepooh.hotelsystembackend.dto.RegisterDTO;
import com.winniethepooh.hotelsystembackend.dto.UserInfoChangeDTO;
import com.winniethepooh.hotelsystembackend.entity.Individual;
import com.winniethepooh.hotelsystembackend.entity.User;
import com.winniethepooh.hotelsystembackend.vo.QueryUserVO;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDate;

@Mapper
public interface UserMapper {

    void createUser(RegisterDTO registerDTO);

    User findUserByPhone(String phone);

    void updateLastLoginTime(int id);

    void updatePassword(Integer id, String password);

    User findUserById(Integer id);

    void modifyUserInfo(UserInfoChangeDTO userInfoChangeDTO);

    void createIndividual(Individual individual);

    Individual findIndividual(String name, String phone, String idCard);

    Individual findIndividualById(Integer individualId);

    QueryUserVO findUserByIdV2(Integer id);

    Integer getCustomerCountSince(LocalDate date);

    Integer getNewCustomerCount(LocalDate date);

    Integer getTodayCustomerCount(LocalDate date);
}
