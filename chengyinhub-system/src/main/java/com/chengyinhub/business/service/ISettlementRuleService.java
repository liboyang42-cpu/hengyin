package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.domain.vo.PlatformSettlementAccountVO;

import java.util.List;

public interface ISettlementRuleService {
    List<SettlementRule> selectRuleList(SettlementRule query);

    SettlementRule getActiveRule(Long merchantId, String bizType);

    int saveRule(SettlementRule rule);

    PlatformSettlementAccountVO getPlatformSettlementAccount();

    int updatePlatformSettlementAccount(Long memberId);
}
