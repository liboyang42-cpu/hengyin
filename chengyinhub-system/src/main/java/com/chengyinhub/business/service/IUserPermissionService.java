package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.UserPermission;

/**
 * 角色配额配置 Service 接口
 */
public interface IUserPermissionService
{
    /**
     * 按角色查询配额配置
     *
     * @param role 角色 player/club/merchant
     * @return 配额配置
     */
    public UserPermission getByRole(String role);

    public java.util.List<UserPermission> getAll();

    public int update(UserPermission p);
}
