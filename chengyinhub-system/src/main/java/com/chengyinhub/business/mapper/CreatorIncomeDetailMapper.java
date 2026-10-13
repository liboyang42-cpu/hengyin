package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CreatorIncomeDetail;

import java.util.List;

/**
 * P3 创作者收益 Mapper。
 */
public interface CreatorIncomeDetailMapper {
    List<CreatorIncomeDetail> selectRecentIncome(Long creatorId);
}
