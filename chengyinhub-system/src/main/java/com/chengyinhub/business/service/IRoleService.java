package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UserPermission;

/**
 * 会员角色（RBAC）Service 接口。
 *
 * 角色地基：统一解析会员角色 + 取角色配额，供 RoleCenter / 配额校验复用。
 */
public interface IRoleService
{
    /** 角色常量 */
    String ROLE_PLAYER = "player";
    String ROLE_CLUB = "club";
    String ROLE_MERCHANT = "merchant";

    /**
     * 解析会员角色：member.role 非空取之；为空时按 userType 兜底（过渡期保险）。
     *
     * @param member 会员
     * @return player/club/merchant
     */
    String resolveRole(UmsMember member);

    /**
     * 取会员对应的角色配额配置。
     *
     * @param member 会员
     * @return 配额配置（可能为 null，调用方需判空）
     */
    UserPermission getPermission(UmsMember member);
}
