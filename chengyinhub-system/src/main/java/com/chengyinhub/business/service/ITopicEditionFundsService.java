package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.TopicEditionFunds;
import com.chengyinhub.business.domain.TopicEditionCost;
import com.chengyinhub.business.domain.vo.TopicEditionFinanceOverviewVO;

import java.util.Date;

public interface ITopicEditionFundsService {
    TopicEditionFunds recomputeProvisional(Long topicId, Date asOf, String idempotencyKey, String operator);

    TopicEditionFunds recompute(Long topicId, Date asOf, String idempotencyKey, String operator,
                                TopicEditionFundsPhase phase);

    TopicEditionFunds currentProvisional(Long topicId);

    int repairUnknownExplorePurchases(Long topicId);

    TopicEditionFinanceOverviewVO currentFinanceOverview(Long topicId);

    TopicEditionCost saveCost(Long topicId, TopicEditionCost cost, String operator);
}
