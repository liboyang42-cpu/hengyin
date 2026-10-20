package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.GrowthBadge;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 成长徽章定义 Mapper(含俱乐部自建勋章 club_id 归属)。
 */
public interface GrowthBadgeMapper {

    /** 新建徽章定义(俱乐部自建时 club_id 非空)。 */
    int insertGrowthBadge(GrowthBadge badge);

    /** 某俱乐部自建的徽章列表。 */
    List<GrowthBadge> selectByClub(@Param("clubId") Long clubId);

    /** 按编码取徽章定义(颁发校验归属用)。 */
    GrowthBadge selectByCode(@Param("badgeCode") String badgeCode);

    /** 平台级启用中的徽章定义(club_id 为空),按排序号升序;俱乐部专属徽章不在此列。 */
    List<GrowthBadge> selectAllActive();

    /** 启用中的俱乐部专属徽章定义里,编码在给定集合内的(个人墙只展示本人已获得的俱乐部徽章)。 */
    List<GrowthBadge> selectActiveClubByCodes(@Param("codes") Collection<String> codes);
}
