package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.GrowthBadge;
import org.apache.ibatis.annotations.Param;

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

    /** 所有启用中的徽章定义,按排序号升序(引擎侧规则装载用)。 */
    List<GrowthBadge> selectAllActive();
}
