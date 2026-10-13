package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.vo.TopicCompletionAwardVO;
import java.util.Date;
import com.chengyinhub.common.constant.RewardPolicy;

public interface ITopicCompletionAwardService {
    TopicCompletionAwardVO grantOnce(Long memberId, Long topicId, Long pointsSourceId, Date occurredAt);

    TopicCompletionAwardVO grantOnce(Long memberId, Long topicId, Long pointsSourceId, Date occurredAt,
            RewardPolicy rewardPolicy);
}
