package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MemberGrowth;

import java.util.List;
import java.util.Date;
import org.apache.ibatis.annotations.Param;

/**
 * P3 玩家成长账户 Mapper。
 */
public interface MemberGrowthMapper {
    MemberGrowth selectByMemberId(Long memberId);

    List<MemberGrowth> selectAdminGrowthList(MemberGrowth growth);

    int insertMemberGrowth(MemberGrowth growth);

    int updateMemberGrowthReward(MemberGrowth growth);

    int insertEarnedExp(@Param("memberId") Long memberId, @Param("exp") int exp,
                        @Param("reason") String reason, @Param("earnedAt") Date earnedAt,
                        @Param("missionProgressId") Long missionProgressId);

    /** 拍板4 冲回:当前经验(加锁);没有成长账户返 null。 */
    Integer selectExpForUpdate(@org.apache.ibatis.annotations.Param("memberId") Long memberId);

    int deductExp(@org.apache.ibatis.annotations.Param("memberId") Long memberId,
                  @org.apache.ibatis.annotations.Param("exp") Integer exp);
}
