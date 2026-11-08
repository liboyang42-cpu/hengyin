package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.marketing.MarketingRevenueRule;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

public interface MarketingRevenueRuleMapper {

    int insertRule(MarketingRevenueRule rule);

    /** 只允许改草稿：where status = 0，其它状态返回 0 行。 */
    int updateRule(MarketingRevenueRule rule);

    MarketingRevenueRule selectById(@Param("id") Long id);

    MarketingRevenueRule selectByIdForUpdate(@Param("id") Long id);

    MarketingRevenueRule selectDraftByRuleKey(@Param("ruleKey") String ruleKey);

    Integer selectMaxVersion(@Param("ruleKey") String ruleKey);

    MarketingRevenueRule selectActiveByRuleKey(@Param("ruleKey") String ruleKey);

    List<MarketingRevenueRule> selectList(@Param("ruleKey") String ruleKey,
                                          @Param("scenario") Integer scenario,
                                          @Param("status") Integer status);

    int activate(@Param("id") Long id,
                 @Param("publishTime") Date publishTime,
                 @Param("effectiveFrom") Date effectiveFrom);

    int retireActiveByRuleKey(@Param("ruleKey") String ruleKey,
                              @Param("retireTime") Date retireTime);

    int retireById(@Param("id") Long id,
                   @Param("retireTime") Date retireTime);
}
