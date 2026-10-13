package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MemberGrowth;

import java.util.List;

/**
 * P3 玩家成长账户 Mapper。
 */
public interface MemberGrowthMapper {
    MemberGrowth selectByMemberId(Long memberId);

    List<MemberGrowth> selectAdminGrowthList(MemberGrowth growth);

    int insertMemberGrowth(MemberGrowth growth);

    int updateMemberGrowthReward(MemberGrowth growth);

    /** 拍板4 冲回:当前经验(加锁);没有成长账户返 null。 */
    Integer selectExpForUpdate(@org.apache.ibatis.annotations.Param("memberId") Long memberId);

    int deductExp(@org.apache.ibatis.annotations.Param("memberId") Long memberId,
                  @org.apache.ibatis.annotations.Param("exp") Integer exp);
}
