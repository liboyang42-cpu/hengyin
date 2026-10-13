package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CreatorMetricDaily;

/**
 * P3 创作者指标 Mapper。
 */
public interface CreatorMetricDailyMapper {
    CreatorMetricDaily selectLatestMetric(Long creatorId);
}
