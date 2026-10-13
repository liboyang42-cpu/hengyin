package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.AnalyticsBatchDTO;

/**
 * P3 行为事件服务。
 */
public interface IAnalyticsEventService {
    /** Number of durable request events including matching replays; conflicts roll back the batch. */
    int recordBatch(Long memberId, AnalyticsBatchDTO request);

    Long visibleCommunityPostAuthor(Long memberId, Long postId);

    int aggregateDaily(String statDate);
}
