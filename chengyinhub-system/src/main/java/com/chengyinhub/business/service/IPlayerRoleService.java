package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.PlayerActivityRole;

/**
 * 俱乐部活动角色分配(升级2):进场自动发 5 个固定角色之一。
 */
public interface IPlayerRoleService {

    /**
     * 取当前角色;没有则按该活动已分配人数轮转分配一个并落库(幂等:唯一键收敛并发)。
     */
    PlayerActivityRole assignOrGet(Long memberId, Long activityId);

    /** 只读已有角色；未分配返回 null，不轮转、不创建。 */
    PlayerActivityRole readAssigned(Long memberId, Long activityId);

    /** 角色码 → 中文名(未知码回空串,不抛) */
    String roleName(String roleCode);
}
