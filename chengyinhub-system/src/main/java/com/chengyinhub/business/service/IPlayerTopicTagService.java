package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.PlayerTopicTag;

import java.util.List;
import java.util.Set;

public interface IPlayerTopicTagService {
    PlayerTopicTag upsertPending(Long memberId, Long topicId, String tagCode, String tagValue, Long sourceNodeId);
    PlayerTopicTag ownedById(Long memberId, Long tagId);
    PlayerTopicTag confirm(Long memberId, Long tagId);
    PlayerTopicTag revoke(Long memberId, Long tagId);
    PlayerTopicTag correctPending(Long memberId, Long tagId, String tagValue, Set<String> allowedValues);
    List<PlayerTopicTag> pendingForNode(Long memberId, Long topicId, Long sourceNodeId);
    List<PlayerTopicTag> confirmedForConsumer(Long memberId, Long topicId, Set<String> declaredCodes);
    List<PlayerTopicTag> confirmedForOwner(Long memberId, Long topicId);
}
