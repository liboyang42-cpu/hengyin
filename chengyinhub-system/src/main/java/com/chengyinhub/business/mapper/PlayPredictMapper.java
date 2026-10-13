package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.PlayPredictBet;
import com.chengyinhub.business.domain.PlayPredictResult;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/** 竞猜下注与结算。 */
public interface PlayPredictMapper {

    /**
     * 下注。撞 uk_round_member 会抛 DuplicateKeyException —— 调用方据此判"已经押过了",
     * 不要先查后插(那中间有并发窗口)。
     */
    int insertBet(PlayPredictBet bet);

    PlayPredictBet selectBet(@Param("nodeId") Long nodeId,
                            @Param("playDay") Date playDay,
                            @Param("memberId") Long memberId);

    List<PlayPredictBet> selectBetsForRound(@Param("nodeId") Long nodeId,
                                            @Param("playDay") Date playDay);

    /**
     * 把一注标记成已结算。带 rewardStatus=0 的条件 —— 命中 0 行即为重复结算,
     * 奖不会重复发。
     */
    int markBetSettled(@Param("id") Long id,
                       @Param("rewardStatus") Integer rewardStatus,
                       @Param("updateTime") Date updateTime);

    int insertResult(PlayPredictResult result);

    PlayPredictResult selectResult(@Param("nodeId") Long nodeId, @Param("playDay") Date playDay);

    /**
     * 锁定读:取轮次行并加行锁({@code SELECT … FOR UPDATE}),必须在事务内调用。
     *
     * <p>下注前判 {@code settle_status} 必须用它,而不是先快照读再写注 —— 快照读与
     * 写注之间商家结算的 UPDATE 可能落地,那一注就没人收尾。锁定读拿到的是最新
     * 已提交状态,同时让结算的 UPDATE 等本事务提交,两个方向都堵上。
     */
    PlayPredictResult selectResultForUpdate(@Param("nodeId") Long nodeId, @Param("playDay") Date playDay);

    /**
     * 结算一轮。带 settle_status=0 的条件,一轮只能从待结算走到已结算/作废一次。
     */
    int settleRound(@Param("nodeId") Long nodeId,
                    @Param("playDay") Date playDay,
                    @Param("settledOption") String settledOption,
                    @Param("settleStatus") Integer settleStatus,
                    @Param("settledBy") Long settledBy,
                    @Param("settledAt") Date settledAt);

    /** 商家待办:这些节点里还没结算、且已过截止的轮次。 */
    List<PlayPredictResult> selectPendingRounds(@Param("nodeIds") List<Long> nodeIds);

    /**
     * 超时兜底用:play_day 早于某个日期、且仍未结算的轮次。
     *
     * <p>按日期而不是按 nodeIds —— 定时任务没有节点清单可传,它要扫的正是
     * 「没人管的那些轮次」。
     */
    List<PlayPredictResult> selectStaleRounds(@Param("beforeDay") Date beforeDay,
                                              @Param("limit") Integer limit);
}
