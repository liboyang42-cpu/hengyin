package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ClubEventCampaign;
import com.chengyinhub.business.domain.ClubEventDelivery;

import java.util.Date;
import java.util.List;

/**
 * 俱乐部通知的独立事务边界。批次创建、单条投递和失败回执必须彼此隔离，
 * 避免 IM 写入失败把可重试状态一并回滚。
 */
public interface IClubEventNotificationTransactionService {
    ClubEventCampaign create(ClubEventCampaign campaign, List<Long> recipients, Date now);

    List<ClubEventDelivery> claim(ClubEventCampaign campaign, int limit, Date leaseUntil);

    List<ClubEventDelivery> claimAuthorized(Long actorMemberId, ClubEventCampaign campaign,
                                            int limit, Date leaseUntil);

    boolean deliver(ClubEventCampaign campaign, Long deliveryId);

    boolean recordFailure(Long campaignId, Long deliveryId, String safeError, Date nextAttemptAt);
}
