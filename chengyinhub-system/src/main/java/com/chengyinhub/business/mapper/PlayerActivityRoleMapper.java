package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.PlayerActivityRole;
import org.apache.ibatis.annotations.Param;

/**
 * 俱乐部活动角色 Mapper(升级2)
 */
public interface PlayerActivityRoleMapper {

    PlayerActivityRole selectByActivityAndMember(@Param("activityId") Long activityId,
                                                 @Param("memberId") Long memberId);

    int countByActivity(@Param("activityId") Long activityId);

    int insertPlayerActivityRole(PlayerActivityRole role);
}
