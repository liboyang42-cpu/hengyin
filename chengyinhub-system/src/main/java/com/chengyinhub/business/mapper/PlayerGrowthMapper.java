package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.PlayerGrowth;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Date;

/**
 * 玩家成长聚合 Mapper
 *
 * @author cy
 * @date 2026-06-21
 */
public interface PlayerGrowthMapper
{
    PlayerGrowth selectByMember(Long memberId);

    /** 写事务专用:依赖现有 uk_member,不覆盖已有累计或时间字段。 */
    int ensureGrowthRow(@Param("memberId") Long memberId, @Param("now") Date now);

    /** 写事务专用当前读;必须先 ensureGrowthRow,锁持有到外层事务结束。 */
    PlayerGrowth selectByMemberForUpdate(Long memberId);

    /** RUN-13 批量读等级:只回 member_id/level 两列,无行的成员不出现在结果里(兜底 1 在 service 层)。 */
    List<PlayerGrowth> selectLevelsByMemberIds(@Param("memberIds") List<Long> memberIds);

    int insertPlayerGrowth(PlayerGrowth playerGrowth);

    int updatePlayerGrowth(PlayerGrowth playerGrowth);

    /** P-22「累计打卡 N 处」之一:本人票上生效核销过的店(按章去重,一章一家店)。只读。 */
    Integer countRedeemedShops(@Param("memberId") Long memberId);

    /** P-22「累计打卡 N 处」之二:本人完成的城市站点(非商家节点、非自由探索主题,按节点去重)。只读。 */
    Integer countCompletedCityStations(@Param("memberId") Long memberId);
}
