package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.SettlementRule;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface SettlementRuleMapper {
    List<SettlementRule> selectRuleList(SettlementRule query);

    SettlementRule selectActiveRule(@Param("merchantId") Long merchantId, @Param("bizType") String bizType);

    int insertSettlementRule(SettlementRule rule);

    int updateSettlementRule(SettlementRule rule);
}
