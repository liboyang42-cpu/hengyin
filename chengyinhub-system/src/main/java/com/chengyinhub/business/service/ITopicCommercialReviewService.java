package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.enums.TopicLifecycleState;

/** 商业主题立项审/开售审提交通道。 */
public interface ITopicCommercialReviewService {
    Long submitProjectReview(Long topicId, Long submitterId, TopicLifecycleState requestedTarget);
    Long submitSaleReview(Long topicId, Long submitterId);
}
