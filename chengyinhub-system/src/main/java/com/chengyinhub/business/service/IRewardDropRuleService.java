package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.RewardDropRule;

import java.util.List;
import java.util.Map;

/**
 * M6 掉落规则后台管理服务。
 */
public interface IRewardDropRuleService {

    String BASE_PROB_BP_CONFIG_KEY = "reward.drop.base.prob.bp";
    int DEFAULT_BASE_PROB_BP = 1500;

    List<RewardDropRule> selectRuleList(RewardDropRule filter);

    RewardDropRule selectRuleById(Long id);

    RewardDropRule insertRule(RewardDropRule rule);

    RewardDropRule updateRule(RewardDropRule rule);

    int deleteRuleById(Long id);

    int getBaseProbBp();

    int updateBaseProbBp(int probBp, String updateBy);

    Map<String, Object> previewProbabilities(Long topicId, String sceneKey);
}
