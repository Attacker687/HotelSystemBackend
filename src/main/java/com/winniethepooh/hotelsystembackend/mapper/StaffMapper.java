package com.winniethepooh.hotelsystembackend.mapper;

import com.winniethepooh.hotelsystembackend.dto.ModifyStatusDTO;
import com.winniethepooh.hotelsystembackend.dto.StaffLoginDTO;
import com.winniethepooh.hotelsystembackend.dto.StaffRegisterDTO;
import com.winniethepooh.hotelsystembackend.entity.Staff;
import org.apache.ibatis.annotations.Mapper;

import javax.management.relation.Relation;
import java.util.List;

@Mapper
public interface StaffMapper {

    boolean existStaffByAccount(String accountName);

    Staff getStaffByAccountAndPassword(StaffLoginDTO staffLoginDTO);

    void createStaff(StaffRegisterDTO staffRegisterDTO);

    void modifyStatus(ModifyStatusDTO modifyStatusDTO);

    void deleteStaff(Integer id);

    Staff getStaffById(Integer id);

    List<Staff> getStaffList(Integer offset, Integer limit, Integer role, String account);

    int getStaffListTotal(Integer role, String account);
}
