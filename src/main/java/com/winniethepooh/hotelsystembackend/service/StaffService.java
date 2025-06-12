package com.winniethepooh.hotelsystembackend.service;

import com.winniethepooh.hotelsystembackend.dto.ModifyStatusDTO;
import com.winniethepooh.hotelsystembackend.dto.StaffLoginDTO;
import com.winniethepooh.hotelsystembackend.dto.StaffRegisterDTO;
import com.winniethepooh.hotelsystembackend.entity.Staff;
import com.winniethepooh.hotelsystembackend.vo.PageBean;
import com.winniethepooh.hotelsystembackend.vo.StaffVO;

import java.util.List;

public interface StaffService {
    Staff staffLoginService(StaffLoginDTO staffLoginDTO);

    void staffRegisterService(StaffRegisterDTO staffRegisterDTO);

    void modifyStatusService(ModifyStatusDTO modifyStatusDTO);

    void deleteStaffService(Integer id);

    PageBean<StaffVO> getStaffListService(Integer page, Integer pageSize, Integer role, String account);
}
