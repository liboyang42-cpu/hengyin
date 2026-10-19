package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.GameBroadcast;
import com.chengyinhub.business.domain.GameBroadcastDelivery;
import com.chengyinhub.business.domain.GameCommandReceipt;
import com.chengyinhub.business.domain.GamePlayerNodeOutcome;
import com.chengyinhub.business.domain.GameSession;
import com.chengyinhub.business.domain.GameStationRun;
import com.chengyinhub.business.domain.GameStoryState;
import com.chengyinhub.business.domain.GameTaskSubmission;
import com.chengyinhub.business.domain.GameTeamRoleAssignment;
import com.chengyinhub.business.domain.dto.GameMerchantEntryView;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;
import java.util.Map;

public interface GameSessionRuntimeMapper {
    GameSession selectSessionByActivity(Long activityId);
    List<Long> selectActiveTeamIds(Long activityId);
    GameSession selectSessionByActivityForUpdate(Long activityId);
    int readyRouteCheckpoints(@Param("sessionId") Long sessionId, @Param("startAt") Date startAt,
                              @Param("endAt") Date endAt, @Param("now") Date now);
    List<Map<String, Object>> selectClubRouteTeamProgressProjection(Long sessionId);
    int insertSession(GameSession session);
    int updateSessionCas(@Param("id") Long id, @Param("expectedRevision") Integer expectedRevision,
                         @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus,
                         @Param("currentChapterId") Long currentChapterId,
                         @Param("actorMemberId") Long actorMemberId, @Param("now") Date now);
    int updateLeaderboardVisibilityCas(@Param("id") Long id,
                                       @Param("expectedRevision") Integer expectedRevision,
                                       @Param("status") String status, @Param("visible") boolean visible,
                                       @Param("actorMemberId") Long actorMemberId, @Param("now") Date now);

    int insertStationsFromTopic(@Param("sessionId") Long sessionId, @Param("activityId") Long activityId,
                                @Param("topicId") Long topicId, @Param("now") Date now);
    int updateStationCode(@Param("sessionId") Long sessionId, @Param("nodeId") Long nodeId,
                          @Param("stationCode") String stationCode);
    List<GameStationRun> selectStations(Long sessionId);
    List<GameStationRun> selectMerchantStations(@Param("sessionId") Long sessionId,
                                                @Param("merchantMemberId") Long merchantMemberId);
    int countMerchantOperableStation(@Param("merchantMemberId") Long merchantMemberId,
                                     @Param("nodeId") Long nodeId);
    List<Map<String, Object>> selectMerchantStationRecaps(@Param("sessionId") Long sessionId,
                                                           @Param("merchantMemberId") Long merchantMemberId);
    List<GameMerchantEntryView> selectMerchantEntries(@Param("merchantMemberId") Long merchantMemberId);
    GameStationRun selectStation(@Param("sessionId") Long sessionId, @Param("nodeId") Long nodeId);
    int countMissingRouteReception(@Param("sessionId") Long sessionId, @Param("nodeId") Long nodeId);
    int updateStationStatusCas(@Param("station") GameStationRun station,
                               @Param("merchantMemberId") Long merchantMemberId,
                               @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus,
                               @Param("expectedRevision") Integer expectedRevision, @Param("now") Date now);
    int activateReadyStations(@Param("sessionId") Long sessionId, @Param("now") Date now);
    int closeStations(@Param("sessionId") Long sessionId, @Param("now") Date now);
    int countUnreadyStations(Long sessionId);
    int countActiveTeams(Long activityId);
    int countTeamMembersWithoutRole(@Param("sessionId") Long sessionId, @Param("activityId") Long activityId);

    Long selectActiveTeamId(@Param("activityId") Long activityId, @Param("memberId") Long memberId);
    int isActiveTeamMember(@Param("activityId") Long activityId, @Param("teamId") Long teamId,
                           @Param("memberId") Long memberId);
    int isActiveTeam(@Param("activityId") Long activityId, @Param("teamId") Long teamId);
    int countRole(@Param("sessionId") Long sessionId, @Param("roleCode") String roleCode);
    GameTeamRoleAssignment selectActiveRoleForUpdate(@Param("sessionId") Long sessionId,
                                                     @Param("memberId") Long memberId);
    int supersedeActiveRole(@Param("sessionId") Long sessionId, @Param("memberId") Long memberId,
                            @Param("supersededBy") Long supersededBy, @Param("now") Date now);
    int insertRole(GameTeamRoleAssignment assignment);
    int confirmRole(@Param("sessionId") Long sessionId, @Param("memberId") Long memberId,
                    @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus,
                    @Param("now") Date now);
    GameTeamRoleAssignment selectRole(@Param("sessionId") Long sessionId, @Param("memberId") Long memberId);
    List<GameTeamRoleAssignment> selectRoles(Long sessionId);
    List<Map<String, Object>> selectClubMemberProjection(Long sessionId);
    List<Map<String, Object>> selectClubTeamProgressProjection(Long sessionId);
    Map<String, Object> selectClubRecapAggregate(@Param("sessionId") Long sessionId,
                                                 @Param("activityId") Long activityId);
    List<Map<String, Object>> selectClubStationRecaps(Long sessionId);
    List<Map<String, Object>> selectClubRoleCollaboration(@Param("sessionId") Long sessionId,
                                                          @Param("activityId") Long activityId);

