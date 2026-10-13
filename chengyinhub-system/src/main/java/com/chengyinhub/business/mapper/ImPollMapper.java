package com.chengyinhub.business.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.ImPoll;
import com.chengyinhub.business.domain.ImPollOption;

public interface ImPollMapper {
    int insertPoll(ImPoll poll);
    int insertOption(ImPollOption option);
    ImPoll selectById(Long id);
    ImPoll selectByBusinessKey(@Param("conversationId") Long conversationId,
                               @Param("creatorMemberId") Long creatorMemberId,
                               @Param("clientPollKey") String clientPollKey);
    List<ImPollOption> selectOptions(Long pollId);
    List<ImPollOption> selectResults(Long pollId);
    int attachMessage(@Param("pollId") Long pollId, @Param("messageId") Long messageId);
    int insertAudienceFromConversation(@Param("pollId") Long pollId,
                                       @Param("conversationId") Long conversationId);
    int countAudience(@Param("pollId") Long pollId, @Param("memberId") Long memberId);
    Long selectMemberVoteOption(@Param("pollId") Long pollId, @Param("memberId") Long memberId);
    int insertVoteIfOpen(@Param("pollId") Long pollId, @Param("optionId") Long optionId,
                         @Param("memberId") Long memberId);
    int closePollCas(@Param("pollId") Long pollId, @Param("memberId") Long memberId,
                     @Param("expectedVersion") Long expectedVersion);
    int expirePollCas(@Param("pollId") Long pollId, @Param("expectedVersion") Long expectedVersion);
}
