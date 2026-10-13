package com.chengyinhub.business.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.UserPermissionMapper;
import com.chengyinhub.business.domain.UserPermission;
import com.chengyinhub.business.service.IUserPermissionService;

/**
 * 角色配额配置 Service 业务层处理
 */
@Service
public class UserPermissionServiceImpl implements IUserPermissionService
{
    @Autowired
    private UserPermissionMapper userPermissionMapper;

    @Override
    public UserPermission getByRole(String role)
    {
        return userPermissionMapper.selectByRole(role);
    }

    @Override
    public java.util.List<UserPermission> getAll()
    {
        return userPermissionMapper.selectAll();
    }

    @Override
    public int update(UserPermission p)
    {
        return userPermissionMapper.updateUserPermission(p);
    }
}
