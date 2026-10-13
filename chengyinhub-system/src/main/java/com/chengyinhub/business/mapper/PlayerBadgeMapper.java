package com.chengyinhub.business.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.PlayerBadge;

/**
 * 玩家已获徽章 Mapper
 *
 * @author cy
 * @date 2026-06-21
 */
public interface PlayerBadgeMapper
{
    List<PlayerBadge> selectListByMember(Long memberId);

    int countByMemberAndCode(@Param("memberId") Long memberId,
                             @Param("badgeCode") String badgeCode);

    PlayerBadge selectOne(@Param("memberId") Long memberId, @Param("badgeCode") String badgeCode, @Param("sourceId") Long sourceId);

    int insertPlayerBadge(PlayerBadge playerBadge);
}
