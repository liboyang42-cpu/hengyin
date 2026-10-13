package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.PlayerTopicTag;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface PlayerTopicTagMapper {
    int upsertPending(PlayerTopicTag tag);
    PlayerTopicTag selectOwnedById(@Param("id") Long id, @Param("memberId") Long memberId);
    int updatePendingValueOwned(@Param("id") Long id, @Param("memberId") Long memberId,
                                @Param("tagValue") String tagValue);
    int confirmOwned(@Param("id") Long id, @Param("memberId") Long memberId);
    int revokeOwned(@Param("id") Long id, @Param("memberId") Long memberId);
    List<PlayerTopicTag> selectPendingByNode(@Param("memberId") Long memberId,
                                             @Param("topicId") Long topicId,
                                             @Param("sourceNodeId") Long sourceNodeId);
    List<PlayerTopicTag> selectConfirmedByCodes(@Param("memberId") Long memberId,
                                                @Param("topicId") Long topicId,
                                                @Param("codes") List<String> codes);
    List<PlayerTopicTag> selectConfirmedByTopic(@Param("memberId") Long memberId,
                                                @Param("topicId") Long topicId);
}
