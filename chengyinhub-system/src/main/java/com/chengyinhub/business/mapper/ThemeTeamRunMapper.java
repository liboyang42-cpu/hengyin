package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ThemeTeamRun;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** Call locking reads only after the team row and its live membership rows are locked. */
public interface ThemeTeamRunMapper {
    ThemeTeamRun selectByRequestForUpdate(@Param("teamId") Long teamId, @Param("activityId") Long activityId, @Param("topicId") Long topicId, @Param("requestKey") String requestKey);
    ThemeTeamRun selectById(@Param("id") Long id);
    ThemeTeamRun selectByIdForUpdate(@Param("id") Long id);
    ThemeTeamRun selectCurrentByTeam(@Param("teamId") Long teamId, @Param("activityId") Long activityId,
                                     @Param("topicId") Long topicId);
    ThemeTeamRun selectCurrentByTeamForUpdate(@Param("teamId") Long teamId, @Param("activityId") Long activityId,
                                              @Param("topicId") Long topicId);
    ThemeTeamRun selectBySourceForUpdate(@Param("teamId") Long teamId, @Param("activityId") Long activityId,
                                         @Param("topicId") Long topicId, @Param("sourceSessionId") Long sourceSessionId);
    ThemeTeamRun selectByPreviousRunForUpdate(@Param("teamId") Long teamId, @Param("activityId") Long activityId,
                                              @Param("topicId") Long topicId, @Param("previousRunId") Long previousRunId);
    /** Locate this member's audited personal session, not permission to join the live game. */
    ThemeTeamRun selectBySessionId(@Param("sessionId") Long sessionId, @Param("memberId") Long memberId);
    ThemeTeamRun selectByInstance(@Param("teamId") Long teamId, @Param("activityId") Long activityId,
                                  @Param("topicId") Long topicId, @Param("playInstanceId") String playInstanceId);
    ThemeTeamRun selectByCurrentMember(@Param("memberId") Long memberId, @Param("activityId") Long activityId, @Param("topicId") Long topicId);
    List<ThemeTeamRun.Member> selectMembersForUpdate(@Param("runId") Long runId);
    List<ThemeTeamRun.Member> selectMembers(@Param("runId") Long runId);
    int insertRun(ThemeTeamRun run);
    /** Assign team-replay:<id> once, in the same transaction as all member sessions and audit links. */
    int assignPlayInstance(@Param("id") Long id, @Param("playInstanceId") String playInstanceId);
    int insertMember(ThemeTeamRun.Member member);
}
