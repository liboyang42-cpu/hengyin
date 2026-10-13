package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CircleExploreAnswer;
import com.chengyinhub.business.domain.CircleExploreRecord;
import com.chengyinhub.business.domain.CircleExploreSession;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 圈层玩家会话、自助记录与轻互动事实。 */
public interface CircleExploreMapper {
    int insertSession(CircleExploreSession session);
    int insertMember(@Param("sessionId") Long sessionId, @Param("memberId") Long memberId);
    CircleExploreSession selectSessionById(@Param("id") Long id);
    CircleExploreSession selectActiveSessionByInvite(@Param("inviteCode") String inviteCode);
    int countMember(@Param("sessionId") Long sessionId, @Param("memberId") Long memberId);
    int countSessionMembers(@Param("sessionId") Long sessionId);
    int insertRecord(CircleExploreRecord record);
    int upsertAnswer(CircleExploreAnswer answer);
    int countDistinctRecordedMerchants(@Param("sessionId") Long sessionId);
    int countAnswersByStage(@Param("sessionId") Long sessionId, @Param("stage") String stage);
    List<CircleExploreRecord> selectRecords(@Param("sessionId") Long sessionId);
    List<CircleExploreAnswer> selectAnswers(@Param("sessionId") Long sessionId);
    int completeSession(@Param("sessionId") Long sessionId);
}
