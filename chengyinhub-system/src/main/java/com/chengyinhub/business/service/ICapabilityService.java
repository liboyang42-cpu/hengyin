package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MemberCapability;

import java.util.List;

/** 兼容能力视图：权限只由持久 ums_member.role 决定；member_capability 只保留历史/展示投影。
 * grant/revoke 委托 RoleService 的唯一角色写入口，不独立授予能力。
 */
public interface ICapabilityService {

    /** 授予能力(幂等:已存在则激活,无则插入)。 */
    void grant(Long memberId, String capability, Long grantedBy);

    /** 撤销能力(幂等:置 status=0;无记录则 no-op)。 */
    void revoke(Long memberId, String capability);

    /** 是否拥有某有效能力(有效会员含 player 基础行为)。 */
    boolean hasCapability(Long memberId, String capability);

    /** 该会员所有有效能力(含隐式 player)。 */
    List<String> getCapabilities(Long memberId);

    /** 该会员有效能力记录(不含 player,用于富化展示)。 */
    List<MemberCapability> listValid(Long memberId);
}
