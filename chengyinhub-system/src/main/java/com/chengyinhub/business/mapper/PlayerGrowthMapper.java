package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.PlayerGrowth;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 玩家成长聚合 Mapper
 *
 * @author cy
 * @date 2026-06-21
 */
public interface PlayerGrowthMapper
{
    PlayerGrowth selectByMember(Long memberId);

    /** RUN-13 批量读等级:只回 member_id/level 两列,无行的成员不出现在结果里(兜底 1 在 service 层)。 */
    List<PlayerGrowth> selectLevelsByMemberIds(@Param("memberIds") List<Long> memberIds);

    int insertPlayerGrowth(PlayerGrowth playerGrowth);

    int updatePlayerGrowth(PlayerGrowth playerGrowth);
}