    List<GameStoryState> selectStoryStates(Long sessionId);

    GameStoryState selectStoryState(@Param("sessionId") Long sessionId, @Param("ownerType") String ownerType,
                                    @Param("ownerId") Long ownerId);
    GameStoryState selectStoryStateForUpdate(@Param("sessionId") Long sessionId, @Param("ownerType") String ownerType,
                                             @Param("ownerId") Long ownerId);
    int insertStoryState(GameStoryState state);
    int updateStoryStateCas(GameStoryState state);

    String selectFinishedRecapJson(@Param("sessionId") Long sessionId);

    GameCommandReceipt selectReceipt(@Param("activityId") Long activityId,
                                     @Param("actorMemberId") Long actorMemberId,
                                     @Param("requestId") String requestId);
    GameCommandReceipt selectReceiptForUpdate(@Param("activityId") Long activityId,
                                              @Param("actorMemberId") Long actorMemberId,
                                              @Param("requestId") String requestId);
    int insertReceiptClaim(GameCommandReceipt receipt);
    int completeReceipt(GameCommandReceipt receipt);

    int insertSubmission(GameTaskSubmission submission);
    GameTaskSubmission selectSubmission(Long id);
    GameTaskSubmission selectActiveSubmission(@Param("sessionId") Long sessionId,
                                              @Param("memberId") Long memberId,
                                              @Param("nodeId") Long nodeId,
                                              @Param("taskCode") String taskCode);
    GameTaskSubmission selectActiveSubmissionForUpdate(@Param("sessionId") Long sessionId,
                                                       @Param("memberId") Long memberId,
                                                       @Param("nodeId") Long nodeId,
                                                       @Param("taskCode") String taskCode);
    int decideSubmissionCas(GameTaskSubmission submission);

    /**
     * 俱乐部现场事件面要用的提交清单(Figma D8「任务驳回」那一类事件)。
     * ⚠️ 刻意**不查 evidence_json**:俱乐部看不到玩家提交的照片与答案内容,
     *    它能做的只是「驳回重交」,不是替商家判内容合不合格。
     */
    List<Map<String, Object>> selectClubSubmissionProjection(@Param("sessionId") Long sessionId);

    int insertPlayerNodeOutcome(GamePlayerNodeOutcome outcome);
    GamePlayerNodeOutcome selectPlayerNodeOutcome(@Param("sessionId") Long sessionId,
                                                  @Param("memberId") Long memberId,
                                                  @Param("nodeId") Long nodeId);
    List<GamePlayerNodeOutcome> selectPlayerNodeOutcomes(@Param("sessionId") Long sessionId,
                                                         @Param("memberId") Long memberId);
    List<Map<String, Object>> selectPlayerHintLevels(@Param("sessionId") Long sessionId,
                                                     @Param("memberId") Long memberId);

    int insertBroadcast(GameBroadcast broadcast);
    int insertBroadcastDeliveries(@Param("broadcastId") Long broadcastId,
                                  @Param("sessionId") Long sessionId,
                                  @Param("activityId") Long activityId,
                                  @Param("targetType") String targetType,
                                  @Param("targetId") Long targetId,
                                  @Param("roleCode") String roleCode,
                                  @Param("now") Date now);
    List<GameBroadcastDelivery> selectPendingDeliveries(Long broadcastId);
    int markDelivery(@Param("id") Long id, @Param("status") String status,
                     @Param("messageId") Long messageId, @Param("errorCode") String errorCode,
                     @Param("now") Date now);
    int countBroadcastDeliveries(@Param("broadcastId") Long broadcastId, @Param("status") String status);
    int updateBroadcastStatus(@Param("id") Long id, @Param("fromStatus") String fromStatus,
                              @Param("toStatus") String toStatus, @Param("now") Date now);
    List<GameBroadcast> selectBroadcasts(Long sessionId);
    List<Map<String, Object>> selectSessionLeaderboard(Long sessionId);
    List<Map<String, Object>> selectTeamActionProjection(@Param("sessionId") Long sessionId,
                                                         @Param("teamId") Long teamId);
    List<Map<String, Object>> selectMySubmissionProjection(@Param("sessionId") Long sessionId,
                                                           @Param("memberId") Long memberId);
}
