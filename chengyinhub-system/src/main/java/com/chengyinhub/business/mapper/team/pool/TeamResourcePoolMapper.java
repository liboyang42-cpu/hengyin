package com.chengyinhub.business.mapper.team.pool;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

/** 队伍共享资源池：池行（冻结额度）、按节点消耗、原回执与完成事实只读。 */
public interface TeamResourcePoolMapper
{
    Map<String, Object> selectTeam(@Param("teamId") long teamId);
    int countActiveMember(@Param("teamId") long teamId, @Param("memberId") long memberId);
    Long selectActivityTopic(@Param("activityId") long activityId);
    String selectPublishedTheme(@Param("topicId") long topicId);
    int countListedNode(@Param("topicId") long topicId, @Param("nodeId") long nodeId);

    Map<String, Object> selectPool(@Param("teamId") long teamId);
    Map<String, Object> lockPool(@Param("teamId") long teamId);
    int insertPool(Map<String, Object> row);
    int consume(@Param("teamId") long teamId, @Param("kind") String kind);
    int startTimer(@Param("teamId") long teamId);

    Map<String, Object> selectUse(@Param("teamId") long teamId, @Param("kind") String kind, @Param("nodeId") long nodeId);
    int insertUse(@Param("teamId") long teamId, @Param("kind") String kind, @Param("nodeId") long nodeId,
            @Param("memberId") long memberId, @Param("requestId") String requestId);
    List<Long> selectSolvedNodes(@Param("teamId") long teamId, @Param("topicId") long topicId,
            @Param("activityId") long activityId);

    int reserveReceipt(@Param("memberId") long memberId, @Param("requestId") String requestId,
            @Param("teamId") long teamId, @Param("fingerprint") String fingerprint);
    Map<String, Object> lockReceipt(@Param("memberId") long memberId, @Param("requestId") String requestId);
    Map<String, Object> selectReceipt(@Param("memberId") long memberId, @Param("requestId") String requestId);
    int finishReceipt(@Param("memberId") long memberId, @Param("requestId") String requestId, @Param("response") String response);

    String selectTeamRunSnapshot(@Param("teamId") long teamId, @Param("topicId") long topicId, @Param("activityId") long activityId,
            @Param("preferMemberId") long preferMemberId);
}
