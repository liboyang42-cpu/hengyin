package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.OrientScoreAppeal;
import com.chengyinhub.business.domain.OrientScoreRecovery;
import com.chengyinhub.business.domain.OrientScoreSyncReceipt;
import com.chengyinhub.business.domain.OrientScoreEntry;
import com.chengyinhub.business.domain.OrientScoreSettlement;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.TopicRouteSession;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** F1b 城市定向计分:流水只有 insert,没有 update/delete 语句 —— 只追加由 mapper 形状保证。 */
public interface OrientScoringMapper {
    List<Map<String, Object>> selectRankedBoard(@Param("activityId") Long activityId, @Param("topicId") Long topicId,
                                               @Param("memberId") Long memberId, @Param("routeRanked") boolean routeRanked,
                                               @Param("limit") int limit);

    OrientScoreRecovery selectRecoveryById(@Param("id") Long id);
    OrientScoreRecovery selectRecoveryForUpdate(@Param("sessionId") Long sessionId, @Param("kind") String kind);
    List<OrientScoreRecovery> selectRecoveriesBySession(@Param("sessionId") Long sessionId);
    List<OrientScoreRecovery> selectRecoveriesByStatus(@Param("status") Integer status, @Param("limit") int limit);
    int insertRecovery(OrientScoreRecovery recovery);
    int reviewRecovery(OrientScoreRecovery recovery);

    /** 行锁住会话,串行化同一局的入账/结算/冲正。 */
    TopicRouteSession selectSessionForUpdate(@Param("id") Long id);
    TopicRouteSession selectSessionById(@Param("id") Long id);

    OrientScoreSyncReceipt selectSyncReceipt(@Param("sessionId") Long sessionId,
                                             @Param("idempotencyKey") String idempotencyKey);
    OrientScoreSyncReceipt selectSyncReceiptForUpdate(@Param("sessionId") Long sessionId,
                                                      @Param("idempotencyKey") String idempotencyKey);
    int insertSyncReceipt(OrientScoreSyncReceipt receipt);
    int countPendingAlternatives(@Param("memberId") Long memberId, @Param("activityId") Long activityId,
                                 @Param("topicId") Long topicId, @Param("playInstanceId") String playInstanceId);
    List<Long> selectCompletedAlternativeNodes(@Param("memberId") Long memberId, @Param("activityId") Long activityId,
                                 @Param("topicId") Long topicId, @Param("playInstanceId") String playInstanceId);

    int countPendingProgress(@Param("memberId") Long memberId, @Param("activityId") Long activityId,
                             @Param("topicId") Long topicId, @Param("playInstanceId") String playInstanceId);

    int insertEntry(OrientScoreEntry entry);
    OrientScoreEntry selectEntryById(@Param("id") Long id);
    OrientScoreEntry selectEntryByIdem(@Param("sessionId") Long sessionId, @Param("idempotencyKey") String idempotencyKey);
    OrientScoreEntry selectEntryByReverses(@Param("reversesEntryId") Long reversesEntryId);
    List<OrientScoreEntry> selectEntriesBySession(@Param("sessionId") Long sessionId);

    /** 本局已通过的任务:status=1 且 review_status=1(拍照待审/驳回不算通过)。 */
    List<PlayerNodeProgress> selectClearedProgress(@Param("memberId") Long memberId, @Param("activityId") Long activityId,
                                                   @Param("topicId") Long topicId,
                                                   @Param("playInstanceId") String playInstanceId);

    int insertSettlement(OrientScoreSettlement settlement);
    OrientScoreSettlement selectSettlementBySession(@Param("sessionId") Long sessionId);

    int insertAppeal(OrientScoreAppeal appeal);
    OrientScoreAppeal selectAppealById(@Param("id") Long id);
    OrientScoreAppeal selectAppealByIdem(@Param("memberId") Long memberId, @Param("idempotencyKey") String idempotencyKey);
    List<OrientScoreAppeal> selectAppealsBySession(@Param("sessionId") Long sessionId);
    List<OrientScoreAppeal> selectAppealsByStatus(@Param("status") Integer status, @Param("limit") int limit);
    int countOpenAppeals(@Param("sessionId") Long sessionId, @Param("entryId") Long entryId, @Param("nodeId") Long nodeId);
    /** CAS:只从 0 待处理转出,并发重复处理只有一边拿到 1 行。 */
    int resolveAppeal(OrientScoreAppeal appeal);

    int insertDispute(OrientScoreAppeal dispute);
    OrientScoreAppeal selectDisputeById(@Param("id") Long id);
    OrientScoreAppeal selectDisputeByIdem(@Param("memberId") Long memberId, @Param("idempotencyKey") String idempotencyKey);
    List<OrientScoreAppeal> selectDisputesBySession(@Param("sessionId") Long sessionId);
    List<OrientScoreAppeal> selectDisputesByStatus(@Param("status") Integer status, @Param("limit") int limit);
    int countOpenDisputes(@Param("sessionId") Long sessionId, @Param("memberId") Long memberId);
    int resolveDispute(OrientScoreAppeal dispute);

    /**
     * 排行榜附加分:与 D-13 同口径取每人本场最新一局(max(id)),返回 memberId / totalCenti。
     * 只做展示附加,不参与排序(排序仍由 PlayerNodeProgressMapper + LeaderboardRankPolicy 决定)。
     */
    List<Map<String, Object>> selectBoardTotals(@Param("activityId") Long activityId, @Param("topicId") Long topicId,
                                               @Param("memberIds") List<Long> memberIds);
}
