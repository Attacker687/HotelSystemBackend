package com.winniethepooh.hotelsystembackend.service.impl;

import com.winniethepooh.hotelsystembackend.constant.StaffStatusConstant;
import com.winniethepooh.hotelsystembackend.dto.ModifyStatusDTO;
import com.winniethepooh.hotelsystembackend.dto.StaffLoginDTO;
import com.winniethepooh.hotelsystembackend.dto.StaffRegisterDTO;
import com.winniethepooh.hotelsystembackend.entity.Staff;
import com.winniethepooh.hotelsystembackend.exception.DuplicatedException;
import com.winniethepooh.hotelsystembackend.exception.PasswordIncorrectException;
import com.winniethepooh.hotelsystembackend.mapper.StaffMapper;
import com.winniethepooh.hotelsystembackend.service.RedisService;
import com.winniethepooh.hotelsystembackend.service.StaffService;
import com.winniethepooh.hotelsystembackend.utils.PasswordUtils;
import com.winniethepooh.hotelsystembackend.vo.PageBean;
import com.winniethepooh.hotelsystembackend.vo.StaffVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class StaffServiceImpl implements StaffService {
    @Autowired
    private StaffMapper staffMapper;
    @Autowired
    private RedisService redisService;
    @Override
    public Staff staffLoginService(StaffLoginDTO staffLoginDTO) {
        // 账号不存在、已停用或删除、密码错误提示相同，不能用来枚举账号
        Staff staff = staffMapper.getActiveStaffByAccount(staffLoginDTO.getAccount());
        if (staff == null || !PasswordUtils.matches(staffLoginDTO.getPassword(), staff.getPassword()))
            throw new PasswordIncorrectException("账号或密码错误");
        // 旧 MD5 哈希在登录成功时迁移为 BCrypt
        if (PasswordUtils.isLegacy(staff.getPassword()))
            staffMapper.updatePassword(staff.getId(), PasswordUtils.hash(staffLoginDTO.getPassword()));
        return staff;
    }

    @Override
    public void staffRegisterService(StaffRegisterDTO staffRegisterDTO) {
        if (staffMapper.existStaffByAccount(staffRegisterDTO.getAccount()))
            throw new DuplicatedException("此账号已注册");
        staffRegisterDTO.setPassword(PasswordUtils.hash(staffRegisterDTO.getPassword()));
        staffMapper.createStaff(staffRegisterDTO);
    }

    @Override
    public void modifyStatusService(ModifyStatusDTO modifyStatusDTO) {
        staffMapper.modifyStatus(modifyStatusDTO);

        // 设为禁用立即删除token
        if (modifyStatusDTO.getStatus() == StaffStatusConstant.INACTIVE)
            redisService.revokeSession(RedisService.principal(
                    staffMapper.getStaffById(modifyStatusDTO.getId()).getRole(), modifyStatusDTO.getId()));
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public void deleteStaffService(Integer id) {

        // 先删除token再删除账号
        redisService.revokeSession(RedisService.principal(staffMapper.getStaffById(id).getRole(), id));
        staffMapper.deleteStaff(id);
    }

    @Override
    public PageBean<StaffVO> getStaffListService(Integer page, Integer pageSize, Integer role, String account) {
        if (page == null || page <= 0) page = 1;
        if (pageSize == null || pageSize <= 0) pageSize = 10;
        PageBean<StaffVO> pageBean = new PageBean<>();
        Integer offset = (page - 1) * pageSize;
        Integer limit = pageSize;
        List<Staff> staffList = staffMapper.getStaffList(offset, limit, role, account);
        int total = staffMapper.getStaffListTotal(role, account);
        List<StaffVO> staffVOList = new ArrayList<>();
        for (Staff staff : staffList) {
            StaffVO staffVO = new StaffVO();
            BeanUtils.copyProperties(staff, staffVO);
            staffVOList.add(staffVO);
        }
        pageBean.setList(staffVOList);
        pageBean.setTotal(total);
        return pageBean;
    }
}
