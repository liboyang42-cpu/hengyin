package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.UserPermission;

/**
 * 角色配额配置 Mapper 接口
 */
public interface UserPermissionMapper
{
    /**
     * 按角色查询配额配置（membership_level 为空的基础配置）
     *
     * @param role 角色 player/club/merchant
     * @return 配额配置
     */
    public UserPermission selectByRole(String role);

    public java.util.List<UserPermission> selectAll();

    public int updateUserPermission(UserPermission p);
}
