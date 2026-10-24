package com.chengyinhub.business.service;

import java.time.LocalDate;
import java.util.Map;

/**
 * O-14 每日推荐打分。
 */
public interface IRecommendationScoreDailyService {
    /**
     * 按 statDate 重算全部活跃会员的推荐分。同日重跑结果一致(按会员 × 类型先删后写，同事务)。
     *
     * @return members / rows / failures 计数
     */
    Map<String, Integer> computeDaily(LocalDate statDate);
}
