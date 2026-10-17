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
     * 仅接受合法持久角色；兼容字段/身份资料不能授予权限。
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

    /** 仅供已经完成业务权限/归属检查的审核、建会与转让流程调用；角色和兼容投影同事务。 */
    void changeRole(Long memberId, String role, Long actorId);
}
