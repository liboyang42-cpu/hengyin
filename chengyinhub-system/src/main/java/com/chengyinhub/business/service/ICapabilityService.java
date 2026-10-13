package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MemberCapability;

import java.util.List;

/**
 * 会员能力服务(P1 能力模型)。
 *
 * 能力真源 = member_capability 表(多能力),替代单值 ums_member.role 作能力判定。
 * player 能力隐式默认(不落表),hasCapability(memberId,"player") 永远 true。
 * role 列保留作"默认视角指针"(双写过渡),不在此判定能力。
 */
public interface ICapabilityService {

    /** 授予能力(幂等:已存在则激活,无则插入)。 */
    void grant(Long memberId, String capability, Long grantedBy);

    /** 撤销能力(幂等:置 status=0;无记录则 no-op)。 */
    void revoke(Long memberId, String capability);

    /** 是否拥有某有效能力(player 隐式 true)。 */
    boolean hasCapability(Long memberId, String capability);

    /** 该会员所有有效能力(含隐式 player)。 */
    List<String> getCapabilities(Long memberId);

    /** 该会员有效能力记录(不含 player,用于富化展示)。 */
    List<MemberCapability> listValid(Long memberId);
}
