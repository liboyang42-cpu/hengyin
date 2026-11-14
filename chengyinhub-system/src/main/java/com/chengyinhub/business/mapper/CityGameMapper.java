package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;
import java.util.Map;

/** CITY game storage. Rows are camelCase maps; every write is guarded by the caller's row lock. */
public interface CityGameMapper {
    Map<String, Object> selectBoard(@Param("boardId") String boardId);
    Map<String, Object> selectBoardForUpdate(@Param("boardId") String boardId);
    Map<String, Object> selectCurrentBoard(@Param("regionId") String regionId);
    Map<String, Object> selectBoardByRelease(@Param("releaseId") long releaseId);
    int advanceBoard(@Param("boardId") String boardId);
    int insertEvent(@Param("boardId") String boardId, @Param("sequence") long sequence, @Param("kind") String kind,
                    @Param("propertyId") String propertyId, @Param("teamId") String teamId, @Param("boardVersion") long boardVersion);
    List<Map<String, Object>> selectEvents(@Param("boardId") String boardId, @Param("after") long after, @Param("limit") int limit);

    List<Map<String, Object>> selectPoints(@Param("boardId") String boardId);
    List<Map<String, Object>> selectProperties(@Param("boardId") String boardId);
    Map<String, Object> selectPropertyForUpdate(@Param("boardId") String boardId, @Param("propertyId") String propertyId);
    Map<String, Object> selectPropertyByTrack(@Param("boardId") String boardId, @Param("trackIndex") int trackIndex);
    int countTrack(@Param("boardId") String boardId);
    int updateProperty(@Param("boardId") String boardId, @Param("propertyId") String propertyId, @Param("expectedVersion") long expectedVersion,
                       @Param("status") String status, @Param("holderTeamId") String holderTeamId, @Param("heldSince") Date heldSince,
                       @Param("protectedUntil") Date protectedUntil, @Param("challengeState") String challengeState);
    long countHeld(@Param("boardId") String boardId, @Param("teamId") String teamId);
    List<Map<String, Object>> selectServices(@Param("boardId") String boardId);
    Map<String, Object> selectService(@Param("boardId") String boardId, @Param("serviceId") String serviceId);

    int insertTeam(Map<String, Object> team);
    Map<String, Object> selectTeam(@Param("teamId") String teamId);
    Map<String, Object> selectTeamForUpdate(@Param("teamId") String teamId);
    Map<String, Object> selectTeamByCode(@Param("inviteCode") String inviteCode);
    Map<String, Object> selectTeamByInvite(@Param("inviteCode") String inviteCode);
    int updateTeamState(@Param("teamId") String teamId, @Param("state") String state, @Param("sessionId") String sessionId,
                        @Param("sessionState") String sessionState);
    int moveTeam(@Param("teamId") String teamId, @Param("position") int position, @Param("landedPropertyId") String landedPropertyId);
    int addTeamScore(@Param("teamId") String teamId, @Param("score") long score);
    List<Map<String, Object>> selectTeamsByBoard(@Param("boardId") String boardId);

    int insertParticipation(Map<String, Object> participation);
    Map<String, Object> selectParticipation(@Param("boardId") String boardId, @Param("memberId") long memberId);
    Map<String, Object> selectParticipationForUpdate(@Param("boardId") String boardId, @Param("memberId") long memberId);
    int updateParticipation(@Param("participationId") String participationId, @Param("present") int present, @Param("confirmed") int confirmed);
    int deleteParticipation(@Param("participationId") String participationId);
    List<Map<String, Object>> selectTeamMembers(@Param("teamId") String teamId);

    int insertRound(@Param("boardId") String boardId, @Param("teamId") String teamId, @Param("roundSeq") long roundSeq, @Param("actions") int actions);
    Map<String, Object> selectOpenRound(@Param("boardId") String boardId, @Param("teamId") String teamId);
    Map<String, Object> selectLastRound(@Param("boardId") String boardId, @Param("teamId") String teamId);
    int spendActions(@Param("boardId") String boardId, @Param("teamId") String teamId, @Param("roundSeq") long roundSeq, @Param("units") int units);
    int settleRound(@Param("boardId") String boardId, @Param("teamId") String teamId, @Param("roundSeq") long roundSeq, @Param("score") long score);
    int insertRoll(@Param("boardId") String boardId, @Param("teamId") String teamId, @Param("roundSeq") long roundSeq, @Param("memberId") long memberId,
                   @Param("requestId") String requestId, @Param("purpose") String purpose, @Param("diceId") String diceId, @Param("face") int face);

    int insertTaskAttempt(@Param("boardId") String boardId, @Param("propertyId") String propertyId, @Param("teamId") String teamId,
                          @Param("memberId") long memberId, @Param("requestId") String requestId);
    List<String> selectTaskAttemptProperties(@Param("boardId") String boardId, @Param("teamId") String teamId);

    int insertQuote(Map<String, Object> quote);
    Map<String, Object> selectQuoteForUpdate(@Param("quoteId") String quoteId);
    int consumeQuote(@Param("quoteId") String quoteId, @Param("requestId") String requestId);

    Map<String, Object> selectRequest(@Param("memberId") long memberId, @Param("domain") String domain, @Param("requestId") String requestId);
    int insertRequest(Map<String, Object> receipt);

    Long selectMemberPoints(@Param("memberId") long memberId);
    int deductMemberPoints(@Param("memberId") long memberId, @Param("points") long points);
    int insertPointsDetail(@Param("memberId") long memberId, @Param("before") long before, @Param("points") long points,
                           @Param("reason") String reason, @Param("businessKey") String businessKey);

    int recordOuting(@Param("outingId") String outingId, @Param("boardId") String boardId, @Param("memberId") long memberId,
                     @Param("day") Date day, @Param("t0") Date t0, @Param("pointsSpent") long pointsSpent, @Param("zeroPointBlocks") int zeroPointBlocks);
    List<Map<String, Object>> selectOutings(@Param("boardId") String boardId, @Param("asOf") Date asOf, @Param("limit") int limit);
    Map<String, Object> selectBoardFacts(@Param("boardId") String boardId, @Param("asOf") Date asOf);

    // ---- gaps A pass 2: lifecycle, ordered-map point log, budget rolls ----
    int freezeBoard(@Param("boardId") String boardId);
    int countTeamsStillPlaying(@Param("boardId") String boardId);
    int bumpPointSlot(@Param("boardId") String boardId, @Param("pointId") String pointId);
    int insertPointEvent(@Param("boardId") String boardId, @Param("pointId") String pointId, @Param("component") String component,
                         @Param("holderTeamId") String holderTeamId);
    int insertBudgetRoll(@Param("boardId") String boardId, @Param("teamId") String teamId, @Param("roundSeq") long roundSeq, @Param("memberId") long memberId,
                         @Param("requestId") String requestId, @Param("diceId") String diceId, @Param("face") int face, @Param("faceIndex") int faceIndex,
                         @Param("resultJson") String resultJson);
    List<Map<String, Object>> selectRoundRolls(@Param("boardId") String boardId, @Param("teamId") String teamId, @Param("roundSeq") long roundSeq);
    Map<String, Object> selectRoundForUpdate(@Param("boardId") String boardId, @Param("teamId") String teamId, @Param("roundSeq") long roundSeq);
}
