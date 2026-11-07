package com.chengyinhub.business.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.service.impl.PricingServiceImpl;

/**
 * 第十二轮 k1 第 7 项之后 PricingServiceImpl 没有硬编码兜底费率了:查不到规则就拒绝。
 * 这些纯计算单测原来靠兜底的 10% / 5% / 2% / 5 家算期望值;现在把同一组数显式写成规则行注入,
 * 期望值不变,只是费率来自「规则」而不是隐形默认。
 */
public final class PricingTestRules {

    private PricingTestRules() {
    }

    public static SettlementRule topicRule() {
        SettlementRule rule = new SettlementRule();
        rule.setPlatformCommissionRate(new BigDecimal("0.10"));
        rule.setGuaranteeBaseRate(new BigDecimal("0.05"));
        rule.setGuaranteeStepRate(new BigDecimal("0.02"));
        rule.setGuaranteeMaxMerchant(5);
        return rule;
    }

    public static ISettlementRuleService ruleService() {
        ISettlementRuleService rules = mock(ISettlementRuleService.class);
        when(rules.getActiveRule(0L, "topic")).thenReturn(topicRule());
        return rules;
    }

    public static PricingServiceImpl withTopicRule(PricingServiceImpl service) {
        ReflectionTestUtils.setField(service, "settlementRuleService", ruleService());
        return service;
    }
}
