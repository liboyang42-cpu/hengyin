package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CircleExploreSession;
import com.chengyinhub.business.domain.vo.CircleExploreCardVO;

/** 玩家圈层主题会话。 */
public interface ICircleExploreService {
    void assertEligible(Long topicId, Long memberId);
    CircleExploreSession create(Long topicId, Long memberId);
    CircleExploreSession join(String inviteCode, Long memberId);
    void record(Long sessionId, Long offerId, String note, Long memberId);
    void answer(Long sessionId, String stage, String value, Long memberId);
    CircleExploreCardVO card(Long sessionId, Long memberId);
}
