package com.chengyinhub.business.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

/**
 * P7 daily-loop:每日任务进度只从真实事实表数出来(漫游结算、迷雾格、节点完成、到店),不另记计数器。只读。
 */
public interface DailyLoopMapper
{
    /** 今天结算的、达到门槛的漫游次数(里程或新格子任一达标;门槛为 0 的那一项不参与)。 */
    int countQualifyingWalksToday(@Param("memberId") Long memberId,
                                  @Param("minMeters") int minMeters,
                                  @Param("minCells") int minCells);

    /** 今天新点亮的迷雾格数。 */
    int countRevealedCellsToday(@Param("memberId") Long memberId);

    /** 今天完成的游戏节点数(含盲盒派发的节点)。 */
    int countNodesPlayedToday(@Param("memberId") Long memberId);

    /** 今天漫游中到过的店(按来源去重)。 */
    int countShopsVisitedToday(@Param("memberId") Long memberId);

    /** 今天已记完成的每日任务编码(自动记录与旧自报通道共用 biz_type='' / biz_id=0 的幂等键)。 */
    List<String> selectMissionCodesCompletedToday(@Param("memberId") Long memberId);
}
