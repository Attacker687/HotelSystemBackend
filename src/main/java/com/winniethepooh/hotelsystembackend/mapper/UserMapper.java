package com.winniethepooh.hotelsystembackend.mapper;

import com.winniethepooh.hotelsystembackend.dto.RegisterDTO;
import com.winniethepooh.hotelsystembackend.dto.UserInfoChangeDTO;
import com.winniethepooh.hotelsystembackend.entity.Individual;
import com.winniethepooh.hotelsystembackend.entity.User;
import com.winniethepooh.hotelsystembackend.vo.QueryUserVO;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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

    /** date 之前（不含 date 当天）建立的入住人数。 */
    Integer getCustomerCountBefore(LocalDate date);

    /** [startDate, endDate] 内建立的入住人的创建时间。 */
    List<LocalDateTime> getIndividualCreatedTimes(LocalDate startDate, LocalDate endDate);
}
