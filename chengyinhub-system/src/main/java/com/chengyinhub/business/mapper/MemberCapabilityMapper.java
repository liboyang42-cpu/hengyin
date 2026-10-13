package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MemberCapability;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 会员能力 Mapper(member_capability)。
 */
public interface MemberCapabilityMapper {

    /** 该会员有效的(可能多个)能力。 */
    List<MemberCapability> selectByMember(@Param("memberId") Long memberId);

    /** 该会员某能力记录(含已撤销,用于 grant 判存在)。 */
    MemberCapability selectByMemberAndCapability(@Param("memberId") Long memberId,
                                                 @Param("capability") String capability);

    /** 插入(首次授予)。 */
    int insertMemberCapability(MemberCapability cap);

    /** 更新状态(grant 激活/revoke 撤销,复用同一行靠 uk_member_capability)。 */
    int updateStatus(@Param("memberId") Long memberId,
                     @Param("capability") String capability,
                     @Param("status") Integer status,
                     @Param("grantedBy") Long grantedBy);
}
