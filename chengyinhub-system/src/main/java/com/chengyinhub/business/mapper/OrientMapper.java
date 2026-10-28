package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.OrientNodeGeo;
import com.chengyinhub.business.domain.OrientSafetyRule;
import com.chengyinhub.business.domain.OrientTaskAlternative;
import com.chengyinhub.business.domain.OrientTaskAttempt;
import com.chengyinhub.business.domain.OrientTaskCompletion;
import com.chengyinhub.business.domain.PlayTeam;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;
import java.util.Map;

/** 城市定向(Doc2 F1d)的坐标 / 替代任务 / 队伍看板 / 安全规则读写。 */
public interface OrientMapper {

    // ---- 坐标 ----
    OrientNodeGeo selectNodeGeo(Long nodeId);

    int insertNodeGeo(OrientNodeGeo geo);

    /** 只在指纹变化时由服务层调用;按 node_id 覆盖。 */
    int updateNodeGeo(OrientNodeGeo geo);

    // ---- 替代任务配置 ----
    OrientTaskAlternative selectAlternative(@Param("taskId") Long taskId, @Param("type") String type);

    List<OrientTaskAlternative> selectAlternativesByTask(Long taskId);

    int insertAlternative(OrientTaskAlternative alternative);

    int updateAlternative(OrientTaskAlternative alternative);

    // ---- 提交 ----
    OrientTaskAttempt selectAttemptByIdempotencyKey(@Param("memberId") Long memberId,
                                                   @Param("idempotencyKey") String idempotencyKey);

    OrientTaskAttempt selectAttemptById(@Param("id") Long id);

    OrientTaskAttempt selectAttemptByIdForUpdate(Long id);

    int insertAttempt(OrientTaskAttempt attempt);

    int countPendingPhoto(@Param("memberId") Long memberId, @Param("activityId") Long activityId,
                          @Param("topicId") Long topicId, @Param("playInstanceId") String playInstanceId,
                          @Param("taskId") Long taskId);

    /** CAS:仅当当前状态为 fromStatus 时改成 toStatus。 */
    int updateAttemptStatus(@Param("id") Long id, @Param("fromStatus") int fromStatus,
                            @Param("toStatus") int toStatus, @Param("reviewBy") Long reviewBy,
                            @Param("now") Date now);

    // ---- 完成事实 ----
    OrientTaskCompletion selectCompletion(@Param("memberId") Long memberId, @Param("activityId") Long activityId,
                                          @Param("topicId") Long topicId,
                                          @Param("playInstanceId") String playInstanceId,
                                          @Param("taskId") Long taskId);

    /** 撞 uk_orient_completion 抛 DuplicateKeyException = 其他路径已完成。 */
    int insertCompletion(OrientTaskCompletion completion);

    /** 既有主路径(player_node_progress)是否已完成该节点。只读。 */
    int countLegacyNodeDone(@Param("memberId") Long memberId, @Param("activityId") Long activityId,
                            @Param("topicId") Long topicId, @Param("playInstanceId") String playInstanceId,
                            @Param("nodeId") Long nodeId);

    // ---- 队伍看板 ----
    /** 公开申请制且仍可加入的队伍;不含邀请码。 */
    List<Map<String, Object>> selectLobbyTeams(@Param("ownerType") Integer ownerType,
                                               @Param("ownerId") Long ownerId, @Param("limit") int limit);

    /** 参与排名的队伍:未解散。 */
    List<PlayTeam> selectBoardTeams(@Param("ownerType") Integer ownerType, @Param("ownerId") Long ownerId,
                                    @Param("limit") int limit);

    /** 多支队伍的在岗成员(status=1);退出/移出的成员不在其中。 */
    List<Map<String, Object>> selectActiveMembersOfTeams(@Param("teamIds") List<Long> teamIds);

    /** 成员 × 任务 的最早完成时间,两条来源取并集:orient_task_completion 与 player_node_progress(status=1)。 */
    List<Map<String, Object>> selectCompletionFacts(@Param("activityId") Long activityId,
                                                    @Param("topicId") Long topicId,
                                                    @Param("memberIds") List<Long> memberIds);

    // ---- 安全规则 ----
    OrientSafetyRule selectSafetyRule(Long topicId);

    int insertSafetyRule(OrientSafetyRule rule);

    int updateSafetyRule(OrientSafetyRule rule);
}
